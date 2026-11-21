package com.apex5.driver.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Process
import android.os.SystemClock
import com.apex5.driver.model.DriverConfig
import com.apex5.driver.model.DriverOutputMode
import com.apex5.driver.model.InputPreferenceMode
import com.apex5.driver.model.LiveControllerState
import com.apex5.driver.model.TouchProfile
import com.apex5.driver.model.TriggerHapticMode
import com.apex5.driver.model.TriggerTuningConfig
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class Apex5UsbSession(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val configProvider: () -> DriverConfig,
    private val touchProfileProvider: () -> TouchProfile,
    private val displayRotationProvider: () -> Int,
    private val onPacketOut: (ByteArray) -> Unit,
    private val onTouchOut: (ByteArray) -> Unit,
    private val onLiveState: (LiveControllerState) -> Unit,
    private val onMetadata: (enhancedActive: Boolean, fw: String, battery: String) -> Unit,
    private val onTriggerCapabilities: (forceTriggerSupported: Boolean, onboardSummary: String) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit,
    private val onDisconnected: (String) -> Unit
) {
    private enum class Tag { XINPUT_IN, HID_IN, OUT }

    private data class RequestSlot(
        val tag: Tag,
        val request: UsbRequest,
        val buffer: ByteBuffer
    )

    private data class AppliedTriggerKey(
        val mode: TriggerHapticMode,
        val strength: Int,
        val startTravel: Int,
        val freq: Int,
        val globalScale: Int,
        val onboardSeq: Int
    )

    private val running = AtomicBoolean(false)
    private var ioThread: Thread? = null
    private var connection: UsbDeviceConnection? = null

    private var xinputIntf: UsbInterface? = null
    private var xinputInEp: UsbEndpoint? = null
    private var xinputOutEp: UsbEndpoint? = null

    private var hidIntf: UsbInterface? = null
    private var hidInEp: UsbEndpoint? = null
    private var hidOutEp: UsbEndpoint? = null

    private val pendingOutQueue = ConcurrentLinkedQueue<Pair<UsbEndpoint, ByteArray>>()
    private val mapper = InputMapper()
    private val touchMapper = TouchMapperEngine()

    @Volatile
    private var enhancedStreamActive = false
    @Volatile
    private var detectedDeviceId = -1
    private var lastEfMillis = 0L
    private var firmwareText = "--"
    private var batteryText = "--"

    @Volatile
    private var onboardLeftBlock: ByteArray? = null
    @Volatile
    private var onboardRightBlock: ByteArray? = null
    @Volatile
    private var onboardSummaryText = "未读取板载配置"
    private val onboardReadSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val pendingOnboardRead = AtomicBoolean(false)
    private val forceTriggerReapply = AtomicBoolean(false)

    @Volatile
    private var currentGripLow = 0
    @Volatile
    private var currentGripHigh = 0
    @Volatile
    private var currentTrigL = 0
    @Volatile
    private var currentTrigR = 0

    private fun isForceTriggerSupported(): Boolean {
        return detectedDeviceId in Apex5Protocol.APEX5_DEVICE_IDS ||
            (detectedDeviceId < 0 && device.productId == Apex5Protocol.APEX5_PID)
    }

    fun start(): Boolean {
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            onLog("openDevice() 失败，请检查 USB 权限")
            return false
        }
        connection = conn

        onLog("USB 设备: ${device.productName} (VID=0x%04X, PID=0x%04X), 接口数=${device.interfaceCount}"
            .format(device.vendorId, device.productId))

        var hidFallback: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            onLog("  接口 #${intf.id}: class=${intf.interfaceClass}, sub=${intf.interfaceSubclass}, proto=${intf.interfaceProtocol}, ep=${intf.endpointCount}")

            if (intf.interfaceClass == 255 && intf.interfaceSubclass == 93 && intf.interfaceProtocol == 1 && xinputIntf == null) {
                xinputIntf = intf
            } else if (intf.interfaceClass == UsbConstants.USB_CLASS_HID) {
                if (intf.id == 2) {
                    hidIntf = intf
                } else {
                    hidFallback = intf
                }
            }
        }
        if (hidIntf == null) {
            hidIntf = hidFallback
        }

        xinputIntf?.let { intf ->
            if (conn.claimInterface(intf, true)) {
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.direction == UsbConstants.USB_DIR_IN && xinputInEp == null) xinputInEp = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT && xinputOutEp == null) xinputOutEp = ep
                }
                onLog("已接管 Interface #${intf.id} (XInput): IN=${xinputInEp?.address}(interval=${xinputInEp?.interval}ms), OUT=${xinputOutEp?.address}")
            } else {
                onLog("claimInterface XInput #${intf.id} 失败")
                xinputIntf = null
            }
        }

        hidIntf?.let { intf ->
            if (conn.claimInterface(intf, true)) {
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.direction == UsbConstants.USB_DIR_IN && hidInEp == null) hidInEp = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT && hidOutEp == null) hidOutEp = ep
                }
                onLog("已接管 Interface #${intf.id} (Flydigi HID): IN=${hidInEp?.address}(interval=${hidInEp?.interval}ms), OUT=${hidOutEp?.address}")
            } else {
                onLog("claimInterface HID #${intf.id} 失败")
                hidIntf = null
            }
        }

        if (xinputInEp == null && hidInEp == null) {
            onLog("未找到可用的输入端点")
            stop()
            return false
        }

        running.set(true)
        ioThread = Thread({ runIoLoop(conn) }, "Apex5-USB-IO").apply { start() }
        return true
    }

    fun requestSyncOnboardTriggers() {
        if (!running.get() || hidOutEp == null) return
        pendingOnboardRead.set(true)
        forceTriggerReapply.set(true)
    }

    fun triggerForceTriggerPulse(strength: Int = 225) {
        if (!running.get()) return
        val hidOut = hidOutEp
        if (hidOut != null && isForceTriggerSupported()) {
            val kick = strength.coerceIn(80, 255)
            queueHidCommands(
                hidOut,
                Apex5Protocol.buildForceTriggerMachineGunCmd(
                    side = Apex5Protocol.TRIGGER_SIDE_LEFT,
                    stroke = 0,
                    pressure = 95,
                    strength = kick,
                    frequency = 24,
                    matchStroke = false
                ),
                Apex5Protocol.buildForceTriggerMachineGunCmd(
                    side = Apex5Protocol.TRIGGER_SIDE_RIGHT,
                    stroke = 0,
                    pressure = 95,
                    strength = kick,
                    frequency = 24,
                    matchStroke = false
                ),
                Apex5Protocol.buildRumbleV2Cmd(90, 120, 0, 0)
            )
            Thread {
                Thread.sleep(280)
                if (running.get()) {
                    hidOutEp?.let { ep ->
                        pendingOutQueue.offer(ep to Apex5Protocol.buildRumbleV2Cmd(currentGripLow, currentGripHigh, 0, 0))
                    }
                    forceTriggerReapply.set(true)
                }
            }.start()
        } else {
            Thread {
                sendRumble(0, 0, forceTriggerL = strength, forceTriggerR = strength)
                Thread.sleep(240)
                sendRumble(0, 0, forceTriggerL = 0, forceTriggerR = 0)
            }.start()
        }
    }

    fun sendRumble(low: Int, high: Int, forceTriggerL: Int? = null, forceTriggerR: Int? = null) {
        if (!running.get()) return
        val cfg = configProvider()
        val mainScale = cfg.mainRumbleIntensityPercent.coerceIn(0, 100) / 100f
        val sLow = (low * mainScale).toInt().coerceIn(0, 255)
        val sHigh = (high * mainScale).toInt().coerceIn(0, 255)
        currentGripLow = sLow
        currentGripHigh = sHigh

        val forceTrigHw = isForceTriggerSupported()
        val trigScale = cfg.triggerRumbleIntensityPercent.coerceIn(0, 100) / 100f
        val trigL = if (forceTrigHw) {
            0
        } else {
            forceTriggerL ?: if (cfg.leftTrigger.hapticMode == TriggerHapticMode.SYNC_MAIN) {
                (sLow * (cfg.leftTrigger.hapticStrengthPercent / 100f) * trigScale).toInt().coerceIn(0, 255)
            } else currentTrigL
        }
        val trigR = if (forceTrigHw) {
            0
        } else {
            forceTriggerR ?: if (cfg.rightTrigger.hapticMode == TriggerHapticMode.SYNC_MAIN) {
                (sHigh * (cfg.rightTrigger.hapticStrengthPercent / 100f) * trigScale).toInt().coerceIn(0, 255)
            } else currentTrigR
        }

        currentTrigL = trigL
        currentTrigR = trigR

        val preferV2 = cfg.inputMode == InputPreferenceMode.AUTO_PREFER_ENHANCED && enhancedStreamActive && hidOutEp != null
        if (preferV2) {
            hidOutEp?.let { ep ->
                pendingOutQueue.offer(ep to Apex5Protocol.buildRumbleV2Cmd(sLow, sHigh, trigL, trigR))
            }
        } else {
            xinputOutEp?.let { ep ->
                pendingOutQueue.offer(ep to Apex5Protocol.buildRumbleXInputCmd(sLow, sHigh))
            } ?: hidOutEp?.let { ep ->
                pendingOutQueue.offer(ep to Apex5Protocol.buildRumbleV2Cmd(sLow, sHigh, trigL, trigR))
            }
        }
    }

    fun stop() {
        running.set(false)
        ioThread?.join(500)
        ioThread = null
        connection?.let { conn ->
            xinputIntf?.let { runCatching { conn.releaseInterface(it) } }
            hidIntf?.let { runCatching { conn.releaseInterface(it) } }
            runCatching { conn.close() }
        }
        connection = null
        xinputIntf = null
        xinputInEp = null
        xinputOutEp = null
        hidIntf = null
        hidInEp = null
        hidOutEp = null
    }

    private fun queueHidCommands(ep: UsbEndpoint, vararg cmds: ByteArray) {
        cmds.forEach { pendingOutQueue.offer(ep to it) }
    }

    private fun runIoLoop(conn: UsbDeviceConnection) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val slots = mutableListOf<RequestSlot>()

        fun allocateInRequests(ep: UsbEndpoint, tag: Tag, count: Int = 4) {
            val pktSize = maxOf(64, ep.maxPacketSize)
            repeat(count) {
                val req = UsbRequest()
                if (req.initialize(conn, ep)) {
                    val slot = RequestSlot(tag, req, ByteBuffer.allocateDirect(pktSize))
                    req.clientData = slot
                    slot.buffer.clear()
                    if (req.queue(slot.buffer)) {
                        slots += slot
                    } else {
                        req.close()
                    }
                }
            }
        }

        val xinputQueueDepth = if (hidInEp != null) 2 else 4
        xinputInEp?.let { allocateInRequests(it, Tag.XINPUT_IN, xinputQueueDepth) }
        hidInEp?.let { allocateInRequests(it, Tag.HID_IN, 8) }

        val outPool = mutableMapOf<Int, MutableList<RequestSlot>>()
        fun sendOutNow(ep: UsbEndpoint, payload: ByteArray) {
            val pool = outPool.getOrPut(ep.address) { mutableListOf() }
            val slot = if (pool.isNotEmpty()) {
                pool.removeAt(pool.lastIndex)
            } else {
                val req = UsbRequest()
                if (!req.initialize(conn, ep)) return
                val s = RequestSlot(Tag.OUT, req, ByteBuffer.allocateDirect(maxOf(64, payload.size)))
                req.clientData = s
                s
            }
            slot.buffer.clear()
            slot.buffer.put(payload)
            slot.buffer.flip()
            if (!slot.request.queue(slot.buffer)) {
                pool.add(slot)
            }
        }

        var handshakeSent = false
        var handshakeSentMs = 0L
        var initialOnboardRequested = false
        var lastHeartbeat = 0L
        var lastReacquire = 0L
        var lastRateTick = SystemClock.elapsedRealtime()
        var pktCount = 0
        var currentRateHz = 0
        var lastUiEmit = 0L
        var lastLoggedUnknown = 0L

        val tmp = ByteArray(128)
        val rawFrame = MutableRawFrame()
        var hasValidFrame = false
        var exitReason = "已停止"

        var lastTrigHapticCheck = 0L
        var prevLtTrigHigh = false
        var prevRtTrigHigh = false
        var ltPulseUntilMs = 0L
        var rtPulseUntilMs = 0L

        var lastLeftForceKey: AppliedTriggerKey? = null
        var lastRightForceKey: AppliedTriggerKey? = null

        var activeOnboardSlot = 0
        var onboardTotalChunks = 0
        var onboardBlob: ByteArray? = null
        var onboardChunkSeen: BooleanArray? = null
        var onboardAppliedThisCycle = false
        var onboardReadStartMs = 0L

        fun finalizeOnboardBlob(nowMs: Long) {
            val blob = onboardBlob ?: return
            val seen = onboardChunkSeen ?: return
            if (onboardAppliedThisCycle || blob.size < Apex5Protocol.ONBOARD_MIN_REQUIRED_BYTES) return
            val allDone = seen.all { it }
            val minNeededChunks = (Apex5Protocol.ONBOARD_MIN_REQUIRED_BYTES + 19) / 20
            val trigDone = seen.size >= minNeededChunks && (0 until minNeededChunks).all { seen[it] }
            if (!allDone && !(trigDone && nowMs - onboardReadStartMs >= 260L)) return

            onboardAppliedThisCycle = true
            val leftBlock = blob.sliceArray(
                Apex5Protocol.ONBOARD_LEFT_TRIGGER_OFFSET until (Apex5Protocol.ONBOARD_LEFT_TRIGGER_OFFSET + 20)
            )
            val rightBlock = blob.sliceArray(
                Apex5Protocol.ONBOARD_RIGHT_TRIGGER_OFFSET until (Apex5Protocol.ONBOARD_RIGHT_TRIGGER_OFFSET + 20)
            )
            onboardLeftBlock = leftBlock
            onboardRightBlock = rightBlock
            val summary = "槽位#${activeOnboardSlot + 1}: 左=${Apex5Protocol.describeStoredTriggerBlock(leftBlock)} · 右=${Apex5Protocol.describeStoredTriggerBlock(rightBlock)}"
            onboardSummaryText = summary
            onLog("已读取 PC 板载扳机预设 ($summary)")
            onTriggerCapabilities(isForceTriggerSupported(), summary)
            onboardReadSeq.incrementAndGet()
        }

        fun updateApex5ForceTriggers(cfg: DriverConfig, nowMs: Long) {
            val hidOut = hidOutEp ?: return
            if (!handshakeSent || !isForceTriggerSupported()) return
            if (detectedDeviceId < 0 && nowMs - handshakeSentMs < 450L) return

            if (forceTriggerReapply.compareAndSet(true, false)) {
                lastLeftForceKey = null
                lastRightForceKey = null
            }

            val seq = onboardReadSeq.get()
            val gScale = cfg.triggerRumbleIntensityPercent.coerceIn(0, 100)

            val lCfg = cfg.leftTrigger
            val nextL = AppliedTriggerKey(
                mode = lCfg.hapticMode,
                strength = lCfg.hapticStrengthPercent,
                startTravel = lCfg.startTravelPercent,
                freq = lCfg.frequencyPercent,
                globalScale = gScale,
                onboardSeq = seq
            )
            if (nextL != lastLeftForceKey) {
                val prevMode = lastLeftForceKey?.mode
                lastLeftForceKey = nextL
                pendingOutQueue.offer(
                    hidOut to Apex5Protocol.buildForceTriggerCmdForConfig(
                        side = Apex5Protocol.TRIGGER_SIDE_LEFT,
                        cfg = lCfg,
                        globalTriggerPercent = gScale,
                        onboardBlock20 = onboardLeftBlock
                    )
                )
                if (prevMode != lCfg.hapticMode || (lCfg.hapticMode == TriggerHapticMode.FOLLOW_ONBOARD && seq > 0)) {
                    onLog("左扳机伺服 (0x51/0x52) -> ${lCfg.hapticMode.shortLabel}")
                }
            }

            val rCfg = cfg.rightTrigger
            val nextR = AppliedTriggerKey(
                mode = rCfg.hapticMode,
                strength = rCfg.hapticStrengthPercent,
                startTravel = rCfg.startTravelPercent,
                freq = rCfg.frequencyPercent,
                globalScale = gScale,
                onboardSeq = seq
            )
            if (nextR != lastRightForceKey) {
                val prevMode = lastRightForceKey?.mode
                lastRightForceKey = nextR
                pendingOutQueue.offer(
                    hidOut to Apex5Protocol.buildForceTriggerCmdForConfig(
                        side = Apex5Protocol.TRIGGER_SIDE_RIGHT,
                        cfg = rCfg,
                        globalTriggerPercent = gScale,
                        onboardBlock20 = onboardRightBlock
                    )
                )
                if (prevMode != rCfg.hapticMode || (rCfg.hapticMode == TriggerHapticMode.FOLLOW_ONBOARD && seq > 0)) {
                    onLog("右扳机伺服 (0x51/0x52) -> ${rCfg.hapticMode.shortLabel}")
                }
            }
        }

        fun computeTriggerMotor(
            rawVal: Int,
            tCfg: TriggerTuningConfig,
            globalScale: Float,
            isLeft: Boolean,
            nowMs: Long
        ): Int {
            val strength = (tCfg.hapticStrengthPercent.coerceIn(0, 100) / 100f) * globalScale
            return when (tCfg.hapticMode) {
                TriggerHapticMode.OFF, TriggerHapticMode.FOLLOW_ONBOARD, TriggerHapticMode.TRIGGER_LOCK -> 0
                TriggerHapticMode.SYNC_MAIN -> {
                    val base = if (isLeft) currentGripLow else currentGripHigh
                    (base * strength).toInt().coerceIn(0, 255)
                }
                TriggerHapticMode.SNIPER_CLICK -> {
                    val threshold = 115
                    val high = rawVal >= threshold
                    val prevHigh = if (isLeft) prevLtTrigHigh else prevRtTrigHigh
                    if (high && !prevHigh) {
                        if (isLeft) ltPulseUntilMs = nowMs + 48L else rtPulseUntilMs = nowMs + 48L
                    }
                    if (isLeft) prevLtTrigHigh = high else prevRtTrigHigh = high
                    val activeUntil = if (isLeft) ltPulseUntilMs else rtPulseUntilMs
                    if (nowMs < activeUntil) (235f * strength).toInt().coerceIn(0, 255) else 0
                }
                TriggerHapticMode.MACHINE_GUN -> {
                    if (rawVal < 80) 0
                    else {
                        val phase = (nowMs % 62L) < 30L
                        if (phase) (210f * strength).toInt().coerceIn(0, 255) else 0
                    }
                }
                TriggerHapticMode.RACING_PROGRESSIVE -> {
                    if (rawVal < 25) 0
                    else {
                        val frac = ((rawVal - 25) / 230f).coerceIn(0f, 1f)
                        ((40f + frac * 190f) * strength).toInt().coerceIn(0, 255)
                    }
                }
            }
        }

        fun updateTriggerHaptics(cfg: DriverConfig, nowMs: Long) {
            if (isForceTriggerSupported()) return
            if (!enhancedStreamActive || hidOutEp == null) return
            if (nowMs - lastTrigHapticCheck < 22L) return
            lastTrigHapticCheck = nowMs

            val lMode = cfg.leftTrigger.hapticMode
            val rMode = cfg.rightTrigger.hapticMode
            if (lMode == TriggerHapticMode.OFF && rMode == TriggerHapticMode.OFF && currentTrigL == 0 && currentTrigR == 0) {
                return
            }
            if (lMode == TriggerHapticMode.SYNC_MAIN && rMode == TriggerHapticMode.SYNC_MAIN) {
                return
            }

            val globalScale = cfg.triggerRumbleIntensityPercent.coerceIn(0, 100) / 100f
            val nextL = if (lMode == TriggerHapticMode.SYNC_MAIN) currentTrigL
            else computeTriggerMotor(rawFrame.lt, cfg.leftTrigger, globalScale, true, nowMs)
            val nextR = if (rMode == TriggerHapticMode.SYNC_MAIN) currentTrigR
            else computeTriggerMotor(rawFrame.rt, cfg.rightTrigger, globalScale, false, nowMs)

            if (nextL != currentTrigL || nextR != currentTrigR) {
                currentTrigL = nextL
                currentTrigR = nextR
                hidOutEp?.let { ep ->
                    pendingOutQueue.offer(ep to Apex5Protocol.buildRumbleV2Cmd(currentGripLow, currentGripHigh, nextL, nextR))
                }
            }
        }

        fun handleDecodedFrame(cfg: DriverConfig, now: Long) {
            hasValidFrame = true
            pktCount++
            val needUi = (now - lastUiEmit >= 33L)
            val (pktBytes, live) = mapper.processPacket(rawFrame, cfg, currentRateHz, needUi)
            if (cfg.outputMode == DriverOutputMode.GAMEPAD) {
                onPacketOut(pktBytes)
            } else {
                val touchBytes = touchMapper.processFrame(
                    raw = rawFrame,
                    config = cfg,
                    profile = touchProfileProvider(),
                    displayRotation = displayRotationProvider(),
                    gyroToggleActive = mapper.lastGyroToggleActive,
                    gyroHoldPressed = mapper.lastGyroHoldPressed
                )
                if (touchBytes != null) {
                    onTouchOut(touchBytes)
                }
            }
            updateTriggerHaptics(cfg, now)
            if (live != null) {
                lastUiEmit = now
                onLiveState(live)
            }
        }

        while (running.get()) {
            val now = SystemClock.elapsedRealtime()
            val cfg = configProvider()
            val wantEnhanced = cfg.inputMode == InputPreferenceMode.AUTO_PREFER_ENHANCED && hidInEp != null

            if (wantEnhanced && hidOutEp != null) {
                if (!handshakeSent) {
                    handshakeSent = true
                    handshakeSentMs = now
                    lastHeartbeat = now
                    onLog("发送 Flydigi V2 增强握手 (CheckArch / GetInfo / GetStatus / SetStatus / Acquire)")
                    queueHidCommands(
                        hidOutEp!!,
                        Apex5Protocol.buildCheckArchCmd(),
                        Apex5Protocol.buildGetInfoCmd(),
                        Apex5Protocol.buildGetStatusCmd(),
                        Apex5Protocol.buildSetStatusCmd(),
                        Apex5Protocol.buildAcquireCmd(true)
                    )
                } else if (now - lastHeartbeat >= 25_000L) {
                    lastHeartbeat = now
                    queueHidCommands(
                        hidOutEp!!,
                        Apex5Protocol.buildAcquireCmd(true),
                        Apex5Protocol.buildGetInfoCmd(),
                        Apex5Protocol.buildGetStatusCmd()
                    )
                } else if (now - lastEfMillis > 1500L && now - lastReacquire > 2500L) {
                    lastReacquire = now
                    queueHidCommands(
                        hidOutEp!!,
                        Apex5Protocol.buildSetStatusCmd(),
                        Apex5Protocol.buildAcquireCmd(true)
                    )
                }
            } else if (!wantEnhanced && enhancedStreamActive) {
                enhancedStreamActive = false
                onMetadata(false, firmwareText, batteryText)
            }

            if (hidOutEp != null && isForceTriggerSupported()) {
                if (!initialOnboardRequested && handshakeSent && (detectedDeviceId >= 0 || now - handshakeSentMs > 900L)) {
                    initialOnboardRequested = true
                    pendingOnboardRead.set(true)
                }
                if (pendingOnboardRead.compareAndSet(true, false)) {
                    onboardTotalChunks = 0
                    onboardBlob = null
                    onboardChunkSeen = null
                    onboardAppliedThisCycle = false
                    onboardReadStartMs = now
                    pendingOutQueue.offer(hidOutEp!! to Apex5Protocol.buildMapStatusCmd())
                }
                finalizeOnboardBlob(now)
                updateApex5ForceTriggers(cfg, now)
            }

            // Flush outgoing requests (rumble & commands)
            while (true) {
                val item = pendingOutQueue.poll() ?: break
                sendOutNow(item.first, item.second)
            }

            val waitMs = if (cfg.outputMode == DriverOutputMode.TOUCH_MAPPER) 8L else 25L
            val completed = try {
                conn.requestWait(waitMs)
            } catch (_: TimeoutException) {
                null
            } catch (e: Exception) {
                exitReason = "USB 异常: ${e.message}"
                break
            }

            if (completed == null) {
                if (enhancedStreamActive && now - lastEfMillis > 350L) {
                    enhancedStreamActive = false
                    onMetadata(false, firmwareText, batteryText)
                }
                if (hasValidFrame && cfg.outputMode == DriverOutputMode.TOUCH_MAPPER) {
                    val touchBytes = touchMapper.processFrame(
                        raw = rawFrame,
                        config = cfg,
                        profile = touchProfileProvider(),
                        displayRotation = displayRotationProvider(),
                        gyroToggleActive = mapper.lastGyroToggleActive,
                        gyroHoldPressed = mapper.lastGyroHoldPressed
                    )
                    if (touchBytes != null) {
                        onTouchOut(touchBytes)
                    }
                }
                continue
            }

            val slot = completed.clientData as? RequestSlot ?: continue
            if (slot.tag == Tag.OUT) {
                outPool.getOrPut(completed.endpoint.address) { mutableListOf() }.add(slot)
                continue
            }

            val len = slot.buffer.position()
            if (len > 0) {
                val copyLen = minOf(len, tmp.size)
                slot.buffer.flip()
                slot.buffer.get(tmp, 0, copyLen)

                when (slot.tag) {
                    Tag.HID_IN -> {
                        val hdrOff = Apex5Protocol.findV2HeaderOffset(tmp, copyLen)
                        if (hdrOff >= 0 && copyLen - hdrOff >= 3) {
                            when (tmp[hdrOff + 2]) {
                                Apex5Protocol.REPORT_INPUT_EF -> {
                                    if (wantEnhanced && Apex5Protocol.parseV2InputEFInto(tmp, copyLen, rawFrame)) {
                                        lastEfMillis = now
                                        if (!enhancedStreamActive) {
                                            enhancedStreamActive = true
                                            onLog("飞智增强流 (0xEF) 已激活：支持背键 M1-M4、LM/RM 与陀螺仪")
                                            onMetadata(true, firmwareText, batteryText)
                                        }
                                        handleDecodedFrame(cfg, now)
                                    }
                                }

                                Apex5Protocol.CMD_GET_INFO -> {
                                    Apex5Protocol.parseV2InfoReply(tmp, copyLen)?.let { info ->
                                        detectedDeviceId = info.deviceId
                                        firmwareText = info.firmwareFormatted()
                                        batteryText = info.batteryFormatted()
                                        val ftOk = info.deviceId in Apex5Protocol.APEX5_DEVICE_IDS
                                        onLog("手柄信息: devId=${info.deviceId}(${if (ftOk) "Apex5力反馈扳机" else "标准V2"}), FW=$firmwareText, 电量=$batteryText")
                                        onMetadata(enhancedStreamActive, firmwareText, batteryText)
                                        onTriggerCapabilities(ftOk, onboardSummaryText)
                                        if (ftOk && !initialOnboardRequested) {
                                            initialOnboardRequested = true
                                            pendingOnboardRead.set(true)
                                        }
                                    }
                                }

                                Apex5Protocol.CMD_GET_STATUS, Apex5Protocol.CMD_SET_STATUS -> {
                                    if (copyLen - hdrOff >= 10) {
                                        val allowThirdParty = tmp[hdrOff + 9].toInt() and 0xFF
                                        onLog("V2 状态回复 (cmd=0x%02X): flag=%d [%s]".format(
                                            tmp[hdrOff + 2], allowThirdParty, Apex5Protocol.hexPreview(tmp, copyLen, 12)
                                        ))
                                    }
                                }

                                Apex5Protocol.CMD_MAP_STATUS -> {
                                    Apex5Protocol.parseMapStatusActiveSlot(tmp, copyLen)?.let { activeSlot ->
                                        activeOnboardSlot = activeSlot
                                        onLog("读取板载槽位 #${activeSlot + 1}，正在拉取 PC 空间站扳机预设...")
                                        hidOutEp?.let { ep ->
                                            pendingOutQueue.offer(ep to Apex5Protocol.buildMapReadCmd(activeSlot, 20))
                                        }
                                    }
                                }

                                Apex5Protocol.CMD_MAP_READ -> {
                                    Apex5Protocol.parseMapReadChunk(tmp, copyLen)?.let { chunk ->
                                        if (chunk.total in 1..64 && chunk.index in 0 until chunk.total) {
                                            if (onboardBlob == null || onboardTotalChunks != chunk.total) {
                                                onboardTotalChunks = chunk.total
                                                onboardBlob = ByteArray(chunk.total * 20)
                                                onboardChunkSeen = BooleanArray(chunk.total)
                                            }
                                            val blob = onboardBlob!!
                                            val seen = onboardChunkSeen!!
                                            val dstOff = chunk.index * 20
                                            if (dstOff + 20 <= blob.size) {
                                                System.arraycopy(chunk.payload20, 0, blob, dstOff, 20)
                                                seen[chunk.index] = true
                                            }
                                            finalizeOnboardBlob(now)
                                        }
                                    }
                                }

                                Apex5Protocol.CMD_CHECK_ARCH,
                                Apex5Protocol.CMD_ACQUIRE,
                                Apex5Protocol.CMD_SET_FORCE_TRIGGER,
                                Apex5Protocol.CMD_SET_FORCE_TRIGGER_GRIP -> {
                                    // Expected ACKs for V2 handshake & force-trigger commands
                                }

                                else -> {
                                    if (now - lastLoggedUnknown > 1000L) {
                                        lastLoggedUnknown = now
                                        onLog("HID V2 cmd=0x%02X len=$copyLen: ${Apex5Protocol.hexPreview(tmp, copyLen, 14)}"
                                            .format(tmp[hdrOff + 2]))
                                    }
                                }
                            }
                        }
                    }

                    Tag.XINPUT_IN -> {
                        val useXInput = !wantEnhanced || !enhancedStreamActive || (now - lastEfMillis > 250L)
                        if (useXInput && Apex5Protocol.parseXInputInto(tmp, copyLen, rawFrame)) {
                            handleDecodedFrame(cfg, now)
                        }
                    }

                    Tag.OUT -> Unit
                }
            }

            val rateElapsed = now - lastRateTick
            if (rateElapsed >= 1000L) {
                currentRateHz = ((pktCount * 1000L) / rateElapsed).toInt()
                pktCount = 0
                lastRateTick = now
            }

            slot.buffer.clear()
            if (running.get() && !slot.request.queue(slot.buffer)) {
                exitReason = "USB 设备已断开"
                break
            }
        }

        // Release mechanical trigger locks / force resistance cleanly when stopping driver
        if (isForceTriggerSupported()) {
            hidOutEp?.let { ep ->
                runCatching {
                    val leftNorm = Apex5Protocol.buildForceTriggerNormalCmd(Apex5Protocol.TRIGGER_SIDE_LEFT)
                    val rightNorm = Apex5Protocol.buildForceTriggerNormalCmd(Apex5Protocol.TRIGGER_SIDE_RIGHT)
                    conn.bulkTransfer(ep, leftNorm, leftNorm.size, 60)
                    conn.bulkTransfer(ep, rightNorm, rightNorm.size, 60)
                }
            }
        }

        slots.forEach { runCatching { it.request.cancel(); it.request.close() } }
        outPool.values.flatten().forEach { runCatching { it.request.cancel(); it.request.close() } }

        if (running.getAndSet(false)) {
            onDisconnected(exitReason)
        }
    }
}
