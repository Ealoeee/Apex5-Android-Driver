# Apex5 Android Driver (飞智八爪鱼 5 安卓有线驱动与映射器)

[![Android CI](https://github.com/Ealoeee/Apex5-Android-Driver/actions/workflows/android.yml/badge.svg)](https://github.com/Ealoeee/Apex5-Android-Driver/actions/workflows/android.yml)
[![Latest Release](https://img.shields.io/github/v/release/Ealoeee/Apex5-Android-Driver)](https://github.com/Ealoeee/Apex5-Android-Driver/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

免 Root 通过 **Android USB Host (`UsbManager`) + Shizuku (`/dev/uinput`)** 将 **飞智八爪鱼 5 (`0x37D7:0x2501`)** 有线连接适配为系统原生 **Xbox 360 手柄** 或 **10 指虚拟多点触控屏**（支持游戏内悬浮窗可视化键位编辑）。

> 直接下载安装 APK：前往 [**Releases 页面**](https://github.com/Ealoeee/Apex5-Android-Driver/releases/latest) 下载最新的 `Apex5-Driver-*-debug.apk`。

---

## 核心特性

- **全套飞智空间站 4.0 配置引擎 (`v0.4.0`)**：
  - **左右摇杆精调与曲线可视化**：独立内/外死区、中心/边界补偿、4 种响应曲线（线性 / FPS S形精瞄 / 快起步 / 防抖延迟）、圆形/矩形外圈模式与防抖平滑。
  - **扳机行程与 `0x12` 力反馈马达**：左右扳机独立死区与快速扳机锁 (`Hair-Trigger`)，支持跟随握把、狙击破阻感、机枪连震、赛车引擎渐进等 5 种扳机反馈波形。
  - **全键重映射 · Turbo 连发 · 手柄连招宏**：标准面板键互换 + `M1–M4 / LM / RM` 改键、按住连发 (`3–25 Hz`)、多步连招宏、握把/扳机震动缩放、陀螺仪滤波平滑，以及一键场景预设与 JSON 配置分享码。
- **应用内在线更新 & 持久化签名 (`v0.4.0`)**：
  - 内置 GitHub Releases 新版本检测与进度条下载（支持 `gh-proxy.com` 镜像加速）、系统安装器以及 **Shizuku 静默覆盖安装 (`pm install -r -d`)**；CI 与本地共用同一持久化签名，告别覆盖升级冲突。
- **双输出模式一键热切换**：
  - **原生 Xbox 360 手柄模式 (`0x045E:0x028E`)**：面向支持原生手柄的手游、串流与模拟器，支持 `EV_FF` 力反馈震动回传及 `M1–M4 / LM / RM` 自定义映射。
  - **触屏映射模式 (`Apex 5 Virtual Touchscreen`)**：通过 `/dev/uinput` 创建 `INPUT_PROP_DIRECT` 10 指虚拟触控屏，畅玩纯触屏手游：
    - 支持 **点击/长按**、**连发点击 (3–25 Hz)**、**轮盘摇杆 (浮动中心自适应)**、**MOBA 技能轮盘指向 (按住+右摇杆瞄准)**、**FPS 无限视角滑动 (边缘自动抬起复位 + 陀螺仪叠加)**，以及 **组合键 (`LT + X`) 与多步序列宏**。
    - 内置 **FPS 通用预设**、**ARPG 动作预设**、**MOBA 王者荣耀技能指向预设**，支持方案复制与剪贴板 JSON 导入/导出。
- **飞智空间站风格游戏内悬浮窗与可视化键位编辑器 (`v0.5.0`)**：
  - 悬浮控制球支持快捷切换 `Xbox 手柄 / 触屏映射`、切换预设方案、显隐超细霓虹键位圈并实时调节 `0–100%` 透明度。
  - 可折叠深色玻璃顶部工具栏集成 `+ 添加`、`另存为`、`测试`（实体手柄按键与摇杆实时落点预览）、`辅助线` 与 `透明度` 滑块；画布配备全屏十字定位辅助线与中心吸附、右下角拖拽/双指捏合缩放圆环大小，并支持 **直接按下手柄实体按键自动识别添加**。
- **平板优先的响应式控制台**：
  - 深空曜石暗黑美学 + Edge-to-Edge 全面屏适配，横屏平板提供左侧 `Navigation Rail` + Bento 双列视图、16:10 方案键位蓝图预览、摇杆死区环与彩色诊断终端。
- **双通道 USB 协议读取**：
  - 优先握手飞智 V2 私有 HID (`Interface 2`, `0xFFA0`) 获取 **`0xEF` 增强流**（含 **M1–M4 背键**、**LM/RM 肩键**、**陀螺仪 IMU**、固件与电量），8 深度 `HID_IN` URB 队列保障低抖动轮询。
  - 未开启第三方接管时，**无缝自动回退标准 XInput (`Interface 0`)**。

---

## 项目结构

```text
Apex5-Android-Driver/
├── .github/
│   ├── ISSUE_TEMPLATE/          # Bug 反馈与功能建议模板
│   └── workflows/android.yml    # GitHub Actions 自动编译与 Tag Release 发布 APK
├── app/
│   └── src/main/
│       ├── aidl/                # Shizuku UserService IPC 接口
│       ├── cpp/                 # NDK C++ /dev/uinput (Xbox 360 + Extra Keys + 10-slot MT Touch)
│       └── java/com/apex5/driver/
│           ├── config/          # 空间站配置与触屏预设方案 JSON 持久化
│           ├── model/           # 驱动状态、空间站参数与触屏节点模型
│           ├── overlay/         # 悬浮球、键位提示层与游戏内可视化键位编辑器
│           ├── service/         # 前台 USB 守护服务与全局状态控制器
│           ├── shizuku/         # Shizuku 权限探测、uinput 与静默安装桥接
│           ├── ui/              # Jetpack Compose 控制台、空间站面板与触控校准画板
│           ├── update/          # GitHub Releases 在线检查更新与下载安装管理
│           └── usb/             # USB Host 异步读取、0xEF 解码、曲线算法与映射引擎
├── docs/
│   ├── ARCHITECTURE.md          # 系统架构与内核 uinput 原理说明
│   └── ROADMAP.md               # 版本迭代规划
├── scripts/
│   └── build_apk.ps1            # Windows 本地一键构建脚本
├── CHANGELOG.md
└── LICENSE
```

---

## 使用前准备（以小米平板 / HyperOS 为例）

1. 在平板安装并启动 **Shizuku**（通过无线调试激活）。
2. 长按八爪鱼 5 正面 **HOME 键**，在屏幕菜单选择 **`Connect / 模式切换` → `PC` → `有线`**。
3. 用支持数据传输的 **Type-C to Type-C 数据线** 连接手柄与平板（确认系统 OTG 已打开）。
4. 若需使用背键/LM/RM 与陀螺仪而增强流未激活，可先在电脑端飞智空间站 4.0 中开启 **「允许第三方应用接管映射」**（不开启也可自动以标准 XInput 运行）。
5. 如使用触屏映射与悬浮窗编辑器，请授予本应用 **「显示在其他应用上层」** 权限；若触控方向与旋转不一致，可在「触屏映射与悬浮窗」页调整 **触控屏幕方向校准** 并用内置画板验证。

---

## 本地构建

```powershell
.\scripts\build_apk.ps1
```

Linux / macOS 或 GitHub Actions：

```bash
./gradlew :app:assembleDebug --no-daemon
```

编译产物位于 `app/build/outputs/apk/debug/app-debug.apk`。更多架构细节见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 与 [docs/ROADMAP.md](docs/ROADMAP.md)。
