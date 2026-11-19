package com.apex5.driver.usb

import com.apex5.driver.model.DriverConfig
import com.apex5.driver.model.ExtraButtonId
import com.apex5.driver.model.GyroTriggerMode
import com.apex5.driver.model.LiveControllerState
import com.apex5.driver.model.MappingAction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign

class InputMapper {
    companion object {
        private const val PKT_MAGIC = 0x41505835
        const val PACKET_SIZE = 24
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

    private var tmpStickX = 0
    private var tmpStickY = 0

    private fun computeStickDeadzone(x: Int, y: Int, deadzonePercent: Int) {
        if (deadzonePercent <= 0) {
            tmpStickX = x
            tmpStickY = y
            return
        }
        val dz = (deadzonePercent / 100.0f) * 32767.0f
        val mag = hypot(x.toFloat(), y.toFloat())
        if (mag <= dz) {
            tmpStickX = 0
            tmpStickY = 0
            return
        }
        val norm = ((mag - dz) / (32767.0f - dz)).coerceIn(0f, 1f)
        val scale = (norm * 32767.0f) / mag
        tmpStickX = (x * scale).roundToInt().coerceIn(-32768, 32767)
        tmpStickY = (y * scale).roundToInt().coerceIn(-32768, 32767)
    }

    fun processPacket(
        raw: MutableRawFrame,
        config: DriverConfig,
        packetRateHz: Int,
        buildLiveState: Boolean
    ): Pair<ByteArray, LiveControllerState?> {
        var a = raw.a
        var b = raw.b
        var x = raw.x
        var y = raw.y
        var lb = raw.lb
        var rb = raw.rb
        var back = raw.back
        var start = raw.start
        var home = raw.home
        var l3 = raw.l3
        var r3 = raw.r3
        var lt = raw.lt
        var rt = raw.rt
        var hatX = raw.hatX
        var hatY = raw.hatY
        var extraKeys = 0

        var gyroHoldPressed = false
        var gyroTogglePressed = false

        if (raw.isEnhanced) {
            val mappings = config.buttonMappings
            for (idx in 0 until 6) {
                val (btnId, pressed) = when (idx) {
                    0 -> ExtraButtonId.M1 to raw.m1
                    1 -> ExtraButtonId.M2 to raw.m2
                    2 -> ExtraButtonId.M3 to raw.m3
                    3 -> ExtraButtonId.M4 to raw.m4
                    4 -> ExtraButtonId.LM to raw.lm
                    else -> ExtraButtonId.RM to raw.rm
                }
                if (!pressed) continue
                when (mappings[btnId] ?: MappingAction.NONE) {
                    MappingAction.NONE -> Unit
                    MappingAction.A -> a = true
                    MappingAction.B -> b = true
                    MappingAction.X -> x = true
                    MappingAction.Y -> y = true
                    MappingAction.LB -> lb = true
                    MappingAction.RB -> rb = true
                    MappingAction.L3 -> l3 = true
                    MappingAction.R3 -> r3 = true
                    MappingAction.LT_FULL -> lt = 255
                    MappingAction.RT_FULL -> rt = 255
                    MappingAction.BACK -> back = true
                    MappingAction.START -> start = true
                    MappingAction.HOME -> home = true
                    MappingAction.DPAD_UP -> hatY = -1
                    MappingAction.DPAD_DOWN -> hatY = 1
                    MappingAction.DPAD_LEFT -> hatX = -1
                    MappingAction.DPAD_RIGHT -> hatX = 1
                    MappingAction.GYRO_HOLD -> gyroHoldPressed = true
                    MappingAction.GYRO_TOGGLE -> gyroTogglePressed = true
                    MappingAction.EXTRA_BUTTON -> extraKeys = extraKeys or (1 shl idx)
                }
            }
        }

        if (gyroTogglePressed && !prevTogglePressed) {
            gyroToggleActive = !gyroToggleActive
        }
        prevTogglePressed = gyroTogglePressed
        lastGyroToggleActive = gyroToggleActive
        lastGyroHoldPressed = gyroHoldPressed

        val gyroAimActive = raw.isEnhanced && when (config.gyroTriggerMode) {
            GyroTriggerMode.OFF -> false
            GyroTriggerMode.ALWAYS_ON -> true
            GyroTriggerMode.HOLD_LT -> lt >= 48
            GyroTriggerMode.HOLD_MAPPING -> gyroHoldPressed || gyroToggleActive
        }

        computeStickDeadzone(raw.lx, raw.ly, config.stickDeadzonePercent)
        val lx = tmpStickX
        val ly = tmpStickY

        computeStickDeadzone(raw.rx, raw.ry, config.stickDeadzonePercent)
        var rx = tmpStickX
        var ry = tmpStickY

        if (gyroAimActive) {
            val dzDps = config.gyroDeadzoneDps
            val yaw = if (abs(raw.gyroYawDps) > dzDps) {
                raw.gyroYawDps - sign(raw.gyroYawDps) * dzDps
            } else 0f
            val pitch = if (abs(raw.gyroPitchDps) > dzDps) {
                raw.gyroPitchDps - sign(raw.gyroPitchDps) * dzDps
            } else 0f

            val signX = if (config.gyroInvertX) 1f else -1f
            val signY = if (config.gyroInvertY) 1f else -1f
            val gyroRx = (yaw * signX * config.gyroSensitivityX * 160f).roundToInt()
            val gyroRy = (pitch * signY * config.gyroSensitivityY * 160f).roundToInt()

            rx = (rx + gyroRx).coerceIn(-32768, 32767)
            ry = (ry + gyroRy).coerceIn(-32768, 32767)
        }

        var btnMask = 0
        if (a) btnMask = btnMask or (1 shl 0)
        if (b) btnMask = btnMask or (1 shl 1)
        if (x) btnMask = btnMask or (1 shl 2)
        if (y) btnMask = btnMask or (1 shl 3)
        if (lb) btnMask = btnMask or (1 shl 4)
        if (rb) btnMask = btnMask or (1 shl 5)
        if (back) btnMask = btnMask or (1 shl 6)
        if (start) btnMask = btnMask or (1 shl 7)
        if (home && !config.blockHomeButton) btnMask = btnMask or (1 shl 8)
        if (l3) btnMask = btnMask or (1 shl 9)
        if (r3) btnMask = btnMask or (1 shl 10)

        outBuf.clear()
        outBuf.putInt(PKT_MAGIC)
        outBuf.putShort(btnMask.toShort())
        outBuf.put(extraKeys.toByte())
        outBuf.put(lt.coerceIn(0, 255).toByte())
        outBuf.put(rt.coerceIn(0, 255).toByte())
        outBuf.put(hatX.coerceIn(-1, 1).toByte())
        outBuf.put(hatY.coerceIn(-1, 1).toByte())
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
                lt = lt,
                rt = rt,
                hatX = hatX,
                hatY = hatY,
                a = a,
                b = b,
                x = x,
                y = y,
                lb = lb,
                rb = rb,
                back = back,
                start = start,
                home = home,
                l3 = l3,
                r3 = r3,
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
}
