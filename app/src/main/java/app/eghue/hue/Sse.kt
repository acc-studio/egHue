package app.eghue.hue

/** Minimal Server-Sent Events line parser: feed lines, get each event's data when it completes. */
class SseParser {
    private val data = StringBuilder()

    /** Returns the event's data when [line] terminates an event, otherwise null. */
    fun feed(line: String): String? {
        if (line.isEmpty()) {
            if (data.isEmpty()) return null
            val out = data.toString()
            data.setLength(0)
            return out
        }
        if (line.startsWith(":")) return null // comment / keep-alive
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        if (field == "data") {
            if (data.isNotEmpty()) data.append('\n')
            data.append(value)
        }
        return null
    }
}
