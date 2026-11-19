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
    EXTRA_BUTTON("独立附加手柄键 (BUTTON_1~6)")
}

enum class GyroTriggerMode(val label: String) {
    OFF("关闭陀螺仪映射"),
    ALWAYS_ON("始终开启"),
    HOLD_LT("按住 LT 时开启"),
    HOLD_MAPPING("由自定义按键(按住/开关)控制")
}

data class DriverConfig(
    val inputMode: InputPreferenceMode = InputPreferenceMode.AUTO_PREFER_ENHANCED,
    val stickDeadzonePercent: Int = 4,
    val enableExtraButtonDevice: Boolean = false,
    val blockHomeButton: Boolean = false,
    val buttonMappings: Map<ExtraButtonId, MappingAction> = mapOf(
        ExtraButtonId.M1 to MappingAction.A,
        ExtraButtonId.M2 to MappingAction.B,
        ExtraButtonId.M3 to MappingAction.X,
        ExtraButtonId.M4 to MappingAction.Y,
        ExtraButtonId.LM to MappingAction.LB,
        ExtraButtonId.RM to MappingAction.RB
    ),
    val gyroTriggerMode: GyroTriggerMode = GyroTriggerMode.HOLD_LT,
    val gyroSensitivityX: Float = 1.8f,
    val gyroSensitivityY: Float = 1.5f,
    val gyroInvertX: Boolean = false,
    val gyroInvertY: Boolean = false,
    val gyroDeadzoneDps: Float = 2.0f
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
    val ffSupported: Boolean = false,
    val extraDeviceActive: Boolean = false,
    val usbConnected: Boolean = false,
    val usbPermissionGranted: Boolean = false,
    val deviceSummary: String = "未检测到手柄",
    val enhancedActive: Boolean = false,
    val firmwareText: String = "--",
    val batteryText: String = "--",
    val shizukuState: ShizukuState = ShizukuState.STOPPED,
    val lastError: String? = null,
    val logs: List<String> = emptyList(),
    val liveState: LiveControllerState = LiveControllerState()
)
