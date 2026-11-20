package com.apex5.driver.config

import android.content.Context
import com.apex5.driver.model.ControllerInputSource
import com.apex5.driver.model.MacroStep
import com.apex5.driver.model.TouchNodeConfig
import com.apex5.driver.model.TouchNodeKind
import com.apex5.driver.model.TouchProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object TouchProfileStore {
    private const val FILE_NAME = "touch_profiles.json"
    private val lock = Any()

    @Volatile
    private var cachedProfiles: List<TouchProfile>? = null

    fun defaultPresets(): List<TouchProfile> = listOf(
        TouchProfile(
            id = "fps_default",
            name = "FPS 通用预设 (和平/三角洲)",
            nodes = listOf(
                TouchNodeConfig(
                    id = "n_ls",
                    input = ControllerInputSource.LEFT_STICK,
                    kind = TouchNodeKind.JOYSTICK,
                    xNorm = 0.17f,
                    yNorm = 0.72f,
                    radiusNorm = 0.10f
                ),
                TouchNodeConfig(
                    id = "n_rs",
                    input = ControllerInputSource.RIGHT_STICK,
                    kind = TouchNodeKind.CAMERA_SWIPE,
                    xNorm = 0.68f,
                    yNorm = 0.48f,
                    radiusNorm = 0.14f,
                    sensitivity = 1.15f,
                    gyroAssist = true
                ),
                TouchNodeConfig(
                    id = "n_rt",
                    input = ControllerInputSource.RT,
                    kind = TouchNodeKind.TAP,
                    xNorm = 0.87f,
                    yNorm = 0.72f,
                    radiusNorm = 0.045f
                ),
                TouchNodeConfig(
                    id = "n_lt",
                    input = ControllerInputSource.LT,
                    kind = TouchNodeKind.TAP,
                    xNorm = 0.88f,
                    yNorm = 0.44f,
                    radiusNorm = 0.045f
                ),
                TouchNodeConfig(
                    id = "n_a",
                    input = ControllerInputSource.A,
                    kind = TouchNodeKind.TAP,
                    xNorm = 0.93f,
                    yNorm = 0.58f,
                    radiusNorm = 0.04f
                ),
                TouchNodeConfig(
                    id = "n_b",
                    input = ControllerInputSource.B,
                    kind = TouchNodeKind.TAP,
                    xNorm = 0.87f,
                    yNorm = 0.88f,
                    radiusNorm = 0.04f
                ),
                TouchNodeConfig(
                    id = "n_x",
                    input = ControllerInputSource.X,
                    kind = TouchNodeKind.TAP,
                    xNorm = 0.76f,
                    yNorm = 0.87f,
                    radiusNorm = 0.04f
                ),
                TouchNodeConfig(
                    id = "n_m1",
                    input = ControllerInputSource.M1,
                    kind = TouchNodeKind.TAP,
                    xNorm = 0.72f,
                    yNorm = 0.54f,
                    radiusNorm = 0.04f
                ),
                TouchNodeConfig(
                    id = "n_m2",
                    input = ControllerInputSource.M2,
                    kind = TouchNodeKind.TAP,
                    xNorm = 0.79f,
                    yNorm = 0.54f,
                    radiusNorm = 0.04f
                )
            )
        ),
        TouchProfile(
            id = "arpg_default",
            name = "ARPG 动作预设 (原神/鸣潮)",
            nodes = listOf(
                TouchNodeConfig(
                    id = "a_ls",
                    input = ControllerInputSource.LEFT_STICK,
                    kind = TouchNodeKind.JOYSTICK,
                    xNorm = 0.16f,
                    yNorm = 0.73f,
                    radiusNorm = 0.10f
                ),
                TouchNodeConfig(
                    id = "a_rs",
                    input = ControllerInputSource.RIGHT_STICK,
                    kind = TouchNodeKind.CAMERA_SWIPE,
                    xNorm = 0.64f,
                    yNorm = 0.45f,
                    radiusNorm = 0.15f,
                    sensitivity = 1.0f,
                    gyroAssist = false
                ),
                TouchNodeConfig(
                    id = "a_a",
                    input = ControllerInputSource.A,
                    kind = TouchNodeKind.TAP,
                    xNorm = 0.89f,
                    yNorm = 0.79f,
                    radiusNorm = 0.05f
                ),
                TouchNodeConfig(
                    id = "a_b",
                    input = ControllerInputSource.B,
                    kind = TouchNodeKind.TAP,
                    xNorm = 0.92f,
                    yNorm = 0.61f,
                    radiusNorm = 0.045f
                ),
                TouchNodeConfig(
                    id = "a_x",
                    input = ControllerInputSource.X,
                    kind = TouchNodeKind.TAP,
                    xNorm = 0.79f,
                    yNorm = 0.80f,
                    radiusNorm = 0.045f
                ),
                TouchNodeConfig(
                    id = "a_y",
                    input = ControllerInputSource.Y,
                    kind = TouchNodeKind.TAP,
                    xNorm = 0.79f,
                    yNorm = 0.63f,
                    radiusNorm = 0.045f
                ),
                TouchNodeConfig(
                    id = "a_rb",
                    input = ControllerInputSource.RB,
                    kind = TouchNodeKind.TAP,
                    xNorm = 0.90f,
                    yNorm = 0.43f,
                    radiusNorm = 0.042f
                )
            )
        ),
        TouchProfile(
            id = "moba_default",
            name = "MOBA 预设 (王者荣耀 · 技能指向)",
            nodes = listOf(
                TouchNodeConfig(
                    id = "m_ls",
                    input = ControllerInputSource.LEFT_STICK,
                    kind = TouchNodeKind.JOYSTICK,
                    xNorm = 0.17f,
                    yNorm = 0.73f,
                    radiusNorm = 0.11f
                ),
                TouchNodeConfig(
                    id = "m_a",
                    input = ControllerInputSource.A,
                    kind = TouchNodeKind.TAP,
                    xNorm = 0.87f,
                    yNorm = 0.80f,
                    radiusNorm = 0.055f
                ),
                TouchNodeConfig(
                    id = "m_x",
                    input = ControllerInputSource.X,
                    kind = TouchNodeKind.SKILL_AIM,
                    xNorm = 0.72f,
                    yNorm = 0.85f,
                    radiusNorm = 0.042f
                ),
                TouchNodeConfig(
                    id = "m_y",
                    input = ControllerInputSource.Y,
                    kind = TouchNodeKind.SKILL_AIM,
                    xNorm = 0.78f,
                    yNorm = 0.67f,
                    radiusNorm = 0.042f
                ),
                TouchNodeConfig(
                    id = "m_rb",
                    input = ControllerInputSource.RB,
                    kind = TouchNodeKind.SKILL_AIM,
                    xNorm = 0.88f,
                    yNorm = 0.56f,
                    radiusNorm = 0.042f
                ),
                TouchNodeConfig(
                    id = "m_rt",
                    input = ControllerInputSource.RT,
                    kind = TouchNodeKind.SKILL_AIM,
                    xNorm = 0.69f,
                    yNorm = 0.67f,
                    radiusNorm = 0.040f
                ),
                TouchNodeConfig(
                    id = "m_home",
                    input = ControllerInputSource.HOME,
                    kind = TouchNodeKind.TAP,
                    xNorm = 0.50f,
                    yNorm = 0.86f,
                    radiusNorm = 0.034f
                ),
                TouchNodeConfig(
                    id = "m_b",
                    input = ControllerInputSource.B,
                    kind = TouchNodeKind.TAP,
                    xNorm = 0.62f,
                    yNorm = 0.88f,
                    radiusNorm = 0.036f
                ),
                TouchNodeConfig(
                    id = "m_m1",
                    input = ControllerInputSource.M1,
                    kind = TouchNodeKind.TAP,
                    xNorm = 0.88f,
                    yNorm = 0.39f,
                    radiusNorm = 0.038f
                ),
                TouchNodeConfig(
                    id = "m_lb_y",
                    input = ControllerInputSource.Y,
                    modifierInput = ControllerInputSource.LB,
                    kind = TouchNodeKind.TAP,
                    xNorm = 0.89f,
                    yNorm = 0.19f,
                    radiusNorm = 0.032f
                ),
                TouchNodeConfig(
                    id = "m_lb_b",
                    input = ControllerInputSource.B,
                    modifierInput = ControllerInputSource.LB,
                    kind = TouchNodeKind.TAP,
                    xNorm = 0.89f,
                    yNorm = 0.27f,
                    radiusNorm = 0.032f
                )
            )
        ),
        TouchProfile(
            id = "empty_custom",
            name = "空白自定义方案",
            nodes = emptyList()
        )
    )

    fun loadAll(context: Context): List<TouchProfile> = synchronized(lock) {
        cachedProfiles?.let { return it }
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        val parsed = if (file.exists()) {
            runCatching { parseProfilesJson(file.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }
        } else null
        val result = parsed ?: defaultPresets().also { saveLocked(context, it) }
        cachedProfiles = result
        result
    }

    fun getActiveProfile(context: Context, activeId: String): TouchProfile {
        val all = loadAll(context)
        return all.firstOrNull { it.id == activeId } ?: all.first()
    }

    fun saveAll(context: Context, profiles: List<TouchProfile>) = synchronized(lock) {
        val nonEmpty = if (profiles.isEmpty()) defaultPresets() else profiles
        cachedProfiles = nonEmpty
        saveLocked(context, nonEmpty)
    }

    fun saveProfile(context: Context, profile: TouchProfile) = synchronized(lock) {
        val current = loadAll(context).toMutableList()
        val idx = current.indexOfFirst { it.id == profile.id }
        if (idx >= 0) {
            current[idx] = profile
        } else {
            current.add(profile)
        }
        cachedProfiles = current
        saveLocked(context, current)
    }

    fun createNewProfile(context: Context, name: String): TouchProfile = synchronized(lock) {
        val current = loadAll(context).toMutableList()
        val created = TouchProfile(
            id = "custom_" + UUID.randomUUID().toString().take(8),
            name = name.ifBlank { "自定义方案 ${current.size + 1}" },
            nodes = listOf(
                TouchNodeConfig(
                    id = "n_" + UUID.randomUUID().toString().take(6),
                    input = ControllerInputSource.LEFT_STICK,
                    kind = TouchNodeKind.JOYSTICK,
                    xNorm = 0.18f,
                    yNorm = 0.72f,
                    radiusNorm = 0.10f
                ),
                TouchNodeConfig(
                    id = "n_" + UUID.randomUUID().toString().take(6),
                    input = ControllerInputSource.RIGHT_STICK,
                    kind = TouchNodeKind.CAMERA_SWIPE,
                    xNorm = 0.68f,
                    yNorm = 0.48f,
                    radiusNorm = 0.14f
                )
            )
        )
        current.add(created)
        cachedProfiles = current
        saveLocked(context, current)
        created
    }

    fun duplicateProfile(context: Context, profileId: String): TouchProfile? = synchronized(lock) {
        val current = loadAll(context).toMutableList()
        val src = current.firstOrNull { it.id == profileId } ?: return null
        val cloned = src.copy(
            id = "copy_" + UUID.randomUUID().toString().take(8),
            name = "${src.name} (副本)",
            nodes = src.nodes.map { it.copy(id = "n_" + UUID.randomUUID().toString().take(6)) }
        )
        current.add(cloned)
        cachedProfiles = current
        saveLocked(context, current)
        cloned
    }

    fun deleteProfile(context: Context, profileId: String): List<TouchProfile> = synchronized(lock) {
        val current = loadAll(context).filterNot { it.id == profileId }
        val next = if (current.isEmpty()) defaultPresets() else current
        cachedProfiles = next
        saveLocked(context, next)
        next
    }

    fun resetPresets(context: Context): List<TouchProfile> = synchronized(lock) {
        val presets = defaultPresets()
        cachedProfiles = presets
        saveLocked(context, presets)
        presets
    }

    fun exportProfileJson(profile: TouchProfile): String {
        return profileToJson(profile).toString(2)
    }

    fun importProfileJson(context: Context, jsonText: String): Result<TouchProfile> = synchronized(lock) {
        return runCatching {
            val trimmed = jsonText.trim()
            val importedRaw = if (trimmed.startsWith("[")) {
                parseProfilesJson(trimmed).first()
            } else {
                parseSingleProfile(JSONObject(trimmed), 0)
            }
            val imported = importedRaw.copy(
                id = "imp_" + UUID.randomUUID().toString().take(8),
                name = importedRaw.name.ifBlank { "导入方案" },
                nodes = importedRaw.nodes.map {
                    it.copy(id = "n_" + UUID.randomUUID().toString().take(6))
                }
            )
            val current = loadAll(context).toMutableList()
            current.add(imported)
            cachedProfiles = current
            saveLocked(context, current)
            imported
        }
    }

    private fun saveLocked(context: Context, profiles: List<TouchProfile>) {
        val arr = JSONArray()
        profiles.forEach { p -> arr.put(profileToJson(p)) }
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        file.writeText(arr.toString(2))
    }

    private fun profileToJson(p: TouchProfile): JSONObject {
        val obj = JSONObject()
        obj.put("id", p.id)
        obj.put("name", p.name)
        val nodesArr = JSONArray()
        p.nodes.forEach { n ->
            val no = JSONObject()
            no.put("id", n.id)
            no.put("input", n.input.name)
            no.put("kind", n.kind.name)
            no.put("xNorm", n.xNorm.toDouble())
            no.put("yNorm", n.yNorm.toDouble())
            no.put("radiusNorm", n.radiusNorm.toDouble())
            no.put("sensitivity", n.sensitivity.toDouble())
            no.put("rapidHz", n.rapidHz)
            no.put("gyroAssist", n.gyroAssist)
            n.modifierInput?.let { mod -> no.put("modifierInput", mod.name) }
            if (n.macroSteps.isNotEmpty()) {
                val stepsArr = JSONArray()
                n.macroSteps.forEach { st ->
                    stepsArr.put(
                        JSONObject().apply {
                            put("xNorm", st.xNorm.toDouble())
                            put("yNorm", st.yNorm.toDouble())
                            put("holdMs", st.holdMs)
                            put("delayAfterMs", st.delayAfterMs)
                        }
                    )
                }
                no.put("macroSteps", stepsArr)
            }
            nodesArr.put(no)
        }
        obj.put("nodes", nodesArr)
        return obj
    }

    private fun parseProfilesJson(text: String): List<TouchProfile> {
        val arr = JSONArray(text)
        val out = ArrayList<TouchProfile>(arr.length())
        for (i in 0 until arr.length()) {
            out.add(parseSingleProfile(arr.getJSONObject(i), i))
        }
        return out
    }

    private fun parseSingleProfile(obj: JSONObject, indexHint: Int): TouchProfile {
        val id = obj.optString("id", "profile_$indexHint")
        val name = obj.optString("name", "未命名方案")
        val nodesArr = obj.optJSONArray("nodes") ?: JSONArray()
        val nodes = ArrayList<TouchNodeConfig>(nodesArr.length())
        for (j in 0 until nodesArr.length()) {
            val no = nodesArr.getJSONObject(j)
            val input = runCatching {
                ControllerInputSource.valueOf(no.getString("input"))
            }.getOrNull() ?: continue
            val kind = runCatching {
                TouchNodeKind.valueOf(no.getString("kind"))
            }.getOrNull() ?: TouchNodeKind.TAP
            val modInput = no.optString("modifierInput", "").takeIf { it.isNotEmpty() }?.let { modName ->
                runCatching { ControllerInputSource.valueOf(modName) }.getOrNull()
            }
            val stepsArr = no.optJSONArray("macroSteps")
            val macroSteps = if (stepsArr != null) {
                buildList {
                    for (k in 0 until stepsArr.length()) {
                        val so = stepsArr.getJSONObject(k)
                        add(
                            MacroStep(
                                xNorm = so.optDouble("xNorm", 0.5).toFloat().coerceIn(0.02f, 0.98f),
                                yNorm = so.optDouble("yNorm", 0.5).toFloat().coerceIn(0.02f, 0.98f),
                                holdMs = so.optInt("holdMs", 45).coerceIn(15, 500),
                                delayAfterMs = so.optInt("delayAfterMs", 55).coerceIn(15, 1000)
                            )
                        )
                    }
                }
            } else {
                emptyList()
            }

            nodes.add(
                TouchNodeConfig(
                    id = no.optString("id", "n_${indexHint}_$j"),
                    input = input,
                    kind = kind,
                    xNorm = no.optDouble("xNorm", 0.5).toFloat().coerceIn(0.02f, 0.98f),
                    yNorm = no.optDouble("yNorm", 0.5).toFloat().coerceIn(0.02f, 0.98f),
                    radiusNorm = no.optDouble("radiusNorm", 0.08).toFloat().coerceIn(0.03f, 0.25f),
                    sensitivity = no.optDouble("sensitivity", 1.0).toFloat().coerceIn(0.2f, 4.0f),
                    rapidHz = no.optInt("rapidHz", 12).coerceIn(3, 30),
                    gyroAssist = no.optBoolean("gyroAssist", true),
                    modifierInput = modInput,
                    macroSteps = macroSteps
                )
            )
        }
        return TouchProfile(id = id, name = name, nodes = nodes)
    }
}
