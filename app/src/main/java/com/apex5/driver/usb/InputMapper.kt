package com.apex5.driver.usb

import com.apex5.driver.model.ControllerInputSource
import com.apex5.driver.model.DriverConfig
import com.apex5.driver.model.ExtraButtonId
import com.apex5.driver.model.GamepadMacroStep
import com.apex5.driver.model.GyroTriggerMode
import com.apex5.driver.model.LiveControllerState
import com.apex5.driver.model.MappingAction
import com.apex5.driver.model.StandardButtonId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

class InputMapper {
    companion object {
        private const val PKT_MAGIC = 0x41505835
        const val PACKET_SIZE = 24
    }

    private class GamepadMacroRuntime {
        var prevPressed = false
        var active = false
        var stepIdx = 0
        var isHolding = true
        var phaseStartNs = 0L
    }

    private var prevTogglePressed = false
    private var gyroToggleActive = false
    private var seqCounter = 0

    var lastGyroToggleActive: Boolean = false
        private set
    var lastGyroHoldPressed: Boolean = false
        private set

    private val outBytes = ByteArray(PACKET_SIZE)
    private val outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)

    private var prevLxNorm = 0f
    private var prevLyNorm = 0f
    private var prevRxNorm = 0f
    private var prevRyNorm = 0f

    private var smoothYawDps = 0f
    private var smoothPitchDps = 0f

    private val macroRuntimes = Array(6) { GamepadMacroRuntime() }

    private fun isTurboHigh(nowNs: Long, rateHz: Int): Boolean {
        val hz = rateHz.coerceIn(3, 25)
        val periodNs = 1_000_000_000L / hz
        return (nowNs % periodNs) < (periodNs / 2L)
    }

    private data class OutButtons(
        var a: Boolean = false,
        var b: Boolean = false,
        var x: Boolean = false,
        var y: Boolean = false,
        var lb: Boolean = false,
        var rb: Boolean = false,
        var l3: Boolean = false,
        var r3: Boolean = false,
        var back: Boolean = false,
        var start: Boolean = false,
        var home: Boolean = false,
        var hatX: Int = 0,
        var hatY: Int = 0,
        var lt: Int = 0,
        var rt: Int = 0,
        var gyroHold: Boolean = false,
        var gyroToggle: Boolean = false,
        var extraMask: Int = 0
    )

    private fun applyAction(action: MappingAction, extraIdx: Int, out: OutButtons) {
        when (action) {
            MappingAction.NONE -> Unit
            MappingAction.A -> out.a = true
            MappingAction.B -> out.b = true
            MappingAction.X -> out.x = true
            MappingAction.Y -> out.y = true
            MappingAction.LB -> out.lb = true
            MappingAction.RB -> out.rb = true
            MappingAction.L3 -> out.l3 = true
            MappingAction.R3 -> out.r3 = true
            MappingAction.LT_FULL -> out.lt = 255
            MappingAction.RT_FULL -> out.rt = 255
            MappingAction.BACK -> out.back = true
            MappingAction.START -> out.start = true
            MappingAction.HOME -> out.home = true
            MappingAction.DPAD_UP -> out.hatY = -1
            MappingAction.DPAD_DOWN -> out.hatY = 1
            MappingAction.DPAD_LEFT -> out.hatX = -1
            MappingAction.DPAD_RIGHT -> out.hatX = 1
            MappingAction.GYRO_HOLD -> out.gyroHold = true
            MappingAction.GYRO_TOGGLE -> out.gyroToggle = true
            MappingAction.EXTRA_BUTTON -> if (extraIdx in 0..5) out.extraMask = out.extraMask or (1 shl extraIdx)
            MappingAction.RUN_MACRO -> Unit
        }
    }

    fun processPacket(
        raw: MutableRawFrame,
        config: DriverConfig,
        packetRateHz: Int,
        buildLiveState: Boolean,
        nowNs: Long = System.nanoTime()
    ): Pair<ByteArray, LiveControllerState?> {
        val turboKeys = config.turboEnabledKeys
        val turboPulse = isTurboHigh(nowNs, config.turboRateHz)

        fun gate(src: ControllerInputSource, pressed: Boolean): Boolean {
            if (!pressed) return false
            return if (src in turboKeys) turboPulse else true
        }

        val shapedLt = StickMath.shapeTrigger(raw.lt, config.leftTrigger)
        val shapedRt = StickMath.shapeTrigger(raw.rt, config.rightTrigger)

        val out = OutButtons(
            back = gate(ControllerInputSource.BACK, raw.back),
            start = gate(ControllerInputSource.START, raw.start),
            home = raw.home,
            hatX = raw.hatX,
            hatY = raw.hatY,
            lt = if (ControllerInputSource.LT in turboKeys && shapedLt > 0 && !turboPulse) 0 else shapedLt,
            rt = if (ControllerInputSource.RT in turboKeys && shapedRt > 0 && !turboPulse) 0 else shapedRt
        )

        // Apply face/shoulder button remappings + Turbo
        val stdPairs = arrayOf(
            Triple(StandardButtonId.A, ControllerInputSource.A, raw.a),
            Triple(StandardButtonId.B, ControllerInputSource.B, raw.b),
            Triple(StandardButtonId.X, ControllerInputSource.X, raw.x),
            Triple(StandardButtonId.Y, ControllerInputSource.Y, raw.y),
            Triple(StandardButtonId.LB, ControllerInputSource.LB, raw.lb),
            Triple(StandardButtonId.RB, ControllerInputSource.RB, raw.rb),
            Triple(StandardButtonId.L3, ControllerInputSource.L3, raw.l3),
            Triple(StandardButtonId.R3, ControllerInputSource.R3, raw.r3)
        )
        for ((stdId, src, isDown) in stdPairs) {
            if (gate(src, isDown)) {
                val mapped = config.faceButtonMappings[stdId] ?: stdId.defaultAction
                applyAction(mapped, -1, out)
            }
        }

        if (raw.isEnhanced) {
            val mappings = config.buttonMappings
            val extraSources = arrayOf(
                ControllerInputSource.M1,
                ControllerInputSource.M2,
                ControllerInputSource.M3,
                ControllerInputSource.M4,
                ControllerInputSource.LM,
                ControllerInputSource.RM
            )
            for (idx in 0 until 6) {
                val (btnId, rawPressed) = when (idx) {
                    0 -> ExtraButtonId.M1 to raw.m1
                    1 -> ExtraButtonId.M2 to raw.m2
                    2 -> ExtraButtonId.M3 to raw.m3
                    3 -> ExtraButtonId.M4 to raw.m4
                    4 -> ExtraButtonId.LM to raw.lm
                    else -> ExtraButtonId.RM to raw.rm
                }
                val action = mappings[btnId] ?: MappingAction.NONE
                if (action == MappingAction.RUN_MACRO) {
                    val steps = config.gamepadMacros[btnId].orEmpty()
                    evaluateMacro(macroRuntimes[idx], rawPressed, steps, nowNs, idx, out)
                } else if (gate(extraSources[idx], rawPressed)) {
                    applyAction(action, idx, out)
                }
            }
        }

        if (out.gyroToggle && !prevTogglePressed) {
            gyroToggleActive = !gyroToggleActive
        }
        prevTogglePressed = out.gyroToggle
        lastGyroToggleActive = gyroToggleActive
        lastGyroHoldPressed = out.gyroHold

        val gyroAimActive = raw.isEnhanced && when (config.gyroTriggerMode) {
            GyroTriggerMode.OFF -> false
            GyroTriggerMode.ALWAYS_ON -> true
            GyroTriggerMode.HOLD_LT -> out.lt >= 48
            GyroTriggerMode.HOLD_MAPPING -> out.gyroHold || gyroToggleActive
        }

        val (lxNorm, lyNorm) = StickMath.shapeStick(raw.lx, raw.ly, config.leftStick, prevLxNorm, prevLyNorm)
        prevLxNorm = lxNorm
        prevLyNorm = lyNorm
        val lx = (lxNorm * 32767f).roundToInt().coerceIn(-32768, 32767)
        val ly = (lyNorm * 32767f).roundToInt().coerceIn(-32768, 32767)

        val (rxNorm, ryNorm) = StickMath.shapeStick(raw.rx, raw.ry, config.rightStick, prevRxNorm, prevRyNorm)
        prevRxNorm = rxNorm
        prevRyNorm = ryNorm
        var rx = (rxNorm * 32767f).roundToInt().coerceIn(-32768, 32767)
        var ry = (ryNorm * 32767f).roundToInt().coerceIn(-32768, 32767)

        if (gyroAimActive) {
            val alpha = (1f - config.gyroSmoothing.coerceIn(0f, 0.75f))
            smoothYawDps += (raw.gyroYawDps - smoothYawDps) * alpha
            smoothPitchDps += (raw.gyroPitchDps - smoothPitchDps) * alpha

            val dzDps = config.gyroDeadzoneDps
            val yaw = if (abs(smoothYawDps) > dzDps) smoothYawDps - sign(smoothYawDps) * dzDps else 0f
            val pitch = if (abs(smoothPitchDps) > dzDps) smoothPitchDps - sign(smoothPitchDps) * dzDps else 0f

            val signX = if (config.gyroInvertX) 1f else -1f
            val signY = if (config.gyroInvertY) 1f else -1f
            val gyroRx = (yaw * signX * config.gyroSensitivityX * 160f).roundToInt()
            val gyroRy = (pitch * signY * config.gyroSensitivityY * 160f).roundToInt()

            rx = (rx + gyroRx).coerceIn(-32768, 32767)
            ry = (ry + gyroRy).coerceIn(-32768, 32767)
        } else {
            smoothYawDps = 0f
            smoothPitchDps = 0f
        }

        var btnMask = 0
        if (out.a) btnMask = btnMask or (1 shl 0)
        if (out.b) btnMask = btnMask or (1 shl 1)
        if (out.x) btnMask = btnMask or (1 shl 2)
        if (out.y) btnMask = btnMask or (1 shl 3)
        if (out.lb) btnMask = btnMask or (1 shl 4)
        if (out.rb) btnMask = btnMask or (1 shl 5)
        if (out.back) btnMask = btnMask or (1 shl 6)
        if (out.start) btnMask = btnMask or (1 shl 7)
        if (out.home && !config.blockHomeButton) btnMask = btnMask or (1 shl 8)
        if (out.l3) btnMask = btnMask or (1 shl 9)
        if (out.r3) btnMask = btnMask or (1 shl 10)

        outBuf.clear()
        outBuf.putInt(PKT_MAGIC)
        outBuf.putShort(btnMask.toShort())
        outBuf.put(out.extraMask.toByte())
        outBuf.put(out.lt.coerceIn(0, 255).toByte())
        outBuf.put(out.rt.coerceIn(0, 255).toByte())
        outBuf.put(out.hatX.coerceIn(-1, 1).toByte())
        outBuf.put(out.hatY.coerceIn(-1, 1).toByte())
        outBuf.put(0)
        outBuf.putShort(lx.toShort())
        outBuf.putShort(ly.toShort())
        outBuf.putShort(rx.toShort())
        outBuf.putShort(ry.toShort())
        outBuf.putInt(++seqCounter)

        val live = if (buildLiveState) {
            LiveControllerState(
                source = if (raw.isEnhanced) "飞智增强 HID (0xEF)" else "标准 XInput (Interface 0)",
                lx = lx,
                ly = ly,
                rx = rx,
                ry = ry,
                lt = out.lt,
                rt = out.rt,
                hatX = out.hatX,
                hatY = out.hatY,
                a = out.a,
                b = out.b,
                x = out.x,
                y = out.y,
                lb = out.lb,
                rb = out.rb,
                back = out.back,
                start = out.start,
                home = out.home,
                l3 = out.l3,
                r3 = out.r3,
                m1 = raw.m1,
                m2 = raw.m2,
                m3 = raw.m3,
                m4 = raw.m4,
                lm = raw.lm,
                rm = raw.rm,
                gyroPitchDps = raw.gyroPitchDps,
                gyroYawDps = raw.gyroYawDps,
                gyroRollDps = raw.gyroRollDps,
                gyroAimActive = gyroAimActive,
                packetRateHz = packetRateHz
            )
        } else null

        return outBytes to live
    }

    private fun evaluateMacro(
        rt: GamepadMacroRuntime,
        pressed: Boolean,
        steps: List<GamepadMacroStep>,
        nowNs: Long,
        extraIdx: Int,
        out: OutButtons
    ) {
        if (steps.isEmpty()) return
        if (pressed && !rt.prevPressed && !rt.active) {
            rt.active = true
            rt.stepIdx = 0
            rt.isHolding = true
            rt.phaseStartNs = nowNs
        }
        rt.prevPressed = pressed

        if (!rt.active || rt.stepIdx >= steps.size) {
            rt.active = false
            return
        }

        val step = steps[rt.stepIdx]
        val elapsedMs = (nowNs - rt.phaseStartNs) / 1_000_000L
        if (rt.isHolding) {
            if (elapsedMs < step.holdMs) {
                applyAction(step.action, extraIdx, out)
            } else {
                rt.isHolding = false
                rt.phaseStartNs = nowNs
            }
        } else {
            if (elapsedMs >= step.delayAfterMs) {
                rt.stepIdx++
                if (rt.stepIdx >= steps.size) {
                    rt.active = false
                } else {
                    rt.isHolding = true
                    rt.phaseStartNs = nowNs
                    applyAction(steps[rt.stepIdx].action, extraIdx, out)
                }
            }
        }
    }
}
