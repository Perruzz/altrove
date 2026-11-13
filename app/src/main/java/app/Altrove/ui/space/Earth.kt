package app.altrove.ui.space

import java.util.Base64
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Earth as a cloud of dots: points spread evenly over a sphere, kept only where
 * there is land.
 *
 * Each dot is a unit vector, stored as three floats in [xyz]: x points east, y north, and z
 * towards the viewer when the globe is centred on latitude 0, longitude 0.
 */
internal class EarthDots private constructor(val xyz: FloatArray) {

    val count: Int get() = xyz.size / 3

    companion object {
        /**
         * @param samples points tried over the whole sphere; about three in ten fall on land.
         */
        fun build(samples: Int = 9500): EarthDots {
            val land = Base64.getDecoder().decode(EARTH_MASK_BASE64)
            val goldenAngle = (PI * (3.0 - sqrt(5.0))).toFloat()
            val kept = FloatArray(samples * 3)
            var size = 0
            // A Fibonacci lattice: unlike a grid of meridians and parallels, it keeps the dots
            // evenly spaced all the way to the poles.
            for (i in 0 until samples) {
                val y = 1f - 2f * (i + 0.5f) / samples
                val ring = sqrt(1f - y * y)
                val angle = i * goldenAngle
                val x = cos(angle) * ring
                val z = sin(angle) * ring
                val latitude = Math.toDegrees(asin(y).toDouble())
                val longitude = Math.toDegrees(atan2(x, z).toDouble())
                if (isLand(land, latitude, longitude)) {
                    kept[size++] = x
                    kept[size++] = y
                    kept[size++] = z
                }
            }
            return EarthDots(kept.copyOf(size))
        }

        private fun isLand(mask: ByteArray, latitude: Double, longitude: Double): Boolean {
            val row = (90.0 - latitude).toInt().coerceIn(0, EARTH_MASK_HEIGHT - 1)
            val column = (longitude + 180.0).toInt().coerceIn(0, EARTH_MASK_WIDTH - 1)
            val bit = row * EARTH_MASK_WIDTH + column
            return (mask[bit shr 3].toInt() shr (7 - (bit and 7))) and 1 == 1
        }
    }
}
