package app.altrove.ui.space

import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.altrove.Coordinates
import app.altrove.R
import app.altrove.Session
import app.altrove.ui.Space
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Room kept around the sphere for its halo, orbit and countdown ring. */
private val GlobeMargin = 30.dp

/**
 * The Earth, as a sphere of dots floating in the sky, always turned towards where you are
 * about to be.
 *
 * - [destination] is marked by a pulsing beacon; [draft] (coordinates typed or picked but not
 *   saved yet) by a turning reticle.
 * - At rest a little satellite circles it; during a [session] a ring around it runs down
 *   with the five minutes.
 * - Dragging sideways spins it, and a tap picks the place under the finger ([onPick]).
 *
 * @param active 0 at rest, 1 while "elsewhere"; read while drawing.
 */
@Composable
internal fun Globe(
    scene: SpaceScene,
    destination: Coordinates?,
    draft: Coordinates?,
    session: Session.Active?,
    active: () -> Float,
    onPick: (Coordinates) -> Unit,
    modifier: Modifier = Modifier,
) {
    val painter = remember { GlobePainter(EarthDots.build()) }
    val pick by rememberUpdatedState(onPick)
    val margin = with(LocalDensity.current) { GlobeMargin.toPx() }

    Canvas(
        modifier = modifier
            .onGloballyPositioned { scene.vanishingPoint = it.boundsInRoot().center }
            .pointerInput(Unit) {
                detectTapGestures { point -> locate(point, size, margin, scene.globe)?.let(pick) }
            }
            .pointerInput(Unit) {
                val velocity = VelocityTracker()
                fun degreesPerPixel() = DEGREES / (min(size.width, size.height) / 2f - margin)
                detectHorizontalDragGestures(
                    onDragStart = {
                        velocity.resetTracking()
                        scene.globe.dragging = true
                    },
                    onDragEnd = { scene.globe.release(-velocity.calculateVelocity().x * degreesPerPixel()) },
                    onDragCancel = { scene.globe.release(0f) },
                ) { change, amount ->
                    velocity.addPosition(change.uptimeMillis, change.position)
                    // The surface follows the finger: dragging right brings up what lies west.
                    scene.globe.dragBy(-amount * degreesPerPixel())
                    change.consume()
                }
            },
        contentDescription = stringResource(R.string.globe_description),
    ) {
        painter.draw(this, scene, destination, draft, session, active(), margin)
    }
}

/** The place under [point], or null if it falls outside the sphere. */
private fun locate(point: Offset, size: IntSize, margin: Float, motion: GlobeMotion): Coordinates? {
    val radius = min(size.width, size.height) / 2f - margin
    val nx = (point.x - size.width / 2f) / radius
    val ny = -(point.y - size.height / 2f) / radius
    val planar = nx * nx + ny * ny
    if (planar > 0.94f) return null // Outside, or so close to the edge that it is a guess.
    val nz = sqrt(1f - planar)
    val tilt = motion.latitude / DEGREES
    val y = ny * cos(tilt) + nz * sin(tilt)
    val z = -ny * sin(tilt) + nz * cos(tilt)
    val latitude = asin(y.coerceIn(-1f, 1f)) * DEGREES
    val longitude = GlobeMotion.wrap(motion.longitude + atan2(nx, z) * DEGREES)
    return Coordinates(latitude.toDouble(), longitude.toDouble())
}

/**
 * Draws the globe. Holds the buffers and paints it reuses on every frame, so that drawing
 * allocates nothing.
 */
private class GlobePainter(private val dots: EarthDots) {
    private val near = FloatArray(dots.count * 2)
    private val middle = FloatArray(dots.count * 2)
    private val far = FloatArray(dots.count * 2)
    private val lights = FloatArray(dots.count / LIGHT_EVERY * 2 + 2)

    private val graticule = buildGraticule()
    private val lines = FloatArray(graticule.size / 6 * 4)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // Rotation of the current frame, and the last projected point.
    private var sinLongitude = 0f
    private var cosLongitude = 1f
    private var sinLatitude = 0f
    private var cosLatitude = 1f
    private var px = 0f
    private var py = 0f
    private var depth = 0f

    // Gradients are rebuilt only when the sphere changes size or mood.
    private var cachedRadius = -1f
    private var cachedMood = -1f
    private var cachedCenter = Offset.Unspecified
    private var halo: Brush? = null
    private var body: Brush? = null
    private var rim: Brush? = null
    private var ring: Brush? = null

    fun draw(
        scope: DrawScope,
        scene: SpaceScene,
        destination: Coordinates?,
        draft: Coordinates?,
        session: Session.Active?,
        mood: Float,
        margin: Float,
    ) = with(scope) {
        val time = scene.time
        val launch = scene.launchPulse.value
        val save = scene.savePulse.value
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = min(size.width, size.height) / 2f - margin
        if (baseRadius <= 0f) return@with
        // The sphere swells for an instant when the jump starts.
        val radius = baseRadius * (1f + 0.05f * swell(launch))
        val glow = lerp(Space.Violet, Space.Magenta, mood)
        val land = lerp(Space.Lavender, Color(0xFFFFE4F2), mood)
        prepare(center, radius, mood, glow)
        aim(scene.globe)

        // Behind the sphere.
        drawCountdown(center, baseRadius, session, mood)
        drawOrbit(center, baseRadius, time, 1f - mood, front = false)

        drawCircle(halo!!, radius * HALO, center)
        drawCircle(body!!, radius, center)
        drawGraticule(center, radius, glow)
        drawLand(center, radius, land, time, scene.calm)
        drawCircle(rim!!, radius, center)

        if (launch < 1f) drawLaunch(center, radius, launch, glow)
        if (destination != null && project(destination, center, radius)) {
            drawBeacon(time, save, mood, scene.calm)
            if (save < 1f) drawLock(radius, save)
        }
        if (draft != null && project(draft, center, radius)) drawReticle(time)

        drawOrbit(center, baseRadius, time, 1f - mood, front = true)
    }

    private fun prepare(center: Offset, radius: Float, mood: Float, glow: Color) {
        if (radius == cachedRadius && mood == cachedMood && center == cachedCenter) return
        cachedRadius = radius
        cachedMood = mood
        cachedCenter = center
        halo = Brush.radialGradient(
            0f to glow.copy(alpha = 0.5f),
            1f / HALO to glow.copy(alpha = 0.5f),
            1f to Color.Transparent,
            center = center,
            radius = radius * HALO,
        )
        body = Brush.radialGradient(
            listOf(Color(0xFF26266E), Color(0xFF10103A), Color(0xFF070720)),
            center = Offset(center.x - radius * 0.38f, center.y - radius * 0.42f),
            radius = radius * 1.7f,
        )
        rim = Brush.radialGradient(
            0f to Color.Transparent,
            0.8f to Color.Transparent,
            1f to glow.copy(alpha = 0.42f),
            center = center,
            radius = radius,
        )
        ring = Brush.sweepGradient(listOf(Space.Gold, Space.Magenta, Space.Violet, Space.Gold), center)
    }

    private fun aim(motion: GlobeMotion) {
        val longitude = motion.longitude / DEGREES
        val latitude = motion.latitude / DEGREES
        sinLongitude = sin(longitude)
        cosLongitude = cos(longitude)
        sinLatitude = sin(latitude)
        cosLatitude = cos(latitude)
    }

    /** Projects a unit vector into [px], [py]; returns its depth (positive: facing the viewer). */
    private fun project(x: Float, y: Float, z: Float, center: Offset, radius: Float): Float {
        val x1 = x * cosLongitude - z * sinLongitude
        val z1 = x * sinLongitude + z * cosLongitude
        px = center.x + radius * x1
        py = center.y - radius * (y * cosLatitude - z1 * sinLatitude)
        return y * sinLatitude + z1 * cosLatitude
    }

    /** Projects a place; returns false if it is on the far side. */
    private fun project(place: Coordinates, center: Offset, radius: Float): Boolean {
        val latitude = place.latitude.toFloat() / DEGREES
        val longitude = place.longitude.toFloat() / DEGREES
        val circle = cos(latitude)
        depth = project(circle * sin(longitude), sin(latitude), circle * cos(longitude), center, radius)
        return depth > 0.04f
    }

    private fun DrawScope.drawLand(center: Offset, radius: Float, color: Color, time: Float, calm: Boolean) {
        val xyz = dots.xyz
        var n = 0
        var m = 0
        var f = 0
        var l = 0
        for (i in 0 until dots.count) {
            val d = project(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2], center, radius)
            if (d <= 0.02f) continue
            when {
                d > 0.72f -> {
                    near[n++] = px
                    near[n++] = py
                    // A sprinkling of dots twinkle like city lights.
                    if (i % LIGHT_EVERY == 0 && (calm || sin(time * 1.6f + i) > 0.1f)) {
                        lights[l++] = px
                        lights[l++] = py
                    }
                }
                d > 0.4f -> {
                    middle[m++] = px
                    middle[m++] = py
                }
                else -> {
                    far[f++] = px
                    far[f++] = py
                }
            }
        }
        val unit = 1.dp.toPx()
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            paint.color = color.copy(alpha = 0.36f).toArgb()
            paint.strokeWidth = unit * 1.3f
            native.drawPoints(far, 0, f, paint)
            paint.color = color.copy(alpha = 0.68f).toArgb()
            paint.strokeWidth = unit * 1.7f
            native.drawPoints(middle, 0, m, paint)
            paint.color = color.toArgb()
            paint.strokeWidth = unit * 2.05f
            native.drawPoints(near, 0, n, paint)
            paint.color = Color.White.toArgb()
            paint.strokeWidth = unit * 2.6f
            native.drawPoints(lights, 0, l, paint)
        }
    }

    private fun DrawScope.drawGraticule(center: Offset, radius: Float, color: Color) {
        var count = 0
        var i = 0
        while (i < graticule.size) {
            val d1 = project(graticule[i], graticule[i + 1], graticule[i + 2], center, radius)
            val x1 = px
            val y1 = py
            val d2 = project(graticule[i + 3], graticule[i + 4], graticule[i + 5], center, radius)
            if (d1 > 0f && d2 > 0f) {
                lines[count++] = x1
                lines[count++] = y1
                lines[count++] = px
                lines[count++] = py
            }
            i += 6
        }
        drawIntoCanvas { canvas ->
            paint.color = color.copy(alpha = 0.13f).toArgb()
            paint.strokeWidth = 1.dp.toPx() * 0.8f
            canvas.nativeCanvas.drawLines(lines, 0, count, paint)
        }
    }

    /** The saved destination: a bright core with rings rippling out of it. */
    private fun DrawScope.drawBeacon(time: Float, save: Float, mood: Float, calm: Boolean) {
        val at = Offset(px, py)
        val facing = ((depth - 0.04f) / 0.3f).coerceIn(0f, 1f)
        val unit = 1.dp.toPx()
        val color = lerp(Space.Gold, Color.White, 0.25f)
        if (!calm) {
            val pace = 0.5f + 0.35f * mood
            for (k in 0 until 3) {
                val t = (time * pace + k / 3f) % 1f
                drawCircle(
                    color = color,
                    radius = unit * (4f + 24f * t),
                    center = at,
                    alpha = (1f - t) * (1f - t) * 0.7f * facing,
                    style = Stroke(unit * 1.4f),
                )
            }
        }
        // It lands with a little overshoot when a destination is saved.
        val pop = 1f + 1.6f * (1f - save) * (1f - save) * (1f - save)
        drawCircle(color, unit * 8.5f * pop, at, alpha = 0.3f * facing)
        drawCircle(Color.White, unit * 3.4f * pop, at, alpha = facing)
    }

    /** Coordinates not saved yet: a reticle that keeps turning. */
    private fun DrawScope.drawReticle(time: Float) {
        val at = Offset(px, py)
        val facing = ((depth - 0.04f) / 0.3f).coerceIn(0f, 1f)
        val unit = 1.dp.toPx()
        val size = unit * 13f
        drawCircle(Space.Cyan, size, at, alpha = 0.9f * facing, style = Stroke(unit * 1.5f))
        drawCircle(Space.Cyan, unit * 2.2f, at, alpha = facing)
        rotate(degrees = time * 40f, pivot = at) {
            for (k in 0 until 4) {
                rotate(degrees = k * 90f, pivot = at) {
                    drawLine(
                        color = Space.Cyan,
                        start = Offset(at.x, at.y - size - unit * 5f),
                        end = Offset(at.x, at.y - size + unit * 3f),
                        strokeWidth = unit * 1.5f,
                        cap = StrokeCap.Round,
                        alpha = facing,
                    )
                }
            }
        }
    }

    /** Saving: two shock waves across the sphere and a burst of sparks. */
    private fun DrawScope.drawLock(radius: Float, progress: Float) {
        val at = Offset(px, py)
        val unit = 1.dp.toPx()
        for (k in 0 until 2) {
            val p = ((progress - k * 0.16f) / (1f - k * 0.16f)).coerceIn(0f, 1f)
            if (p <= 0f) continue
            drawCircle(
                color = lerp(Color.White, Space.Gold, p),
                radius = unit * 8f + (radius * 1.3f - unit * 8f) * easeOut(p),
                center = at,
                alpha = (1f - p) * 0.85f,
                style = Stroke(unit * (3f - 2f * p)),
            )
        }
        val reach = easeOut(progress)
        for (k in 0 until SPARKS) {
            val angle = k * (2f * PI.toFloat() / SPARKS) + 0.4f
            val distance = unit * (34f + 26f * ((k * 7) % 5) / 4f) * reach
            val spark = Offset(at.x + cos(angle) * distance, at.y + sin(angle) * distance)
            val fade = 1f - progress
            val color = if (k % 2 == 0) Space.Gold else Color.White
            drawCircle(color, unit * 6f * fade, spark, alpha = 0.3f * fade)
            drawCircle(color, unit * 2.6f * fade, spark, alpha = fade)
        }
    }

    /** Starting a session: rings of light thrown off by the sphere. */
    private fun DrawScope.drawLaunch(center: Offset, radius: Float, progress: Float, glow: Color) {
        val unit = 1.dp.toPx()
        for (k in 0 until 2) {
            val p = ((progress - k * 0.14f) / (1f - k * 0.14f)).coerceIn(0f, 1f)
            if (p <= 0f) continue
            drawCircle(
                color = lerp(Color.White, glow, p),
                radius = radius * (1.02f + 1.5f * easeOut(p)),
                center = center,
                alpha = (1f - p) * (1f - p) * 0.8f,
                style = Stroke(unit * (4f - 3f * p)),
            )
        }
    }

    /** At rest: a tilted orbit and its satellite, half of it hidden behind the sphere. */
    private fun DrawScope.drawOrbit(center: Offset, radius: Float, time: Float, alpha: Float, front: Boolean) {
        if (alpha <= 0.01f) return
        val unit = 1.dp.toPx()
        val a = radius + unit * 22f
        val b = a * 0.34f
        rotate(degrees = ORBIT_TILT, pivot = center) {
            // Angles grow clockwise from three o'clock: 0..180 is the near half, below the centre.
            drawArc(
                color = Space.Lavender,
                startAngle = if (front) 0f else 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(center.x - a, center.y - b),
                size = Size(a * 2f, b * 2f),
                alpha = alpha * if (front) 0.42f else 0.2f,
                style = Stroke(unit * 1.1f),
            )
            val angle = time * 0.8f
            if ((sin(angle) >= 0f) == front) {
                val at = Offset(center.x + a * cos(angle), center.y + b * sin(angle))
                drawCircle(Space.Cyan, unit * 5.5f, at, alpha = alpha * 0.3f)
                drawCircle(Color.White, unit * 2.4f, at, alpha = alpha)
            }
        }
    }

    /** During a session: a ring that empties as the five minutes run out. */
    private fun DrawScope.drawCountdown(center: Offset, radius: Float, session: Session.Active?, alpha: Float) {
        if (alpha <= 0.01f) return
        val unit = 1.dp.toPx()
        val r = radius + unit * 16f
        drawCircle(Color.White, r, center, alpha = 0.1f * alpha, style = Stroke(unit * 2f))
        if (session == null) return
        val left = (session.endElapsedRealtime - SystemClock.elapsedRealtime()).toFloat() / Session.DURATION_MS
        val fraction = left.coerceIn(0f, 1f)
        // Turned so that both the gradient and the arc start at twelve o'clock.
        rotate(degrees = -90f, pivot = center) {
            drawArc(
                brush = ring!!,
                startAngle = 0f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2f, r * 2f),
                alpha = alpha,
                style = Stroke(unit * 3f, cap = StrokeCap.Round),
            )
            val tip = 2f * PI.toFloat() * fraction
            val at = Offset(center.x + r * cos(tip), center.y + r * sin(tip))
            drawCircle(Space.Gold, unit * 7f, at, alpha = 0.35f * alpha)
            drawCircle(Color.White, unit * 3f, at, alpha = alpha)
        }
    }

    private companion object {
        const val HALO = 1.3f
        const val ORBIT_TILT = -20f
        const val LIGHT_EVERY = 23
        const val SPARKS = 12

        /** 0 → 1 → 0 over the first part of a pulse. */
        fun swell(progress: Float): Float = if (progress >= 0.45f) 0f else sin(PI.toFloat() * progress / 0.45f)

        fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

        /**
         * Meridians and parallels every 30 degrees, as short straight segments on the unit
         * sphere: six floats per segment (x, y, z of each end).
         */
        fun buildGraticule(): FloatArray {
            val segments = ArrayList<Float>()
            fun point(latitude: Int, longitude: Int) {
                val circle = cos(latitude / DEGREES)
                segments += circle * sin(longitude / DEGREES)
                segments += sin(latitude / DEGREES)
                segments += circle * cos(longitude / DEGREES)
            }
            val stride = 6
            for (longitude in -180 until 180 step 30) {
                for (latitude in -84 until 84 step stride) {
                    point(latitude, longitude)
                    point(latitude + stride, longitude)
                }
            }
            for (latitude in -60..60 step 30) {
                for (longitude in -180 until 180 step stride) {
                    point(latitude, longitude)
                    point(latitude, longitude + stride)
                }
            }
            return segments.toFloatArray()
        }
    }
}

/** Degrees per radian. */
private const val DEGREES = 57.29578f
