package app.altrove

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs

/** A point on Earth, in decimal degrees. */
data class Coordinates(val latitude: Double, val longitude: Double) {

    /** Compact text such as "41.8902, 12.4922": dot as decimal separator, no trailing zeros. */
    fun format(decimals: Int = 5): String = "${latitude.trimmed(decimals)}, ${longitude.trimmed(decimals)}"

    /**
     * Two short lines for the widget, which can be very narrow: "41.8902° N" over "12.4922° E".
     * A hemisphere letter replaces the sign, and a no-break space keeps each line in one piece.
     */
    fun formatStacked(): String = axis(latitude, 'N', 'S') + "\n" + axis(longitude, 'E', 'W')

    private fun axis(value: Double, positive: Char, negative: Char): String {
        val magnitude = abs(value)
        val decimals = if (magnitude >= 100) 3 else 4 // Same width with two or three integer digits.
        val hemisphere = if (value < 0) negative else positive
        return String.format(Locale.US, "%.${decimals}f° %c", magnitude, hemisphere)
    }

    companion object {
        private val NUMBER = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)""")
        private val PAIR = Regex("""\s*([+-]?\d+\.\d+)\s*[,;\s]\s*([+-]?\d+\.\d+)\s*""")

        fun parseLatitude(text: String): Double? = parse(text)?.takeIf { it in -90.0..90.0 }

        fun parseLongitude(text: String): Double? = parse(text)?.takeIf { it in -180.0..180.0 }

        /**
         * Recognises a whole "latitude, longitude" pair, the way Google Maps copies it, so that
         * pasting it into one field can fill both. Both numbers must have a decimal point, which
         * keeps a latitude typed with a decimal comma ("41,89") from being read as a pair.
         */
        fun splitPair(text: String): Pair<String, String>? {
            val match = PAIR.matchEntire(text.replace('−', '-')) ?: return null
            val (latitude, longitude) = match.destructured
            if (parseLatitude(latitude) == null || parseLongitude(longitude) == null) return null
            return latitude to longitude
        }

        /** The text as it should be stored: trimmed, with a dot as decimal separator. */
        fun normalize(text: String): String = text.trim().replace(',', '.').replace('−', '-')

        private fun parse(text: String): Double? {
            val normalized = normalize(text)
            // toDouble() alone would also accept "NaN", "Infinity", "1e3" or "0x1p3".
            if (!NUMBER.matches(normalized)) return null
            return normalized.toDoubleOrNull()
        }

        private fun Double.trimmed(decimals: Int): String {
            val rounded = BigDecimal.valueOf(this).setScale(decimals, RoundingMode.HALF_UP)
            return if (rounded.signum() == 0) "0" else rounded.stripTrailingZeros().toPlainString()
        }
    }
}
