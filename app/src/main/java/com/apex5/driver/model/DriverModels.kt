package com.apex5.driver.model

enum class ShizukuState(val label: String) {
    NOT_INSTALLED("未安装 Shizuku"),
    STOPPED("Shizuku 未运行"),
    NO_PERMISSION("未授予 Shizuku 权限"),
    READY("Shizuku 已就绪 (shell)")
}

enum class InputPreferenceMode(val label: String) {
    AUTO_PREFER_ENHANCED("自动 (优先增强 0xEF，自动回退 XInput)"),
    XINPUT_ONLY("仅 XInput (低功耗标准兼容模式)")
}

enum class ExtraButtonId(val label: String) {
    M1("背键 M1"),
    M2("背键 M2"),
    M3("背键 M3"),
    M4("背键 M4"),
    LM("顶部小肩键 LM"),
    RM("顶部小肩键 RM")
}

enum class StandardButtonId(val label: String, val defaultAction: MappingAction) {
    A("A 键", MappingAction.A),
    B("B 键", MappingAction.B),
    X("X 键", MappingAction.X),
    Y("Y 键", MappingAction.Y),
    LB("LB 左肩键", MappingAction.LB),
    RB("RB 右肩键", MappingAction.RB),
    L3("L3 左摇杆下压", MappingAction.L3),
    R3("R3 右摇杆下压", MappingAction.R3)
}

enum class MappingAction(val label: String) {
    NONE("不映射 (无)"),
    A("A 键"),
    B("B 键"),
    X("X 键"),
    Y("Y 键"),
    LB("LB (左肩键)"),
    RB("RB (右肩键)"),
    L3("L3 (左摇杆按下)"),
    R3("R3 (右摇杆按下)"),
    LT_FULL("LT 满压"),
    RT_FULL("RT 满压"),
    BACK("Back / View"),
    START("Start / Menu"),
    HOME("Home / Guide"),
    DPAD_UP("十字键 上"),
    DPAD_DOWN("十字键 下"),
    DPAD_LEFT("十字键 左"),
    DPAD_RIGHT("十字键 右"),
    GYRO_HOLD("按住启用陀螺仪瞄准"),
    GYRO_TOGGLE("开关切换陀螺仪瞄准"),
    EXTRA_BUTTON("独立附加手柄键 (BUTTON_1~6)"),
    RUN_MACRO("执行手柄连招宏")
}

enum class GyroTriggerMode(val label: String) {
    OFF("关闭陀螺仪映射"),
    ALWAYS_ON("始终开启"),
    HOLD_LT("按住 LT 时开启"),
    HOLD_MAPPING("由自定义按键(按住/开关)控制")
}

enum class DriverOutputMode(val label: String) {
    GAMEPAD("原生 Xbox 360 手柄模式"),
    TOUCH_MAPPER("触屏映射模式 (虚拟多点触控)")
}

enum class TouchOrientationMode(val label: String) {
    AUTO("自动跟随屏幕旋转 (推荐)"),
    DIRECT_NO_ROTATE("直出坐标 (不旋转)"),
    ROTATE_90("顺时针 90° 校准"),
    ROTATE_180("180° 校准"),
    ROTATE_270("逆时针 90° 校准")
}

enum class StickResponseCurve(val label: String, val shortLabel: String) {
    LINEAR("默认线性 (1:1 真实手感)", "线性"),
    FPS_PRECISION("FPS 精瞄 (S形缓起慢瞄)", "FPS精瞄"),
    FAST_RESPONSE("敏捷爆发 (快起步响应)", "快起步"),
    STABLE_DELAY("稳健防抖 (沉稳起步)", "防抖延迟")
}

enum class StickCircularityMode(val label: String, val shortLabel: String) {
    CIRCLE("圆形调教 (精准控向 / FPS)", "圆形"),
    RECTANGLE("矩形调教 (斜向跑满 100%)", "矩形")
}

data class StickTuningConfig(
    val centerDeadzonePercent: Int = 4,
    val centerCompensationPercent: Int = 0,
    val outerDeadzonePercent: Int = 0,
    val edgeCompensationPercent: Int = 0,
    val curvePreset: StickResponseCurve = StickResponseCurve.LINEAR,
    val circularityMode: StickCircularityMode = StickCircularityMode.CIRCLE,
    val antiJitterEnabled: Boolean = false
)

enum class TriggerHapticMode(val label: String, val shortLabel: String) {
    OFF("常规线性 (无力反馈震动)", "常规"),
    SYNC_MAIN("跟随游戏握把震动", "跟随震动"),
    SNIPER_CLICK("狙击破阻感 (半程清脆脉冲)", "狙击"),
    MACHINE_GUN("机枪高频连震 (按住连振)", "机枪"),
    RACING_PROGRESSIVE("赛车引擎渐进震动", "赛车")
}

data class TriggerTuningConfig(
    val deadzoneStartPercent: Int = 2,
    val deadzoneEndPercent: Int = 0,
    val hairTriggerMode: Boolean = false,
    val hapticMode: TriggerHapticMode = TriggerHapticMode.OFF,
    val hapticStrengthPercent: Int = 75
)

data class GamepadMacroStep(
    val action: MappingAction,
    val holdMs: Int = 45,
    val delayAfterMs: Int = 55
)

enum class SpaceStationPreset(val label: String, val desc: String) {
    BALANCED("默认均衡", "4% 死区 · 线性摇杆 · 常规扳机"),
    FPS_COMPETITIVE("FPS 竞技精瞄", "S形精瞄曲线 · 狙击扳机 + RT 扳机锁 · LT 陀螺仪"),
    RACING_SIM("赛车拟真", "0% 死区 · 矩形边界 · LT/RT 渐进引擎震动"),
    ACTION_COMBO("动作格斗", "快起步曲线 · 0% 中心补偿 · M1/M2 连发启用的高爆发模板")
}

enum class ControllerInputSource(val shortLabel: String, val label: String) {
    LEFT_STICK("L摇杆", "左摇杆 (LX/LY)"),
    RIGHT_STICK("R摇杆", "右摇杆 (RX/RY)"),
    A("A", "A 键"),
    B("B", "B 键"),
    X("X", "X 键"),
    Y("Y", "Y 键"),
    LB("LB", "LB 左肩键"),
    RB("RB", "RB 右肩键"),
    LT("LT", "LT 左扳机"),
    RT("RT", "RT 右扳机"),
    L3("L3", "L3 左摇杆按下"),
    R3("R3", "R3 右摇杆按下"),
    BACK("Back", "Back / View"),
    START("Start", "Start / Menu"),
    DPAD_UP("↑", "十字键 上"),
    DPAD_DOWN("↓", "十字键 下"),
    DPAD_LEFT("←", "十字键 左"),
    DPAD_RIGHT("→", "十字键 右"),
    M1("M1", "背键 M1 (0xEF)"),
    M2("M2", "背键 M2 (0xEF)"),
    M3("M3", "背键 M3 (0xEF)"),
    M4("M4", "背键 M4 (0xEF)"),
    LM("LM", "顶部肩键 LM (0xEF)"),
    RM("RM", "顶部肩键 RM (0xEF)")
}

enum class TouchNodeKind(val label: String) {
    TAP("点击 / 长按"),
    RAPID_TAP("连发点击"),
    JOYSTICK("轮盘摇杆 (LS/RS)"),
    CAMERA_SWIPE("FPS 视角滑动 (无限转向 + 陀螺仪)"),
    MACRO("序列宏 (多步连点)"),
    SKILL_AIM("技能指向 (按住 + 右摇杆瞄准)")
}

data class MacroStep(
    val xNorm: Float,
    val yNorm: Float,
    val holdMs: Int = 45,
    val delayAfterMs: Int = 55
)

data class TouchNodeConfig(
    val id: String,
    val input: ControllerInputSource,
    val kind: TouchNodeKind,
    val xNorm: Float,
    val yNorm: Float,
    val radiusNorm: Float = 0.09f,
    val sensitivity: Float = 1.0f,
    val rapidHz: Int = 12,
    val gyroAssist: Boolean = true,
    val modifierInput: ControllerInputSource? = null,
    val macroSteps: List<MacroStep> = emptyList()
) {
    fun displayTriggerLabel(): String {
        return if (modifierInput != null) {
            "${modifierInput.shortLabel}+${input.shortLabel}"
        } else {
            input.shortLabel
        }
    }

    fun overlayLabel(): String {
        val base = when (input) {
            ControllerInputSource.LEFT_STICK -> "L"
            ControllerInputSource.RIGHT_STICK -> "R"
            else -> input.shortLabel
        }
        return if (modifierInput != null) {
            val mod = when (modifierInput) {
                ControllerInputSource.LEFT_STICK -> "L"
                ControllerInputSource.RIGHT_STICK -> "R"
                else -> modifierInput.shortLabel
            }
            "$mod+$base"
        } else {
            base
        }
    }
}

data class TouchProfile(
    val id: String,
    val name: String,
    val nodes: List<TouchNodeConfig>
)

data class DriverConfig(
    val outputMode: DriverOutputMode = DriverOutputMode.GAMEPAD,
    val inputMode: InputPreferenceMode = InputPreferenceMode.AUTO_PREFER_ENHANCED,
    val stickDeadzonePercent: Int = 4,
    val enableExtraButtonDevice: Boolean = false,
    val blockHomeButton: Boolean = false,
    val touchOrientationMode: TouchOrientationMode = TouchOrientationMode.AUTO,
    val activeTouchProfileId: String = "fps_default",
    val showTouchHints: Boolean = true,
    val touchHintsOpacityPercent: Int = 55,
    val buttonMappings: Map<ExtraButtonId, MappingAction> = mapOf(
        ExtraButtonId.M1 to MappingAction.A,
        ExtraButtonId.M2 to MappingAction.B,
        ExtraButtonId.M3 to MappingAction.X,
        ExtraButtonId.M4 to MappingAction.Y,
        ExtraButtonId.LM to MappingAction.LB,
        ExtraButtonId.RM to MappingAction.RB
    ),
    val faceButtonMappings: Map<StandardButtonId, MappingAction> = StandardButtonId.entries.associateWith { it.defaultAction },
    val turboEnabledKeys: Set<ControllerInputSource> = emptySet(),
    val turboRateHz: Int = 12,
    val gamepadMacros: Map<ExtraButtonId, List<GamepadMacroStep>> = mapOf(
        ExtraButtonId.M1 to listOf(
            GamepadMacroStep(MappingAction.X, 40, 45),
            GamepadMacroStep(MappingAction.A, 45, 50)
        )
    ),
    val leftStick: StickTuningConfig = StickTuningConfig(),
    val rightStick: StickTuningConfig = StickTuningConfig(),
    val leftTrigger: TriggerTuningConfig = TriggerTuningConfig(),
    val rightTrigger: TriggerTuningConfig = TriggerTuningConfig(),
    val mainRumbleIntensityPercent: Int = 100,
    val triggerRumbleIntensityPercent: Int = 80,
    val gyroTriggerMode: GyroTriggerMode = GyroTriggerMode.HOLD_LT,
    val gyroSensitivityX: Float = 1.8f,
    val gyroSensitivityY: Float = 1.5f,
    val gyroInvertX: Boolean = false,
    val gyroInvertY: Boolean = false,
    val gyroDeadzoneDps: Float = 2.0f,
    val gyroSmoothing: Float = 0.15f
)

data class LiveControllerState(
    val source: String = "未连接",
    val lx: Int = 0,
    val ly: Int = 0,
    val rx: Int = 0,
    val ry: Int = 0,
    val lt: Int = 0,
    val rt: Int = 0,
    val hatX: Int = 0,
    val hatY: Int = 0,
    val a: Boolean = false,
    val b: Boolean = false,
    val x: Boolean = false,
    val y: Boolean = false,
    val lb: Boolean = false,
    val rb: Boolean = false,
    val back: Boolean = false,
    val start: Boolean = false,
    val home: Boolean = false,
    val l3: Boolean = false,
    val r3: Boolean = false,
    val m1: Boolean = false,
    val m2: Boolean = false,
    val m3: Boolean = false,
    val m4: Boolean = false,
    val lm: Boolean = false,
    val rm: Boolean = false,
    val gyroPitchDps: Float = 0f,
    val gyroYawDps: Float = 0f,
    val gyroRollDps: Float = 0f,
    val gyroAimActive: Boolean = false,
    val packetRateHz: Int = 0
)

data class DriverStatus(
    val serviceRunning: Boolean = false,
    val uinputActive: Boolean = false,
    val touchActive: Boolean = false,
    val activeOutputMode: DriverOutputMode = DriverOutputMode.GAMEPAD,
    val ffSupported: Boolean = false,
    val extraDeviceActive: Boolean = false,
    val usbConnected: Boolean = false,
    val usbPermissionGranted: Boolean = false,
    val overlayPermissionGranted: Boolean = false,
    val overlayRunning: Boolean = false,
    val deviceSummary: String = "未检测到手柄",
    val enhancedActive: Boolean = false,
    val firmwareText: String = "--",
    val batteryText: String = "--",
    val shizukuState: ShizukuState = ShizukuState.STOPPED,
    val lastError: String? = null,
    val logs: List<String> = emptyList(),
    val liveState: LiveControllerState = LiveControllerState()
)
