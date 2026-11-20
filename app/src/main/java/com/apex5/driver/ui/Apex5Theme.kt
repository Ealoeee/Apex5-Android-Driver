package com.apex5.driver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object Apex5Palette {
    val BgObsidian = Color(0xFF070B14)
    val RailBg = Color(0xFF0B1120)
    val CardBg = Color(0xFF0F172A)
    val CardElevated = Color(0xFF162036)
    val InnerBg = Color(0xFF090E1B)
    val BorderSubtle = Color(0xFF22304A)
    val BorderStrong = Color(0xFF33476B)

    val Cyan = Color(0xFF38BDF8)
    val CyanDeep = Color(0xFF0284C7)
    val Violet = Color(0xFFA855F7)
    val Pink = Color(0xFFEC4899)
    val Emerald = Color(0xFF22C55E)
    val Amber = Color(0xFFF59E0B)
    val Rose = Color(0xFFEF4444)

    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
}

@Composable
internal fun BentoCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    accent: Color = Apex5Palette.Cyan,
    contentPadding: Int = 16,
    content: @Composable BoxScope.() -> Unit
) {
    val borderBrush = if (highlighted) {
        Brush.linearGradient(listOf(accent.copy(alpha = 0.65f), Apex5Palette.BorderSubtle))
    } else {
        Brush.linearGradient(listOf(Apex5Palette.BorderSubtle, Apex5Palette.BorderSubtle))
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Apex5Palette.CardElevated.copy(alpha = 0.72f), Apex5Palette.CardBg)
                )
            )
            .border(1.dp, borderBrush, RoundedCornerShape(16.dp))
            .padding(contentPadding.dp),
        content = content
    )
}

@Composable
internal fun BentoSectionHeader(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color = Apex5Palette.Cyan,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            if (icon != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(accent.copy(alpha = 0.14f))
                        .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            Column {
                Text(
                    text = title,
                    color = Apex5Palette.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = Apex5Palette.TextSecondary,
                        fontSize = 11.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
internal fun StatusPillBadge(
    text: String,
    color: Color,
    pulseDot: Boolean = true
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        if (pulseDot) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            color = color,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
internal fun <T> SleekDropdownRow(
    label: String,
    currentText: String,
    options: List<Pair<String, T>>,
    onSelect: (T) -> Unit,
    hint: String? = null
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(0.46f)) {
            Text(
                text = label,
                color = Apex5Palette.TextPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium
            )
            if (!hint.isNullOrBlank()) {
                Text(
                    text = hint,
                    color = Apex5Palette.TextMuted,
                    fontSize = 11.sp
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(0.54f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Apex5Palette.InnerBg)
                    .border(1.dp, Apex5Palette.BorderStrong, RoundedCornerShape(10.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentText,
                    color = Apex5Palette.Cyan,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                Text("▾", color = Apex5Palette.TextSecondary, fontSize = 12.sp)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (text, value) ->
                    DropdownMenuItem(
                        text = { Text(text, fontSize = 13.sp) },
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
