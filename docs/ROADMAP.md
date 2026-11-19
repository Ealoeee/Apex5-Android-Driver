# 版本规划 (Roadmap)

## v0.1.0 (已发布)
- [x] USB Host 接管飞智八爪鱼 5 (`0x37D7:0x2501`) 双通道（XInput + `0xEF` 增强流）
- [x] Shizuku `/dev/uinput` 虚拟原生 Xbox 360 手柄 + `EV_FF` 震动回传
- [x] 背键 `M1–M4` / `LM / RM` 自定义映射 + 陀螺仪辅助右摇杆瞄准

## v0.2.0 (当前版本)
- [x] `/dev/uinput` 虚拟 10 指多点触控屏 (`INPUT_PROP_DIRECT` + Protocol B)
- [x] 触屏映射引擎：点击、连发、轮盘摇杆、FPS 无限视角滑动（含陀螺仪叠加）
- [x] 游戏内悬浮窗控制球、半透明键位提示与可视化键位拖动编辑器
- [x] 触屏方案预设与 JSON 持久化 + App 内实时触控校准画板
- [x] GitHub Actions Tag 自动构建并发布 Release APK

## v0.3.0 (规划中)
- [ ] **游戏自动识别切换方案**：根据前台包名自动匹配触屏/手柄模式与映射方案
- [ ] **组合键与宏支持**：支持单键触发多步点击序列或组合键（例如 `LT + X`）
- [ ] **0xEF 轮询回报率深度优化**：分析 Android `UsbRequest` / Libusb 等长连接调度，尝试逼近有线硬件 ~970Hz 上限
- [ ] **无线 2.4G 接收器支持调研**：验证 Flydigi 2.4G Dongle VID/PID 与多包协议兼容
