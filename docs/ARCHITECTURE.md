# 架构说明 (Architecture)

## 1. 整体数据流

```text
Flydigi Apex 5 (USB 0x37D7:0x2501)
  ├─ Interface 0 (XInput, class 255/93/1) ──┐
  └─ Interface 2 (Flydigi V2 0xFFA0, 0xEF) ──┼─▶ Apex5UsbSession (UsbRequest.queue/requestWait)
                                             │
        ┌────────────────────────────────────┴────────────────────────────────────┐
        ▼ (输出模式: Xbox 360 手柄)                                                 ▼ (输出模式: 触屏映射)
   InputMapper (24B APX5 包)                                             TouchMapperEngine (68B APT5 包)
        │                                                                         │
        └──────────────────────▶ ParcelFileDescriptor Pipe ◀──────────────────────┘
                                             │
                                  Shizuku UserService (uid=2000 shell)
                                  Native C++ (uinput_gamepad.cpp)
                                             │
               ┌─────────────────────────────┼─────────────────────────────┐
               ▼                             ▼                             ▼
   /dev/uinput: Xbox 360 pad      /dev/uinput: Extra Buttons    /dev/uinput: Virtual Touchscreen
    (0x045E:0x028E + EV_FF)          (BTN_TRIGGER +0..5)         (INPUT_PROP_DIRECT, 10-slot MT)
```

## 2. 双通道 USB 读取 (`Apex5UsbSession` & `Apex5Protocol`)

- 启动后优先对 `Interface 2` 发送 V2 握手命令序列：`CheckArch -> GetInfo -> GetStatus -> SetStatus -> Acquire(true)`。
- 若手柄启用了第三方接管，`0xEF` 增强流会持续上报：标准按键/摇杆/扳机 + **`M1–M4` 背键** + **`LM/RM` 小肩键** + **6 轴 IMU 陀螺仪** + 固件版本/电量。
- 若未收到 `0xEF` 或用户选择纯兼容模式，驱动无缝回退到 `Interface 0` (XInput)，保障开箱即用。

## 3. 内核级零 Root 注入 (`Shizuku` + `uinput_gamepad.cpp`)

- Android 禁止普通 App 直接打开 `/dev/uinput`，但 `shell (uid=2000)` 所属的 `uhid (3011)` / `input (1004)` 组拥有 `/dev/uinput` 读写权限。
- 通过 Shizuku 绑定 `UinputUserService`，以 `ParcelFileDescriptor` 管道将 `GamepadReportPacket` (24B) 与 `TouchFramePacket` (68B) 直接送入 C++ `poll()` 循环并写入内核 `uinput` 设备，几乎零 IPC 序列化开销。

## 4. 虚拟多点触控与悬浮窗编辑器 (`v0.2.0`)

- 虚拟触屏设置 `INPUT_PROP_DIRECT` 与 Linux Multi-Touch Protocol B (`ABS_MT_SLOT 0..9`, `ABS_MT_TRACKING_ID`, `ABS_MT_POSITION_X/Y 0..10000`)。
- `TouchMapperEngine` 支持：
  - **轮盘摇杆 (`JOYSTICK`)**：首帧先按在摇杆中心，随后移动偏移，兼容固定与浮动中心轮盘。
  - **FPS 视角滑动 (`CAMERA_SWIPE`)**：右摇杆与陀螺仪叠加滑动，到达边界自动抬起并瞬时回中，实现无限 360° 转向。
  - **连发点击 (`RAPID_TAP`)**：在 USB 超时空闲时仍以 ~125Hz 节拍刷新，确保长按不卡顿。
- `FloatingOverlayService` 提供悬浮球、游戏内半透明键位提示以及可视化拖动编辑器（支持按手柄实体按键自动绑定）。
