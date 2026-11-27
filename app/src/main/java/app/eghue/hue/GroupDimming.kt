package app.eghue.hue

/**
 * Pro-rata dimming for a room or zone: moving the room's brightness scales every light by the same
 * factor, so a 50% and a 100% light at "75%" become 40% and 80% at "60%", rather than both 60%.
 */
object GroupDimming {
    private const val MIN = 1f
    private const val MAX = 100f

    /** True if [levels] are far enough apart that one shared brightness would flatten them. */
    fun differs(levels: Collection<Float>): Boolean =
        levels.size >= 2 && (levels.max() - levels.min()) >= 1f

    /**
     * Brightness per light so their average is [target] while keeping the ratios of [baseline].
     * Lights that hit 1% or 100% stop there and the rest keep moving, so the average still lands
     * on [target]. Values are rounded to 0.1, as the bridge takes them.
     */
    fun scale(baseline: Map<String, Float>, target: Float): Map<String, Float> {
        if (baseline.isEmpty()) return emptyMap()
        val t = target.coerceIn(MIN, MAX)
        val base = baseline.mapValues { it.value.coerceAtLeast(0.1f) }
        fun at(k: Float) = base.mapValues { (it.value * k).coerceIn(MIN, MAX) }
        fun avg(k: Float) = at(k).values.average().toFloat()
        // avg(k) only grows with k, so bisect for the factor that hits the target.
        var lo = 0f
        var hi = MAX / base.values.min()
        repeat(40) {
            val mid = (lo + hi) / 2
            if (avg(mid) < t) lo = mid else hi = mid
        }
        return at(hi).mapValues { kotlin.math.round(it.value * 10).coerceIn(10f, 1000f) / 10f }
    }
}
