package com.apex5.driver.usb

import android.view.Surface
import com.apex5.driver.model.ControllerInputSource
import com.apex5.driver.model.DriverConfig
import com.apex5.driver.model.GyroTriggerMode
import com.apex5.driver.model.TouchNodeConfig
import com.apex5.driver.model.TouchNodeKind
import com.apex5.driver.model.TouchOrientationMode
import com.apex5.driver.model.TouchProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign

class TouchMapperEngine {
    companion object {
        private const val TOUCH_MAGIC = 0x41505435 // 'APT5'
        const val MAX_SLOTS = 10
        const val PACKET_SIZE = 68 // 4 + 1 + 3 + 10 * 6
        private const val TOUCH_MAX = 10000f
        private const val CAMERA_IDLE_RELEASE_NS = 65_000_000L
        private const val CAMERA_RESET_COOLDOWN_NS = 9_000_000L
        private const val MIN_PACKET_INTERVAL_NS = 4_500_000L // ~220Hz cap for continuous moves
    }

    private data class SlotOutput(
        var active: Boolean = false,
        var x: Float = 0.5f,
        var y: Float = 0.5f
    )

    private class CameraSwipeRuntime {
        var touching = false
        var curX = 0f
        var curY = 0f
        var idleSinceNs = 0L
        var resetReleaseUntilNs = 0L
    }

    private class JoystickRuntime {
        var touching = false
        var downFrames = 0
    }

    private class MacroRuntime {
        var prevPressed = false
        var active = false
        var stepIdx = 0
        var isHolding = true
        var phaseStartNs = 0L
    }

    private val outBytes = ByteArray(PACKET_SIZE)
    private val outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)

    private val prevSlotActive = BooleanArray(MAX_SLOTS)
    private val prevSlotX = IntArray(MAX_SLOTS)
    private val prevSlotY = IntArray(MAX_SLOTS)
    private val currentSlots = Array(MAX_SLOTS) { SlotOutput() }

    private val cameraRuntimes = HashMap<String, CameraSwipeRuntime>()
    private val joystickRuntimes = HashMap<String, JoystickRuntime>()
    private val macroRuntimes = HashMap<String, MacroRuntime>()
    private val nodeSlotMap = HashMap<String, Int>()
    private val slotOwner = arrayOfNulls<String>(MAX_SLOTS)
    private val suppressedSingleInputs = HashSet<ControllerInputSource>()

    private var lastFrameNs = 0L
    private var lastEmitNs = 0L

    fun reset() {
        cameraRuntimes.clear()
        joystickRuntimes.clear()
        macroRuntimes.clear()
        nodeSlotMap.clear()
        slotOwner.fill(null)
        suppressedSingleInputs.clear()
        prevSlotActive.fill(false)
        prevSlotX.fill(0)
        prevSlotY.fill(0)
        for (s in currentSlots) {
            s.active = false
        }
        lastFrameNs = 0L
        lastEmitNs = 0L
    }

    fun processFrame(
        raw: MutableRawFrame,
        config: DriverConfig,
        profile: TouchProfile,
        displayRotation: Int,
        gyroToggleActive: Boolean,
        gyroHoldPressed: Boolean,
        nowNs: Long = System.nanoTime()
    ): ByteArray? {
        val dtSec = if (lastFrameNs == 0L) 0.004f
        else ((nowNs - lastFrameNs).coerceIn(500_000L, 50_000_000L)) / 1_000_000_000f
        lastFrameNs = nowNs

        for (s in currentSlots) {
            s.active = false
        }

        // Cleanup stale node owners
        val validIds = HashSet<String>(profile.nodes.size)
        for (n in profile.nodes) validIds.add(n.id)
        for (slot in 0 until MAX_SLOTS) {
            val owner = slotOwner[slot]
            if (owner != null && owner !in validIds) {
                slotOwner[slot] = null
                nodeSlotMap.remove(owner)
            }
        }

        val gyroAimActive = raw.isEnhanced && when (config.gyroTriggerMode) {
            GyroTriggerMode.OFF -> false
            GyroTriggerMode.ALWAYS_ON -> true
            GyroTriggerMode.HOLD_LT -> raw.lt >= 48
            GyroTriggerMode.HOLD_MAPPING -> gyroHoldPressed || gyroToggleActive
        }

        // Suppress plain single-key triggers when an active combo trigger uses the same primary input
        suppressedSingleInputs.clear()
        for (node in profile.nodes) {
            val mod = node.modifierInput
            if (mod != null && isInputPressed(mod, raw) && isInputPressed(node.input, raw)) {
                suppressedSingleInputs.add(node.input)
            }
        }

        var discreteTransition = false

        for (node in profile.nodes) {
            val slot = allocateSlot(node.id) ?: continue
            val out = currentSlots[slot]
            evaluateNode(node, raw, config, gyroAimActive, dtSec, nowNs, out)
            if (out.active != prevSlotActive[slot]) {
                discreteTransition = true
            }
        }

        if (!discreteTransition && (nowNs - lastEmitNs) < MIN_PACKET_INTERVAL_NS) {
            return null
        }

        var anyChanged = false
        for (slot in 0 until MAX_SLOTS) {
            val s = currentSlots[slot]
            if (s.active) {
                val (kx, ky) = transformToKernelCoords(s.x, s.y, displayRotation, config.touchOrientationMode)
                if (!prevSlotActive[slot] || prevSlotX[slot] != kx || prevSlotY[slot] != ky) {
                    anyChanged = true
                }
                prevSlotActive[slot] = true
                prevSlotX[slot] = kx
                prevSlotY[slot] = ky
            } else {
                if (prevSlotActive[slot]) {
                    anyChanged = true
                }
                prevSlotActive[slot] = false
            }
        }

        if (!anyChanged) return null

        lastEmitNs = nowNs
        encodePacket()
        return outBytes
    }

    private fun allocateSlot(nodeId: String): Int? {
        nodeSlotMap[nodeId]?.let { existing ->
            return existing
        }
        for (i in 0 until MAX_SLOTS) {
            if (slotOwner[i] == null) {
                slotOwner[i] = nodeId
                nodeSlotMap[nodeId] = i
                return i
            }
        }
        return null
    }

    private fun isNodeTriggered(node: TouchNodeConfig, raw: MutableRawFrame): Boolean {
        val mod = node.modifierInput
        if (mod == null) {
            if (node.input in suppressedSingleInputs) return false
            return isInputPressed(node.input, raw)
        }
        return isInputPressed(mod, raw) && isInputPressed(node.input, raw)
    }

    private fun evaluateNode(
        node: TouchNodeConfig,
        raw: MutableRawFrame,
        config: DriverConfig,
        gyroAimActive: Boolean,
        dtSec: Float,
        nowNs: Long,
        out: SlotOutput
    ) {
        when (node.kind) {
            TouchNodeKind.TAP -> {
                if (isNodeTriggered(node, raw)) {
                    out.active = true
                    out.x = node.xNorm
                    out.y = node.yNorm
                }
            }

            TouchNodeKind.RAPID_TAP -> {
                if (isNodeTriggered(node, raw)) {
                    val periodNs = (1_000_000_000L / node.rapidHz.coerceIn(3, 30)).coerceAtLeast(30_000_000L)
                    val phase = nowNs % periodNs
                    if (phase < periodNs / 2L) {
                        out.active = true
                        out.x = node.xNorm
                        out.y = node.yNorm
                    }
                }
            }

            TouchNodeKind.MACRO -> {
                val rt = macroRuntimes.getOrPut(node.id) { MacroRuntime() }
                val pressed = isNodeTriggered(node, raw)
                val steps = if (node.macroSteps.isNotEmpty()) {
                    node.macroSteps
                } else {
                    // Default fallback 2-step double-tap at node coordinate
                    listOf(
                        com.apex5.driver.model.MacroStep(node.xNorm, node.yNorm, 45, 55),
                        com.apex5.driver.model.MacroStep(node.xNorm, node.yNorm, 45, 45)
                    )
                }

                if (pressed && !rt.prevPressed && !rt.active) {
                    rt.active = true
                    rt.stepIdx = 0
                    rt.isHolding = true
                    rt.phaseStartNs = nowNs
                }
                rt.prevPressed = pressed

                if (!rt.active) return

                if (rt.stepIdx >= steps.size) {
                    rt.active = false
                    return
                }

                val step = steps[rt.stepIdx]
                val elapsedMs = (nowNs - rt.phaseStartNs) / 1_000_000L
                if (rt.isHolding) {
                    if (elapsedMs < step.holdMs) {
                        out.active = true
                        out.x = step.xNorm
                        out.y = step.yNorm
                    } else {
                        rt.isHolding = false
                        rt.phaseStartNs = nowNs
                        out.active = false
                    }
                } else {
                    if (elapsedMs >= step.delayAfterMs) {
                        rt.stepIdx++
                        if (rt.stepIdx >= steps.size) {
                            rt.active = false
                        } else {
                            rt.isHolding = true
                            rt.phaseStartNs = nowNs
                            val nextStep = steps[rt.stepIdx]
                            out.active = true
                            out.x = nextStep.xNorm
                            out.y = nextStep.yNorm
                        }
                    } else {
                        out.active = false
                    }
                }
            }

            TouchNodeKind.JOYSTICK -> {
                if (node.modifierInput != null && !isInputPressed(node.modifierInput, raw)) {
                    joystickRuntimes[node.id]?.let {
                        it.touching = false
                        it.downFrames = 0
                    }
                    return
                }
                val (sx, sy) = readStickNormalized(node.input, raw, config.stickDeadzonePercent)
                val rt = joystickRuntimes.getOrPut(node.id) { JoystickRuntime() }
                if (sx == 0f && sy == 0f) {
                    rt.touching = false
                    rt.downFrames = 0
                } else {
                    if (!rt.touching) {
                        rt.touching = true
                        rt.downFrames = 0
                    }
                    // Anchor frame 0 at center so floating game joysticks always capture cleanly
                    val factor = if (rt.downFrames == 0) 0f else 1f
                    rt.downFrames++
                    out.active = true
                    out.x = (node.xNorm + sx * node.radiusNorm * factor).coerceIn(0.01f, 0.99f)
                    out.y = (node.yNorm + sy * node.radiusNorm * factor).coerceIn(0.01f, 0.99f)
                }
            }

            TouchNodeKind.CAMERA_SWIPE -> {
                if (node.modifierInput != null && !isInputPressed(node.modifierInput, raw)) {
                    cameraRuntimes[node.id]?.touching = false
                    return
                }
                val (sx, sy) = readStickNormalized(node.input, raw, config.stickDeadzonePercent)
                val rt = cameraRuntimes.getOrPut(node.id) { CameraSwipeRuntime() }

                var vx = sx * 1.15f * node.sensitivity
                var vy = sy * 0.90f * node.sensitivity

                if (node.gyroAssist && gyroAimActive) {
                    val dzDps = config.gyroDeadzoneDps
                    val yaw = if (abs(raw.gyroYawDps) > dzDps) raw.gyroYawDps - sign(raw.gyroYawDps) * dzDps else 0f
                    val pitch = if (abs(raw.gyroPitchDps) > dzDps) raw.gyroPitchDps - sign(raw.gyroPitchDps) * dzDps else 0f
                    val signX = if (config.gyroInvertX) 1f else -1f
                    val signY = if (config.gyroInvertY) 1f else -1f
                    vx += yaw * signX * config.gyroSensitivityX * 0.018f
                    vy += pitch * signY * config.gyroSensitivityY * 0.018f
                }

                if (nowNs < rt.resetReleaseUntilNs) {
                    out.active = false
                    return
                }

                val hasMotion = abs(vx) > 0.005f || abs(vy) > 0.005f
                if (!hasMotion) {
                    if (rt.touching) {
                        if (rt.idleSinceNs == 0L) rt.idleSinceNs = nowNs
                        if ((nowNs - rt.idleSinceNs) >= CAMERA_IDLE_RELEASE_NS) {
                            rt.touching = false
                            rt.idleSinceNs = 0L
                            out.active = false
                        } else {
                            out.active = true
                            out.x = rt.curX
                            out.y = rt.curY
                        }
                    }
                    return
                }

                rt.idleSinceNs = 0L
                if (!rt.touching) {
                    rt.touching = true
                    rt.curX = node.xNorm
                    rt.curY = node.yNorm
                    out.active = true
                    out.x = rt.curX
                    out.y = rt.curY
                    return
                }

                rt.curX += vx * dtSec
                rt.curY += vy * dtSec

                val maxRx = node.radiusNorm.coerceIn(0.06f, 0.30f)
                val maxRy = (node.radiusNorm * 0.85f).coerceIn(0.05f, 0.28f)

                val outOfBounds =
                    abs(rt.curX - node.xNorm) > maxRx ||
                        abs(rt.curY - node.yNorm) > maxRy ||
                        rt.curX < 0.04f || rt.curX > 0.96f ||
                        rt.curY < 0.04f || rt.curY > 0.96f

                if (outOfBounds) {
                    rt.touching = false
                    rt.resetReleaseUntilNs = nowNs + CAMERA_RESET_COOLDOWN_NS
                    out.active = false
                } else {
                    out.active = true
                    out.x = rt.curX
                    out.y = rt.curY
                }
            }
        }
    }

    private fun readStickNormalized(
        input: ControllerInputSource,
        raw: MutableRawFrame,
        deadzonePercent: Int
    ): Pair<Float, Float> {
        val (rx, ry) = when (input) {
            ControllerInputSource.RIGHT_STICK -> raw.rx to raw.ry
            else -> raw.lx to raw.ly
        }
        val dz = (deadzonePercent / 100.0f) * 32767.0f
        val mag = hypot(rx.toFloat(), ry.toFloat())
        if (mag <= dz || mag < 1f) return 0f to 0f
        val norm = ((mag - dz) / (32767.0f - dz)).coerceIn(0f, 1f)
        val scale = norm / mag
        return (rx * scale) to (ry * scale)
    }

    private fun isInputPressed(input: ControllerInputSource, raw: MutableRawFrame): Boolean {
        return when (input) {
            ControllerInputSource.LEFT_STICK -> hypot(raw.lx.toFloat(), raw.ly.toFloat()) > 16000f
            ControllerInputSource.RIGHT_STICK -> hypot(raw.rx.toFloat(), raw.ry.toFloat()) > 16000f
            ControllerInputSource.A -> raw.a
            ControllerInputSource.B -> raw.b
            ControllerInputSource.X -> raw.x
            ControllerInputSource.Y -> raw.y
            ControllerInputSource.LB -> raw.lb
            ControllerInputSource.RB -> raw.rb
            ControllerInputSource.LT -> raw.lt >= 48
            ControllerInputSource.RT -> raw.rt >= 48
            ControllerInputSource.L3 -> raw.l3
            ControllerInputSource.R3 -> raw.r3
            ControllerInputSource.BACK -> raw.back
            ControllerInputSource.START -> raw.start
            ControllerInputSource.DPAD_UP -> raw.hatY < 0
            ControllerInputSource.DPAD_DOWN -> raw.hatY > 0
            ControllerInputSource.DPAD_LEFT -> raw.hatX < 0
            ControllerInputSource.DPAD_RIGHT -> raw.hatX > 0
            ControllerInputSource.M1 -> raw.isEnhanced && raw.m1
            ControllerInputSource.M2 -> raw.isEnhanced && raw.m2
            ControllerInputSource.M3 -> raw.isEnhanced && raw.m3
            ControllerInputSource.M4 -> raw.isEnhanced && raw.m4
            ControllerInputSource.LM -> raw.isEnhanced && raw.lm
            ControllerInputSource.RM -> raw.isEnhanced && raw.rm
        }
    }

    private fun transformToKernelCoords(
        nx: Float,
        ny: Float,
        displayRotation: Int,
        mode: TouchOrientationMode
    ): Pair<Int, Int> {
        val x = nx.coerceIn(0f, 1f)
        val y = ny.coerceIn(0f, 1f)

        val (rx, ry) = when (mode) {
            TouchOrientationMode.DIRECT_NO_ROTATE -> x to y
            TouchOrientationMode.ROTATE_90 -> (1f - y) to x
            TouchOrientationMode.ROTATE_180 -> (1f - x) to (1f - y)
            TouchOrientationMode.ROTATE_270 -> y to (1f - x)
            TouchOrientationMode.AUTO -> when (displayRotation) {
                Surface.ROTATION_90 -> (1f - y) to x
                Surface.ROTATION_180 -> (1f - x) to (1f - y)
                Surface.ROTATION_270 -> y to (1f - x)
                else -> x to y
            }
        }
        return (rx * TOUCH_MAX).roundToInt().coerceIn(0, 10000) to
            (ry * TOUCH_MAX).roundToInt().coerceIn(0, 10000)
    }

    private fun encodePacket() {
        outBuf.clear()
        outBuf.putInt(TOUCH_MAGIC)
        var activeCount = 0
        for (slot in 0 until MAX_SLOTS) {
            if (prevSlotActive[slot]) activeCount++
        }
        outBuf.put(activeCount.toByte())
        outBuf.put(0)
        outBuf.put(0)
        outBuf.put(0)

        var written = 0
        for (slot in 0 until MAX_SLOTS) {
            if (prevSlotActive[slot]) {
                outBuf.put(1)
                outBuf.put(slot.toByte())
                outBuf.putShort(prevSlotX[slot].toShort())
                outBuf.putShort(prevSlotY[slot].toShort())
                written++
            }
        }
        while (written < MAX_SLOTS) {
            outBuf.put(0)
            outBuf.put(0)
            outBuf.putShort(0)
            outBuf.putShort(0)
            written++
        }
    }
}
