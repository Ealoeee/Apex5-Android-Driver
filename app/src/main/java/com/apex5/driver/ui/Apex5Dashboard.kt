package com.apex5.driver.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex5.driver.model.DriverConfig
import com.apex5.driver.model.DriverOutputMode
import com.apex5.driver.model.DriverStatus
import com.apex5.driver.model.ExtraButtonId
import com.apex5.driver.model.GyroTriggerMode
import com.apex5.driver.model.InputPreferenceMode
import com.apex5.driver.model.LiveControllerState
import com.apex5.driver.model.MappingAction
import com.apex5.driver.model.ShizukuState
import com.apex5.driver.model.TouchOrientationMode
import com.apex5.driver.model.TouchProfile
import kotlin.math.roundToInt

private val BgColor = Color(0xFF0B1120)
private val CardColor = Color(0xFF131C31)
private val CardBorder = Color(0xFF233252)
private val AccentCyan = Color(0xFF38BDF8)
private val OkGreen = Color(0xFF22C55E)
private val WarnAmber = Color(0xFFF59E0B)
private val ErrRed = Color(0xFFEF4444)
private val MutedText = Color(0xFF94A3B8)

@Composable
fun Apex5Dashboard(
    status: DriverStatus,
    config: DriverConfig,
    touchProfiles: List<TouchProfile>,
    onRefresh: () -> Unit,
    onRequestShizuku: () -> Unit,
    onRequestUsb: () -> Unit,
    onStartDriver: () -> Unit,
    onStopDriver: () -> Unit,
    onSwitchOutputMode: (DriverOutputMode) -> Unit,
    onStartOverlay: (Boolean) -> Unit,
    onStopOverlay: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onSelectTouchProfile: (String) -> Unit,
    onCreateTouchProfile: (String) -> TouchProfile,
    onDeleteTouchProfile: (String) -> Unit,
    onResetTouchPresets: () -> Unit,
    onProbePermissions: () -> Unit,
    onTestRumble: () -> Unit,
    onUpdateConfig: ((DriverConfig) -> DriverConfig) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        HeaderSection(status = status, onRefresh = onRefresh)

        ControlBanner(
            status = status,
            config = config,
            onRequestShizuku = onRequestShizuku,
            onRequestUsb = onRequestUsb,
            onStartDriver = onStartDriver,
            onStopDriver = onStopDriver,
            onSwitchOutputMode = onSwitchOutputMode,
            onStartOverlay = onStartOverlay,
            onStopOverlay = onStopOverlay,
            onProbePermissions = onProbePermissions
        )

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = CardColor,
            contentColor = AccentCyan,
            modifier = Modifier.clip(RoundedCornerShape(12.dp))
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("实时状态与测试", fontWeight = FontWeight.SemiBold) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("触屏映射与悬浮窗", fontWeight = FontWeight.SemiBold) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("背键映射与陀螺仪", fontWeight = FontWeight.SemiBold) }
            )
            Tab(
                selected = selectedTab == 3,
                onClick = { selectedTab = 3 },
                text = { Text("诊断日志 (${status.logs.size})", fontWeight = FontWeight.SemiBold) }
            )
        }

        when (selectedTab) {
            0 -> LiveMonitorSection(status = status, onTestRumble = onTestRumble)
            1 -> TouchMappingSection(
                status = status,
                config = config,
                touchProfiles = touchProfiles,
                onStartOverlay = onStartOverlay,
                onStopOverlay = onStopOverlay,
                onRequestOverlayPermission = onRequestOverlayPermission,
                onSelectTouchProfile = onSelectTouchProfile,
                onCreateTouchProfile = onCreateTouchProfile,
                onDeleteTouchProfile = onDeleteTouchProfile,
                onResetTouchPresets = onResetTouchPresets,
                onUpdateConfig = onUpdateConfig
            )
            2 -> MappingAndGyroSection(
                config = config,
                serviceRunning = status.serviceRunning,
                onUpdateConfig = onUpdateConfig
            )
            3 -> LogsSection(logs = status.logs, onProbePermissions = onProbePermissions)
        }
    }
}

@Composable
private fun HeaderSection(status: DriverStatus, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "飞智八爪鱼 5 有线驱动 (Apex 5)",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "USB Host + Shizuku /dev/uinput 虚拟手柄 & 多点触屏映射 v0.2.0",
                color = MutedText,
                fontSize = 13.sp
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                text = if (status.serviceRunning) {
                    if (status.activeOutputMode == DriverOutputMode.TOUCH_MAPPER) "触屏映射中" else "手柄运行中"
                } else "未启动",
                color = if (status.serviceRunning) OkGreen else MutedText
            )
            OutlinedButton(onClick = onRefresh) {
                Text("刷新检测", color = AccentCyan)
            }
        }
    }
}

@Composable
private fun ControlBanner(
    status: DriverStatus,
    config: DriverConfig,
    onRequestShizuku: () -> Unit,
    onRequestUsb: () -> Unit,
    onStartDriver: () -> Unit,
    onStopDriver: () -> Unit,
    onSwitchOutputMode: (DriverOutputMode) -> Unit,
    onStartOverlay: (Boolean) -> Unit,
    onStopOverlay: () -> Unit,
    onProbePermissions: () -> Unit
) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoBox(
                    title = "1. Shizuku 权限通道",
                    value = status.shizukuState.label,
                    ok = status.shizukuState == ShizukuState.READY,
                    modifier = Modifier.weight(1f),
                    actionLabel = when (status.shizukuState) {
                        ShizukuState.NO_PERMISSION -> "授权 Shizuku"
                        ShizukuState.READY -> "自检 /dev/uinput"
                        else -> null
                    },
                    onAction = {
                        if (status.shizukuState == ShizukuState.NO_PERMISSION) onRequestShizuku()
                        else onProbePermissions()
                    }
                )

                InfoBox(
                    title = "2. 有线 USB 手柄 (PC -> 有线)",
                    value = status.deviceSummary + if (status.usbConnected && !status.usbPermissionGranted) " [待授权]" else "",
                    ok = status.usbConnected && status.usbPermissionGranted,
                    modifier = Modifier.weight(1f),
                    actionLabel = if (status.usbConnected && !status.usbPermissionGranted) "授权 USB" else null,
                    onAction = onRequestUsb
                )

                InfoBox(
                    title = "3. 虚拟设备与协议状态",
                    value = if (status.serviceRunning) {
                        val outType = if (status.touchActive) "虚拟触屏" else "Xbox 360"
                        val mode = if (status.enhancedActive) "增强 0xEF" else "XInput"
                        "$outType | $mode | FW ${status.firmwareText} | ${status.batteryText}"
                    } else {
                        "手柄需长按 HOME 切至「PC -> 有线」"
                    },
                    ok = status.uinputActive || status.touchActive,
                    modifier = Modifier.weight(1f)
                )
            }

            // Output mode selector bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("输出模式:", color = MutedText, fontSize = 13.sp)
                DriverOutputMode.entries.forEach { mode ->
                    val selected = config.outputMode == mode
                    OutlinedButton(
                        onClick = { onSwitchOutputMode(mode) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) AccentCyan.copy(alpha = 0.2f) else Color.Transparent
                        ),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Text(
                            text = mode.label,
                            color = if (selected) AccentCyan else MutedText,
                            fontSize = 12.5f.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = {
                        if (status.overlayRunning) onStopOverlay() else onStartOverlay(false)
                    },
                    modifier = Modifier.height(38.dp)
                ) {
                    Text(
                        text = if (status.overlayRunning) "关闭悬浮窗" else "开启悬浮窗",
                        color = if (status.overlayRunning) OkGreen else AccentCyan,
                        fontSize = 12.5f.sp
                    )
                }
            }

            status.lastError?.let { err ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(ErrRed.copy(alpha = 0.16f))
                        .border(1.dp, ErrRed.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(text = err, color = Color(0xFFFCA5A5), fontSize = 13.sp)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!status.serviceRunning) {
                    Button(
                        onClick = onStartDriver,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color(0xFF082F49)),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("启动 Apex 5 有线驱动", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                } else {
                    Button(
                        onClick = onStopDriver,
                        colors = ButtonDefaults.buttonColors(containerColor = ErrRed, contentColor = Color.White),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("停止有线驱动", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveMonitorSection(status: DriverStatus, onTestRumble: () -> Unit) {
    val live = status.liveState

    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("数据通道：${live.source}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        "回报率: ${live.packetRateHz} Hz | 陀螺仪瞄准: ${if (live.gyroAimActive) "激活中" else "未激活"}",
                        color = MutedText,
                        fontSize = 12.sp
                    )
                }
                OutlinedButton(onClick = onTestRumble, enabled = status.serviceRunning) {
                    Text("测试手柄震动", color = AccentCyan)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StickVisualizer("左摇杆 (LX / LY)", live.lx, live.ly, live.l3)
                Column(
                    modifier = Modifier.width(220.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TriggerBar("LT 扳机", live.lt)
                    TriggerBar("RT 扳机", live.rt)
                    Text(
                        "十字键: X=${live.hatX}, Y=${live.hatY}",
                        color = MutedText,
                        fontSize = 12.sp
                    )
                    Text(
                        "陀螺仪 (°/s): P=%.1f  Y=%.1f  R=%.1f".format(
                            live.gyroPitchDps,
                            live.gyroYawDps,
                            live.gyroRollDps
                        ),
                        color = AccentCyan,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                StickVisualizer("右摇杆 (RX / RY)", live.rx, live.ry, live.r3)
            }

            Text("标准 Xbox 按键", color = MutedText, fontSize = 12.sp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ButtonBadge("A", live.a)
                ButtonBadge("B", live.b)
                ButtonBadge("X", live.x)
                ButtonBadge("Y", live.y)
                ButtonBadge("LB", live.lb)
                ButtonBadge("RB", live.rb)
                ButtonBadge("L3", live.l3)
                ButtonBadge("R3", live.r3)
                ButtonBadge("Back", live.back)
                ButtonBadge("Start", live.start)
                ButtonBadge("Home", live.home)
                ButtonBadge("↑", live.hatY < 0)
                ButtonBadge("↓", live.hatY > 0)
                ButtonBadge("←", live.hatX < 0)
                ButtonBadge("→", live.hatX > 0)
            }

            Text("飞智增强按键 (仅 0xEF 增强流可用)", color = MutedText, fontSize = 12.sp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ButtonBadge("M1", live.m1, accent = Color(0xFFA855F7))
                ButtonBadge("M2", live.m2, accent = Color(0xFFA855F7))
                ButtonBadge("M3", live.m3, accent = Color(0xFFA855F7))
                ButtonBadge("M4", live.m4, accent = Color(0xFFA855F7))
                ButtonBadge("LM", live.lm, accent = Color(0xFFEC4899))
                ButtonBadge("RM", live.rm, accent = Color(0xFFEC4899))
            }
        }
    }
}

@Composable
private fun TouchMappingSection(
    status: DriverStatus,
    config: DriverConfig,
    touchProfiles: List<TouchProfile>,
    onStartOverlay: (Boolean) -> Unit,
    onStopOverlay: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onSelectTouchProfile: (String) -> Unit,
    onCreateTouchProfile: (String) -> TouchProfile,
    onDeleteTouchProfile: (String) -> Unit,
    onResetTouchPresets: () -> Unit,
    onUpdateConfig: ((DriverConfig) -> DriverConfig) -> Unit
) {
    val activeProfile = touchProfiles.firstOrNull { it.id == config.activeTouchProfileId } ?: touchProfiles.firstOrNull()
    var newProfileName by remember { mutableStateOf("") }
    val activePointers = remember { mutableStateListOf<Offset>() }
    var lastTouchLog by remember { mutableStateOf("等待触控事件（可切换到「触屏映射模式」后按手柄测试）") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("悬浮窗与游戏内可视化编辑", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    "在游戏画面上方直接拖动摇杆轮盘、FPS 视角滑动区与按键坐标，并支持按下手柄实体按键自动识别绑定。",
                    color = MutedText,
                    fontSize = 12.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(
                        text = if (status.overlayPermissionGranted) "悬浮窗权限已授予" else "缺少悬浮窗权限",
                        color = if (status.overlayPermissionGranted) OkGreen else WarnAmber
                    )
                    if (!status.overlayPermissionGranted) {
                        OutlinedButton(onClick = onRequestOverlayPermission) {
                            Text("前往授权悬浮窗", color = AccentCyan)
                        }
                    } else {
                        Button(
                            onClick = { onStartOverlay(true) },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color(0xFF082F49))
                        ) {
                            Text("打开悬浮窗并编辑键位", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { if (status.overlayRunning) onStopOverlay() else onStartOverlay(false) }
                        ) {
                            Text(if (status.overlayRunning) "隐藏悬浮窗" else "仅显示悬浮球", color = AccentCyan)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("触屏模式显示半透明键位提示", color = Color.White, fontSize = 14.sp)
                        Text("在游戏界面显示键位圆圈提示，不阻挡真实手指触控", color = MutedText, fontSize = 12.sp)
                    }
                    Switch(
                        checked = config.showTouchHints,
                        onCheckedChange = { show -> onUpdateConfig { it.copy(showTouchHints = show) } }
                    )
                }

                DropdownRow(
                    label = "触控屏幕方向校准",
                    currentText = config.touchOrientationMode.label,
                    options = TouchOrientationMode.entries.map { it.label to it },
                    onSelect = { mode -> onUpdateConfig { it.copy(touchOrientationMode = mode) } }
                )
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("触屏映射方案管理", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

                if (touchProfiles.isNotEmpty() && activeProfile != null) {
                    DropdownRow(
                        label = "当前启用方案",
                        currentText = "${activeProfile.name} (${activeProfile.nodes.size} 键位)",
                        options = touchProfiles.map { "${it.name} (${it.nodes.size})" to it.id },
                        onSelect = onSelectTouchProfile
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newProfileName,
                        onValueChange = { newProfileName = it },
                        placeholder = { Text("输入新方案名称，例如：三角洲行动") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            onCreateTouchProfile(newProfileName.trim())
                            newProfileName = ""
                        }
                    ) {
                        Text("新建方案")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (activeProfile != null && touchProfiles.size > 1) {
                        OutlinedButton(onClick = { onDeleteTouchProfile(activeProfile.id) }) {
                            Text("删除当前方案", color = ErrRed)
                        }
                    }
                    OutlinedButton(onClick = onResetTouchPresets) {
                        Text("恢复内置预设", color = MutedText)
                    }
                }

                activeProfile?.let { prof ->
                    Text(
                        text = "当前方案节点: " + if (prof.nodes.isEmpty()) "无（请打开悬浮窗添加）" else prof.nodes.joinToString(" · ") {
                            "${it.input.shortLabel}(${(it.xNorm * 100).roundToInt()}%,${(it.yNorm * 100).roundToInt()}%)"
                        },
                        color = MutedText,
                        fontSize = 12.sp
                    )
                }
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("虚拟触控校准测试画板", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(lastTouchLog, color = AccentCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF090E1A))
                        .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    activePointers.clear()
                                    event.changes.forEach { change ->
                                        if (change.pressed) {
                                            activePointers.add(change.position)
                                        }
                                    }
                                    if (activePointers.isNotEmpty()) {
                                        val first = activePointers.first()
                                        val nx = (first.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                        val ny = (first.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                        lastTouchLog = "检测到 ${activePointers.size} 指触控 | 局部坐标=(%.2f, %.2f)".format(nx, ny)
                                    }
                                }
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stepX = size.width / 4f
                        val stepY = size.height / 3f
                        for (i in 1..3) {
                            drawLine(CardBorder, Offset(stepX * i, 0f), Offset(stepX * i, size.height), 1f)
                        }
                        for (j in 1..2) {
                            drawLine(CardBorder, Offset(0f, stepY * j), Offset(size.width, stepY * j), 1f)
                        }
                        activePointers.forEach { pt ->
                            drawCircle(color = AccentCyan.copy(alpha = 0.25f), radius = 34f, center = pt)
                            drawCircle(color = AccentCyan, radius = 14f, center = pt)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MappingAndGyroSection(
    config: DriverConfig,
    serviceRunning: Boolean,
    onUpdateConfig: ((DriverConfig) -> DriverConfig) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("协议与基础设置", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

                DropdownRow(
                    label = "USB 读取模式",
                    currentText = config.inputMode.label,
                    options = InputPreferenceMode.entries.map { it.label to it },
                    onSelect = { mode -> onUpdateConfig { it.copy(inputMode = mode) } }
                )

                Column {
                    Text(
                        "摇杆内死区: ${config.stickDeadzonePercent}%",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Slider(
                        value = config.stickDeadzonePercent.toFloat(),
                        onValueChange = { v -> onUpdateConfig { it.copy(stickDeadzonePercent = v.roundToInt()) } },
                        valueRange = 0f..20f,
                        steps = 19
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("创建独立附加按键虚拟设备 (BUTTON_1~6)", color = Color.White, fontSize = 14.sp)
                        Text(
                            if (serviceRunning) "修改后需重启驱动服务生效；用于模拟器单独绑定 M1~M4/LM/RM"
                            else "开启后可在支持多键位的模拟器里独立绑定映射为「附加手柄键」的背键",
                            color = MutedText,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = config.enableExtraButtonDevice,
                        onCheckedChange = { checked -> onUpdateConfig { it.copy(enableExtraButtonDevice = checked) } }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("屏蔽 Home (Guide) 键全局输出", color = Color.White, fontSize = 14.sp)
                        Text(
                            "仅在测试界面显示状态，不向系统发送 GUIDE/HOME 事件（防止部分系统按 LOGO 键切回桌面）",
                            color = MutedText,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = config.blockHomeButton,
                        onCheckedChange = { checked -> onUpdateConfig { it.copy(blockHomeButton = checked) } }
                    )
                }
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("背键 M1–M4 与顶部肩键 LM / RM 映射", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    "实时生效。注意：若在飞智 PC 空间站开启过「允许第三方接管映射」，增强模式会接管原始背键并按此表映射。",
                    color = MutedText,
                    fontSize = 12.sp
                )

                ExtraButtonId.entries.forEach { btnId ->
                    val current = config.buttonMappings[btnId] ?: MappingAction.NONE
                    DropdownRow(
                        label = btnId.label,
                        currentText = current.label,
                        options = MappingAction.entries.map { it.label to it },
                        onSelect = { action ->
                            onUpdateConfig { cur ->
                                cur.copy(buttonMappings = cur.buttonMappings + (btnId to action))
                            }
                        }
                    )
                }
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("陀螺仪辅助右摇杆瞄准 (Gyro -> Right Stick)", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

                DropdownRow(
                    label = "触发方式",
                    currentText = config.gyroTriggerMode.label,
                    options = GyroTriggerMode.entries.map { it.label to it },
                    onSelect = { mode -> onUpdateConfig { it.copy(gyroTriggerMode = mode) } }
                )

                Text("水平灵敏度 (Yaw -> RX): %.2f".format(config.gyroSensitivityX), color = Color.White, fontSize = 14.sp)
                Slider(
                    value = config.gyroSensitivityX,
                    onValueChange = { v -> onUpdateConfig { it.copy(gyroSensitivityX = v) } },
                    valueRange = 0.2f..5.0f
                )

                Text("垂直灵敏度 (Pitch -> RY): %.2f".format(config.gyroSensitivityY), color = Color.White, fontSize = 14.sp)
                Slider(
                    value = config.gyroSensitivityY,
                    onValueChange = { v -> onUpdateConfig { it.copy(gyroSensitivityY = v) } },
                    valueRange = 0.2f..5.0f
                )

                Text("防抖阈值: %.1f °/s".format(config.gyroDeadzoneDps), color = Color.White, fontSize = 14.sp)
                Slider(
                    value = config.gyroDeadzoneDps,
                    onValueChange = { v -> onUpdateConfig { it.copy(gyroDeadzoneDps = v) } },
                    valueRange = 0f..15f
                )

                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("反转水平轴", color = Color.White, fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = config.gyroInvertX,
                            onCheckedChange = { inv -> onUpdateConfig { it.copy(gyroInvertX = inv) } }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("反转垂直轴", color = Color.White, fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = config.gyroInvertY,
                            onCheckedChange = { inv -> onUpdateConfig { it.copy(gyroInvertY = inv) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsSection(logs: List<String>, onProbePermissions: () -> Unit) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("运行与握手诊断日志", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                OutlinedButton(onClick = onProbePermissions) {
                    Text("测试 /dev/uinput 权限", color = AccentCyan)
                }
            }
            if (logs.isEmpty()) {
                Text("暂无日志，插上手柄并点击启动驱动后将显示握手与接口枚举详情。", color = MutedText, fontSize = 13.sp)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF090E1A))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    logs.asReversed().forEach { line ->
                        Text(
                            text = line,
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardColor),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun InfoBox(
    title: String,
    value: String,
    ok: Boolean,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0E1629))
            .border(1.dp, if (ok) OkGreen.copy(alpha = 0.45f) else CardBorder, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = MutedText, fontSize = 12.sp)
        Text(
            text = value,
            color = if (ok) OkGreen else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        if (actionLabel != null && onAction != null) {
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier.height(34.dp)
            ) {
                Text(actionLabel, color = AccentCyan, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StickVisualizer(label: String, x: Int, y: Int, pressed: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(modifier = Modifier.size(110.dp)) {
            val r = size.minDimension / 2f - 4f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = Color(0xFF0E1629), radius = r, center = center)
            drawCircle(
                color = if (pressed) AccentCyan else CardBorder,
                radius = r,
                center = center,
                style = Stroke(width = 3f)
            )
            drawLine(CardBorder, Offset(center.x - r, center.y), Offset(center.x + r, center.y), 1.5f)
            drawLine(CardBorder, Offset(center.x, center.y - r), Offset(center.x, center.y + r), 1.5f)

            val nx = (x / 32768f).coerceIn(-1f, 1f)
            val ny = (y / 32768f).coerceIn(-1f, 1f)
            val dot = Offset(center.x + nx * (r - 8f), center.y + ny * (r - 8f))
            drawCircle(color = AccentCyan, radius = 9f, center = dot)
        }
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text("X=$x  Y=$y", color = MutedText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TriggerBar(label: String, value: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, fontSize = 12.sp)
            Text("$value / 255", color = AccentCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        LinearProgressIndicator(
            progress = { (value / 255f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = AccentCyan,
            trackColor = Color(0xFF0E1629)
        )
    }
}

@Composable
private fun ButtonBadge(label: String, active: Boolean, accent: Color = AccentCyan) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) accent.copy(alpha = 0.25f) else Color(0xFF0E1629))
            .border(1.dp, if (active) accent else CardBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            color = if (active) accent else MutedText,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun <T> DropdownRow(
    label: String,
    currentText: String,
    options: List<Pair<String, T>>,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(0.45f))
        Box(modifier = Modifier.weight(0.55f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0E1629))
                    .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(currentText, color = AccentCyan, fontSize = 13.sp)
                Text("▼", color = MutedText, fontSize = 11.sp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (text, value) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        }
                    )
                }
            }
        }
    }
}
