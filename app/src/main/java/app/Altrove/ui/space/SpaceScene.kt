package app.altrove.ui.space

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import app.altrove.Coordinates
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Everything that moves behind and around the form: the starfield, the globe and the
 * one-off flourishes (arriving, saving, leaving for elsewhere, coming back).
 *
 * The stars and the globe are simulated by hand, once per frame, in plain arrays and fields;
 * only [time] is Compose state, and it is read while drawing, so a frame costs a redraw of two
 * canvases and never a recomposition.
 *
 * @param calm true when the system has animations turned off: nothing drifts or streaks, the
 *   globe jumps straight to where it should be, and the flourishes are skipped.
 */
@Stable
internal class SpaceScene(val calm: Boolean) {
    val stars = Starfield(count = 230, seed = 7)
    val globe = GlobeMotion()
    val meteor = Meteor(seed = 11)

    /** 0 while drifting, 1 at full warp. */
    val warp = Animatable(0f)

    /** The entrance, 0 to 1, once per launch. */
    val intro = Animatable(0f)

    /** One-shot effects. Each runs from 0 to 1 and rests at 1. */
    val savePulse = Animatable(1f)
    val launchPulse = Animatable(1f)

    /** A brief wash of light at the peak of the warp. */
    val flash = Animatable(0f)

    /** Seconds since the scene started. Written once per frame; read it only while drawing. */
    var time by mutableFloatStateOf(0f)
        private set

    /**
     * Where the stars fly out from, in root coordinates: the centre of the globe. Unspecified
     * until the globe has been laid out.
     */
    var vanishingPoint: Offset = Offset.Unspecified

    /** The simulation loop. Runs for as long as the screen is on show. */
    suspend fun run() {
        val start = withFrameNanos { it }
        var last = start
        while (true) {
            val now = withFrameNanos { it }
            // Clamped, so a hiccup (or coming back from the background) never causes a jump.
            val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
            last = now
            val seconds = (now - start) / 1e9f
            if (calm) {
                globe.settle()
            } else {
                stars.step(dt, starSpeed())
                globe.step(dt, seconds)
                meteor.step(seconds)
            }
            time = seconds
        }
    }

    /** How fast the stars rush past, in depths per second. */
    fun starSpeed(): Float {
        val w = warp.value
        return CRUISE_SPEED + (WARP_SPEED - CRUISE_SPEED) * w * w
    }

    /** Dropping out of warp into the scene, the globe sweeping round to its destination. */
    suspend fun playIntro() = coroutineScope {
        globe.stiffness = GlobeMotion.SOFT
        globe.windUp()
        launch {
            warp.snapTo(1f)
            warp.animateTo(0f, tween(1500, easing = CubicBezierEasing(0.15f, 0.6f, 0.15f, 1f)))
        }
        launch {
            intro.snapTo(0f)
            intro.animateTo(1f, tween(1500, easing = LinearEasing))
            globe.stiffness = GlobeMotion.FIRM
        }
    }

    /** Straight to the settled scene, with no entrance. */
    suspend fun skipIntro() {
        warp.snapTo(0f)
        intro.snapTo(1f)
        globe.stiffness = GlobeMotion.FIRM
        globe.settle()
    }

    /** A destination was locked in. */
    suspend fun playSave() {
        savePulse.snapTo(0f)
        savePulse.animateTo(1f, tween(1300, easing = LinearEasing))
    }

    /** The jump to elsewhere. */
    suspend fun playLaunch() = coroutineScope {
        launch {
            launchPulse.snapTo(0f)
            launchPulse.animateTo(1f, tween(1700, easing = LinearEasing))
        }
        if (calm) return@coroutineScope
        launch {
            warp.animateTo(1f, tween(420, easing = FastOutLinearInEasing))
            delay(240)
            warp.animateTo(0f, tween(1200, easing = LinearOutSlowInEasing))
        }
        launch {
            delay(300)
            flash.animateTo(0.5f, tween(120))
            flash.animateTo(0f, tween(700, easing = LinearOutSlowInEasing))
        }
    }

    /** Back to the real position: a shorter, gentler echo of the launch. */
    suspend fun playReturn() {
        if (calm) return
        warp.animateTo(0.5f, tween(240, easing = FastOutLinearInEasing))
        warp.animateTo(0f, tween(900, easing = LinearOutSlowInEasing))
    }

    private companion object {
        const val CRUISE_SPEED = 0.012f
        const val WARP_SPEED = 1.7f
    }
}

/**
 * Stars in front of the viewer, flown through at a variable speed. Slowly, it is a gentle
 * parallax drift; fast, every star stretches into a streak: the same model does both the
 * ambient sky and the jump to warp.
 *
 * Positions are in focal lengths: a star at (x, y, z) appears x / z to the right of the
 * vanishing point and y / z below it. z runs from 1 (far) down to [NEAR].
 */
internal class Starfield(val count: Int, seed: Int) {
    private val random = Random(seed)
    val x = FloatArray(count)
    val y = FloatArray(count)
    val z = FloatArray(count) { 1f }
    val magnitude = FloatArray(count)
    val phase = FloatArray(count)
    val age = FloatArray(count)
    val tint = IntArray(count)

    // How far the screen reaches from the vanishing point, in focal lengths.
    private var reachX = 0.5f
    private var reachUp = 0.4f
    private var reachDown = 1.4f
    private var scattered = false

    /** Called by whoever draws, once the size of the screen is known (and whenever it changes). */
    fun setReach(x: Float, up: Float, down: Float) {
        reachX = x
        reachUp = up
        reachDown = down
        if (!scattered) {
            scattered = true
            for (i in 0 until count) spawn(i, far = false)
        }
    }

    /** @param far true for a star entering at the horizon, false to place it at any depth. */
    private fun spawn(i: Int, far: Boolean) {
        val depth = if (far) 1f else NEAR + random.nextFloat() * (1f - NEAR)
        z[i] = depth
        // Uniform over the screen once projected, whatever the depth.
        x[i] = (random.nextFloat() * 2f - 1f) * reachX * depth
        y[i] = (-reachUp + random.nextFloat() * (reachUp + reachDown)) * depth
        // Mostly faint, a few bright ones.
        val r = random.nextFloat()
        magnitude[i] = 0.55f + r * r * r * 1.9f
        phase[i] = random.nextFloat() * 6.2832f
        age[i] = if (far) 0f else 10f
        tint[i] = when (random.nextInt(100)) {
            in 0..8 -> TINT_WARM
            in 9..26 -> TINT_VIOLET
            else -> TINT_WHITE
        }
    }

    fun step(dt: Float, speed: Float) {
        if (!scattered) return
        for (i in 0 until count) {
            val depth = z[i] - speed * dt
            z[i] = depth
            age[i] += dt
            if (depth < NEAR) {
                spawn(i, far = true)
                continue
            }
            val px = x[i] / depth
            val py = y[i] / depth
            if (abs(px) > reachX * EDGE || py < -reachUp * EDGE || py > reachDown * EDGE) spawn(i, far = true)
        }
    }

    companion object {
        const val NEAR = 0.06f
        const val TINT_WHITE = 0
        const val TINT_VIOLET = 1
        const val TINT_WARM = 2
        private const val EDGE = 1.06f
    }
}

/** A shooting star, every now and then. All positions are fractions of the screen. */
internal class Meteor(seed: Int) {
    private val random = Random(seed)
    private var nextAt = 4f + random.nextFloat() * 4f

    var startedAt = -100f
        private set
    var fromX = 0f
        private set
    var fromY = 0f
        private set

    /** Horizontal direction of travel: 1 to the right, -1 to the left. */
    var heading = 1f
        private set
    var slope = 0.4f
        private set

    fun step(time: Float) {
        if (time < nextAt) return
        startedAt = time
        heading = if (random.nextBoolean()) 1f else -1f
        fromX = if (heading > 0f) random.nextFloat() * 0.5f else 0.5f + random.nextFloat() * 0.5f
        fromY = 0.03f + random.nextFloat() * 0.3f
        slope = 0.28f + random.nextFloat() * 0.3f
        nextAt = time + 7f + random.nextFloat() * 9f
    }

    companion object {
        const val DURATION = 0.85f
    }
}

/**
 * Where the globe is looking, and how it gets there.
 *
 * [latitude] and [longitude] are the point at the centre of the disc. With a [target] the
 * view is pulled to it by a damped spring (and then sways a little, so it never looks frozen);
 * without one the globe just turns. A finger can spin it; it drifts back after a moment.
 */
internal class GlobeMotion {
    var latitude = IDLE_TILT
        private set
    var longitude = -40f
        private set

    var target: Coordinates? = null

    /** Spring constant, in 1/s². [SOFT] for the sweeping entrance, [FIRM] afterwards. */
    var stiffness = FIRM

    var dragging = false
    private var latitudeVelocity = 0f
    private var longitudeVelocity = 0f
    private var fling = 0f
    private var coast = 0f

    fun step(dt: Float, time: Float) {
        if (dragging) return
        if (abs(fling) > 3f) {
            // Spun by a finger: keep turning, losing speed.
            longitude = wrap(longitude + fling * dt)
            fling *= exp(-2.6f * dt)
            coast = COAST_SECONDS
            return
        }
        if (coast > 0f) {
            coast -= dt
            return
        }
        val goal = target
        if (goal == null) {
            longitudeVelocity += (IDLE_SPIN - longitudeVelocity) * (2f * dt).coerceAtMost(1f)
            longitude = wrap(longitude + longitudeVelocity * dt)
            latitudeVelocity += (-(latitude - IDLE_TILT) * 5f - latitudeVelocity * 3.5f) * dt
            latitude += latitudeVelocity * dt
            return
        }
        val goalLongitude = goal.longitude.toFloat() + 3f * sin(time * 0.31f)
        val goalLatitude = (goal.latitude.toFloat() + 1.5f * sin(time * 0.23f + 1.3f)).coerceIn(-88f, 88f)
        val damping = 2f * sqrt(stiffness) * 0.84f
        longitudeVelocity += (-stiffness * wrap(longitude - goalLongitude) - damping * longitudeVelocity) * dt
        longitude = wrap(longitude + longitudeVelocity * dt)
        latitudeVelocity += (-stiffness * (latitude - goalLatitude) - damping * latitudeVelocity) * dt
        latitude = (latitude + latitudeVelocity * dt).coerceIn(-89f, 89f)
    }

    /** Puts the view a good half turn away from its target, for the entrance to sweep back. */
    fun windUp() {
        val goal = target ?: return
        longitude = wrap(goal.longitude.toFloat() - 205f)
        latitude = (goal.latitude.toFloat() * 0.3f + 28f).coerceIn(-70f, 70f)
        longitudeVelocity = 0f
        latitudeVelocity = 0f
    }

    /** Jumps to the target with no animation. */
    fun settle() {
        val goal = target ?: return
        longitude = goal.longitude.toFloat()
        latitude = goal.latitude.toFloat().coerceIn(-89f, 89f)
        longitudeVelocity = 0f
        latitudeVelocity = 0f
    }

    fun dragBy(degrees: Float) {
        longitude = wrap(longitude + degrees)
        longitudeVelocity = 0f
        latitudeVelocity = 0f
    }

    fun release(degreesPerSecond: Float) {
        dragging = false
        fling = degreesPerSecond.coerceIn(-720f, 720f)
        coast = COAST_SECONDS
    }

    companion object {
        const val SOFT = 6.5f
        const val FIRM = 30f
        const val IDLE_TILT = 16f
        const val IDLE_SPIN = 9f
        const val COAST_SECONDS = 1.1f

        /** Brings an angle in degrees back into -180..180. */
        fun wrap(degrees: Float): Float {
            var d = degrees % 360f
            if (d >= 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }
    }
}
