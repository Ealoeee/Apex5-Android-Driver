package com.apex5.driver.config

import android.content.Context
import android.content.SharedPreferences
import com.apex5.driver.model.ControllerInputSource
import com.apex5.driver.model.DriverConfig
import com.apex5.driver.model.DriverOutputMode
import com.apex5.driver.model.ExtraButtonId
import com.apex5.driver.model.GamepadMacroStep
import com.apex5.driver.model.GyroTriggerMode
import com.apex5.driver.model.InputPreferenceMode
import com.apex5.driver.model.MappingAction
import com.apex5.driver.model.SpaceStationPreset
import com.apex5.driver.model.StandardButtonId
import com.apex5.driver.model.StickCircularityMode
import com.apex5.driver.model.StickResponseCurve
import com.apex5.driver.model.StickTuningConfig
import com.apex5.driver.model.TouchOrientationMode
import com.apex5.driver.model.TriggerHapticMode
import com.apex5.driver.model.TriggerTuningConfig
import org.json.JSONArray
import org.json.JSONObject

object ConfigStore {
    private const val PREF_NAME = "apex5_driver_config"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): DriverConfig {
        val p = prefs(context)
        val outputMode = runCatching {
            DriverOutputMode.valueOf(p.getString("output_mode", DriverOutputMode.GAMEPAD.name)!!)
        }.getOrDefault(DriverOutputMode.GAMEPAD)

        val inputMode = runCatching {
            InputPreferenceMode.valueOf(p.getString("input_mode", InputPreferenceMode.AUTO_PREFER_ENHANCED.name)!!)
        }.getOrDefault(InputPreferenceMode.AUTO_PREFER_ENHANCED)

        val gyroMode = runCatching {
            GyroTriggerMode.valueOf(p.getString("gyro_mode", GyroTriggerMode.HOLD_LT.name)!!)
        }.getOrDefault(GyroTriggerMode.HOLD_LT)

        val touchOri = runCatching {
            TouchOrientationMode.valueOf(p.getString("touch_orientation", TouchOrientationMode.AUTO.name)!!)
        }.getOrDefault(TouchOrientationMode.AUTO)

        val defaults = DriverConfig()
        val mappings = ExtraButtonId.entries.associateWith { btn ->
            val saved = p.getString("map_${btn.name}", defaults.buttonMappings[btn]?.name ?: MappingAction.NONE.name)
            runCatching { MappingAction.valueOf(saved!!) }.getOrDefault(MappingAction.NONE)
        }

        val faceMappings = StandardButtonId.entries.associateWith { btn ->
            val saved = p.getString("fmap_${btn.name}", btn.defaultAction.name)
            runCatching { MappingAction.valueOf(saved!!) }.getOrDefault(btn.defaultAction)
        }

        val turboKeys = p.getString("turbo_keys", "")
            ?.split(",")
            ?.mapNotNull { token ->
                val trimmed = token.trim()
                if (trimmed.isEmpty()) null else runCatching { ControllerInputSource.valueOf(trimmed) }.getOrNull()
            }?.toSet() ?: emptySet()

        val legacyDeadzone = p.getInt("deadzone_pct", 4).coerceIn(0, 25)
        val leftStick = p.getString("left_stick_json", null)?.let { parseStickJson(it, legacyDeadzone) }
            ?: StickTuningConfig(centerDeadzonePercent = legacyDeadzone)
        val rightStick = p.getString("right_stick_json", null)?.let { parseStickJson(it, legacyDeadzone) }
            ?: StickTuningConfig(centerDeadzonePercent = legacyDeadzone)

        val leftTrigger = p.getString("left_trigger_json", null)?.let { parseTriggerJson(it) }
            ?: TriggerTuningConfig()
        val rightTrigger = p.getString("right_trigger_json", null)?.let { parseTriggerJson(it) }
            ?: TriggerTuningConfig()

        val macros = p.getString("gamepad_macros_json", null)?.let { parseMacrosJson(it) }
            ?: defaults.gamepadMacros

        return DriverConfig(
            outputMode = outputMode,
            inputMode = inputMode,
            stickDeadzonePercent = leftStick.centerDeadzonePercent,
            enableExtraButtonDevice = p.getBoolean("enable_extra_dev", false),
            blockHomeButton = p.getBoolean("block_home_btn", false),
            touchOrientationMode = touchOri,
            activeTouchProfileId = p.getString("active_touch_profile", "fps_default") ?: "fps_default",
            showTouchHints = p.getBoolean("show_touch_hints", true),
            buttonMappings = mappings,
            faceButtonMappings = faceMappings,
            turboEnabledKeys = turboKeys,
            turboRateHz = p.getInt("turbo_rate_hz", 12).coerceIn(3, 25),
            gamepadMacros = macros,
            leftStick = leftStick,
            rightStick = rightStick,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            mainRumbleIntensityPercent = p.getInt("main_rumble_pct", 100).coerceIn(0, 100),
            triggerRumbleIntensityPercent = p.getInt("trigger_rumble_pct", 80).coerceIn(0, 100),
            gyroTriggerMode = gyroMode,
            gyroSensitivityX = p.getFloat("gyro_sens_x", 1.8f).coerceIn(0.2f, 5.0f),
            gyroSensitivityY = p.getFloat("gyro_sens_y", 1.5f).coerceIn(0.2f, 5.0f),
            gyroInvertX = p.getBoolean("gyro_inv_x", false),
            gyroInvertY = p.getBoolean("gyro_inv_y", false),
            gyroDeadzoneDps = p.getFloat("gyro_dz_dps", 2.0f).coerceIn(0f, 15f),
            gyroSmoothing = p.getFloat("gyro_smoothing", 0.15f).coerceIn(0f, 0.75f)
        )
    }

    fun save(context: Context, cfg: DriverConfig) {
        prefs(context).edit().apply {
            putString("output_mode", cfg.outputMode.name)
            putString("input_mode", cfg.inputMode.name)
            putInt("deadzone_pct", cfg.leftStick.centerDeadzonePercent)
            putBoolean("enable_extra_dev", cfg.enableExtraButtonDevice)
            putBoolean("block_home_btn", cfg.blockHomeButton)
            putString("touch_orientation", cfg.touchOrientationMode.name)
            putString("active_touch_profile", cfg.activeTouchProfileId)
            putBoolean("show_touch_hints", cfg.showTouchHints)
            cfg.buttonMappings.forEach { (k, v) -> putString("map_${k.name}", v.name) }
            cfg.faceButtonMappings.forEach { (k, v) -> putString("fmap_${k.name}", v.name) }
            putString("turbo_keys", cfg.turboEnabledKeys.joinToString(",") { it.name })
            putInt("turbo_rate_hz", cfg.turboRateHz)
            putString("gamepad_macros_json", macrosToJson(cfg.gamepadMacros).toString())
            putString("left_stick_json", stickToJson(cfg.leftStick).toString())
            putString("right_stick_json", stickToJson(cfg.rightStick).toString())
            putString("left_trigger_json", triggerToJson(cfg.leftTrigger).toString())
            putString("right_trigger_json", triggerToJson(cfg.rightTrigger).toString())
            putInt("main_rumble_pct", cfg.mainRumbleIntensityPercent)
            putInt("trigger_rumble_pct", cfg.triggerRumbleIntensityPercent)
            putString("gyro_mode", cfg.gyroTriggerMode.name)
            putFloat("gyro_sens_x", cfg.gyroSensitivityX)
            putFloat("gyro_sens_y", cfg.gyroSensitivityY)
            putBoolean("gyro_inv_x", cfg.gyroInvertX)
            putBoolean("gyro_inv_y", cfg.gyroInvertY)
            putFloat("gyro_dz_dps", cfg.gyroDeadzoneDps)
            putFloat("gyro_smoothing", cfg.gyroSmoothing)
            apply()
        }
    }

    fun applySpaceStationPreset(current: DriverConfig, preset: SpaceStationPreset): DriverConfig {
        return when (preset) {
            SpaceStationPreset.BALANCED -> current.copy(
                stickDeadzonePercent = 4,
                leftStick = StickTuningConfig(),
                rightStick = StickTuningConfig(),
                leftTrigger = TriggerTuningConfig(),
                rightTrigger = TriggerTuningConfig(),
                turboEnabledKeys = emptySet(),
                mainRumbleIntensityPercent = 100,
                triggerRumbleIntensityPercent = 80,
                gyroTriggerMode = GyroTriggerMode.HOLD_LT,
                gyroSmoothing = 0.15f
            )
            SpaceStationPreset.FPS_COMPETITIVE -> current.copy(
                stickDeadzonePercent = 2,
                leftStick = StickTuningConfig(
                    centerDeadzonePercent = 2,
                    centerCompensationPercent = 2,
                    outerDeadzonePercent = 5,
                    curvePreset = StickResponseCurve.FAST_RESPONSE,
                    circularityMode = StickCircularityMode.RECTANGLE,
                    antiJitterEnabled = false
                ),
                rightStick = StickTuningConfig(
                    centerDeadzonePercent = 1,
                    centerCompensationPercent = 3,
                    outerDeadzonePercent = 0,
                    curvePreset = StickResponseCurve.FPS_PRECISION,
                    circularityMode = StickCircularityMode.CIRCLE,
                    antiJitterEnabled = true
                ),
                leftTrigger = TriggerTuningConfig(
                    deadzoneStartPercent = 2,
                    deadzoneEndPercent = 10,
                    hairTriggerMode = false,
                    hapticMode = TriggerHapticMode.SYNC_MAIN,
                    hapticStrengthPercent = 65
                ),
                rightTrigger = TriggerTuningConfig(
                    deadzoneStartPercent = 2,
                    deadzoneEndPercent = 0,
                    hairTriggerMode = true,
                    hapticMode = TriggerHapticMode.SNIPER_CLICK,
                    hapticStrengthPercent = 85
                ),
                gyroTriggerMode = GyroTriggerMode.HOLD_LT,
                gyroSensitivityX = 2.0f,
                gyroSensitivityY = 1.6f,
                gyroDeadzoneDps = 1.5f,
                gyroSmoothing = 0.2f
            )
            SpaceStationPreset.RACING_SIM -> current.copy(
                stickDeadzonePercent = 0,
                leftStick = StickTuningConfig(
                    centerDeadzonePercent = 0,
                    curvePreset = StickResponseCurve.LINEAR,
                    circularityMode = StickCircularityMode.CIRCLE,
                    antiJitterEnabled = false
                ),
                rightStick = StickTuningConfig(
                    centerDeadzonePercent = 4,
                    curvePreset = StickResponseCurve.LINEAR,
                    circularityMode = StickCircularityMode.CIRCLE
                ),
                leftTrigger = TriggerTuningConfig(
                    deadzoneStartPercent = 0,
                    deadzoneEndPercent = 0,
                    hairTriggerMode = false,
                    hapticMode = TriggerHapticMode.RACING_PROGRESSIVE,
                    hapticStrengthPercent = 80
                ),
                rightTrigger = TriggerTuningConfig(
                    deadzoneStartPercent = 0,
                    deadzoneEndPercent = 0,
                    hairTriggerMode = false,
                    hapticMode = TriggerHapticMode.RACING_PROGRESSIVE,
                    hapticStrengthPercent = 90
                ),
                gyroTriggerMode = GyroTriggerMode.OFF
            )
            SpaceStationPreset.ACTION_COMBO -> current.copy(
                stickDeadzonePercent = 3,
                leftStick = StickTuningConfig(
                    centerDeadzonePercent = 3,
                    outerDeadzonePercent = 8,
                    curvePreset = StickResponseCurve.FAST_RESPONSE,
                    circularityMode = StickCircularityMode.RECTANGLE
                ),
                rightStick = StickTuningConfig(
                    centerDeadzonePercent = 3,
                    curvePreset = StickResponseCurve.FAST_RESPONSE,
                    circularityMode = StickCircularityMode.CIRCLE
                ),
                leftTrigger = TriggerTuningConfig(hairTriggerMode = true, hapticMode = TriggerHapticMode.SYNC_MAIN),
                rightTrigger = TriggerTuningConfig(hairTriggerMode = true, hapticMode = TriggerHapticMode.MACHINE_GUN, hapticStrengthPercent = 70),
                turboEnabledKeys = setOf(ControllerInputSource.M1, ControllerInputSource.M2),
                turboRateHz = 15
            )
        }
    }

    fun exportSpaceStationJson(cfg: DriverConfig): String {
        val obj = JSONObject()
        obj.put("schema", "apex5-space-v1")
        obj.put("leftStick", stickToJson(cfg.leftStick))
        obj.put("rightStick", stickToJson(cfg.rightStick))
        obj.put("leftTrigger", triggerToJson(cfg.leftTrigger))
        obj.put("rightTrigger", triggerToJson(cfg.rightTrigger))
        obj.put("mainRumbleIntensityPercent", cfg.mainRumbleIntensityPercent)
        obj.put("triggerRumbleIntensityPercent", cfg.triggerRumbleIntensityPercent)
        obj.put("turboRateHz", cfg.turboRateHz)
        obj.put("turboEnabledKeys", JSONArray(cfg.turboEnabledKeys.map { it.name }))
        obj.put("buttonMappings", JSONObject().apply {
            cfg.buttonMappings.forEach { (k, v) -> put(k.name, v.name) }
        })
        obj.put("faceButtonMappings", JSONObject().apply {
            cfg.faceButtonMappings.forEach { (k, v) -> put(k.name, v.name) }
        })
        obj.put("gamepadMacros", macrosToJson(cfg.gamepadMacros))
        obj.put("gyroTriggerMode", cfg.gyroTriggerMode.name)
        obj.put("gyroSensitivityX", cfg.gyroSensitivityX.toDouble())
        obj.put("gyroSensitivityY", cfg.gyroSensitivityY.toDouble())
        obj.put("gyroInvertX", cfg.gyroInvertX)
        obj.put("gyroInvertY", cfg.gyroInvertY)
        obj.put("gyroDeadzoneDps", cfg.gyroDeadzoneDps.toDouble())
        obj.put("gyroSmoothing", cfg.gyroSmoothing.toDouble())
        return obj.toString(2)
    }

    fun importSpaceStationJson(jsonText: String, base: DriverConfig): Result<DriverConfig> {
        return runCatching {
            val obj = JSONObject(jsonText.trim())
            val ls = obj.optJSONObject("leftStick")?.let { parseStickJson(it.toString(), base.leftStick.centerDeadzonePercent) } ?: base.leftStick
            val rs = obj.optJSONObject("rightStick")?.let { parseStickJson(it.toString(), base.rightStick.centerDeadzonePercent) } ?: base.rightStick
            val lt = obj.optJSONObject("leftTrigger")?.let { parseTriggerJson(it.toString()) } ?: base.leftTrigger
            val rt = obj.optJSONObject("rightTrigger")?.let { parseTriggerJson(it.toString()) } ?: base.rightTrigger

            val btnMap = obj.optJSONObject("buttonMappings")?.let { mo ->
                ExtraButtonId.entries.associateWith { id ->
                    runCatching { MappingAction.valueOf(mo.optString(id.name, MappingAction.NONE.name)) }
                        .getOrDefault(base.buttonMappings[id] ?: MappingAction.NONE)
                }
            } ?: base.buttonMappings

            val faceMap = obj.optJSONObject("faceButtonMappings")?.let { fo ->
                StandardButtonId.entries.associateWith { id ->
                    runCatching { MappingAction.valueOf(fo.optString(id.name, id.defaultAction.name)) }
                        .getOrDefault(id.defaultAction)
                }
            } ?: base.faceButtonMappings

            val turboKeys = obj.optJSONArray("turboEnabledKeys")?.let { arr ->
                buildSet {
                    for (i in 0 until arr.length()) {
                        runCatching { ControllerInputSource.valueOf(arr.getString(i)) }.getOrNull()?.let { add(it) }
                    }
                }
            } ?: base.turboEnabledKeys

            val macros = obj.optJSONObject("gamepadMacros")?.let { parseMacrosJson(it.toString()) } ?: base.gamepadMacros

            base.copy(
                stickDeadzonePercent = ls.centerDeadzonePercent,
                leftStick = ls,
                rightStick = rs,
                leftTrigger = lt,
                rightTrigger = rt,
                mainRumbleIntensityPercent = obj.optInt("mainRumbleIntensityPercent", base.mainRumbleIntensityPercent).coerceIn(0, 100),
                triggerRumbleIntensityPercent = obj.optInt("triggerRumbleIntensityPercent", base.triggerRumbleIntensityPercent).coerceIn(0, 100),
                turboRateHz = obj.optInt("turboRateHz", base.turboRateHz).coerceIn(3, 25),
                turboEnabledKeys = turboKeys,
                buttonMappings = btnMap,
                faceButtonMappings = faceMap,
                gamepadMacros = macros,
                gyroTriggerMode = runCatching { GyroTriggerMode.valueOf(obj.optString("gyroTriggerMode", base.gyroTriggerMode.name)) }
                    .getOrDefault(base.gyroTriggerMode),
                gyroSensitivityX = obj.optDouble("gyroSensitivityX", base.gyroSensitivityX.toDouble()).toFloat().coerceIn(0.2f, 5.0f),
                gyroSensitivityY = obj.optDouble("gyroSensitivityY", base.gyroSensitivityY.toDouble()).toFloat().coerceIn(0.2f, 5.0f),
                gyroInvertX = obj.optBoolean("gyroInvertX", base.gyroInvertX),
                gyroInvertY = obj.optBoolean("gyroInvertY", base.gyroInvertY),
                gyroDeadzoneDps = obj.optDouble("gyroDeadzoneDps", base.gyroDeadzoneDps.toDouble()).toFloat().coerceIn(0f, 15f),
                gyroSmoothing = obj.optDouble("gyroSmoothing", base.gyroSmoothing.toDouble()).toFloat().coerceIn(0f, 0.75f)
            )
        }
    }

    private fun stickToJson(s: StickTuningConfig): JSONObject = JSONObject().apply {
        put("centerDeadzonePercent", s.centerDeadzonePercent)
        put("centerCompensationPercent", s.centerCompensationPercent)
        put("outerDeadzonePercent", s.outerDeadzonePercent)
        put("edgeCompensationPercent", s.edgeCompensationPercent)
        put("curvePreset", s.curvePreset.name)
        put("circularityMode", s.circularityMode.name)
        put("antiJitterEnabled", s.antiJitterEnabled)
    }

    private fun parseStickJson(json: String, fallbackDz: Int): StickTuningConfig = runCatching {
        val o = JSONObject(json)
        StickTuningConfig(
            centerDeadzonePercent = o.optInt("centerDeadzonePercent", fallbackDz).coerceIn(0, 25),
            centerCompensationPercent = o.optInt("centerCompensationPercent", 0).coerceIn(0, 15),
            outerDeadzonePercent = o.optInt("outerDeadzonePercent", 0).coerceIn(0, 25),
            edgeCompensationPercent = o.optInt("edgeCompensationPercent", 0).coerceIn(0, 20),
            curvePreset = runCatching { StickResponseCurve.valueOf(o.optString("curvePreset")) }
                .getOrDefault(StickResponseCurve.LINEAR),
            circularityMode = runCatching { StickCircularityMode.valueOf(o.optString("circularityMode")) }
                .getOrDefault(StickCircularityMode.CIRCLE),
            antiJitterEnabled = o.optBoolean("antiJitterEnabled", false)
        )
    }.getOrDefault(StickTuningConfig(centerDeadzonePercent = fallbackDz))

    private fun triggerToJson(t: TriggerTuningConfig): JSONObject = JSONObject().apply {
        put("deadzoneStartPercent", t.deadzoneStartPercent)
        put("deadzoneEndPercent", t.deadzoneEndPercent)
        put("hairTriggerMode", t.hairTriggerMode)
        put("hapticMode", t.hapticMode.name)
        put("hapticStrengthPercent", t.hapticStrengthPercent)
    }

    private fun parseTriggerJson(json: String): TriggerTuningConfig = runCatching {
        val o = JSONObject(json)
        TriggerTuningConfig(
            deadzoneStartPercent = o.optInt("deadzoneStartPercent", 2).coerceIn(0, 25),
            deadzoneEndPercent = o.optInt("deadzoneEndPercent", 0).coerceIn(0, 30),
            hairTriggerMode = o.optBoolean("hairTriggerMode", false),
            hapticMode = runCatching { TriggerHapticMode.valueOf(o.optString("hapticMode")) }
                .getOrDefault(TriggerHapticMode.OFF),
            hapticStrengthPercent = o.optInt("hapticStrengthPercent", 75).coerceIn(10, 100)
        )
    }.getOrDefault(TriggerTuningConfig())

    private fun macrosToJson(map: Map<ExtraButtonId, List<GamepadMacroStep>>): JSONObject = JSONObject().apply {
        map.forEach { (btn, steps) ->
            val arr = JSONArray()
            steps.forEach { st ->
                arr.put(JSONObject().apply {
                    put("action", st.action.name)
                    put("holdMs", st.holdMs)
                    put("delayAfterMs", st.delayAfterMs)
                })
            }
            put(btn.name, arr)
        }
    }

    private fun parseMacrosJson(json: String): Map<ExtraButtonId, List<GamepadMacroStep>> = runCatching {
        val o = JSONObject(json)
        val out = mutableMapOf<ExtraButtonId, List<GamepadMacroStep>>()
        ExtraButtonId.entries.forEach { id ->
            val arr = o.optJSONArray(id.name) ?: return@forEach
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val act = runCatching { MappingAction.valueOf(item.optString("action")) }
                        .getOrDefault(MappingAction.A)
                    add(
                        GamepadMacroStep(
                            action = act,
                            holdMs = item.optInt("holdMs", 45).coerceIn(15, 500),
                            delayAfterMs = item.optInt("delayAfterMs", 55).coerceIn(15, 1000)
                        )
                    )
                }
            }
            if (list.isNotEmpty()) out[id] = list
        }
        out
    }.getOrDefault(emptyMap())
}
