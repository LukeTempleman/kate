package tech.gonxt.kate.ui.orb

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import tech.gonxt.kate.core.OrbState
import tech.gonxt.kate.ui.theme.KateColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * Kate's face. Four living states:
 * IDLE — slow breathing pulse; LISTENING — inward ripples;
 * THINKING — rotating shimmer arcs; SPEAKING — radial waveform driven by [amplitude].
 */
@Composable
fun Orb(
    state: OrbState,
    amplitude: Float,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val smoothAmplitude by animateFloatAsState(amplitude, label = "amp")

    Canvas(modifier = modifier) {
        when (state) {
            OrbState.IDLE -> drawIdle(phase)
            OrbState.LISTENING -> drawListening(phase)
            OrbState.THINKING -> drawThinking(phase)
            OrbState.SPEAKING -> drawSpeaking(phase, smoothAmplitude)
        }
    }
}

private fun DrawScope.core(radius: Float, glowScale: Float = 1f) {
    drawCircle(
        brush = Brush.radialGradient(
            0f to KateColors.Cyan.copy(alpha = 0.35f * glowScale),
            0.55f to KateColors.Cyan.copy(alpha = 0.08f * glowScale),
            1f to Color.Transparent,
            center = center,
            radius = radius * 1.9f,
        ),
        radius = radius * 1.9f,
    )
    drawCircle(color = KateColors.Cyan, radius = radius * 0.16f)
    drawCircle(color = KateColors.Cyan.copy(alpha = 0.9f), radius = radius, style = Stroke(width = 2.5f))
}

private fun DrawScope.drawIdle(phase: Float) {
    val r = size.minDimension / 4f
    val breath = 1f + 0.035f * sin(phase * 2f * Math.PI).toFloat()
    core(r * breath)
    drawCircle(
        color = KateColors.Cyan.copy(alpha = 0.25f),
        radius = r * 1.35f * breath,
        style = Stroke(width = 1f),
    )
}

private fun DrawScope.drawListening(phase: Float) {
    val r = size.minDimension / 4f
    core(r, glowScale = 1.3f)
    for (i in 0 until 3) {
        val p = ((phase * 2f) + i / 3f) % 1f
        val rippleR = r * (2.2f - 1.1f * p)
        drawCircle(
            color = KateColors.Cyan.copy(alpha = 0.35f * p),
            radius = rippleR,
            style = Stroke(width = 1.5f),
        )
    }
}

private fun DrawScope.drawThinking(phase: Float) {
    val r = size.minDimension / 4f
    core(r * 0.92f, glowScale = 0.8f)
    val angle = phase * 360f
    rotate(angle) {
        arcRing(r * 1.25f, startDeg = 0f, sweepDeg = 100f, alpha = 0.9f)
        arcRing(r * 1.25f, startDeg = 180f, sweepDeg = 60f, alpha = 0.5f)
    }
    rotate(-angle * 1.6f) {
        arcRing(r * 1.5f, startDeg = 90f, sweepDeg = 70f, alpha = 0.35f)
    }
}

private fun DrawScope.arcRing(radius: Float, startDeg: Float, sweepDeg: Float, alpha: Float) {
    drawArc(
        color = KateColors.Cyan.copy(alpha = alpha),
        startAngle = startDeg,
        sweepAngle = sweepDeg,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
        style = Stroke(width = 2f, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawSpeaking(phase: Float, amplitude: Float) {
    val r = size.minDimension / 4f
    core(r * (1f + amplitude * 0.06f), glowScale = 0.9f + amplitude * 0.6f)
    val bars = 56
    for (i in 0 until bars) {
        val angle = i * (2 * Math.PI / bars)
        val wobble = sin(phase * 2f * Math.PI * 3 + i * 0.9).toFloat() * 0.5f + 0.5f
        val len = r * 0.12f + r * 0.55f * amplitude * wobble
        val inner = r * 1.15f
        val sx = center.x + cos(angle).toFloat() * inner
        val sy = center.y + sin(angle).toFloat() * inner
        val ex = center.x + cos(angle).toFloat() * (inner + len)
        val ey = center.y + sin(angle).toFloat() * (inner + len)
        drawLine(
            color = KateColors.Cyan.copy(alpha = 0.35f + 0.65f * amplitude * wobble),
            start = Offset(sx, sy),
            end = Offset(ex, ey),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round,
        )
    }
}
