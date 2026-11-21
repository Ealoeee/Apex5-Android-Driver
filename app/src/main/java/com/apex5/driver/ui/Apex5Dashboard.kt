package com.apex5.driver.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex5.driver.BuildConfig
import com.apex5.driver.model.DriverConfig
import com.apex5.driver.model.DriverOutputMode
import com.apex5.driver.model.DriverStatus
import com.apex5.driver.model.ShizukuState
import com.apex5.driver.model.SpaceStationPreset
import com.apex5.driver.model.TouchOrientationMode
import com.apex5.driver.model.TouchProfile
import com.apex5.driver.update.UpdateStage
import com.apex5.driver.update.UpdateUiState
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class NavTab(val label: String, val shortLabel: String, val icon: ImageVector) {
    TELEMETRY("实时控制台", "控制台", Icons.Rounded.SportsEsports),
    TOUCH("触屏映射工作室", "触屏映射", Icons.Rounded.TouchApp),
    STICKS_TRIGGERS("摇杆与扳机", "摇杆扳机", Icons.Rounded.Tune),
    BUTTONS_MACROS("按键·连发·宏", "按键宏", Icons.Rounded.Gamepad),
    LOGS_UPDATE("更新与终端", "更新日志", Icons.Rounded.SystemUpdate)
}

@Composable
fun Apex5Dashboard(
    status: DriverStatus,
    config: DriverConfig,
    touchProfiles: List<TouchProfile>,
    updateState: UpdateUiState,
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
    onDuplicateTouchProfile: (String) -> TouchProfile?,
    onExportTouchProfile: (TouchProfile) -> String,
    onImportTouchProfile: (String) -> Result<TouchProfile>,
    onDeleteTouchProfile: (String) -> Unit,
    onResetTouchPresets: () -> Unit,
    onProbePermissions: () -> Unit,
    onClearLogs: () -> Unit,
    onTestRumble: () -> Unit,
    onTestTriggerRumble: () -> Unit,
    onSyncOnboardTriggers: () -> Unit,
    onApplySpacePreset: (SpaceStationPreset) -> Unit,
    onExportSpaceJson: () -> String,
    onImportSpaceJson: (String) -> Result<DriverConfig>,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdateSystem: () -> Unit,
    onInstallUpdateShizuku: () -> Unit,
    onToggleUpdateMirror: (Boolean) -> Unit,
    onUpdateConfig: ((DriverConfig) -> DriverConfig) -> Unit
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val selectedTab = NavTab.entries[selectedTabIndex]
    val hasUpdateBadge = updateState.stage == UpdateStage.UPDATE_AVAILABLE ||
        updateState.stage == UpdateStage.READY_TO_INSTALL

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Apex5Palette.BgObsidian)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val isWideTablet = maxWidth >= 720.dp

        if (isWideTablet) {
            Row(modifier = Modifier.fillMaxSize()) {
                TabletNavigationRail(
                    selectedTab = selectedTab,
                    hasUpdateBadge = hasUpdateBadge,
                    onSelect = { selectedTabIndex = it.ordinal },
                    status = status,
                    onRefresh = onRefresh
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    PersistentActionHeader(
                        status = status,
                        config = config,
                        isWide = true,
                        onRequestShizuku = onRequestShizuku,
                        onRequestUsb = onRequestUsb,
                        onStartDriver = onStartDriver,
                        onStopDriver = onStopDriver,
                        onSwitchOutputMode = onSwitchOutputMode,
                        onStartOverlay = onStartOverlay,
                        onStopOverlay = onStopOverlay,
                        onProbePermissions = onProbePermissions
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        DashboardTabBody(
                            selectedTab = selectedTab,
                            isWide = true,
                            status = status,
                            config = config,
                            touchProfiles = touchProfiles,
                            updateState = updateState,
                            onStartOverlay = onStartOverlay,
                            onStopOverlay = onStopOverlay,
                            onRequestOverlayPermission = onRequestOverlayPermission,
                            onSelectTouchProfile = onSelectTouchProfile,
                            onCreateTouchProfile = onCreateTouchProfile,
                            onDuplicateTouchProfile = onDuplicateTouchProfile,
                            onExportTouchProfile = onExportTouchProfile,
                            onImportTouchProfile = onImportTouchProfile,
                            onDeleteTouchProfile = onDeleteTouchProfile,
                            onResetTouchPresets = onResetTouchPresets,
                            onProbePermissions = onProbePermissions,
                            onClearLogs = onClearLogs,
                            onTestRumble = onTestRumble,
                            onTestTriggerRumble = onTestTriggerRumble,
                            onSyncOnboardTriggers = onSyncOnboardTriggers,
                            onApplySpacePreset = onApplySpacePreset,
                            onExportSpaceJson = onExportSpaceJson,
                            onImportSpaceJson = onImportSpaceJson,
                            onCheckUpdate = onCheckUpdate,
                            onDownloadUpdate = onDownloadUpdate,
                            onInstallUpdateSystem = onInstallUpdateSystem,
                            onInstallUpdateShizuku = onInstallUpdateShizuku,
                            onToggleUpdateMirror = onToggleUpdateMirror,
                            onUpdateConfig = onUpdateConfig
                        )
                    }
                }
            }
        } else {
            // Compact portrait/phone layout
            Column(modifier = Modifier.fillMaxSize()) {
                CompactPhoneTopBar(status = status, onRefresh = onRefresh)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PersistentActionHeader(
                        status = status,
                        config = config,
                        isWide = false,
                        onRequestShizuku = onRequestShizuku,
                        onRequestUsb = onRequestUsb,
                        onStartDriver = onStartDriver,
                        onStopDriver = onStopDriver,
                        onSwitchOutputMode = onSwitchOutputMode,
                        onStartOverlay = onStartOverlay,
                        onStopOverlay = onStopOverlay,
                        onProbePermissions = onProbePermissions
                    )

                    DashboardTabBody(
                        selectedTab = selectedTab,
                        isWide = false,
                        status = status,
                        config = config,
                        touchProfiles = touchProfiles,
                        updateState = updateState,
                        onStartOverlay = onStartOverlay,
                        onStopOverlay = onStopOverlay,
                        onRequestOverlayPermission = onRequestOverlayPermission,
                        onSelectTouchProfile = onSelectTouchProfile,
                        onCreateTouchProfile = onCreateTouchProfile,
                        onDuplicateTouchProfile = onDuplicateTouchProfile,
                        onExportTouchProfile = onExportTouchProfile,
                        onImportTouchProfile = onImportTouchProfile,
                        onDeleteTouchProfile = onDeleteTouchProfile,
                        onResetTouchPresets = onResetTouchPresets,
                        onProbePermissions = onProbePermissions,
                        onClearLogs = onClearLogs,
                        onTestRumble = onTestRumble,
                        onTestTriggerRumble = onTestTriggerRumble,
                        onSyncOnboardTriggers = onSyncOnboardTriggers,
                        onApplySpacePreset = onApplySpacePreset,
                        onExportSpaceJson = onExportSpaceJson,
                        onImportSpaceJson = onImportSpaceJson,
                        onCheckUpdate = onCheckUpdate,
                        onDownloadUpdate = onDownloadUpdate,
                        onInstallUpdateSystem = onInstallUpdateSystem,
                        onInstallUpdateShizuku = onInstallUpdateShizuku,
                        onToggleUpdateMirror = onToggleUpdateMirror,
                        onUpdateConfig = onUpdateConfig
                    )
                }

                PhoneBottomNavBar(
                    selectedTab = selectedTab,
                    hasUpdateBadge = hasUpdateBadge,
                    onSelect = { selectedTabIndex = it.ordinal }
                )
            }
        }
    }
}

@Composable
private fun TabletNavigationRail(
    selectedTab: NavTab,
    hasUpdateBadge: Boolean,
    onSelect: (NavTab) -> Unit,
    status: DriverStatus,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(224.dp)
            .fillMaxHeight()
            .background(Apex5Palette.RailBg)
            .border(1.dp, Apex5Palette.BorderSubtle)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Brand Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Apex5Palette.Cyan.copy(alpha = 0.28f), Apex5Palette.Violet.copy(alpha = 0.28f))
                            )
                        )
                        .border(1.dp, Apex5Palette.Cyan.copy(alpha = 0.5f), RoundedCornerShape(11.dp))
                ) {
                    Text("A5", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("APEX 5 DRIVER", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("v${BuildConfig.VERSION_NAME} · 空间站内核", color = Apex5Palette.TextSecondary, fontSize = 11.sp)
                }
            }

            HorizontalDivider(color = Apex5Palette.BorderSubtle, modifier = Modifier.padding(vertical = 4.dp))

            NavTab.entries.forEach { tab ->
                val isSelected = tab == selectedTab
                val bg = if (isSelected) Apex5Palette.Cyan.copy(alpha = 0.14f) else Color.Transparent
                val borderCol = if (isSelected) Apex5Palette.Cyan.copy(alpha = 0.45f) else Color.Transparent
                val fg = if (isSelected) Apex5Palette.Cyan else Apex5Palette.TextSecondary

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(bg)
                        .border(1.dp, borderCol, RoundedCornerShape(11.dp))
                        .clickable { onSelect(tab) }
                        .padding(horizontal = 12.dp, vertical = 11.dp)
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = tab.label,
                        color = if (isSelected) Color.White else fg,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (tab == NavTab.LOGS_UPDATE && hasUpdateBadge) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Apex5Palette.Amber)
                        )
                    }
                }
            }
        }

        // Bottom hardware summary + Refresh button
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Apex5Palette.InnerBg)
                    .border(1.dp, Apex5Palette.BorderSubtle, RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("硬件遥测", color = Apex5Palette.TextMuted, fontSize = 11.sp)
                        StatusPillBadge(
                            text = if (status.serviceRunning) "${status.liveState.packetRateHz} Hz" else "待机",
                            color = if (status.serviceRunning) Apex5Palette.Emerald else Apex5Palette.TextMuted
                        )
                    }
                    Text(
                        text = "FW ${status.firmwareText} · 电量 ${status.batteryText}",
                        color = Apex5Palette.TextSecondary,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth().height(38.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, tint = Apex5Palette.Cyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("刷新连接检测", color = Apex5Palette.Cyan, fontSize = 12.5.sp)
            }
        }
    }
}

@Composable
private fun CompactPhoneTopBar(status: DriverStatus, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Apex5Palette.RailBg)
            .border(1.dp, Apex5Palette.BorderSubtle)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Apex5Palette.Cyan.copy(alpha = 0.2f))
                    .border(1.dp, Apex5Palette.Cyan.copy(alpha = 0.5f), RoundedCornerShape(9.dp))
            ) {
                Text("A5", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(Modifier.width(9.dp))
            Column {
                Text("Apex 5 驱动", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    "v${BuildConfig.VERSION_NAME} · ${if (status.serviceRunning) "${status.liveState.packetRateHz}Hz" else "待机"}",
                    color = Apex5Palette.TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPillBadge(
                text = if (status.serviceRunning) {
                    if (status.activeOutputMode == DriverOutputMode.TOUCH_MAPPER) "触屏映射" else "Xbox运行中"
                } else "未启动",
                color = if (status.serviceRunning) Apex5Palette.Emerald else Apex5Palette.TextSecondary
            )
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.height(34.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = Apex5Palette.Cyan, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun PhoneBottomNavBar(
    selectedTab: NavTab,
    hasUpdateBadge: Boolean,
    onSelect: (NavTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Apex5Palette.RailBg)
            .border(1.dp, Apex5Palette.BorderSubtle)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        NavTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            val color = if (selected) Apex5Palette.Cyan else Apex5Palette.TextSecondary
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) Apex5Palette.Cyan.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                    if (tab == NavTab.LOGS_UPDATE && hasUpdateBadge) {
                        Spacer(Modifier.width(3.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Apex5Palette.Amber)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tab.shortLabel,
                    color = color,
                    fontSize = 10.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersistentActionHeader(
    status: DriverStatus,
    config: DriverConfig,
    isWide: Boolean,
    onRequestShizuku: () -> Unit,
    onRequestUsb: () -> Unit,
    onStartDriver: () -> Unit,
    onStopDriver: () -> Unit,
    onSwitchOutputMode: (DriverOutputMode) -> Unit,
    onStartOverlay: (Boolean) -> Unit,
    onStopOverlay: () -> Unit,
    onProbePermissions: () -> Unit
) {
    BentoCard(
        highlighted = status.serviceRunning,
        accent = if (status.serviceRunning) Apex5Palette.Emerald else Apex5Palette.Cyan,
        contentPadding = 14
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 3 compact telemetry readiness tiles
            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ReadinessTile(
                        title = "Shizuku 通道",
                        value = status.shizukuState.label,
                        ok = status.shizukuState == ShizukuState.READY,
                        icon = Icons.Rounded.Security,
                        modifier = Modifier.weight(1f),
                        actionLabel = when (status.shizukuState) {
                            ShizukuState.NO_PERMISSION -> "授权"
                            ShizukuState.READY -> "自检"
                            else -> null
                        },
                        onAction = {
                            if (status.shizukuState == ShizukuState.NO_PERMISSION) onRequestShizuku() else onProbePermissions()
                        }
                    )
                    ReadinessTile(
                        title = "Apex 5 有线 USB",
                        value = status.deviceSummary + if (status.usbConnected && !status.usbPermissionGranted) " [待授权]" else "",
                        ok = status.usbConnected && status.usbPermissionGranted,
                        icon = Icons.Rounded.Usb,
                        modifier = Modifier.weight(1f),
                        actionLabel = if (status.usbConnected && !status.usbPermissionGranted) "授权" else null,
                        onAction = onRequestUsb
                    )
                    ReadinessTile(
                        title = "虚拟输出 & 协议",
                        value = if (status.serviceRunning) {
                            val outType = if (status.touchActive) "10指虚拟触屏" else "Xbox 360 手柄"
                            val mode = if (status.enhancedActive) "增强 0xEF" else "标准 XInput"
                            "$outType · $mode"
                        } else {
                            "手柄长按 HOME 切「PC → 有线」"
                        },
                        ok = status.uinputActive || status.touchActive,
                        icon = Icons.Rounded.Memory,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadinessTile(
                        title = "1. Shizuku 权限通道",
                        value = status.shizukuState.label,
                        ok = status.shizukuState == ShizukuState.READY,
                        icon = Icons.Rounded.Security,
                        actionLabel = when (status.shizukuState) {
                            ShizukuState.NO_PERMISSION -> "授权"
                            ShizukuState.READY -> "自检"
                            else -> null
                        },
                        onAction = {
                            if (status.shizukuState == ShizukuState.NO_PERMISSION) onRequestShizuku() else onProbePermissions()
                        }
                    )
                    ReadinessTile(
                        title = "2. Apex 5 有线 USB",
                        value = status.deviceSummary + if (status.usbConnected && !status.usbPermissionGranted) " [待授权]" else "",
                        ok = status.usbConnected && status.usbPermissionGranted,
                        icon = Icons.Rounded.Usb,
                        actionLabel = if (status.usbConnected && !status.usbPermissionGranted) "授权 USB" else null,
                        onAction = onRequestUsb
                    )
                }
            }

            // Mode switcher + Overlay toggle + Start/Stop CTA
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Apex5Palette.InnerBg)
                        .border(1.dp, Apex5Palette.BorderSubtle, RoundedCornerShape(10.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DriverOutputMode.entries.forEach { mode ->
                        val selected = config.outputMode == mode
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) Apex5Palette.Cyan.copy(alpha = 0.20f) else Color.Transparent
                                )
                                .clickable { onSwitchOutputMode(mode) }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = if (mode == DriverOutputMode.GAMEPAD) "原生 Xbox 360 手柄" else "触屏多点映射",
                                color = if (selected) Apex5Palette.Cyan else Apex5Palette.TextSecondary,
                                fontSize = 12.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = { if (status.overlayRunning) onStopOverlay() else onStartOverlay(false) },
                    modifier = Modifier.height(38.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Layers,
                        contentDescription = null,
                        tint = if (status.overlayRunning) Apex5Palette.Emerald else Apex5Palette.Cyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (status.overlayRunning) "关闭悬浮球" else "开启悬浮球",
                        color = if (status.overlayRunning) Apex5Palette.Emerald else Apex5Palette.Cyan,
                        fontSize = 12.5.sp
                    )
                }

                if (!status.serviceRunning) {
                    Button(
                        onClick = onStartDriver,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Apex5Palette.Cyan,
                            contentColor = Color(0xFF062033)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("启动 Apex 5 驱动", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    }
                } else {
                    Button(
                        onClick = onStopDriver,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Apex5Palette.Rose,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("停止驱动服务", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    }
                }
            }

            status.lastError?.let { err ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Apex5Palette.Rose.copy(alpha = 0.14f))
                        .border(1.dp, Apex5Palette.Rose.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    Text(err, color = Color(0xFFFCA5A5), fontSize = 12.5.sp)
                }
            }
        }
    }
}

@Composable
private fun ReadinessTile(
    title: String,
    value: String,
    ok: Boolean,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(Apex5Palette.InnerBg)
            .border(
                1.dp,
                if (ok) Apex5Palette.Emerald.copy(alpha = 0.42f) else Apex5Palette.BorderSubtle,
                RoundedCornerShape(11.dp)
            )
            .padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background((if (ok) Apex5Palette.Emerald else Apex5Palette.TextMuted).copy(alpha = 0.14f))
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (ok) Apex5Palette.Emerald else Apex5Palette.TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Apex5Palette.TextMuted, fontSize = 10.5.sp, maxLines = 1)
            Text(
                text = value,
                color = if (ok) Apex5Palette.Emerald else Apex5Palette.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Apex5Palette.Cyan.copy(alpha = 0.16f))
                    .border(1.dp, Apex5Palette.Cyan.copy(alpha = 0.4f), RoundedCornerShape(7.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
                Text(actionLabel, color = Apex5Palette.Cyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DashboardTabBody(
    selectedTab: NavTab,
    isWide: Boolean,
    status: DriverStatus,
    config: DriverConfig,
    touchProfiles: List<TouchProfile>,
    updateState: UpdateUiState,
    onStartOverlay: (Boolean) -> Unit,
    onStopOverlay: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onSelectTouchProfile: (String) -> Unit,
    onCreateTouchProfile: (String) -> TouchProfile,
    onDuplicateTouchProfile: (String) -> TouchProfile?,
    onExportTouchProfile: (TouchProfile) -> String,
    onImportTouchProfile: (String) -> Result<TouchProfile>,
    onDeleteTouchProfile: (String) -> Unit,
    onResetTouchPresets: () -> Unit,
    onProbePermissions: () -> Unit,
    onClearLogs: () -> Unit,
    onTestRumble: () -> Unit,
    onTestTriggerRumble: () -> Unit,
    onSyncOnboardTriggers: () -> Unit,
    onApplySpacePreset: (SpaceStationPreset) -> Unit,
    onExportSpaceJson: () -> String,
    onImportSpaceJson: (String) -> Result<DriverConfig>,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdateSystem: () -> Unit,
    onInstallUpdateShizuku: () -> Unit,
    onToggleUpdateMirror: (Boolean) -> Unit,
    onUpdateConfig: ((DriverConfig) -> DriverConfig) -> Unit
) {
    when (selectedTab) {
        NavTab.TELEMETRY -> TelemetryBentoView(status = status, config = config, isWide = isWide, onTestRumble = onTestRumble)
        NavTab.TOUCH -> TouchStudioView(
            status = status,
            config = config,
            touchProfiles = touchProfiles,
            isWide = isWide,
            onStartOverlay = onStartOverlay,
            onStopOverlay = onStopOverlay,
            onRequestOverlayPermission = onRequestOverlayPermission,
            onSelectTouchProfile = onSelectTouchProfile,
            onCreateTouchProfile = onCreateTouchProfile,
            onDuplicateTouchProfile = onDuplicateTouchProfile,
            onExportTouchProfile = onExportTouchProfile,
            onImportTouchProfile = onImportTouchProfile,
            onDeleteTouchProfile = onDeleteTouchProfile,
            onResetTouchPresets = onResetTouchPresets,
            onUpdateConfig = onUpdateConfig
        )
        NavTab.STICKS_TRIGGERS -> StickAndTriggerSpaceView(
            status = status,
            config = config,
            isWide = isWide,
            onUpdateConfig = onUpdateConfig,
            onTestTriggerRumble = onTestTriggerRumble,
            onSyncOnboardTriggers = onSyncOnboardTriggers
        )
        NavTab.BUTTONS_MACROS -> ButtonsMacrosSpaceView(
            status = status,
            config = config,
            isWide = isWide,
            onUpdateConfig = onUpdateConfig,
            onApplySpacePreset = onApplySpacePreset,
            onExportSpaceJson = onExportSpaceJson,
            onImportSpaceJson = onImportSpaceJson,
            onTestRumble = onTestRumble,
            onTestTriggerRumble = onTestTriggerRumble
        )
        NavTab.LOGS_UPDATE -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OtaUpdateCard(
                updateState = updateState,
                shizukuState = status.shizukuState,
                onCheckUpdate = onCheckUpdate,
                onDownloadUpdate = onDownloadUpdate,
                onInstallUpdateSystem = onInstallUpdateSystem,
                onInstallUpdateShizuku = onInstallUpdateShizuku,
                onToggleMirror = onToggleUpdateMirror
            )
            DiagnosticTerminalView(
                logs = status.logs,
                onProbePermissions = onProbePermissions,
                onClearLogs = onClearLogs
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TelemetryBentoView(
    status: DriverStatus,
    config: DriverConfig,
    isWide: Boolean,
    onTestRumble: () -> Unit
) {
    val live = status.liveState

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (isWide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SticksBentoCard(
                    liveLx = live.lx,
                    liveLy = live.ly,
                    liveL3 = live.l3,
                    liveRx = live.rx,
                    liveRy = live.ry,
                    liveR3 = live.r3,
                    leftDeadzonePercent = config.leftStick.centerDeadzonePercent,
                    rightDeadzonePercent = config.rightStick.centerDeadzonePercent,
                    modifier = Modifier.weight(1.1f)
                )
                ChannelAndTriggersCard(
                    status = status,
                    onTestRumble = onTestRumble,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            SticksBentoCard(
                liveLx = live.lx,
                liveLy = live.ly,
                liveL3 = live.l3,
                liveRx = live.rx,
                liveRy = live.ry,
                liveR3 = live.r3,
                leftDeadzonePercent = config.leftStick.centerDeadzonePercent,
                rightDeadzonePercent = config.rightStick.centerDeadzonePercent
            )
            ChannelAndTriggersCard(
                status = status,
                onTestRumble = onTestRumble
            )
        }

        // Tactile Button Matrix Bento Card
        BentoCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BentoSectionHeader(
                    title = "实体按键 & 飞智扩展背键实时矩阵",
                    subtitle = "点击手柄任意按钮，下方对应键帽将即时点亮",
                    icon = Icons.Rounded.Gamepad,
                    accent = Apex5Palette.Cyan
                )

                Text("标准 Xbox 按键 & 十字方向键", color = Apex5Palette.TextSecondary, fontSize = 11.5.sp)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KeycapBadge("A", live.a)
                    KeycapBadge("B", live.b)
                    KeycapBadge("X", live.x)
                    KeycapBadge("Y", live.y)
                    KeycapBadge("LB", live.lb)
                    KeycapBadge("RB", live.rb)
                    KeycapBadge("L3", live.l3)
                    KeycapBadge("R3", live.r3)
                    KeycapBadge("Back", live.back)
                    KeycapBadge("Start", live.start)
                    KeycapBadge("Home", live.home)
                    KeycapBadge("↑", live.hatY < 0)
                    KeycapBadge("↓", live.hatY > 0)
                    KeycapBadge("←", live.hatX < 0)
                    KeycapBadge("→", live.hatX > 0)
                }

                HorizontalDivider(color = Apex5Palette.BorderSubtle)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "飞智 0xEF 增强扩展键 (M1–M4 背键 / LM·RM 小肩键)",
                        color = Apex5Palette.TextSecondary,
                        fontSize = 11.5.sp
                    )
                    StatusPillBadge(
                        text = if (status.enhancedActive) "0xEF 已激活" else "等待 0xEF",
                        color = if (status.enhancedActive) Apex5Palette.Violet else Apex5Palette.TextMuted
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KeycapBadge("M1", live.m1, accent = Apex5Palette.Violet)
                    KeycapBadge("M2", live.m2, accent = Apex5Palette.Violet)
                    KeycapBadge("M3", live.m3, accent = Apex5Palette.Violet)
                    KeycapBadge("M4", live.m4, accent = Apex5Palette.Violet)
                    KeycapBadge("LM", live.lm, accent = Apex5Palette.Pink)
                    KeycapBadge("RM", live.rm, accent = Apex5Palette.Pink)
                }
            }
        }
    }
}

@Composable
private fun SticksBentoCard(
    liveLx: Int,
    liveLy: Int,
    liveL3: Boolean,
    liveRx: Int,
    liveRy: Int,
    liveR3: Boolean,
    leftDeadzonePercent: Int,
    rightDeadzonePercent: Int,
    modifier: Modifier = Modifier
) {
    BentoCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BentoSectionHeader(
                title = "双摇杆矢量 & 死区环",
                subtitle = "虚线内圈代表当前左 ${leftDeadzonePercent}% / 右 ${rightDeadzonePercent}% 内死区",
                icon = Icons.Rounded.SportsEsports,
                accent = Apex5Palette.Cyan,
                trailing = {
                    StatusPillBadge(text = "L ${leftDeadzonePercent}% · R ${rightDeadzonePercent}%", color = Apex5Palette.Cyan, pulseDot = false)
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StickDialVisualizer("左摇杆 LX / LY", liveLx, liveLy, liveL3, leftDeadzonePercent)
                StickDialVisualizer("右摇杆 RX / RY", liveRx, liveRy, liveR3, rightDeadzonePercent)
            }
        }
    }
}

@Composable
private fun ChannelAndTriggersCard(
    status: DriverStatus,
    onTestRumble: () -> Unit,
    modifier: Modifier = Modifier
) {
    val live = status.liveState
    BentoCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BentoSectionHeader(
                title = "实时速率 · 扳机 · 陀螺仪",
                subtitle = "通道：${live.source}",
                icon = Icons.Rounded.Explore,
                accent = Apex5Palette.Violet,
                trailing = {
                    OutlinedButton(
                        onClick = onTestRumble,
                        enabled = status.serviceRunning,
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Icon(Icons.Rounded.Vibration, contentDescription = null, tint = Apex5Palette.Cyan, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("震动测试", color = Apex5Palette.Cyan, fontSize = 11.5.sp)
                    }
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile("USB 回报率", "${live.packetRateHz} Hz", Apex5Palette.Cyan, Modifier.weight(1f))
                MetricTile(
                    "体感瞄准",
                    if (live.gyroAimActive) "激活中" else "未激活",
                    if (live.gyroAimActive) Apex5Palette.Emerald else Apex5Palette.TextSecondary,
                    Modifier.weight(1f)
                )
            }

            GradientTriggerBar("LT 左线性扳机", live.lt, Apex5Palette.Cyan)
            GradientTriggerBar("RT 右线性扳机", live.rt, Apex5Palette.Violet)

            // 3-Axis Gyro mini bars
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Apex5Palette.InnerBg)
                    .border(1.dp, Apex5Palette.BorderSubtle, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("IMU 三轴角速度 (°/s)", color = Apex5Palette.TextSecondary, fontSize = 11.sp)
                GyroAxisBar("Pitch", live.gyroPitchDps, Apex5Palette.Cyan)
                GyroAxisBar("Yaw  ", live.gyroYawDps, Apex5Palette.Violet)
                GyroAxisBar("Roll ", live.gyroRollDps, Apex5Palette.Amber)
            }
        }
    }
}

@Composable
private fun TouchStudioView(
    status: DriverStatus,
    config: DriverConfig,
    touchProfiles: List<TouchProfile>,
    isWide: Boolean,
    onStartOverlay: (Boolean) -> Unit,
    onStopOverlay: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onSelectTouchProfile: (String) -> Unit,
    onCreateTouchProfile: (String) -> TouchProfile,
    onDuplicateTouchProfile: (String) -> TouchProfile?,
    onExportTouchProfile: (TouchProfile) -> String,
    onImportTouchProfile: (String) -> Result<TouchProfile>,
    onDeleteTouchProfile: (String) -> Unit,
    onResetTouchPresets: () -> Unit,
    onUpdateConfig: ((DriverConfig) -> DriverConfig) -> Unit
) {
    val activeProfile = touchProfiles.firstOrNull { it.id == config.activeTouchProfileId } ?: touchProfiles.firstOrNull()
    var newProfileName by remember { mutableStateOf("") }
    var clipboardFeedback by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val profileManagerCard: @Composable (Modifier) -> Unit = { mod ->
            BentoCard(modifier = mod) {
                Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    BentoSectionHeader(
                        title = "触屏方案管理 · 导入 / 导出",
                        subtitle = "内置 FPS / ARPG / MOBA 宏示例预设，支持剪贴板分享 JSON",
                        icon = Icons.Rounded.TouchApp,
                        accent = Apex5Palette.Cyan
                    )

                    if (touchProfiles.isNotEmpty() && activeProfile != null) {
                        SleekDropdownRow(
                            label = "当前启用方案",
                            currentText = "${activeProfile.name} (${activeProfile.nodes.size} 节点)",
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
                            placeholder = { Text("输入新方案名，例如：王者荣耀", fontSize = 12.5.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Apex5Palette.Cyan,
                                unfocusedBorderColor = Apex5Palette.BorderStrong,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                onCreateTouchProfile(newProfileName.trim())
                                newProfileName = ""
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Apex5Palette.Cyan, contentColor = Color(0xFF062033)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(50.dp)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("新建", fontWeight = FontWeight.Bold)
                        }
                    }

                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (activeProfile != null) {
                            OutlinedButton(
                                onClick = {
                                    onDuplicateTouchProfile(activeProfile.id)
                                    clipboardFeedback = "已创建副本方案"
                                },
                                modifier = Modifier.height(36.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, tint = Apex5Palette.Cyan, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("复制副本", color = Apex5Palette.Cyan, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    val json = onExportTouchProfile(activeProfile)
                                    clipboard.setText(AnnotatedString(json))
                                    clipboardFeedback = "已复制「${activeProfile.name}」JSON 到剪贴板"
                                },
                                modifier = Modifier.height(36.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = Apex5Palette.Emerald, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("导出 JSON", color = Apex5Palette.Emerald, fontSize = 12.sp)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                val text = clipboard.getText()?.text.orEmpty()
                                if (text.isBlank()) {
                                    clipboardFeedback = "剪贴板为空，请先复制方案 JSON"
                                } else {
                                    val res = onImportTouchProfile(text)
                                    clipboardFeedback = res.fold(
                                        onSuccess = { "已导入方案「${it.name}」(${it.nodes.size} 节点)" },
                                        onFailure = { "导入失败：JSON 格式不正确" }
                                    )
                                }
                            },
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null, tint = Apex5Palette.Violet, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("剪贴板导入", color = Apex5Palette.Violet, fontSize = 12.sp)
                        }

                        if (activeProfile != null && touchProfiles.size > 1) {
                            OutlinedButton(
                                onClick = { onDeleteTouchProfile(activeProfile.id) },
                                modifier = Modifier.height(36.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Apex5Palette.Rose, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("删除", color = Apex5Palette.Rose, fontSize = 12.sp)
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                onResetTouchPresets()
                                clipboardFeedback = "已恢复默认预设 (含 MOBA 宏示例)"
                            },
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            Text("重置预设", color = Apex5Palette.TextSecondary, fontSize = 12.sp)
                        }
                    }

                    clipboardFeedback?.let { fb ->
                        Text(fb, color = Apex5Palette.Cyan, fontSize = 11.5.sp)
                    }
                }
            }
        }

        val overlayCard: @Composable (Modifier) -> Unit = { mod ->
            BentoCard(modifier = mod) {
                Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    BentoSectionHeader(
                        title = "悬浮窗可视化编辑器 & 校准",
                        subtitle = "支持游戏上方直接拖动、手柄实体键自动拾取、组合键与宏步骤拖拽",
                        icon = Icons.Rounded.Layers,
                        accent = Apex5Palette.Violet,
                        trailing = {
                            StatusPillBadge(
                                text = if (status.overlayPermissionGranted) "悬浮权限就绪" else "缺少悬浮权限",
                                color = if (status.overlayPermissionGranted) Apex5Palette.Emerald else Apex5Palette.Amber
                            )
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (!status.overlayPermissionGranted) {
                            Button(
                                onClick = onRequestOverlayPermission,
                                colors = ButtonDefaults.buttonColors(containerColor = Apex5Palette.Amber, contentColor = Color.Black),
                                modifier = Modifier.weight(1f).height(42.dp)
                            ) {
                                Text("前往授予悬浮窗权限", fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = { onStartOverlay(true) },
                                colors = ButtonDefaults.buttonColors(containerColor = Apex5Palette.Cyan, contentColor = Color(0xFF062033)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).height(42.dp)
                            ) {
                                Text("打开游戏内可视化编辑器", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            OutlinedButton(
                                onClick = { if (status.overlayRunning) onStopOverlay() else onStartOverlay(false) },
                                modifier = Modifier.height(42.dp)
                            ) {
                                Text(
                                    text = if (status.overlayRunning) "隐藏悬浮球" else "仅开悬浮球",
                                    color = Apex5Palette.Cyan,
                                    fontSize = 12.5.sp
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("触屏模式显示悬浮键位圈", color = Apex5Palette.TextPrimary, fontSize = 13.5.sp)
                            Text("超细霓虹环贴合游戏图标，不拦截真实手指触控", color = Apex5Palette.TextMuted, fontSize = 11.sp)
                        }
                        Switch(
                            checked = config.showTouchHints,
                            onCheckedChange = { show -> onUpdateConfig { it.copy(showTouchHints = show) } },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Apex5Palette.Cyan)
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("悬浮键位提示透明度", color = Apex5Palette.TextPrimary, fontSize = 13.sp)
                            Text(
                                text = "${config.touchHintsOpacityPercent}%",
                                color = Apex5Palette.Cyan,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Slider(
                            value = config.touchHintsOpacityPercent.toFloat(),
                            onValueChange = { v ->
                                onUpdateConfig { it.copy(touchHintsOpacityPercent = v.roundToInt().coerceIn(0, 100)) }
                            },
                            valueRange = 0f..100f,
                            colors = SliderDefaults.colors(
                                thumbColor = Apex5Palette.Cyan,
                                activeTrackColor = Apex5Palette.Cyan
                            )
                        )
                    }

                    SleekDropdownRow(
                        label = "触控屏幕方向校准",
                        currentText = config.touchOrientationMode.label,
                        options = TouchOrientationMode.entries.map { it.label to it },
                        onSelect = { mode -> onUpdateConfig { it.copy(touchOrientationMode = mode) } }
                    )
                }
            }
        }

        if (isWide) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                profileManagerCard(Modifier.weight(1.05f))
                overlayCard(Modifier.weight(1f))
            }
        } else {
            overlayCard(Modifier.fillMaxWidth())
            profileManagerCard(Modifier.fillMaxWidth())
        }

        TouchBlueprintAndPadCard(profile = activeProfile, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DiagnosticTerminalView(
    logs: List<String>,
    onProbePermissions: () -> Unit,
    onClearLogs: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var copiedTip by remember { mutableStateOf(false) }

    BentoCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BentoSectionHeader(
                title = "USB 协议握手 & uinput 运行终端日志",
                subtitle = "实时记录接口枚举、0xEF 激活状态与虚拟设备创建详情",
                icon = Icons.Rounded.Terminal,
                accent = Apex5Palette.Cyan,
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onProbePermissions,
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            Text("测试 uinput 权限", color = Apex5Palette.Cyan, fontSize = 11.5.sp)
                        }
                        if (logs.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(logs.joinToString("\n")))
                                    copiedTip = true
                                },
                                modifier = Modifier.height(34.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = Apex5Palette.Emerald, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (copiedTip) "已复制" else "复制", color = Apex5Palette.Emerald, fontSize = 11.5.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    copiedTip = false
                                    onClearLogs()
                                },
                                modifier = Modifier.height(34.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Text("清空", color = Apex5Palette.TextSecondary, fontSize = 11.5.sp)
                            }
                        }
                    }
                }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Apex5Palette.InnerBg)
                    .border(1.dp, Apex5Palette.BorderSubtle, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                if (logs.isEmpty()) {
                    Text(
                        text = "$ waiting for USB attach / driver start...",
                        color = Apex5Palette.TextMuted,
                        fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        logs.asReversed().forEach { line ->
                            val lineColor = when {
                                "错误" in line || "失败" in line || "异常" in line || "拔出" in line -> Color(0xFFFCA5A5)
                                "已激活" in line || "已获得" in line || "已就绪" in line || "成功" in line || "已导入" in line -> Apex5Palette.Emerald
                                "0xEF" in line || "手柄信息" in line || "V2" in line -> Apex5Palette.Cyan
                                "请求" in line || "待" in line -> Apex5Palette.Amber
                                else -> Color(0xFFCBD5E1)
                            }
                            Text(
                                text = line,
                                color = lineColor,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StickDialVisualizer(
    label: String,
    x: Int,
    y: Int,
    pressed: Boolean,
    deadzonePercent: Int
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(modifier = Modifier.size(124.dp)) {
            val r = size.minDimension / 2f - 5f
            val c = Offset(size.width / 2f, size.height / 2f)

            drawCircle(color = Apex5Palette.InnerBg, radius = r, center = c)
            drawCircle(
                color = if (pressed) Apex5Palette.Cyan else Apex5Palette.BorderStrong,
                radius = r,
                center = c,
                style = Stroke(width = if (pressed) 4f else 2.5f)
            )
            // Deadzone ring
            val dzR = (deadzonePercent / 100f).coerceIn(0f, 0.4f) * (r - 8f)
            if (dzR > 3f) {
                drawCircle(
                    color = Apex5Palette.Amber.copy(alpha = 0.5f),
                    radius = dzR,
                    center = c,
                    style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
                )
            }
            drawLine(Apex5Palette.BorderSubtle, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5f)
            drawLine(Apex5Palette.BorderSubtle, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5f)

            val nx = (x / 32768f).coerceIn(-1f, 1f)
            val ny = (y / 32768f).coerceIn(-1f, 1f)
            val dot = Offset(c.x + nx * (r - 9f), c.y + ny * (r - 9f))

            drawLine(Apex5Palette.Cyan.copy(alpha = 0.4f), c, dot, 2f)
            drawCircle(color = Apex5Palette.Cyan.copy(alpha = 0.25f), radius = 16f, center = dot)
            drawCircle(color = if (pressed) Apex5Palette.Emerald else Apex5Palette.Cyan, radius = 8.5f, center = dot)
        }
        Text(label, color = Apex5Palette.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text("X=%6d  Y=%6d".format(x, y), color = Apex5Palette.TextSecondary, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MetricTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Apex5Palette.InnerBg)
            .border(1.dp, Apex5Palette.BorderSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, color = Apex5Palette.TextMuted, fontSize = 10.5.sp)
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun GradientTriggerBar(label: String, value: Int, accent: Color) {
    val frac = (value / 255f).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Apex5Palette.TextPrimary, fontSize = 12.sp)
            Text("$value / 255", color = accent, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Apex5Palette.InnerBg)
                .border(1.dp, Apex5Palette.BorderSubtle, RoundedCornerShape(4.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(frac)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.65f), accent)))
            )
        }
    }
}

@Composable
private fun GyroAxisBar(axis: String, dps: Float, accent: Color) {
    val norm = (abs(dps) / 180f).coerceIn(0f, 1f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(axis, color = Apex5Palette.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(40.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Apex5Palette.CardBg)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(norm)
                    .fillMaxHeight()
                    .background(accent)
            )
        }
        Text("%+6.1f".format(dps), color = accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(52.dp))
    }
}

@Composable
private fun KeycapBadge(label: String, active: Boolean, accent: Color = Apex5Palette.Cyan) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) accent.copy(alpha = 0.25f) else Apex5Palette.InnerBg)
            .border(1.dp, if (active) accent else Apex5Palette.BorderSubtle, RoundedCornerShape(9.dp))
            .padding(horizontal = 13.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = if (active) Color.White else Apex5Palette.TextSecondary,
            fontSize = 12.5.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
        )
    }
}
