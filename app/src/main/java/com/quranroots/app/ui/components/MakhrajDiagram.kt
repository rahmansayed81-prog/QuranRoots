package com.quranroots.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * A schematic side-profile of the vocal tract (face looking left: lips at the
 * left, throat descending at the right, nasal cavity upper-left). The active
 * articulation zone for `makhrajKey` is highlighted with a pulsing marker.
 *
 * The drawing is intentionally stylised — a teaching aid, not an anatomical
 * chart — and fully offline (pure Canvas, no assets).
 */
@Composable
fun MakhrajDiagram(
    makhrajKey: String,
    modifier: Modifier = Modifier,
    animate: Boolean = true
) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) Color(0xFFB9C4CE) else Color(0xFF37474F)
    val cavity = if (dark) Color(0xFF263238) else Color(0xFFECEFF1)
    val accent = MaterialTheme.colorScheme.primary

    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = if (animate) 0.55f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900), repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAnim"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        drawVocalTract(ink, cavity)
        // The active point(s). Some groups highlight two spots (e.g. lips+nose).
        val points = zonePoints(makhrajKey)
        for (p in points) {
            val c = Offset(p.x * size.width, p.y * size.height)
            drawCircle(color = accent.copy(alpha = 0.18f), radius = 34f * pulse, center = c)
            drawCircle(color = accent.copy(alpha = 0.30f), radius = 22f * pulse, center = c)
            drawCircle(color = accent, radius = 10f, center = c)
        }
    }
}

/** Approximate articulation coordinates (fractions of canvas w/h). */
private fun zonePoints(key: String): List<Offset> = when (key) {
    "jawf" -> listOf(Offset(0.46f, 0.44f))
    "halq_aqsa" -> listOf(Offset(0.74f, 0.70f))
    "halq_wasat" -> listOf(Offset(0.71f, 0.60f))
    "halq_adna" -> listOf(Offset(0.68f, 0.51f))
    "lisan_aqsa_q" -> listOf(Offset(0.60f, 0.57f))
    "lisan_aqsa_k" -> listOf(Offset(0.54f, 0.57f))
    "lisan_wasat" -> listOf(Offset(0.47f, 0.60f))
    "lisan_hafa" -> listOf(Offset(0.42f, 0.62f))
    "lisan_adna_hafa" -> listOf(Offset(0.36f, 0.62f))
    "lisan_taraf_n" -> listOf(Offset(0.30f, 0.62f), Offset(0.16f, 0.30f)) // + nose (غنّة)
    "lisan_taraf_r" -> listOf(Offset(0.31f, 0.61f))
    "lisan_nita" -> listOf(Offset(0.27f, 0.60f))
    "lisan_asala" -> listOf(Offset(0.24f, 0.63f))
    "lisan_litha" -> listOf(Offset(0.20f, 0.61f))
    "shafa_fa" -> listOf(Offset(0.13f, 0.56f))
    "shafatan" -> listOf(Offset(0.11f, 0.50f))
    "khaishum" -> listOf(Offset(0.16f, 0.30f))
    else -> listOf(Offset(0.46f, 0.50f))
}

/** Draws the stylised head/mouth cross-section. */
private fun DrawScope.drawVocalTract(ink: Color, cavity: Color) {
    val w = size.width
    val h = size.height
    fun p(fx: Float, fy: Float) = Offset(fx * w, fy * h)

    // Head profile outline (facing left).
    val head = Path().apply {
        moveTo(p(0.05f, 0.30f).x, p(0.05f, 0.30f).y)   // forehead / upper lip front
        cubicTo(
            p(0.20f, 0.05f).x, p(0.20f, 0.05f).y,
            p(0.70f, 0.02f).x, p(0.70f, 0.02f).y,
            p(0.90f, 0.20f).x, p(0.90f, 0.20f).y
        )
        cubicTo(
            p(1.02f, 0.40f).x, p(1.02f, 0.40f).y,
            p(0.98f, 0.85f).x, p(0.98f, 0.85f).y,
            p(0.82f, 0.98f).x, p(0.82f, 0.98f).y
        )
        lineTo(p(0.55f, 0.98f).x, p(0.55f, 0.98f).y)
        cubicTo(
            p(0.55f, 0.80f).x, p(0.55f, 0.80f).y,
            p(0.42f, 0.78f).x, p(0.42f, 0.78f).y,
            p(0.30f, 0.74f).x, p(0.30f, 0.74f).y
        )
        cubicTo(
            p(0.16f, 0.70f).x, p(0.16f, 0.70f).y,
            p(0.05f, 0.62f).x, p(0.05f, 0.62f).y,
            p(0.05f, 0.52f).x, p(0.05f, 0.52f).y
        )
        close()
    }
    drawPath(head, color = cavity)
    drawPath(head, color = ink, style = Stroke(width = 3f))

    // Oral cavity (open mouth area).
    val mouth = Path().apply {
        moveTo(p(0.10f, 0.45f).x, p(0.10f, 0.45f).y)
        cubicTo(
            p(0.35f, 0.40f).x, p(0.35f, 0.40f).y,
            p(0.55f, 0.42f).x, p(0.55f, 0.42f).y,
            p(0.66f, 0.48f).x, p(0.66f, 0.48f).y
        )
        cubicTo(
            p(0.60f, 0.55f).x, p(0.60f, 0.55f).y,
            p(0.40f, 0.58f).x, p(0.40f, 0.58f).y,
            p(0.14f, 0.56f).x, p(0.14f, 0.56f).y
        )
        close()
    }
    drawPath(mouth, color = ink.copy(alpha = 0.10f))

    // Tongue arc (bottom of cavity).
    val tongue = Path().apply {
        moveTo(p(0.16f, 0.64f).x, p(0.16f, 0.64f).y)
        cubicTo(
            p(0.40f, 0.56f).x, p(0.40f, 0.56f).y,
            p(0.58f, 0.58f).x, p(0.58f, 0.58f).y,
            p(0.66f, 0.66f).x, p(0.66f, 0.66f).y
        )
    }
    drawPath(tongue, color = ink, style = Stroke(width = 3f))

    // Throat channel (descending at right).
    val throat = Path().apply {
        moveTo(p(0.66f, 0.48f).x, p(0.66f, 0.48f).y)
        cubicTo(
            p(0.80f, 0.55f).x, p(0.80f, 0.55f).y,
            p(0.80f, 0.75f).x, p(0.80f, 0.75f).y,
            p(0.72f, 0.92f).x, p(0.72f, 0.92f).y
        )
    }
    drawPath(throat, color = ink, style = Stroke(width = 3f))

    // Lips (two short strokes at left).
    drawLine(ink, p(0.06f, 0.44f), p(0.12f, 0.47f), strokeWidth = 5f)
    drawLine(ink, p(0.06f, 0.58f), p(0.12f, 0.55f), strokeWidth = 5f)

    // Nasal passage (upper-left).
    val nose = Path().apply {
        moveTo(p(0.10f, 0.34f).x, p(0.10f, 0.34f).y)
        cubicTo(
            p(0.18f, 0.24f).x, p(0.18f, 0.24f).y,
            p(0.26f, 0.28f).x, p(0.26f, 0.28f).y,
            p(0.24f, 0.40f).x, p(0.24f, 0.40f).y
        )
    }
    drawPath(nose, color = ink.copy(alpha = 0.7f), style = Stroke(width = 2f))
}
