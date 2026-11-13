package app.altrove.ui.space

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import app.altrove.ui.Space
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

private val StarWhite = Color(0xFFF3F0FF)
private val StarViolet = Color(0xFFC6BDFF)
private val StarWarm = Color(0xFFFFE1B0)

/**
 * The sky behind everything: a deep gradient, three slow nebulae, the starfield and, now and
 * then, a shooting star. The nebulae warm up while a session is active.
 *
 * @param active 0 at rest, 1 while "elsewhere"; read while drawing.
 */
@Composable
internal fun SpaceBackground(scene: SpaceScene, active: () -> Float, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            // Gradients are built once per size; per frame only their opacity changes.
            val sky = Brush.verticalGradient(listOf(Space.Dusk, Space.Night, Space.Void))
            val nebulae = listOf(
                Nebula(Offset(w * 0.10f, h * 0.15f), w * 1.0f, Color(0xFF5B3DF5), Color(0xFFC2318B), 0.30f),
                Nebula(Offset(w * 1.02f, h * 0.48f), w * 0.9f, Color(0xFF0FA5C4), Color(0xFFFF8A4C), 0.15f),
                Nebula(Offset(w * 0.22f, h * 1.02f), w * 0.95f, Color(0xFFB8328F), Color(0xFF6C4DFF), 0.14f),
            )
            onDrawBehind {
                val time = scene.time
                val warmth = active()
                drawRect(sky)
                nebulae.forEachIndexed { i, nebula ->
                    val breath = if (scene.calm) 1f else 0.86f + 0.14f * sin(time * (0.19f + i * 0.05f) + i * 2.1f)
                    drawCircle(nebula.cool, nebula.radius, nebula.center, alpha = nebula.strength * breath * (1f - warmth))
                    drawCircle(nebula.warm, nebula.radius, nebula.center, alpha = nebula.strength * breath * warmth)
                }
                drawStars(scene, time)
                drawMeteor(scene, time)
                val flash = scene.flash.value
                if (flash > 0f) drawRect(Color.White, alpha = flash * 0.32f)
            }
        },
    )
}

/** One nebula in its two moods, as ready-made gradients. */
private class Nebula(val center: Offset, val radius: Float, cool: Color, warm: Color, val strength: Float) {
    val cool = Brush.radialGradient(listOf(cool, Color.Transparent), center, radius)
    val warm = Brush.radialGradient(listOf(warm, Color.Transparent), center, radius)
}

private fun DrawScope.drawStars(scene: SpaceScene, time: Float) {
    val stars = scene.stars
    val focal = max(size.width, size.height) * 0.55f
    val origin = if (scene.vanishingPoint.isSpecified) {
        scene.vanishingPoint
    } else {
        Offset(size.width / 2f, size.height * 0.27f)
    }
    stars.setReach(
        x = max(origin.x, size.width - origin.x) / focal,
        up = (origin.y / focal).coerceAtLeast(0.05f),
        down = ((size.height - origin.y) / focal).coerceAtLeast(0.05f),
    )

    val warp = scene.warp.value
    val speed = scene.starSpeed()
    val unit = 1.dp.toPx()
    // A star entering at the horizon fades in; quicker at speed, or it would be gone first.
    val fadeIn = 0.7f - 0.6f * warp
    for (i in 0 until stars.count) {
        val depth = stars.z[i]
        val px = origin.x + stars.x[i] / depth * focal
        val py = origin.y + stars.y[i] / depth * focal
        val twinkle = if (scene.calm) 0.85f else 0.72f + 0.28f * sin(time * (1.2f + (i % 7) * 0.23f) + stars.phase[i])
        val alpha = (stars.age[i] / fadeIn).coerceAtMost(1f) * twinkle
        val radius = stars.magnitude[i] * unit * (0.5f + 0.9f * (1f - depth))
        val color = when (stars.tint[i]) {
            Starfield.TINT_VIOLET -> StarViolet
            Starfield.TINT_WARM -> StarWarm
            else -> StarWhite
        }
        // Where the star was a moment ago: at speed, the gap is the streak.
        val before = depth + speed * STREAK_SECONDS
        val qx = origin.x + stars.x[i] / before * focal
        val qy = origin.y + stars.y[i] / before * focal
        val dx = px - qx
        val dy = py - qy
        if (dx * dx + dy * dy > radius * radius * 2.5f) {
            drawLine(
                color = color,
                start = Offset(qx, qy),
                end = Offset(px, py),
                strokeWidth = radius * 1.5f,
                cap = StrokeCap.Round,
                alpha = (alpha * (0.75f + 0.25f * warp)).coerceAtMost(1f),
            )
        } else {
            drawCircle(color, radius, Offset(px, py), alpha = alpha)
            // The brightest few get a soft halo.
            if (stars.magnitude[i] > 1.9f) drawCircle(color, radius * 2.8f, Offset(px, py), alpha = alpha * 0.14f)
        }
    }
}

private fun DrawScope.drawMeteor(scene: SpaceScene, time: Float) {
    val meteor = scene.meteor
    val progress = (time - meteor.startedAt) / Meteor.DURATION
    if (progress < 0f || progress > 1f) return
    val travel = size.width * 0.6f
    val head = Offset(
        x = meteor.fromX * size.width + meteor.heading * travel * progress,
        y = meteor.fromY * size.height + meteor.slope * travel * progress,
    )
    val glow = sin(PI.toFloat() * progress)
    val length = size.width * 0.2f * glow
    val tail = Offset(head.x - meteor.heading * length, head.y - meteor.slope * length)
    drawLine(
        brush = Brush.linearGradient(listOf(Color.Transparent, StarWhite), start = tail, end = head),
        start = tail,
        end = head,
        strokeWidth = 1.6.dp.toPx(),
        cap = StrokeCap.Round,
        alpha = glow,
    )
    drawCircle(StarWhite, 1.6.dp.toPx(), head, alpha = glow)
}

private const val STREAK_SECONDS = 0.05f
