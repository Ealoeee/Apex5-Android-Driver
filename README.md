# Apex5 有线驱动 (Flydigi Apex 5 Android USB Driver)

免 Root 通过 **Android USB Host (`UsbManager`) + Shizuku (`/dev/uinput`)** 将飞智八爪鱼 5 (`0x37D7:0x2501`) 有线连接适配为系统原生 **Xbox 360 手柄 (`0x045E:0x028E`)**。

## 功能特性

- **双通道 USB 读取**：
  - 优先尝试激活飞智 V2 私有 HID (`Interface 2`, `0xFFA0`) 获取 **`0xEF` 增强流**（支持 **背键 M1–M4**、**额外肩键 LM/RM**、**陀螺仪 IMU**、电量与固件信息）。
  - 若尚未开启第三方接管或增强流未就绪，**无缝自动回退至标准 XInput (`Interface 0`)**，保障基础摇杆/扳机/按键开箱即用。
- **低延迟内核级虚拟手柄**：通过 Shizuku (`uid=2000`) + NDK JNI 打开 `/dev/uinput` 注册原生 Xbox 360 手柄，并支持 `EV_FF` 力反馈震动回传至手柄。
- **增强映射与体感瞄准**：
  - M1–M4 / LM / RM 支持映射到任意 Xbox 按键、扳机、十字键、陀螺仪激活键或独立 `BUTTON_1~6`。
  - 陀螺仪辅助右摇杆瞄准（支持按住 LT、自定义按键或常开，带 X/Y 灵敏度与死区调节）。

## 使用前提（小米平板 / 澎湃 OS）

1. 在平板安装并启动 **Shizuku**（通过无线调试启动）。
2. 长按八爪鱼 5 正面 **HOME 键**，在屏幕菜单选择 **`Connect / 模式切换` → `PC` → `有线`**。
3. 使用支持数据传输的 **Type-C to Type-C 数据线** 连接手柄与平板（确认系统 OTG 已开启）。
4. 若需要读取背键/LM/RM/陀螺仪而增强流未激活，可先在电脑端飞智空间站 4.0 中开启 **「允许第三方应用接管映射」**（不开启也可自动以标准 XInput 模式工作）。

## 本地编译

```powershell
.\build_apk.ps1
```

生成的 APK 位于 `app\build\outputs\apk\debug\app-debug.apk`。
