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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex5.driver.BuildConfig
import com.apex5.driver.model.ControllerInputSource
import com.apex5.driver.model.DriverConfig
import com.apex5.driver.model.DriverStatus
import com.apex5.driver.model.ExtraButtonId
import com.apex5.driver.model.GamepadMacroStep
import com.apex5.driver.model.GyroTriggerMode
import com.apex5.driver.model.InputPreferenceMode
import com.apex5.driver.model.MappingAction
import com.apex5.driver.model.ShizukuState
import com.apex5.driver.model.SpaceStationPreset
import com.apex5.driver.model.StandardButtonId
import com.apex5.driver.model.StickCircularityMode
import com.apex5.driver.model.StickResponseCurve
import com.apex5.driver.model.StickTuningConfig
import com.apex5.driver.model.TriggerHapticMode
import com.apex5.driver.model.TriggerTuningConfig
import com.apex5.driver.update.UpdateStage
import com.apex5.driver.update.UpdateUiState
import com.apex5.driver.usb.StickMath
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
internal fun StickAndTriggerSpaceView(
    status: DriverStatus,
    config: DriverConfig,
    isWide: Boolean,
    onUpdateConfig: ((DriverConfig) -> DriverConfig) -> Unit,
    onTestTriggerRumble: () -> Unit,
    onSyncOnboardTriggers: () -> Unit
) {
    val live = status.liveState
    var editingLeftStick by remember { mutableStateOf(true) }
    val activeStick = if (editingLeftStick) config.leftStick else config.rightStick
    val activeLiveMag = if (editingLeftStick) {
        hypot(live.lx / 32767f, live.ly / 32767f).coerceIn(0f, 1f)
    } else {
        hypot(live.rx / 32767f, live.ry / 32767f).coerceIn(0f, 1f)
    }

    val updateActiveStick: ((StickTuningConfig) -> StickTuningConfig) -> Unit = { transform ->
        onUpdateConfig { cur ->
            if (editingLeftStick) {
                val updated = transform(cur.leftStick)
                cur.copy(leftStick = updated, stickDeadzonePercent = updated.centerDeadzonePercent)
            } else {
                cur.copy(rightStick = transform(cur.rightStick))
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Row 1: Stick Curve Visualizer + Per-Stick Tuning
        val stickCurveCard: @Composable (Modifier) -> Unit = { mod ->
            BentoCard(modifier = mod) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BentoSectionHeader(
                        title = "摇杆响应曲线 & 实时轨迹",
                        subtitle = "飞智空间站 4.0 曲线引擎 · 同步作用于手柄与触屏映射",
                        icon = Icons.Rounded.Tune,
                        accent = Apex5Palette.Cyan,
                        trailing = {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(Apex5Palette.InnerBg)
                                    .border(1.dp, Apex5Palette.BorderSubtle, RoundedCornerShape(9.dp))
                                    .padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(true to "左摇杆 LS", false to "右摇杆 RS").forEach { (isLeft, label) ->
                                    val sel = editingLeftStick == isLeft
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(if (sel) Apex5Palette.Cyan.copy(alpha = 0.22f) else Color.Transparent)
                                            .clickable { editingLeftStick = isLeft }
                                            .padding(horizontal = 10.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (sel) Apex5Palette.Cyan else Apex5Palette.TextSecondary,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    )

                    StickCurveCanvas(
                        stick = activeStick,
                        liveInputMag = if (status.serviceRunning) activeLiveMag else null,
                        accent = if (editingLeftStick) Apex5Palette.Cyan else Apex5Palette.Violet,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onUpdateConfig { cur ->
                                    val src = if (editingLeftStick) cur.leftStick else cur.rightStick
                                    cur.copy(leftStick = src, rightStick = src, stickDeadzonePercent = src.centerDeadzonePercent)
                                }
                            },
                            modifier = Modifier.weight(1f).height(36.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("应用到双侧摇杆", color = Apex5Palette.Cyan, fontSize = 11.5.sp)
                        }
                        OutlinedButton(
                            onClick = { updateActiveStick { StickTuningConfig() } },
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text("恢复默认", color = Apex5Palette.TextSecondary, fontSize = 11.5.sp)
                        }
                    }
                }
            }
        }

        val stickControlsCard: @Composable (Modifier) -> Unit = { mod ->
            val accent = if (editingLeftStick) Apex5Palette.Cyan else Apex5Palette.Violet
            BentoCard(modifier = mod) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BentoSectionHeader(
                        title = if (editingLeftStick) "左摇杆 (LS) 空间站精调" else "右摇杆 (RS) 空间站精调",
                        subtitle = "死区 · 中心/边界补偿 · 圆形/矩形外圈 · 防抖平滑",
                        icon = Icons.Rounded.Gamepad,
                        accent = accent
                    )

                    SleekDropdownRow(
                        label = "响应曲线预设",
                        currentText = activeStick.curvePreset.label,
                        options = StickResponseCurve.entries.map { it.label to it },
                        onSelect = { curve -> updateActiveStick { it.copy(curvePreset = curve) } }
                    )

                    SleekDropdownRow(
                        label = "外圈边界校准模式",
                        currentText = activeStick.circularityMode.label,
                        options = StickCircularityMode.entries.map { it.label to it },
                        onSelect = { mode -> updateActiveStick { it.copy(circularityMode = mode) } }
                    )

                    CompactParamSlider(
                        label = "中心死区 (Inner Deadzone)",
                        valueText = "${activeStick.centerDeadzonePercent}%",
                        value = activeStick.centerDeadzonePercent.toFloat(),
                        range = 0f..25f,
                        steps = 24,
                        accent = accent,
                        onValueChange = { v -> updateActiveStick { it.copy(centerDeadzonePercent = v.roundToInt()) } }
                    )

                    CompactParamSlider(
                        label = "中心起步补偿",
                        valueText = "${activeStick.centerCompensationPercent}%",
                        value = activeStick.centerCompensationPercent.toFloat(),
                        range = 0f..15f,
                        steps = 14,
                        accent = accent,
                        onValueChange = { v -> updateActiveStick { it.copy(centerCompensationPercent = v.roundToInt()) } }
                    )

                    CompactParamSlider(
                        label = "外圈死区缩窄 (更快满推)",
                        valueText = "${activeStick.outerDeadzonePercent}%",
                        value = activeStick.outerDeadzonePercent.toFloat(),
                        range = 0f..25f,
                        steps = 24,
                        accent = accent,
                        onValueChange = { v -> updateActiveStick { it.copy(outerDeadzonePercent = v.roundToInt()) } }
                    )

                    CompactParamSlider(
                        label = "边缘上限限幅",
                        valueText = "${100 - activeStick.edgeCompensationPercent}%",
                        value = activeStick.edgeCompensationPercent.toFloat(),
                        range = 0f..20f,
                        steps = 19,
                        accent = accent,
                        onValueChange = { v -> updateActiveStick { it.copy(edgeCompensationPercent = v.roundToInt()) } }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("零死区微抖过滤 (Anti-Jitter)", color = Apex5Palette.TextPrimary, fontSize = 13.sp)
                            Text("启用轻微指数平滑，降低高敏右摇杆静止抖动", color = Apex5Palette.TextMuted, fontSize = 11.sp)
                        }
                        Switch(
                            checked = activeStick.antiJitterEnabled,
                            onCheckedChange = { en -> updateActiveStick { it.copy(antiJitterEnabled = en) } },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent)
                        )
                    }
                }
            }
        }

        if (isWide) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                stickCurveCard(Modifier.weight(0.95f))
                stickControlsCard(Modifier.weight(1.05f))
            }
        } else {
            stickCurveCard(Modifier.fillMaxWidth())
            stickControlsCard(Modifier.fillMaxWidth())
        }

        // Row 2: Apex 5 Force Trigger Hardware Status & PC Onboard Sync Banner
        BentoCard(highlighted = status.forceTriggerSupported, accent = Apex5Palette.Violet) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BentoSectionHeader(
                    title = "Apex 5 自适应力反馈扳机引擎 (0x51 / 0x52)",
                    subtitle = "八爪鱼 5 固件在断电/重连后会自动复位伺服阻力，需由安卓驱动主动读取并下发指令唤醒",
                    icon = Icons.Rounded.Bolt,
                    accent = Apex5Palette.Violet,
                    trailing = {
                        StatusPillBadge(
                            text = when {
                                !status.serviceRunning -> "驱动未启动"
                                status.forceTriggerSupported -> "伺服引擎就绪"
                                else -> "兼容 V2 马达"
                            },
                            color = when {
                                !status.serviceRunning -> Apex5Palette.TextMuted
                                status.forceTriggerSupported -> Apex5Palette.Emerald
                                else -> Apex5Palette.Amber
                            }
                        )
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PC 空间站板载记忆：${status.onboardTriggerSummary}",
                        color = Apex5Palette.TextSecondary,
                        fontSize = 11.5.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = onSyncOnboardTriggers,
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, tint = Apex5Palette.Cyan, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("一键同步 PC 板载", color = Apex5Palette.Cyan, fontSize = 11.5.sp)
                    }
                }
            }
        }

        // Row 3: Left & Right Trigger Haptic & Travel Cards
        val leftTrigCard: @Composable (Modifier) -> Unit = { mod ->
            TriggerSpaceCard(
                title = "LT 左扳机行程与力反馈伺服",
                triggerCfg = config.leftTrigger,
                liveRaw = live.lt,
                accent = Apex5Palette.Cyan,
                serviceRunning = status.serviceRunning,
                onUpdate = { upd -> onUpdateConfig { it.copy(leftTrigger = upd(it.leftTrigger)) } },
                onTestTriggerRumble = onTestTriggerRumble,
                modifier = mod
            )
        }
        val rightTrigCard: @Composable (Modifier) -> Unit = { mod ->
            TriggerSpaceCard(
                title = "RT 右扳机行程与力反馈伺服",
                triggerCfg = config.rightTrigger,
                liveRaw = live.rt,
                accent = Apex5Palette.Violet,
                serviceRunning = status.serviceRunning,
                onUpdate = { upd -> onUpdateConfig { it.copy(rightTrigger = upd(it.rightTrigger)) } },
                onTestTriggerRumble = onTestTriggerRumble,
                modifier = mod
            )
        }

        if (isWide) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                leftTrigCard(Modifier.weight(1f))
                rightTrigCard(Modifier.weight(1f))
            }
        } else {
            leftTrigCard(Modifier.fillMaxWidth())
            rightTrigCard(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StickCurveCanvas(
    stick: StickTuningConfig,
    liveInputMag: Float?,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Apex5Palette.InnerBg)
            .border(1.dp, Apex5Palette.BorderSubtle, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        val w = size.width
        val h = size.height

        // Subtle 4x4 grid
        for (i in 1..3) {
            val gx = w * (i / 4f)
            val gy = h * (i / 4f)
            drawLine(Apex5Palette.BorderSubtle.copy(alpha = 0.6f), Offset(gx, 0f), Offset(gx, h), 1f)
            drawLine(Apex5Palette.BorderSubtle.copy(alpha = 0.6f), Offset(0f, gy), Offset(w, gy), 1f)
        }

        val innerDz = (stick.centerDeadzonePercent / 100f).coerceIn(0f, 0.35f)
        val outerMax = (1f - stick.outerDeadzonePercent / 100f).coerceAtLeast(innerDz + 0.1f)
        val comp = (stick.centerCompensationPercent / 100f).coerceIn(0f, 0.25f)
        val edgeCap = (1f - stick.edgeCompensationPercent / 100f).coerceIn(0.25f, 1f)

        // Shaded inner deadzone
        if (innerDz > 0f) {
            drawRect(
                color = Apex5Palette.Amber.copy(alpha = 0.14f),
                topLeft = Offset.Zero,
                size = Size(w * innerDz, h)
            )
        }
        // Shaded outer deadzone
        if (outerMax < 1f) {
            drawRect(
                color = Apex5Palette.Violet.copy(alpha = 0.12f),
                topLeft = Offset(w * outerMax, 0f),
                size = Size(w * (1f - outerMax), h)
            )
        }

        // Diagonal 1:1 linear reference
        drawLine(
            color = Apex5Palette.TextMuted.copy(alpha = 0.5f),
            start = Offset(0f, h),
            end = Offset(w, 0f),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        )

        fun evalY(xNorm: Float): Float {
            if (xNorm <= innerDz) return 0f
            val t = ((xNorm - innerDz) / (outerMax - innerDz)).coerceIn(0f, 1f)
            val curved = StickMath.evaluateCurve(t, stick.curvePreset)
            return (comp + curved * (edgeCap - comp)).coerceIn(0f, 1f)
        }

        val path = Path()
        val steps = 80
        for (i in 0..steps) {
            val x = i / steps.toFloat()
            val y = evalY(x)
            val px = x * w
            val py = h - y * h
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path = path,
            color = accent,
            style = Stroke(width = 4.5f, cap = StrokeCap.Round)
        )

        // Live hardware input indicator on curve
        if (liveInputMag != null) {
            val lx = liveInputMag.coerceIn(0f, 1f)
            val ly = evalY(lx)
            val pt = Offset(lx * w, h - ly * h)
            drawLine(accent.copy(alpha = 0.4f), Offset(pt.x, h), pt, 1.5f)
            drawCircle(color = accent.copy(alpha = 0.28f), radius = 14f, center = pt)
            drawCircle(color = Apex5Palette.Emerald, radius = 6.5f, center = pt)
        }
    }
}

@Composable
private fun TriggerSpaceCard(
    title: String,
    triggerCfg: TriggerTuningConfig,
    liveRaw: Int,
    accent: Color,
    serviceRunning: Boolean,
    onUpdate: ((TriggerTuningConfig) -> TriggerTuningConfig) -> Unit,
    onTestTriggerRumble: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shaped = StickMath.shapeTrigger(liveRaw, triggerCfg)
    BentoCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BentoSectionHeader(
                title = title,
                subtitle = "支持 0x51/0x52 真实力反馈伺服阻力、物理扳机锁与独立死区行程",
                icon = Icons.Rounded.Bolt,
                accent = accent,
                trailing = {
                    OutlinedButton(
                        onClick = onTestTriggerRumble,
                        enabled = serviceRunning,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 9.dp)
                    ) {
                        Icon(Icons.Rounded.Vibration, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("测试脉冲", color = accent, fontSize = 11.sp)
                    }
                }
            )

            // Live raw vs shaped pressure bar
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("原始输入 $liveRaw → 映射输出 $shaped / 255", color = Apex5Palette.TextSecondary, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                    Text("${(shaped / 255f * 100f).roundToInt()}%", color = accent, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
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
                            .fillMaxWidth((shaped / 255f).coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.6f), accent)))
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("快速触发模式 (软件快扳 / Hair-Trigger)", color = Apex5Palette.TextPrimary, fontSize = 13.sp)
                    Text("超过起点死区即瞬间输出 100% 满压，适合 FPS 点射/开镜", color = Apex5Palette.TextMuted, fontSize = 11.sp)
                }
                Switch(
                    checked = triggerCfg.hairTriggerMode,
                    onCheckedChange = { hair -> onUpdate { it.copy(hairTriggerMode = hair) } },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent)
                )
            }

            CompactParamSlider(
                label = "起始盲区 (防误触)",
                valueText = "${triggerCfg.deadzoneStartPercent}%",
                value = triggerCfg.deadzoneStartPercent.toFloat(),
                range = 0f..25f,
                steps = 24,
                accent = accent,
                onValueChange = { v -> onUpdate { it.copy(deadzoneStartPercent = v.roundToInt()) } }
            )

            CompactParamSlider(
                label = "末端死区 (提前满行程)",
                valueText = "${triggerCfg.deadzoneEndPercent}%",
                value = triggerCfg.deadzoneEndPercent.toFloat(),
                range = 0f..30f,
                steps = 29,
                accent = accent,
                onValueChange = { v -> onUpdate { it.copy(deadzoneEndPercent = v.roundToInt()) } }
            )

            SleekDropdownRow(
                label = "力反馈伺服模式 (0x51/0x52)",
                currentText = triggerCfg.hapticMode.label,
                options = TriggerHapticMode.entries.map { it.label to it },
                onSelect = { mode -> onUpdate { it.copy(hapticMode = mode) } }
            )

            if (triggerCfg.hapticMode == TriggerHapticMode.FOLLOW_ONBOARD) {
                Text(
                    text = "当前处于「PC 板载预设」跟随模式：驱动将直接读取手柄内置槽位中的力反馈参数并实时下发唤醒伺服电机。",
                    color = Apex5Palette.Cyan,
                    fontSize = 11.sp
                )
            } else if (triggerCfg.hapticMode != TriggerHapticMode.OFF) {
                CompactParamSlider(
                    label = if (triggerCfg.hapticMode == TriggerHapticMode.TRIGGER_LOCK) "物理限位挡点行程" else "力反馈介入起点行程",
                    valueText = "${triggerCfg.startTravelPercent}%",
                    value = triggerCfg.startTravelPercent.toFloat(),
                    range = 0f..90f,
                    steps = 17,
                    accent = accent,
                    onValueChange = { v -> onUpdate { it.copy(startTravelPercent = v.roundToInt()) } }
                )

                if (triggerCfg.hapticMode != TriggerHapticMode.TRIGGER_LOCK) {
                    CompactParamSlider(
                        label = "伺服阻力 / 震动强度",
                        valueText = "${triggerCfg.hapticStrengthPercent}%",
                        value = triggerCfg.hapticStrengthPercent.toFloat(),
                        range = 10f..100f,
                        steps = 17,
                        accent = accent,
                        onValueChange = { v -> onUpdate { it.copy(hapticStrengthPercent = v.roundToInt()) } }
                    )
                }

                if (triggerCfg.hapticMode == TriggerHapticMode.MACHINE_GUN ||
                    triggerCfg.hapticMode == TriggerHapticMode.SYNC_MAIN
                ) {
                    CompactParamSlider(
                        label = "高频连振 / 脉冲频率",
                        valueText = "${triggerCfg.frequencyPercent}%",
                        value = triggerCfg.frequencyPercent.toFloat(),
                        range = 10f..100f,
                        steps = 17,
                        accent = accent,
                        onValueChange = { v -> onUpdate { it.copy(frequencyPercent = v.roundToInt()) } }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ButtonsMacrosSpaceView(
    status: DriverStatus,
    config: DriverConfig,
    isWide: Boolean,
    onUpdateConfig: ((DriverConfig) -> DriverConfig) -> Unit,
    onApplySpacePreset: (SpaceStationPreset) -> Unit,
    onExportSpaceJson: () -> String,
    onImportSpaceJson: (String) -> Result<DriverConfig>,
    onTestRumble: () -> Unit,
    onTestTriggerRumble: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var shareFeedback by remember { mutableStateOf<String?>(null) }
    var editingMacroBtn by remember { mutableStateOf(ExtraButtonId.M1) }
    var remapShowFaceKeys by remember { mutableStateOf(false) }

    val turboCandidates = remember {
        listOf(
            ControllerInputSource.A, ControllerInputSource.B,
            ControllerInputSource.X, ControllerInputSource.Y,
            ControllerInputSource.LB, ControllerInputSource.RB,
            ControllerInputSource.LT, ControllerInputSource.RT,
            ControllerInputSource.M1, ControllerInputSource.M2,
            ControllerInputSource.M3, ControllerInputSource.M4,
            ControllerInputSource.LM, ControllerInputSource.RM
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Card 1: Space Station Presets + Config Import/Export + USB Preferences
        BentoCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BentoSectionHeader(
                    title = "飞智空间站 4.0 场景预设 & 配置分享码",
                    subtitle = "一键载入 FPS / 赛车 / 动作预设，或与 PC / 好友共享全套空间站参数 JSON",
                    icon = Icons.Rounded.Tune,
                    accent = Apex5Palette.Cyan,
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(onExportSpaceJson()))
                                    shareFeedback = "已复制完整空间站配置 JSON 到剪贴板"
                                },
                                modifier = Modifier.height(34.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = Apex5Palette.Emerald, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("导出配置", color = Apex5Palette.Emerald, fontSize = 11.5.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    val txt = clipboard.getText()?.text.orEmpty()
                                    if (txt.isBlank()) {
                                        shareFeedback = "剪贴板为空，请先复制配置 JSON"
                                    } else {
                                        val res = onImportSpaceJson(txt)
                                        shareFeedback = res.fold(
                                            onSuccess = { "已导入空间站全套参数" },
                                            onFailure = { "导入失败：JSON 格式无效" }
                                        )
                                    }
                                },
                                modifier = Modifier.height(34.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = null, tint = Apex5Palette.Violet, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("剪贴板导入", color = Apex5Palette.Violet, fontSize = 11.5.sp)
                            }
                        }
                    }
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SpaceStationPreset.entries.forEach { preset ->
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Apex5Palette.InnerBg)
                                .border(1.dp, Apex5Palette.BorderStrong, RoundedCornerShape(10.dp))
                                .clickable {
                                    onApplySpacePreset(preset)
                                    shareFeedback = "已应用预设：${preset.label}"
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Text(preset.label, color = Apex5Palette.Cyan, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            Text(preset.desc, color = Apex5Palette.TextSecondary, fontSize = 10.5.sp)
                        }
                    }
                }

                shareFeedback?.let { msg ->
                    Text(msg, color = Apex5Palette.Cyan, fontSize = 11.5.sp)
                }

                HorizontalDivider(color = Apex5Palette.BorderSubtle)

                SleekDropdownRow(
                    label = "USB 协议输入通道",
                    currentText = config.inputMode.label,
                    options = InputPreferenceMode.entries.map { it.label to it },
                    onSelect = { mode -> onUpdateConfig { it.copy(inputMode = mode) } }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("独立附加手柄设备 (BUTTON_1~6)", color = Apex5Palette.TextPrimary, fontSize = 13.sp)
                        Text(
                            if (status.serviceRunning) "修改后需重启驱动 · 供模拟器独立绑定映射为「附加手柄键」的按键"
                            else "开启后可在模拟器里把 M1–M4 当作独立原生键值绑定",
                            color = Apex5Palette.TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = config.enableExtraButtonDevice,
                        onCheckedChange = { checked -> onUpdateConfig { it.copy(enableExtraButtonDevice = checked) } },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Apex5Palette.Cyan)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("屏蔽 Home (Guide) 键防止误触切桌面", color = Apex5Palette.TextPrimary, fontSize = 13.sp)
                        Text("屏蔽系统级 Home 键跳转，仅保留遥测状态显示", color = Apex5Palette.TextMuted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = config.blockHomeButton,
                        onCheckedChange = { checked -> onUpdateConfig { it.copy(blockHomeButton = checked) } },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Apex5Palette.Cyan)
                    )
                }
            }
        }

        // Row 2: Key Remapping + Turbo & Gamepad Macro
        val remapCard: @Composable (Modifier) -> Unit = { mod ->
            BentoCard(modifier = mod) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BentoSectionHeader(
                        title = "全键位自定义重映射",
                        subtitle = "支持 M1–M4/LM/RM 扩展键，以及 A/B/X/Y/LB/RB/L3/R3 互换改键",
                        icon = Icons.Rounded.Gamepad,
                        accent = Apex5Palette.Violet,
                        trailing = {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(Apex5Palette.InnerBg)
                                    .border(1.dp, Apex5Palette.BorderSubtle, RoundedCornerShape(9.dp))
                                    .padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(false to "背键/肩键", true to "标准面板键").forEach { (face, lbl) ->
                                    val sel = remapShowFaceKeys == face
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(if (sel) Apex5Palette.Violet.copy(alpha = 0.24f) else Color.Transparent)
                                            .clickable { remapShowFaceKeys = face }
                                            .padding(horizontal = 9.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            lbl,
                                            color = if (sel) Apex5Palette.Violet else Apex5Palette.TextSecondary,
                                            fontSize = 11.sp,
                                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    )

                    if (!remapShowFaceKeys) {
                        ExtraButtonId.entries.forEach { btnId ->
                            val curAct = config.buttonMappings[btnId] ?: MappingAction.NONE
                            SleekDropdownRow(
                                label = btnId.label,
                                currentText = curAct.label,
                                options = MappingAction.entries.map { it.label to it },
                                onSelect = { act ->
                                    onUpdateConfig { cur ->
                                        cur.copy(buttonMappings = cur.buttonMappings + (btnId to act))
                                    }
                                }
                            )
                        }
                    } else {
                        StandardButtonId.entries.forEach { btnId ->
                            val curAct = config.faceButtonMappings[btnId] ?: btnId.defaultAction
                            SleekDropdownRow(
                                label = btnId.label,
                                currentText = curAct.label,
                                options = MappingAction.entries.filter { it != MappingAction.RUN_MACRO }.map { it.label to it },
                                onSelect = { act ->
                                    onUpdateConfig { cur ->
                                        cur.copy(faceButtonMappings = cur.faceButtonMappings + (btnId to act))
                                    }
                                }
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                onUpdateConfig { cur ->
                                    cur.copy(faceButtonMappings = StandardButtonId.entries.associateWith { it.defaultAction })
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(34.dp)
                        ) {
                            Text("恢复标准键位默认布局", color = Apex5Palette.TextSecondary, fontSize = 11.5.sp)
                        }
                    }
                }
            }
        }

        val turboAndMacroCol: @Composable (Modifier) -> Unit = { mod ->
            Column(modifier = mod, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Turbo Rapid-Fire Card
                BentoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        BentoSectionHeader(
                            title = "Turbo 按键连发引擎",
                            subtitle = "点选下方按键开启按住连发，原生手柄模式下全游戏通用",
                            icon = Icons.Rounded.Bolt,
                            accent = Apex5Palette.Amber,
                            trailing = {
                                StatusPillBadge(text = "${config.turboRateHz} 次/秒", color = Apex5Palette.Amber, pulseDot = false)
                            }
                        )

                        CompactParamSlider(
                            label = "全局连发频率",
                            valueText = "${config.turboRateHz} Hz",
                            value = config.turboRateHz.toFloat(),
                            range = 3f..25f,
                            steps = 21,
                            accent = Apex5Palette.Amber,
                            onValueChange = { v -> onUpdateConfig { it.copy(turboRateHz = v.roundToInt()) } }
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            turboCandidates.forEach { src ->
                                val enabled = src in config.turboEnabledKeys
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (enabled) Apex5Palette.Amber.copy(alpha = 0.22f) else Apex5Palette.InnerBg)
                                        .border(1.dp, if (enabled) Apex5Palette.Amber else Apex5Palette.BorderSubtle, RoundedCornerShape(8.dp))
                                        .clickable {
                                            onUpdateConfig { cur ->
                                                val next = if (enabled) cur.turboEnabledKeys - src else cur.turboEnabledKeys + src
                                                cur.copy(turboEnabledKeys = next)
                                            }
                                        }
                                        .padding(horizontal = 11.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        text = if (enabled) "${src.shortLabel} · 连发" else src.shortLabel,
                                        color = if (enabled) Apex5Palette.Amber else Apex5Palette.TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = if (enabled) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                // Gamepad Macro Editor Card
                val macroSteps = config.gamepadMacros[editingMacroBtn].orEmpty()
                val mappedAsMacro = config.buttonMappings[editingMacroBtn] == MappingAction.RUN_MACRO

                BentoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        BentoSectionHeader(
                            title = "手柄背键连招宏编辑器",
                            subtitle = "编辑 M1–M4 / LM / RM 的按键序列（按下一键自动连续打完组合键）",
                            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                            accent = Apex5Palette.Emerald,
                            trailing = {
                                if (!mappedAsMacro) {
                                    OutlinedButton(
                                        onClick = {
                                            onUpdateConfig { cur ->
                                                cur.copy(buttonMappings = cur.buttonMappings + (editingMacroBtn to MappingAction.RUN_MACRO))
                                            }
                                        },
                                        modifier = Modifier.height(32.dp),
                                        contentPadding = PaddingValues(horizontal = 9.dp)
                                    ) {
                                        Text("设为执行宏", color = Apex5Palette.Emerald, fontSize = 11.sp)
                                    }
                                } else {
                                    StatusPillBadge(text = "已绑定执行宏", color = Apex5Palette.Emerald, pulseDot = false)
                                }
                            }
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ExtraButtonId.entries.forEach { btn ->
                                val sel = editingMacroBtn == btn
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (sel) Apex5Palette.Emerald.copy(alpha = 0.22f) else Apex5Palette.InnerBg)
                                        .border(1.dp, if (sel) Apex5Palette.Emerald else Apex5Palette.BorderSubtle, RoundedCornerShape(8.dp))
                                        .clickable { editingMacroBtn = btn }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        btn.name,
                                        color = if (sel) Apex5Palette.Emerald else Apex5Palette.TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }

                        if (macroSteps.isEmpty()) {
                            Text(
                                "当前 ${editingMacroBtn.name} 暂无宏步骤，点击下方「+ 添加动作步骤」开始录制。",
                                color = Apex5Palette.TextMuted,
                                fontSize = 12.sp
                            )
                        } else {
                            val stepActionOptions = MappingAction.entries.filter {
                                it != MappingAction.NONE && it != MappingAction.RUN_MACRO && it != MappingAction.EXTRA_BUTTON
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                macroSteps.forEachIndexed { idx, step ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Apex5Palette.InnerBg)
                                            .border(1.dp, Apex5Palette.BorderSubtle, RoundedCornerShape(10.dp))
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        SleekDropdownRow(
                                            label = "步骤 ${idx + 1}",
                                            currentText = step.action.label,
                                            options = stepActionOptions.map { it.label to it },
                                            onSelect = { act ->
                                                onUpdateConfig { cur ->
                                                    val list = cur.gamepadMacros[editingMacroBtn].orEmpty().toMutableList()
                                                    if (idx in list.indices) list[idx] = list[idx].copy(action = act)
                                                    cur.copy(gamepadMacros = cur.gamepadMacros + (editingMacroBtn to list))
                                                }
                                            }
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("按住 ${step.holdMs}ms · 间隔 ${step.delayAfterMs}ms", color = Apex5Palette.TextSecondary, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                MiniStepChip("-15ms 间隔") {
                                                    onUpdateConfig { cur ->
                                                        val list = cur.gamepadMacros[editingMacroBtn].orEmpty().toMutableList()
                                                        if (idx in list.indices) {
                                                            list[idx] = list[idx].copy(delayAfterMs = (list[idx].delayAfterMs - 15).coerceIn(15, 1000))
                                                        }
                                                        cur.copy(gamepadMacros = cur.gamepadMacros + (editingMacroBtn to list))
                                                    }
                                                }
                                                MiniStepChip("+15ms 间隔") {
                                                    onUpdateConfig { cur ->
                                                        val list = cur.gamepadMacros[editingMacroBtn].orEmpty().toMutableList()
                                                        if (idx in list.indices) {
                                                            list[idx] = list[idx].copy(delayAfterMs = (list[idx].delayAfterMs + 15).coerceIn(15, 1000))
                                                        }
                                                        cur.copy(gamepadMacros = cur.gamepadMacros + (editingMacroBtn to list))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    onUpdateConfig { cur ->
                                        val list = cur.gamepadMacros[editingMacroBtn].orEmpty() + GamepadMacroStep(MappingAction.A, 45, 55)
                                        cur.copy(gamepadMacros = cur.gamepadMacros + (editingMacroBtn to list))
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Apex5Palette.Emerald, contentColor = Color(0xFF052E16)),
                                modifier = Modifier.height(36.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("添加步骤", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            if (macroSteps.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = {
                                        onUpdateConfig { cur ->
                                            val list = cur.gamepadMacros[editingMacroBtn].orEmpty().dropLast(1)
                                            cur.copy(gamepadMacros = cur.gamepadMacros + (editingMacroBtn to list))
                                        }
                                    },
                                    modifier = Modifier.height(36.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp)
                                ) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Apex5Palette.Rose, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("删最后步", color = Apex5Palette.Rose, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isWide) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                remapCard(Modifier.weight(1f))
                turboAndMacroCol(Modifier.weight(1f))
            }
        } else {
            remapCard(Modifier.fillMaxWidth())
            turboAndMacroCol(Modifier.fillMaxWidth())
        }

        // Row 3: Rumble Scaling + Gyro Aim & Smoothing
        val rumbleCard: @Composable (Modifier) -> Unit = { mod ->
            BentoCard(modifier = mod) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BentoSectionHeader(
                        title = "马达震动全局强度分配",
                        subtitle = "独立控制握把非对称双马达与扳机霍尔震动缩放系数",
                        icon = Icons.Rounded.Vibration,
                        accent = Apex5Palette.Cyan
                    )

                    CompactParamSlider(
                        label = "握把主马达总强度 (Low/High)",
                        valueText = "${config.mainRumbleIntensityPercent}%",
                        value = config.mainRumbleIntensityPercent.toFloat(),
                        range = 0f..100f,
                        steps = 19,
                        accent = Apex5Palette.Cyan,
                        onValueChange = { v -> onUpdateConfig { it.copy(mainRumbleIntensityPercent = v.roundToInt()) } }
                    )

                    CompactParamSlider(
                        label = "扳机马达总强度 (LT/RT)",
                        valueText = "${config.triggerRumbleIntensityPercent}%",
                        value = config.triggerRumbleIntensityPercent.toFloat(),
                        range = 0f..100f,
                        steps = 19,
                        accent = Apex5Palette.Violet,
                        onValueChange = { v -> onUpdateConfig { it.copy(triggerRumbleIntensityPercent = v.roundToInt()) } }
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onTestRumble,
                            enabled = status.serviceRunning,
                            modifier = Modifier.weight(1f).height(36.dp)
                        ) {
                            Text("测试握把震动", color = Apex5Palette.Cyan, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = onTestTriggerRumble,
                            enabled = status.serviceRunning,
                            modifier = Modifier.weight(1f).height(36.dp)
                        ) {
                            Text("测试扳机震动", color = Apex5Palette.Violet, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        val gyroCard: @Composable (Modifier) -> Unit = { mod ->
            BentoCard(modifier = mod) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BentoSectionHeader(
                        title = "陀螺仪体感瞄准 & 滤波平滑",
                        subtitle = "同步作用于 Xbox 右摇杆与触屏 FPS 视角区",
                        icon = Icons.Rounded.Explore,
                        accent = Apex5Palette.Violet,
                        trailing = {
                            StatusPillBadge(
                                text = if (status.liveState.gyroAimActive) "瞄准激活" else "待命",
                                color = if (status.liveState.gyroAimActive) Apex5Palette.Emerald else Apex5Palette.TextMuted
                            )
                        }
                    )

                    SleekDropdownRow(
                        label = "触发方式",
                        currentText = config.gyroTriggerMode.label,
                        options = GyroTriggerMode.entries.map { it.label to it },
                        onSelect = { mode -> onUpdateConfig { it.copy(gyroTriggerMode = mode) } }
                    )

                    CompactParamSlider(
                        label = "水平灵敏度 (Yaw → X)",
                        valueText = "%.2fx".format(config.gyroSensitivityX),
                        value = config.gyroSensitivityX,
                        range = 0.2f..5.0f,
                        steps = 0,
                        accent = Apex5Palette.Violet,
                        onValueChange = { v -> onUpdateConfig { it.copy(gyroSensitivityX = v) } }
                    )

                    CompactParamSlider(
                        label = "垂直灵敏度 (Pitch → Y)",
                        valueText = "%.2fx".format(config.gyroSensitivityY),
                        value = config.gyroSensitivityY,
                        range = 0.2f..5.0f,
                        steps = 0,
                        accent = Apex5Palette.Violet,
                        onValueChange = { v -> onUpdateConfig { it.copy(gyroSensitivityY = v) } }
                    )

                    CompactParamSlider(
                        label = "防抖死区阈值",
                        valueText = "%.1f °/s".format(config.gyroDeadzoneDps),
                        value = config.gyroDeadzoneDps,
                        range = 0f..15f,
                        steps = 0,
                        accent = Apex5Palette.Cyan,
                        onValueChange = { v -> onUpdateConfig { it.copy(gyroDeadzoneDps = v) } }
                    )

                    CompactParamSlider(
                        label = "IMU 滤波平滑度 (低延迟 ↔ 极稳)",
                        valueText = "${(config.gyroSmoothing * 100).roundToInt()}%",
                        value = config.gyroSmoothing,
                        range = 0f..0.75f,
                        steps = 0,
                        accent = Apex5Palette.Cyan,
                        onValueChange = { v -> onUpdateConfig { it.copy(gyroSmoothing = v) } }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("反转水平轴", color = Apex5Palette.TextPrimary, fontSize = 12.5.sp)
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = config.gyroInvertX,
                                onCheckedChange = { inv -> onUpdateConfig { it.copy(gyroInvertX = inv) } },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Apex5Palette.Violet)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("反转垂直轴", color = Apex5Palette.TextPrimary, fontSize = 12.5.sp)
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = config.gyroInvertY,
                                onCheckedChange = { inv -> onUpdateConfig { it.copy(gyroInvertY = inv) } },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Apex5Palette.Violet)
                            )
                        }
                    }
                }
            }
        }

        if (isWide) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                rumbleCard(Modifier.weight(0.9f))
                gyroCard(Modifier.weight(1.1f))
            }
        } else {
            rumbleCard(Modifier.fillMaxWidth())
            gyroCard(Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OtaUpdateCard(
    updateState: UpdateUiState,
    shizukuState: ShizukuState,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdateSystem: () -> Unit,
    onInstallUpdateShizuku: () -> Unit,
    onToggleMirror: (Boolean) -> Unit
) {
    val badgeColor = when (updateState.stage) {
        UpdateStage.UPDATE_AVAILABLE, UpdateStage.READY_TO_INSTALL -> Apex5Palette.Amber
        UpdateStage.UP_TO_DATE -> Apex5Palette.Emerald
        UpdateStage.ERROR -> Apex5Palette.Rose
        else -> Apex5Palette.Cyan
    }
    val badgeLabel = when (updateState.stage) {
        UpdateStage.IDLE -> "v${BuildConfig.VERSION_NAME}"
        UpdateStage.CHECKING -> "检查中..."
        UpdateStage.UP_TO_DATE -> "已是最新"
        UpdateStage.UPDATE_AVAILABLE -> "发现 ${updateState.latestRelease?.tagName ?: "新版"}"
        UpdateStage.DOWNLOADING -> "下载中 ${(updateState.progressFraction * 100).roundToInt()}%"
        UpdateStage.READY_TO_INSTALL -> "待安装"
        UpdateStage.INSTALLING_SHIZUKU -> "Shizuku 安装中"
        UpdateStage.ERROR -> "更新异常"
    }

    BentoCard(highlighted = updateState.stage == UpdateStage.UPDATE_AVAILABLE || updateState.stage == UpdateStage.READY_TO_INSTALL) {
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            BentoSectionHeader(
                title = "应用在线检查更新 & 签名说明",
                subtitle = "直连 GitHub Releases 检查最新版本，支持 Shizuku 一键静默覆盖安装",
                icon = Icons.Rounded.SystemUpdate,
                accent = Apex5Palette.Cyan,
                trailing = { StatusPillBadge(text = badgeLabel, color = badgeColor) }
            )

            // Signing explanation banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Apex5Palette.Cyan.copy(alpha = 0.09f))
                    .border(1.dp, Apex5Palette.Cyan.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = "持久化签名已启用：自 v0.4.0 起，CI Release 与本地构建均采用同一固定密钥签名，今后在线升级可直接保留配置覆盖安装，无需再卸载旧版。（注：若手机当前装的是 v0.3.0 及更早旧签名包，升级到 v0.4.0 需最后手动卸载一次旧版）",
                    color = Apex5Palette.TextSecondary,
                    fontSize = 11.5.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(updateState.statusMessage, color = Apex5Palette.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("当前版本: v${updateState.currentVersion} · 仓库 Ealoeee/Apex5-Android-Driver", color = Apex5Palette.TextMuted, fontSize = 11.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("镜像加速", color = Apex5Palette.TextSecondary, fontSize = 11.5.sp)
                    Spacer(Modifier.width(6.dp))
                    Switch(
                        checked = updateState.useMirrorProxy,
                        onCheckedChange = onToggleMirror,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Apex5Palette.Cyan)
                    )
                }
            }

            if (updateState.stage == UpdateStage.DOWNLOADING) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { updateState.progressFraction },
                        modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),
                        color = Apex5Palette.Cyan,
                        trackColor = Apex5Palette.InnerBg
                    )
                }
            }

            updateState.latestRelease?.let { rel ->
                if (rel.releaseNotes.isNotBlank() &&
                    (updateState.stage == UpdateStage.UPDATE_AVAILABLE ||
                        updateState.stage == UpdateStage.DOWNLOADING ||
                        updateState.stage == UpdateStage.READY_TO_INSTALL)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Apex5Palette.InnerBg)
                            .border(1.dp, Apex5Palette.BorderSubtle, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "${rel.title} (${rel.publishedAt}) · ${rel.apkName}",
                                color = Apex5Palette.Cyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = rel.releaseNotes,
                                color = Apex5Palette.TextSecondary,
                                fontSize = 11.5.sp,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            updateState.errorMessage?.let { err ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(Apex5Palette.Rose.copy(alpha = 0.14f))
                        .border(1.dp, Apex5Palette.Rose.copy(alpha = 0.45f), RoundedCornerShape(9.dp))
                        .padding(10.dp)
                ) {
                    Text(err, color = Color(0xFFFCA5A5), fontSize = 12.sp)
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCheckUpdate,
                    enabled = updateState.stage != UpdateStage.CHECKING && updateState.stage != UpdateStage.DOWNLOADING && updateState.stage != UpdateStage.INSTALLING_SHIZUKU,
                    modifier = Modifier.height(38.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, tint = Apex5Palette.Cyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("检查新版本", color = Apex5Palette.Cyan, fontSize = 12.5.sp)
                }

                if (updateState.stage == UpdateStage.UPDATE_AVAILABLE ||
                    (updateState.stage == UpdateStage.ERROR && updateState.latestRelease != null && updateState.downloadedFilePath == null)
                ) {
                    Button(
                        onClick = onDownloadUpdate,
                        colors = ButtonDefaults.buttonColors(containerColor = Apex5Palette.Cyan, contentColor = Color(0xFF062033)),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("下载新版 APK", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    }
                }

                if (updateState.downloadedFilePath != null) {
                    Button(
                        onClick = onInstallUpdateSystem,
                        colors = ButtonDefaults.buttonColors(containerColor = Apex5Palette.Emerald, contentColor = Color(0xFF052E16)),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Text("系统安装器覆盖安装", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    }
                    OutlinedButton(
                        onClick = onInstallUpdateShizuku,
                        enabled = shizukuState == ShizukuState.READY && updateState.stage != UpdateStage.INSTALLING_SHIZUKU,
                        modifier = Modifier.height(38.dp)
                    ) {
                        Text("Shizuku 静默安装", color = Apex5Palette.Violet, fontSize = 12.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactParamSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    accent: Color,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Apex5Palette.TextPrimary, fontSize = 12.5.sp)
            Text(valueText, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
        )
    }
}

@Composable
private fun MiniStepChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Apex5Palette.CardElevated)
            .border(1.dp, Apex5Palette.BorderStrong, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, color = Apex5Palette.Cyan, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
    }
}
