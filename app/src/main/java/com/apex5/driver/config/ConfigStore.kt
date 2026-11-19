package com.apex5.driver.config

import android.content.Context
import android.content.SharedPreferences
import com.apex5.driver.model.DriverConfig
import com.apex5.driver.model.DriverOutputMode
import com.apex5.driver.model.ExtraButtonId
import com.apex5.driver.model.GyroTriggerMode
import com.apex5.driver.model.InputPreferenceMode
import com.apex5.driver.model.MappingAction
import com.apex5.driver.model.TouchOrientationMode

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

        val defaults = DriverConfig().buttonMappings
        val mappings = ExtraButtonId.entries.associateWith { btn ->
            val saved = p.getString("map_${btn.name}", defaults[btn]?.name ?: MappingAction.NONE.name)
            runCatching { MappingAction.valueOf(saved!!) }.getOrDefault(MappingAction.NONE)
        }

        return DriverConfig(
            outputMode = outputMode,
            inputMode = inputMode,
            stickDeadzonePercent = p.getInt("deadzone_pct", 4).coerceIn(0, 20),
            enableExtraButtonDevice = p.getBoolean("enable_extra_dev", false),
            blockHomeButton = p.getBoolean("block_home_btn", false),
            touchOrientationMode = touchOri,
            activeTouchProfileId = p.getString("active_touch_profile", "fps_default") ?: "fps_default",
            showTouchHints = p.getBoolean("show_touch_hints", true),
            buttonMappings = mappings,
            gyroTriggerMode = gyroMode,
            gyroSensitivityX = p.getFloat("gyro_sens_x", 1.8f).coerceIn(0.2f, 5.0f),
            gyroSensitivityY = p.getFloat("gyro_sens_y", 1.5f).coerceIn(0.2f, 5.0f),
            gyroInvertX = p.getBoolean("gyro_inv_x", false),
            gyroInvertY = p.getBoolean("gyro_inv_y", false),
            gyroDeadzoneDps = p.getFloat("gyro_dz_dps", 2.0f).coerceIn(0f, 15f)
        )
    }

    fun save(context: Context, cfg: DriverConfig) {
        prefs(context).edit().apply {
            putString("output_mode", cfg.outputMode.name)
            putString("input_mode", cfg.inputMode.name)
            putInt("deadzone_pct", cfg.stickDeadzonePercent)
            putBoolean("enable_extra_dev", cfg.enableExtraButtonDevice)
            putBoolean("block_home_btn", cfg.blockHomeButton)
            putString("touch_orientation", cfg.touchOrientationMode.name)
            putString("active_touch_profile", cfg.activeTouchProfileId)
            putBoolean("show_touch_hints", cfg.showTouchHints)
            cfg.buttonMappings.forEach { (k, v) ->
                putString("map_${k.name}", v.name)
            }
            putString("gyro_mode", cfg.gyroTriggerMode.name)
            putFloat("gyro_sens_x", cfg.gyroSensitivityX)
            putFloat("gyro_sens_y", cfg.gyroSensitivityY)
            putBoolean("gyro_inv_x", cfg.gyroInvertX)
            putBoolean("gyro_inv_y", cfg.gyroInvertY)
            putFloat("gyro_dz_dps", cfg.gyroDeadzoneDps)
            apply()
        }
    }
}
