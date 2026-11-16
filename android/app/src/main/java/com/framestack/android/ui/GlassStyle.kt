package com.framestack.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object GlassColors {
    val DeepBgTop = Color(0xFF06070B)
    val DeepBgBottom = Color(0xFF0A0D16)
    val GlassSurface = Color(0x9E131722)
    val GlassSurfaceElevated = Color(0xBD1A2030)
    val GlassSurfaceActive = Color(0x36F59E0B)
    val GlassHud = Color(0xB80B0E17)
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

@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(GlassColors.DeepBgTop, GlassColors.DeepBgBottom)
                )
            )
    ) {
        // Soft ambient top-left amber glow & bottom-right sapphire glow for glass refraction depth
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x22F59E0B), Color.Transparent),
                        center = Offset(180f, 140f),
                        radius = 900f
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x1E38BDF8), Color.Transparent),
                        center = Offset(1200f, 1600f),
                        radius = 1100f
                    )
                )
        )
        content()
    }
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
    val borderBrush = if (highlighted) {
        Brush.linearGradient(
            listOf(
                GlassColors.AccentAmber,
                Color(0x55FDE68A),
                Color(0x88F59E0B)
            )
        )
    } else {
        Brush.linearGradient(
            listOf(
                GlassColors.BorderTopLight,
                GlassColors.BorderMid,
                Color(0x24FFFFFF)
            )
        )
    }
    return this
        .clip(shape)
        .background(bg)
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
