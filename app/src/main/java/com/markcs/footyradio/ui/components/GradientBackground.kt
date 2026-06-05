package com.markcs.footyradio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.markcs.footyradio.Config

@Composable
fun GradientBackground(
    modifier: Modifier = Modifier,
    color: Color = Config.gradientColor
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0.0f to color.copy(alpha = 0.3f),
                        0.3f to color.copy(alpha = 0.15f),
                        0.6f to color.copy(alpha = 0.05f),
                        1.0f to color.copy(alpha = 0.0f)
                    ),
                    start = Offset(0f, Float.POSITIVE_INFINITY),
                    end = Offset(Float.POSITIVE_INFINITY, 0f)
                )
            )
    )
}
