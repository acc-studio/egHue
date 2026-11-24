package app.eghue.update

object Versions {
    /** True if semantic version [candidate] (e.g. "1.4.0") is newer than [current] (suffixes like "-debug" ignored). */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate); val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 }
}
