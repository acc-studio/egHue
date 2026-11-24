package app.eghue.hue

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

data class Xy(val x: Double, val y: Double)

/** Colour conversion between Hue's CIE xy / mirek and display sRGB. Colours are ARGB ints. */
object ColorUtil {
    /** What an "on" light with no colour information looks like. */
    const val WARM_WHITE = 0xFFFFC77E.toInt()

    /** Converts CIE xy to a vivid display colour (brightest channel normalised to 1). */
    fun xyToRgb(xy: Xy): Int {
        val x = xy.x.coerceIn(0.0001, 1.0)
        val y = xy.y.coerceIn(0.0001, 1.0)
        val z = 1.0 - x - y
        val bigY = 1.0
        val bigX = (bigY / y) * x
        val bigZ = (bigY / y) * z
        var r = bigX * 1.656492 - bigY * 0.354851 - bigZ * 0.255038
        var g = -bigX * 0.707196 + bigY * 1.655397 + bigZ * 0.036152
        var b = bigX * 0.051713 - bigY * 0.121364 + bigZ * 1.011530
        r = gamma(r.coerceAtLeast(0.0)); g = gamma(g.coerceAtLeast(0.0)); b = gamma(b.coerceAtLeast(0.0))
        val m = max(r, max(g, b))
        if (m > 0) { r /= m; g /= m; b /= m }
        return argb(r, g, b)
    }

    /** Converts a display colour to CIE xy for a Hue colour command. */
    fun rgbToXy(argb: Int): Xy {
        val r = inverseGamma(((argb shr 16) and 0xFF) / 255.0)
        val g = inverseGamma(((argb shr 8) and 0xFF) / 255.0)
        val b = inverseGamma((argb and 0xFF) / 255.0)
        val bigX = r * 0.664511 + g * 0.154324 + b * 0.162028
        val bigY = r * 0.283881 + g * 0.668433 + b * 0.047685
        val bigZ = r * 0.000088 + g * 0.072310 + b * 0.986039
        val sum = bigX + bigY + bigZ
        if (sum <= 0.0) return Xy(0.3227, 0.329) // D65-ish white
        return Xy(bigX / sum, bigY / sum)
    }

    /** Colour temperature (mirek) to display colour, after Tanner Helland's approximation. */
    fun mirekToRgb(mirek: Int): Int {
        val kelvin = 1_000_000.0 / mirek.coerceIn(100, 1000)
        val t = kelvin / 100.0
        val r = if (t <= 66) 255.0 else (329.698727446 * (t - 60).pow(-0.1332047592)).coerceIn(0.0, 255.0)
        val g = if (t <= 66) (99.4708025861 * ln(t) - 161.1195681661).coerceIn(0.0, 255.0)
        else (288.1221695283 * (t - 60).pow(-0.0755148492)).coerceIn(0.0, 255.0)
        val b = when {
            t >= 66 -> 255.0
            t <= 19 -> 0.0
            else -> (138.5177312231 * ln(t - 10) - 305.0447927307).coerceIn(0.0, 255.0)
        }
        return argb(r / 255.0, g / 255.0, b / 255.0)
    }

    /** Average of several display colours (simple RGB mean, good enough for a tile tint). */
    fun average(colors: List<Int>): Int {
        if (colors.isEmpty()) return WARM_WHITE
        var r = 0.0; var g = 0.0; var b = 0.0
        for (c in colors) {
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
        }
        val n = colors.size
        return argb(r / n / 255.0, g / n / 255.0, b / n / 255.0)
    }

    /** Relative luminance 0..1, used to pick black or white text over a colour. */
    fun luminance(argb: Int): Double {
        val r = inverseGamma(((argb shr 16) and 0xFF) / 255.0)
        val g = inverseGamma(((argb shr 8) and 0xFF) / 255.0)
        val b = inverseGamma((argb and 0xFF) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** True if dark text has better WCAG contrast on [argb] than white text. */
    fun prefersDarkText(argb: Int): Boolean {
        val l = luminance(argb)
        val onWhite = 1.05 / (l + 0.05)
        val onDark = (l + 0.05) / (0.012 + 0.05)
        return onDark >= onWhite
    }

    /** Linear blend of two colours; t=0 gives [a], t=1 gives [b]. */
    fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun mix(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun gamma(v: Double) = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
    private fun inverseGamma(v: Double) = if (v > 0.04045) ((v + 0.055) / 1.055).pow(2.4) else v / 12.92
    private fun argb(r: Double, g: Double, b: Double): Int {
        fun c(v: Double) = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }
}
