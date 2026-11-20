package com.apex5.driver.ui

import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex5.driver.model.TouchNodeConfig
import com.apex5.driver.model.TouchNodeKind
import com.apex5.driver.model.TouchProfile
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TouchBlueprintAndPadCard(
    profile: TouchProfile?,
    modifier: Modifier = Modifier
) {
    // 0 = Visual TouchProfile schematic blueprint, 1 = Live multi-touch calibration test pad
    var padMode by remember { mutableIntStateOf(0) }
    val activePointers = remember { mutableStateListOf<Offset>() }
    var lastTouchLog by remember { mutableStateOf("等待触控信号（切至「触屏映射模式」后按手柄即可在此画板实时验点）") }

    BentoCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (padMode == 0) {
                            "16:10 方案键位蓝图：${profile?.name ?: "无方案"}"
                        } else {
                            "虚拟多点触控实时校准画板"
                        },
                        color = Apex5Palette.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (padMode == 0) {
                            "共 ${profile?.nodes?.size ?: 0} 个映射节点 · 含组合键 (${profile?.nodes?.count { it.modifierInput != null } ?: 0}) 与宏序列 (${profile?.nodes?.count { it.kind == TouchNodeKind.MACRO } ?: 0})"
                        } else {
                            lastTouchLog
                        },
                        color = if (padMode == 0) Apex5Palette.TextSecondary else Apex5Palette.Cyan,
                        fontSize = 11.5.sp,
                        fontFamily = if (padMode == 1) FontFamily.Monospace else FontFamily.Default
                    )
                }

                // Segmented switcher
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Apex5Palette.InnerBg)
                        .border(1.dp, Apex5Palette.BorderSubtle, RoundedCornerShape(10.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ModeTogglePill(
                        label = "键位蓝图",
                        icon = Icons.Rounded.GridView,
                        selected = padMode == 0,
                        onClick = { padMode = 0 }
                    )
                    ModeTogglePill(
                        label = "校准画板",
                        icon = Icons.Rounded.TouchApp,
                        selected = padMode == 1,
                        onClick = { padMode = 1 }
                    )
                }
            }

            if (padMode == 0) {
                TouchProfileSchematicCanvas(
                    nodes = profile?.nodes.orEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LegendDot("轮盘摇杆 / 视角", Apex5Palette.Cyan)
                    LegendDot("FPS 视角滑区", Apex5Palette.Violet)
                    LegendDot("连发点击", Apex5Palette.Amber)
                    LegendDot("序列宏 & 路径", Color(0xFFFBBF24))
                }
                if (profile != null && profile.nodes.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        profile.nodes.forEach { n ->
                            NodeSummaryTag(n)
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Apex5Palette.InnerBg)
                        .border(1.dp, Apex5Palette.BorderStrong, RoundedCornerShape(12.dp))
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
                                        lastTouchLog = "检测到 ${activePointers.size} 指触控 · 主落点=(%.2f, %.2f)".format(nx, ny)
                                    }
                                }
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stepX = size.width / 8f
                        val stepY = size.height / 5f
                        for (i in 1..7) {
                            drawLine(
                                Apex5Palette.BorderSubtle.copy(alpha = 0.55f),
                                Offset(stepX * i, 0f),
                                Offset(stepX * i, size.height),
                                1f
                            )
                        }
                        for (j in 1..4) {
                            drawLine(
                                Apex5Palette.BorderSubtle.copy(alpha = 0.55f),
                                Offset(0f, stepY * j),
                                Offset(size.width, stepY * j),
                                1f
                            )
                        }
                        activePointers.forEachIndexed { idx, pt ->
                            drawCircle(
                                color = Apex5Palette.Cyan.copy(alpha = 0.22f),
                                radius = 38f,
                                center = pt
                            )
                            drawCircle(
                                color = Apex5Palette.Cyan,
                                radius = 15f,
                                center = pt
                            )
                            drawContext.canvas.nativeCanvas.drawText(
                                "#${idx + 1}",
                                pt.x,
                                pt.y - 24f,
                                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = android.graphics.Color.WHITE
                                    textSize = 24f
                                    textAlign = Paint.Align.CENTER
                                    typeface = Typeface.DEFAULT_BOLD
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TouchProfileSchematicCanvas(
    nodes: List<TouchNodeConfig>,
    modifier: Modifier = Modifier
) {
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
    }
    val stepPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#FEF3C7")
            textAlign = Paint.Align.CENTER
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Apex5Palette.InnerBg)
            .border(1.5.dp, Apex5Palette.BorderStrong, RoundedCornerShape(12.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val minDim = minOf(w, h)

            // Subtle blueprint grid
            val stepX = w / 8f
            val stepY = h / 5f
            for (i in 1..7) {
                drawLine(
                    Apex5Palette.BorderSubtle.copy(alpha = 0.45f),
                    Offset(stepX * i, 0f),
                    Offset(stepX * i, h),
                    1f
                )
            }
            for (j in 1..4) {
                drawLine(
                    Apex5Palette.BorderSubtle.copy(alpha = 0.45f),
                    Offset(0f, stepY * j),
                    Offset(w, stepY * j),
                    1f
                )
            }

            // Tablet bezel inner frame
            drawRect(
                color = Apex5Palette.BorderSubtle.copy(alpha = 0.35f),
                topLeft = Offset(10f, 10f),
                size = androidx.compose.ui.geometry.Size(w - 20f, h - 20f),
                style = Stroke(width = 1.5f)
            )

            nodes.forEach { n ->
                val cx = n.xNorm * w
                val cy = n.yNorm * h
                val accent = when (n.kind) {
                    TouchNodeKind.JOYSTICK -> Apex5Palette.Cyan
                    TouchNodeKind.CAMERA_SWIPE -> Apex5Palette.Violet
                    TouchNodeKind.RAPID_TAP -> Apex5Palette.Amber
                    TouchNodeKind.MACRO -> Color(0xFFFBBF24)
                    TouchNodeKind.TAP -> Apex5Palette.Cyan
                }

                if (n.kind == TouchNodeKind.JOYSTICK || n.kind == TouchNodeKind.CAMERA_SWIPE) {
                    drawCircle(
                        color = accent.copy(alpha = 0.08f),
                        radius = n.radiusNorm * minDim,
                        center = Offset(cx, cy)
                    )
                    drawCircle(
                        color = accent.copy(alpha = 0.5f),
                        radius = n.radiusNorm * minDim,
                        center = Offset(cx, cy),
                        style = Stroke(
                            width = 2.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                        )
                    )
                }

                if (n.kind == TouchNodeKind.MACRO && n.macroSteps.isNotEmpty()) {
                    var px = cx
                    var py = cy
                    n.macroSteps.forEachIndexed { idx, step ->
                        val sx = step.xNorm * w
                        val sy = step.yNorm * h
                        drawLine(
                            color = Color(0xFFFBBF24).copy(alpha = 0.7f),
                            start = Offset(px, py),
                            end = Offset(sx, sy),
                            strokeWidth = 2.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                        )
                        drawCircle(color = Color(0xFF78350F), radius = 14f, center = Offset(sx, sy))
                        drawCircle(
                            color = Color(0xFFFBBF24),
                            radius = 14f,
                            center = Offset(sx, sy),
                            style = Stroke(width = 2f)
                        )
                        drawContext.canvas.nativeCanvas.drawText("${idx + 1}", sx, sy + 7f, stepPaint)
                        px = sx
                        py = sy
                    }
                }

                val nodeR = (n.radiusNorm * minDim).coerceIn(18f, 34f)
                drawCircle(
                    color = accent.copy(alpha = 0.24f),
                    radius = nodeR,
                    center = Offset(cx, cy)
                )
                drawCircle(
                    color = accent,
                    radius = nodeR,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.5f)
                )

                val trig = n.displayTriggerLabel()
                labelPaint.textSize = if (trig.length > 4) 19f else 24f
                drawContext.canvas.nativeCanvas.drawText(
                    trig,
                    cx,
                    cy + 8f,
                    labelPaint
                )
            }
        }
    }
}

@Composable
private fun ModeTogglePill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Apex5Palette.Cyan.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) Apex5Palette.Cyan else Apex5Palette.TextSecondary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            color = if (selected) Apex5Palette.Cyan else Apex5Palette.TextSecondary,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(label, color = Apex5Palette.TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun NodeSummaryTag(node: TouchNodeConfig) {
    val accent = when (node.kind) {
        TouchNodeKind.JOYSTICK -> Apex5Palette.Cyan
        TouchNodeKind.CAMERA_SWIPE -> Apex5Palette.Violet
        TouchNodeKind.RAPID_TAP -> Apex5Palette.Amber
        TouchNodeKind.MACRO -> Color(0xFFFBBF24)
        TouchNodeKind.TAP -> Apex5Palette.Emerald
    }
    val detail = when (node.kind) {
        TouchNodeKind.MACRO -> "${node.macroSteps.size.coerceAtLeast(1)}步宏"
        TouchNodeKind.RAPID_TAP -> "${node.rapidHz}Hz"
        else -> "${(node.xNorm * 100).roundToInt()}%,${(node.yNorm * 100).roundToInt()}%"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Apex5Palette.InnerBg)
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = node.displayTriggerLabel(),
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = detail,
            color = Apex5Palette.TextSecondary,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
