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
    private val onLog: (String) -> Unit,
    private val onDisconnected: (String) -> Unit
) {
    private enum class Tag { XINPUT_IN, HID_IN, OUT }

    private data class RequestSlot(
        val tag: Tag,
        val request: UsbRequest,
        val buffer: ByteBuffer
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
    private var lastEfMillis = 0L
    private var firmwareText = "--"
    private var batteryText = "--"

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

    fun sendRumble(low: Int, high: Int) {
        if (!running.get()) return
        val cfg = configProvider()
        val preferV2 = cfg.inputMode == InputPreferenceMode.AUTO_PREFER_ENHANCED && enhancedStreamActive && hidOutEp != null
        if (preferV2) {
            hidOutEp?.let { ep ->
                pendingOutQueue.offer(ep to Apex5Protocol.buildRumbleV2Cmd(low, high))
            }
        } else {
            xinputOutEp?.let { ep ->
                pendingOutQueue.offer(ep to Apex5Protocol.buildRumbleXInputCmd(low, high))
            } ?: hidOutEp?.let { ep ->
                pendingOutQueue.offer(ep to Apex5Protocol.buildRumbleV2Cmd(low, high))
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
                                        firmwareText = info.firmwareFormatted()
                                        batteryText = info.batteryFormatted()
                                        onLog("手柄信息: devId=${info.deviceId}, FW=$firmwareText, 电量=$batteryText")
                                        onMetadata(enhancedStreamActive, firmwareText, batteryText)
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

        slots.forEach { runCatching { it.request.cancel(); it.request.close() } }
        outPool.values.flatten().forEach { runCatching { it.request.cancel(); it.request.close() } }

        if (running.getAndSet(false)) {
            onDisconnected(exitReason)
        }
    }
}
