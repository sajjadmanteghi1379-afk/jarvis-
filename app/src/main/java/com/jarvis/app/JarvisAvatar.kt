package com.jarvis.app

import android.graphics.BitmapFactory
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class AvatarState {
    IDLE,
    AWAITING_CMD,
    LISTENING,
    THINKING,
    SPEAKING,
    RESEARCHING,
    BACKGROUND_ACTIVE,
    ERROR
}

fun JarvisState.toAvatarState(): AvatarState = when (this) {
    JarvisState.LISTENING -> AvatarState.LISTENING
    JarvisState.AWAITING_CMD -> AvatarState.AWAITING_CMD
    JarvisState.THINKING -> AvatarState.THINKING
    JarvisState.SPEAKING -> AvatarState.SPEAKING
    JarvisState.RESEARCHING -> AvatarState.RESEARCHING
    JarvisState.ERROR -> AvatarState.ERROR
    JarvisState.BACKGROUND_ACTIVE -> AvatarState.BACKGROUND_ACTIVE
    JarvisState.IDLE -> AvatarState.IDLE
}

@Composable
fun FullBodyAvatarComposable(
    state: AvatarState,
    settings: AvatarSettings,
    modifier: Modifier = Modifier
) {
    JarvisAvatarView(
        state = state,
        settings = settings.copy(style = AvatarStyle.FULL_BODY_PLACEHOLDER),
        modifier = modifier
    )
}

@Composable
fun CompactHudOrbComposable(
    state: AvatarState,
    settings: AvatarSettings,
    modifier: Modifier = Modifier
) {
    JarvisAvatar(
        state = state,
        settings = settings.copy(style = AvatarStyle.MINIMAL_HUD),
        modifier = modifier
    )
}

@Composable
fun JarvisAvatarView(
    state: AvatarState,
    settings: AvatarSettings,
    modifier: Modifier = Modifier
) {
    if (!settings.enabled) return

    val context = LocalContext.current
    val imageBitmap = remember {
        runCatching {
            context.resources.openRawResource(R.drawable.jarvis_male_front).use { stream ->
                BitmapFactory.decodeStream(stream).asImageBitmap()
            }
        }.getOrElse {
            runCatching {
                context.assets.open("avatar/jarvis_male_fullbody.png").use { stream ->
                    BitmapFactory.decodeStream(stream).asImageBitmap()
                }
            }.getOrNull()
        }
    }
    if (imageBitmap == null) {
        JarvisAvatar(state = state, settings = settings, modifier = modifier)
        return
    }

    val motionEnabled = settings.animationsEnabled && !settings.reducedMotion
    val hudEnabled = settings.hudEffectsEnabled
    val transition = rememberInfiniteTransition(label = "jarvisFullBodyAvatar")
    val breath by transition.animateFloat(
        initialValue = 1.00f,
        targetValue = if (motionEnabled) 1.015f else 1.00f,
        animationSpec = infiniteRepeatable(tween((3600 / settings.motionRate()).toInt()), RepeatMode.Reverse),
        label = "avatarBreath"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = if (motionEnabled) 1f else 0.35f,
        animationSpec = infiniteRepeatable(tween((1250 / settings.motionRate()).toInt()), RepeatMode.Reverse),
        label = "avatarPulse"
    )
    val scan by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (motionEnabled) 1f else 0.42f,
        animationSpec = infiniteRepeatable(tween((1700 / settings.motionRate()).toInt())),
        label = "avatarScan"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (motionEnabled) 360f else 26f,
        animationSpec = infiniteRepeatable(tween((5200 / settings.motionRate()).toInt())),
        label = "avatarHudSpin"
    )

    val accent = state.accentColor()
    val dimAlpha = if (state == AvatarState.BACKGROUND_ACTIVE) 0.46f else 1f
    val bodyScale = if (state == AvatarState.IDLE || state == AvatarState.BACKGROUND_ACTIVE) breath else 1f

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color(0xAA061424),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = if (hudEnabled) 0.38f else 0.16f))
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = if (hudEnabled) 0.20f else 0.08f), Color(0xFF020711), Color(0xFF00040A))
                    )
                )
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (hudEnabled) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val center = Offset(w / 2f, h * 0.52f)
                    val radius = minOf(w, h) * 0.36f

                    if (state == AvatarState.LISTENING || state == AvatarState.AWAITING_CMD) {
                        drawCircle(accent.copy(alpha = 0.10f + pulse * 0.10f), radius * (1.1f + pulse * 0.16f), center)
                        drawCircle(accent.copy(alpha = 0.36f), radius * (0.86f + pulse * 0.08f), center, style = Stroke(width = 2.5f))
                    }

                    if (state == AvatarState.THINKING || state == AvatarState.RESEARCHING) {
                        val lineY = h * (0.18f + scan * 0.66f)
                        drawRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.Transparent, accent.copy(alpha = if (state == AvatarState.RESEARCHING) 0.72f else 0.48f), Color.Transparent),
                                startY = lineY - 22f,
                                endY = lineY + 22f
                            ),
                            topLeft = Offset(w * 0.20f, lineY - 22f),
                            size = Size(w * 0.60f, 44f)
                        )
                    }

                    if (state == AvatarState.SPEAKING) {
                        drawCircle(accent.copy(alpha = 0.20f + pulse * 0.22f), radius * (0.13f + pulse * 0.045f), Offset(center.x, h * 0.47f))
                        drawCircle(Color.White.copy(alpha = 0.14f + pulse * 0.18f), radius * 0.055f, Offset(center.x, h * 0.47f))
                    }

                    if (state == AvatarState.RESEARCHING) {
                        rotate(spin, center) {
                            drawArc(
                                color = accent.copy(alpha = 0.86f),
                                startAngle = 20f,
                                sweepAngle = 92f,
                                useCenter = false,
                                topLeft = Offset(center.x - radius, center.y - radius),
                                size = Size(radius * 2f, radius * 2f),
                                style = Stroke(width = 4f, cap = StrokeCap.Round)
                            )
                        }
                        rotate(-spin * 0.7f, center) {
                            drawArc(
                                color = Color(0xFF20E7FF).copy(alpha = 0.58f),
                                startAngle = 210f,
                                sweepAngle = 54f,
                                useCenter = false,
                                topLeft = Offset(center.x - radius * 1.16f, center.y - radius * 1.16f),
                                size = Size(radius * 2.32f, radius * 2.32f),
                                style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                            )
                        }
                    }
                }
            }

            Image(
                bitmap = imageBitmap,
                contentDescription = "Jarvis full body avatar",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .scale(bodyScale)
                    .alpha(dimAlpha)
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color(0xCC020B14),
                border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = if (hudEnabled) 0.55f else 0.22f))
            ) {
                Text(
                    text = state.statusLabel(),
                    color = accent.copy(alpha = if (state == AvatarState.BACKGROUND_ACTIVE) 0.70f else 1f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
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
        animationSpec = infiniteRepeatable(tween((2600 / (settings.animationIntensity * settings.motionSmoothness).coerceAtLeast(0.2f)).toInt()), RepeatMode.Reverse),
        label = "breath"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween((5200 / (settings.animationIntensity * settings.motionSmoothness).coerceAtLeast(0.2f)).toInt())),
        label = "spin"
    )
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween((900 / (settings.animationIntensity * settings.motionSmoothness).coerceAtLeast(0.2f)).toInt())),
        label = "wave"
    )

    val accent = when (state) {
        AvatarState.ERROR -> Color(0xFFFF5E4D)
        AvatarState.RESEARCHING -> Color(0xFFB45CFF)
        AvatarState.THINKING -> Color(0xFF7A8CFF)
        AvatarState.SPEAKING -> Color(0xFF20E7FF)
        AvatarState.LISTENING -> Color(0xFF28FFB2)
        AvatarState.AWAITING_CMD -> Color(0xFFB45CFF)
        AvatarState.BACKGROUND_ACTIVE -> Color(0xFF59798E)
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

                drawSilhouette(center, radius, accent, settings)

                if (state == AvatarState.LISTENING || state == AvatarState.AWAITING_CMD) {
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

private fun AvatarSettings.motionRate(): Float {
    if (!animationsEnabled || reducedMotion) return 1f
    return (animationIntensity * motionSmoothness).coerceAtLeast(0.2f)
}

private fun AvatarState.accentColor(): Color = when (this) {
    AvatarState.ERROR -> Color(0xFFFF5E4D)
    AvatarState.RESEARCHING -> Color(0xFFB45CFF)
    AvatarState.THINKING -> Color(0xFF7A8CFF)
    AvatarState.SPEAKING -> Color(0xFF20E7FF)
    AvatarState.LISTENING -> Color(0xFF7A8CFF)
    AvatarState.AWAITING_CMD -> Color(0xFFB45CFF)
    AvatarState.BACKGROUND_ACTIVE -> Color(0xFF59798E)
    AvatarState.IDLE -> Color(0xFF28D7FF)
}

private fun AvatarState.statusLabel(): String = when (this) {
    AvatarState.IDLE -> "IDLE"
    AvatarState.AWAITING_CMD -> "AWAITING CMD"
    AvatarState.LISTENING -> "LISTENING"
    AvatarState.THINKING -> "THINKING"
    AvatarState.SPEAKING -> "SPEAKING"
    AvatarState.RESEARCHING -> "RESEARCHING"
    AvatarState.BACKGROUND_ACTIVE -> "BACKGROUND ACTIVE"
    AvatarState.ERROR -> "ERROR"
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSilhouette(
    center: Offset,
    radius: Float,
    accent: Color,
    settings: AvatarSettings
) {
    val style = settings.style
    if (style == AvatarStyle.MINIMAL_HUD) {
        drawCircle(accent.copy(alpha = 0.22f), radius * 0.34f, center)
        drawCircle(accent.copy(alpha = 0.9f), radius * 0.46f, center, style = Stroke(width = 2f))
        return
    }

    val headCenter = Offset(center.x, center.y - radius * 0.27f)
    drawCircle(Color(0xFF081C34), radius * 0.28f, headCenter)
    drawCircle(accent.copy(alpha = 0.45f), radius * 0.3f, headCenter, style = Stroke(width = 2.5f))
    if (settings.eyeContactEffect) {
        drawCircle(accent.copy(alpha = 0.86f), radius * 0.035f, Offset(headCenter.x - radius * 0.09f, headCenter.y - radius * 0.02f))
        drawCircle(accent.copy(alpha = 0.86f), radius * 0.035f, Offset(headCenter.x + radius * 0.09f, headCenter.y - radius * 0.02f))
    }

    val body = Path().apply {
        moveTo(center.x, center.y - radius * 0.02f)
        cubicTo(center.x - radius * 0.54f, center.y + radius * 0.28f, center.x - radius * 0.62f, center.y + radius * 0.64f, center.x - radius * 0.3f, center.y + radius * 0.92f)
        cubicTo(center.x - radius * 0.16f, center.y + radius * 1.05f, center.x - radius * 0.12f, center.y + radius * 1.22f, center.x - radius * 0.24f, center.y + radius * 1.5f)
        cubicTo(center.x - radius * 0.05f, center.y + radius * 1.42f, center.x + radius * 0.05f, center.y + radius * 1.42f, center.x + radius * 0.24f, center.y + radius * 1.5f)
        cubicTo(center.x + radius * 0.12f, center.y + radius * 1.22f, center.x + radius * 0.16f, center.y + radius * 1.05f, center.x + radius * 0.3f, center.y + radius * 0.92f)
        cubicTo(center.x + radius * 0.62f, center.y + radius * 0.64f, center.x + radius * 0.54f, center.y + radius * 0.28f, center.x, center.y - radius * 0.02f)
        close()
    }
    drawPath(
        body,
        Brush.verticalGradient(
            listOf(
                Color(0xFF091426).copy(alpha = 0.88f),
                accent.copy(alpha = 0.24f + settings.personalityIntensity * 0.18f),
                Color.Transparent
            )
        )
    )
    drawLine(accent.copy(alpha = 0.46f), Offset(center.x - radius * 0.44f, center.y + radius * 0.4f), Offset(center.x + radius * 0.44f, center.y + radius * 0.4f), strokeWidth = 1.3f)
    drawLine(accent.copy(alpha = 0.34f), Offset(center.x - radius * 0.32f, center.y + radius * 0.93f), Offset(center.x + radius * 0.32f, center.y + radius * 0.93f), strokeWidth = 1.2f)

    if (style == AvatarStyle.FULL_BODY_PLACEHOLDER) {
        repeat(7) { idx ->
            val angle = (-70f + idx * 23f) * PI.toFloat() / 180f
            val from = Offset(center.x + cos(angle) * radius * 0.38f, center.y + radius * 0.28f + sin(angle) * radius * 0.16f)
            val to = Offset(center.x + cos(angle) * radius * 1.18f, center.y + radius * 0.62f + sin(angle) * radius * 0.24f)
            drawLine(accent.copy(alpha = 0.28f), from, to, strokeWidth = 1.5f)
        }
    }
}
