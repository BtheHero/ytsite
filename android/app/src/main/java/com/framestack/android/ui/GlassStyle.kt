package com.framestack.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object GlassColors {
    val DeepBgTop = Color(0xFF06070B)
    val DeepBgBottom = Color(0xFF0A0D16)
    val GlassSurface = Color(0xB3131722)
    val GlassSurfaceElevated = Color(0xCC1A2030)
    val GlassSurfaceActive = Color(0x3DF59E0B)
    val GlassHud = Color(0xC40B0E17)
    val BorderTopLight = Color(0x47FFFFFF)
    val BorderMid = Color(0x1AFFFFFF)
    val BorderAmber = Color(0x99F59E0B)
    val AccentAmber = Color(0xFFF59E0B)
    val AccentAmberSoft = Color(0xFFFDE68A)
    val AccentEmerald = Color(0xFF34D399)
    val TextPrimary = Color(0xFFF4F4F5)
    val TextSecondary = Color(0xFFA1A1AA)
    val TextMuted = Color(0xFF71717A)
}

object GlassBrushes {
    val BorderHighlighted = Brush.linearGradient(
        listOf(
            GlassColors.AccentAmber,
            Color(0x55FDE68A),
            Color(0x88F59E0B)
        )
    )

    val BorderDefault = Brush.linearGradient(
        listOf(
            GlassColors.BorderTopLight,
            GlassColors.BorderMid,
            Color(0x24FFFFFF)
        )
    )

    val AmberButton = Brush.linearGradient(
        listOf(
            GlassColors.AccentAmberSoft,
            GlassColors.AccentAmber
        )
    )

    val HudScrim = Brush.verticalGradient(
        0.0f to Color(0x8806070B),
        0.35f to Color.Transparent,
        0.65f to Color.Transparent,
        1.0f to Color(0xAA06070B)
    )
}

@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val baseBrush = Brush.verticalGradient(
                    colors = listOf(GlassColors.DeepBgTop, GlassColors.DeepBgBottom),
                    startY = 0f,
                    endY = size.height
                )
                val amberGlow = Brush.radialGradient(
                    colors = listOf(Color(0x20F59E0B), Color.Transparent),
                    center = Offset(size.width * 0.14f, size.height * 0.10f),
                    radius = (size.maxDimension * 0.55f).coerceAtLeast(300f)
                )
                val sapphireGlow = Brush.radialGradient(
                    colors = listOf(Color(0x1A38BDF8), Color.Transparent),
                    center = Offset(size.width * 0.88f, size.height * 0.85f),
                    radius = (size.maxDimension * 0.65f).coerceAtLeast(360f)
                )
                onDrawBehind {
                    drawRect(brush = baseBrush)
                    drawRect(brush = amberGlow)
                    drawRect(brush = sapphireGlow)
                }
            },
        content = content
    )
}

fun Modifier.glassPanel(
    shape: Shape = RoundedCornerShape(16.dp),
    highlighted: Boolean = false,
    elevated: Boolean = false,
    borderWidth: Dp = 1.dp,
    fillColor: Color? = null
): Modifier {
    val bg = fillColor ?: when {
        highlighted -> GlassColors.GlassSurfaceActive
        elevated -> GlassColors.GlassSurfaceElevated
        else -> GlassColors.GlassSurface
    }
    val borderBrush = if (highlighted) GlassBrushes.BorderHighlighted else GlassBrushes.BorderDefault
    return this
        .background(bg, shape)
        .border(borderWidth, borderBrush, shape)
}

fun formatResolutionBadge(height: Int, label: String): String {
    return when {
        height >= 2160 -> "4K"
        height >= 1440 -> "1440p"
        height > 0 -> "${height}p"
        else -> label
    }
}
