# Changelog

All notable changes to **Apex5-Android-Driver** are documented in this file.

## [v0.2.0] - 2026-11-20

### Added
- **触屏映射模式 (Virtual Multi-Touchscreen)**：通过 Shizuku `/dev/uinput` 注册 `INPUT_PROP_DIRECT` 10 指多点触控设备 (`ABS_MT_SLOT` / Protocol B)，支持在纯触屏手游中使用八爪鱼 5 操控。
- **游戏内悬浮窗与可视化键位编辑器 (`FloatingOverlayService`)**：
  - 悬浮球支持切换输出模式（`Xbox 手柄` / `触屏映射`）、切换映射方案、开启/隐藏半透明键位提示。
  - 可视化键位编辑器支持自由拖动节点，并支持**直接按下手柄实体按键自动识别添加**。
  - 支持四种节点动作：`点击/长按`、`连发点击 (3-25Hz)`、`轮盘摇杆/技能拖拽 (带首帧中心锚点)`、`FPS 视角滑动 (边缘自动抬起复位 + 陀螺仪体感辅助)`。
- **预设与方案管理 (`TouchProfileStore`)**：内置 `FPS 通用预设`、`ARPG 动作预设` 与 `空白自定义方案`，支持新建、删除与 JSON 持久化。
- **触控校准与测试画板**：支持 `自动跟随旋转 / 不旋转 / 90° / 180° / 270°` 校准，并在 App 内直接可视化测试虚拟触控落点。
- **CI 自动发布**：GitHub Actions 在推送 `v*` Tag 时自动编译 APK 并创建 GitHub Release。

## [v0.1.0] - 2026-11-20

### Added
- 免 Root Android USB Host + Shizuku (`/dev/uinput`) 虚拟 Xbox 360 手柄 (`0x045E:0x028E`) 桥接。
- 双通道读取：优先握手飞智 V2 `0xEF` 增强流（M1–M4 背键、LM/RM 小肩键、陀螺仪 IMU、电量/固件），无缝回退标准 XInput (`Interface 0`)。
- `M1–M4 / LM / RM` 自由映射、陀螺仪辅助右摇杆瞄准、`EV_FF` 震动回传。
- 前台测试界面自动拦截虚拟手柄按键（防止 B/Back 退出界面）及 Home 键屏蔽开关。
