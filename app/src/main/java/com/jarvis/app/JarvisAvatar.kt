package com.jarvis.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class AvatarState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    RESEARCHING,
    ERROR
}

fun JarvisState.toAvatarState(): AvatarState = when (this) {
    JarvisState.LISTENING,
    JarvisState.AWAITING_CMD -> AvatarState.LISTENING
    JarvisState.THINKING -> AvatarState.THINKING
    JarvisState.SPEAKING -> AvatarState.SPEAKING
    JarvisState.RESEARCHING -> AvatarState.RESEARCHING
    JarvisState.ERROR -> AvatarState.ERROR
    JarvisState.IDLE,
    JarvisState.BACKGROUND_ACTIVE -> AvatarState.IDLE
}

@Composable
fun JarvisAvatar(
    state: AvatarState,
    settings: AvatarSettings,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "avatar")
    val breath by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.16f,
        animationSpec = infiniteRepeatable(tween((2600 / settings.animationIntensity.coerceAtLeast(0.2f)).toInt()), RepeatMode.Reverse),
        label = "breath"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween((5200 / settings.animationIntensity.coerceAtLeast(0.2f)).toInt())),
        label = "spin"
    )
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween((900 / settings.animationIntensity.coerceAtLeast(0.2f)).toInt())),
        label = "wave"
    )

    val accent = when (state) {
        AvatarState.ERROR -> Color(0xFFFF5E4D)
        AvatarState.RESEARCHING -> Color(0xFFB45CFF)
        AvatarState.THINKING -> Color(0xFF7A8CFF)
        AvatarState.SPEAKING -> Color(0xFF20E7FF)
        AvatarState.LISTENING -> Color(0xFF28FFB2)
        AvatarState.IDLE -> Color(0xFF28D7FF)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color(0xAA061424),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.22f), Color(0xFF020711), Color(0xFF00040A))
                    )
                )
                .padding(8.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val center = Offset(w / 2f, h / 2f)
                val radius = minOf(w, h) * 0.27f * breath
                val stroke = Stroke(width = minOf(w, h) * 0.012f, cap = StrokeCap.Round)

                drawCircle(accent.copy(alpha = 0.08f), radius * 1.75f, center)
                drawCircle(accent.copy(alpha = 0.18f), radius * 1.15f, center, style = stroke)
                drawCircle(Color(0xFF061B32), radius * 0.88f, center)
                drawCircle(accent.copy(alpha = 0.72f), radius * 0.74f, center, style = stroke)

                rotate(spin, center) {
                    drawArc(
                        color = accent,
                        startAngle = 12f,
                        sweepAngle = 78f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius * 1.25f, center.y - radius * 1.25f),
                        size = Size(radius * 2.5f, radius * 2.5f),
                        style = stroke
                    )
                }

                drawSilhouette(center, radius, accent, settings.style)

                if (state == AvatarState.LISTENING) {
                    repeat(3) { idx ->
                        drawCircle(
                            accent.copy(alpha = 0.18f - idx * 0.04f),
                            radius * (1.45f + idx * 0.28f + (breath - 0.8f) * 0.18f),
                            center,
                            style = Stroke(width = 2f)
                        )
                    }
                }

                if (state == AvatarState.THINKING || state == AvatarState.RESEARCHING) {
                    val gridAlpha = if (state == AvatarState.RESEARCHING) 0.18f else 0.1f
                    for (x in 0..6) {
                        val px = w * x / 6f
                        drawLine(accent.copy(alpha = gridAlpha), Offset(px, h * 0.12f), Offset(px, h * 0.88f), 1f)
                    }
                    for (y in 0..5) {
                        val py = h * y / 5f
                        drawLine(accent.copy(alpha = gridAlpha), Offset(w * 0.12f, py), Offset(w * 0.88f, py), 1f)
                    }
                    rotate(-spin * 1.4f, center) {
                        drawArc(
                            color = accent.copy(alpha = 0.85f),
                            startAngle = 210f,
                            sweepAngle = 58f,
                            useCenter = false,
                            topLeft = Offset(center.x - radius * 1.65f, center.y - radius * 1.65f),
                            size = Size(radius * 3.3f, radius * 3.3f),
                            style = Stroke(width = 3f, cap = StrokeCap.Round)
                        )
                    }
                }

                if (state == AvatarState.SPEAKING && settings.voiceReactive) {
                    val bars = 19
                    val baseY = h * 0.84f
                    val startX = w * 0.18f
                    val gap = (w * 0.64f) / bars
                    repeat(bars) { i ->
                        val amp = (sin((wave * 2f * PI + i * 0.72f)).toFloat() + 1f) * 0.5f
                        val barH = h * (0.035f + amp * 0.11f)
                        drawLine(
                            accent.copy(alpha = 0.78f),
                            Offset(startX + i * gap, baseY - barH),
                            Offset(startX + i * gap, baseY + barH),
                            strokeWidth = 4f,
                            cap = StrokeCap.Round
                        )
                    }
                }

                if (state == AvatarState.ERROR) {
                    drawCircle(Color(0xFFFF8A3D).copy(alpha = 0.22f), radius * 2.1f, center, style = Stroke(width = 5f))
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSilhouette(
    center: Offset,
    radius: Float,
    accent: Color,
    style: AvatarStyle
) {
    if (style == AvatarStyle.MINIMAL_HUD) {
        drawCircle(accent.copy(alpha = 0.22f), radius * 0.34f, center)
        drawCircle(accent.copy(alpha = 0.9f), radius * 0.46f, center, style = Stroke(width = 2f))
        return
    }

    val headCenter = Offset(center.x, center.y - radius * 0.27f)
    drawCircle(Color(0xFF081C34), radius * 0.28f, headCenter)
    drawCircle(accent.copy(alpha = 0.45f), radius * 0.3f, headCenter, style = Stroke(width = 2.5f))

    val body = Path().apply {
        moveTo(center.x, center.y + radius * 0.02f)
        cubicTo(center.x - radius * 0.72f, center.y + radius * 0.42f, center.x - radius * 0.62f, center.y + radius * 0.96f, center.x, center.y + radius * 1.1f)
        cubicTo(center.x + radius * 0.62f, center.y + radius * 0.96f, center.x + radius * 0.72f, center.y + radius * 0.42f, center.x, center.y + radius * 0.02f)
        close()
    }
    drawPath(body, Brush.verticalGradient(listOf(accent.copy(alpha = 0.36f), Color.Transparent)))

    if (style == AvatarStyle.FULL_BODY_PLACEHOLDER) {
        repeat(7) { idx ->
            val angle = (-70f + idx * 23f) * PI.toFloat() / 180f
            val from = Offset(center.x + cos(angle) * radius * 0.38f, center.y + radius * 0.28f + sin(angle) * radius * 0.16f)
            val to = Offset(center.x + cos(angle) * radius * 1.18f, center.y + radius * 0.62f + sin(angle) * radius * 0.24f)
            drawLine(accent.copy(alpha = 0.28f), from, to, strokeWidth = 1.5f)
        }
    }
}
