package com.apex5.driver.usb

import com.apex5.driver.model.StickCircularityMode
import com.apex5.driver.model.StickResponseCurve
import com.apex5.driver.model.StickTuningConfig
import com.apex5.driver.model.TriggerTuningConfig
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

object StickMath {
    fun evaluateCurve(t: Float, curve: StickResponseCurve): Float {
        val x = t.coerceIn(0f, 1f)
        return when (curve) {
            StickResponseCurve.LINEAR -> x
            StickResponseCurve.FPS_PRECISION -> (0.35f * x + 0.65f * x * x * x).coerceIn(0f, 1f)
            StickResponseCurve.FAST_RESPONSE -> x.pow(0.65f).coerceIn(0f, 1f)
            StickResponseCurve.STABLE_DELAY -> x.pow(1.65f).coerceIn(0f, 1f)
        }
    }

    fun shapeStick(
        rawX: Int,
        rawY: Int,
        cfg: StickTuningConfig,
        prevNormX: Float = 0f,
        prevNormY: Float = 0f
    ): Pair<Float, Float> {
        val nx = (rawX / 32767f).coerceIn(-1f, 1f)
        val ny = (rawY / 32767f).coerceIn(-1f, 1f)
        val mag = hypot(nx, ny)
        val innerDz = (cfg.centerDeadzonePercent / 100f).coerceIn(0f, 0.35f)
        val outerMax = (1f - cfg.outerDeadzonePercent / 100f).coerceAtLeast(innerDz + 0.1f)

        if (mag <= innerDz || mag < 1e-4f) {
            return 0f to 0f
        }

        val t = ((mag - innerDz) / (outerMax - innerDz)).coerceIn(0f, 1f)
        val curved = evaluateCurve(t, cfg.curvePreset)
        val comp = (cfg.centerCompensationPercent / 100f).coerceIn(0f, 0.25f)
        val edgeCap = (1f - cfg.edgeCompensationPercent / 100f).coerceIn(0.25f, 1f)
        val finalMag = (comp + curved * (edgeCap - comp)).coerceIn(0f, 1f)

        val ux = nx / mag
        val uy = ny / mag

        var outX: Float
        var outY: Float
        if (cfg.circularityMode == StickCircularityMode.RECTANGLE) {
            val maxAxis = maxOf(abs(ux), abs(uy)).coerceAtLeast(1e-4f)
            val rectScale = (1f / maxAxis).coerceAtMost(1.42f)
            outX = (ux * rectScale * finalMag).coerceIn(-1f, 1f)
            outY = (uy * rectScale * finalMag).coerceIn(-1f, 1f)
        } else {
            outX = (ux * finalMag).coerceIn(-1f, 1f)
            outY = (uy * finalMag).coerceIn(-1f, 1f)
        }

        if (cfg.antiJitterEnabled) {
            outX = prevNormX * 0.28f + outX * 0.72f
            outY = prevNormY * 0.28f + outY * 0.72f
        }
        return outX to outY
    }

    fun shapeTrigger(rawVal: Int, cfg: TriggerTuningConfig): Int {
        val norm = (rawVal.coerceIn(0, 255)) / 255f
        val startDz = (cfg.deadzoneStartPercent / 100f).coerceIn(0f, 0.35f)
        val endMax = (1f - cfg.deadzoneEndPercent / 100f).coerceAtLeast(startDz + 0.08f)

        if (norm <= startDz) return 0
        if (cfg.hairTriggerMode) return 255
        val t = ((norm - startDz) / (endMax - startDz)).coerceIn(0f, 1f)
        return (t * 255f).roundToInt().coerceIn(0, 255)
    }
}
