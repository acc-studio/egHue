package app.eghue.hue

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Automations are stored on the bridge as (v1) schedules, so they run with every phone off.
 * egHue tags its own in the description ("eghue:<kind>") so it can show and edit them properly.
 */
enum class AutoKind(val title: String) {
    /** Lights fade up to the wake time ("sunrise"). */
    WAKE("Wake up"),
    /** Lights fade out at bedtime. */
    BEDTIME("Bedtime"),
    /** A one-off countdown: switch off after N minutes. */
    TIMER("Sleep timer"),
    /** Something happens at a time: on, off or a scene. */
    AT_TIME("At a time"),
    /** A schedule made by another app. */
    OTHER("Schedule"),
}

sealed interface AutoAction {
    data object Off : AutoAction
    /** [brightness] in percent, [mirek] optional white. */
    data class On(val brightness: Int = 100, val mirek: Int? = null) : AutoAction
    /** [sceneV1] is the scene's legacy id without "/scenes/". */
    data class Scene(val sceneV1: String) : AutoAction
}

sealed interface AutoWhen {
    /** Repeats on [days] (empty means once, next time [time] comes round). */
    data class Daily(val time: LocalTime, val days: Set<DayOfWeek>) : AutoWhen
    data class In(val minutes: Int) : AutoWhen
}

/** What an automation does, as the editor builds it. */
data class AutoSpec(
    val kind: AutoKind,
    /** Legacy address of the room/zone ("/groups/3") or light ("/lights/2"); "/groups/0" is the whole home. */
    val targetV1: String,
    val targetName: String,
    val action: AutoAction,
    val whenever: AutoWhen,
    /** Fade length: wake-up fades in over this, bedtime fades out over it. */
    val fadeMinutes: Int = 0,
)

/** An automation as listed. [spec] is null for schedules egHue can't describe (made elsewhere). */
data class Automation(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val kind: AutoKind,
    val spec: AutoSpec?,
    /** For timers: when it goes off. */
    val endsAt: Instant? = null,
)

object Automations {
    const val TAG = "eghue:"
    /** v1 transitiontime is 1/10 s in a 16-bit field: at most ~109 minutes. */
    const val MAX_FADE_MINUTES = 60

    private val dayBits = linkedMapOf(
        DayOfWeek.MONDAY to 64, DayOfWeek.TUESDAY to 32, DayOfWeek.WEDNESDAY to 16, DayOfWeek.THURSDAY to 8,
        DayOfWeek.FRIDAY to 4, DayOfWeek.SATURDAY to 2, DayOfWeek.SUNDAY to 1,
    )
    private val hms = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun bitmask(days: Set<DayOfWeek>): Int = days.sumOf { dayBits.getValue(it) }
    fun daysOf(mask: Int): Set<DayOfWeek> = dayBits.filterValues { mask and it != 0 }.keys

    /** The schedule body for [spec]. [now] decides the date of a one-off. */
    fun toJson(spec: AutoSpec, appKey: String, now: LocalDateTime = LocalDateTime.now()): JsonObject {
        val fade = spec.fadeMinutes.coerceIn(0, MAX_FADE_MINUTES)
        val isGroup = spec.targetV1.startsWith("/groups/")
        val address = "/api/$appKey${spec.targetV1}/${if (isGroup) "action" else "state"}"
        val body = buildJsonObject {
            when (val a = spec.action) {
                AutoAction.Off -> put("on", false)
                is AutoAction.On -> {
                    put("on", true)
                    put("bri", (a.brightness.coerceIn(1, 100) * 2.54).roundToInt().coerceIn(1, 254))
                    a.mirek?.let { put("ct", it) }
                }
                is AutoAction.Scene -> put("scene", a.sceneV1)
            }
            if (fade > 0) put("transitiontime", fade * 600)
        }
        // A wake-up starts its fade early so the lights are fully up at the wake time.
        val lead = if (spec.kind == AutoKind.WAKE) fade else 0
        val localtime = when (val w = spec.whenever) {
            is AutoWhen.In -> "PT%02d:%02d:00".format(w.minutes / 60, w.minutes % 60)
            is AutoWhen.Daily -> {
                val start = w.time.minusMinutes(lead.toLong())
                val wraps = start > w.time // crossed midnight: start the day before
                if (w.days.isEmpty()) {
                    var at = now.toLocalDate().atTime(w.time)
                    if (!at.isAfter(now)) at = at.plusDays(1)
                    at.minusMinutes(lead.toLong()).withNano(0).toString().let { if (it.length == 16) "$it:00" else it }
                } else {
                    val days = if (wraps) w.days.map { it.minus(1) }.toSet() else w.days
                    "W%03d/T%s".format(bitmask(days), start.format(hms))
                }
            }
        }
        return buildJsonObject {
            put("name", "egHue ${spec.kind.title} ${spec.targetName}".take(32))
            put("description", "$TAG${spec.kind.name.lowercase()}".take(64))
            putJsonObject("command") {
                put("address", address)
                put("method", "PUT")
                put("body", body)
            }
            put("localtime", localtime)
            put("status", "enabled")
            if (spec.whenever is AutoWhen.In || (spec.whenever as? AutoWhen.Daily)?.days?.isEmpty() == true) put("autodelete", true)
        }
    }

    /** Reads one schedule from GET /schedules. [names] maps legacy addresses to display names. */
    fun parse(id: String, o: JsonObject, names: (String) -> String?): Automation {
        val name = o.str("name") ?: "Schedule"
        val enabled = o.str("status") != "disabled"
        val kind = o.str("description")?.takeIf { it.startsWith(TAG) }?.removePrefix(TAG)
            ?.let { d -> AutoKind.entries.firstOrNull { it.name.lowercase() == d } }
        val spec = runCatching { specOf(o, kind ?: AutoKind.OTHER, names) }.getOrNull()
        val lt = o.str("localtime") ?: o.str("time").orEmpty()
        val endsAt = if (lt.startsWith("PT")) {
            val start = o.str("starttime")?.let { runCatching { LocalDateTime.parse(it).toInstant(ZoneOffset.UTC) }.getOrNull() }
            val dur = parseDuration(lt.removePrefix("PT"))
            if (start != null && dur != null) start.plus(dur) else null
        } else null
        return Automation(id, name, enabled, kind ?: AutoKind.OTHER, spec, endsAt)
    }

    private fun specOf(o: JsonObject, kind: AutoKind, names: (String) -> String?): AutoSpec? {
        val cmd = o.obj("command") ?: return null
        val address = cmd.str("address") ?: return null
        val m = Regex("^/api/[^/]+(/(groups|lights)/\\d+)/(action|state)$").find(address) ?: return null
        val target = m.groupValues[1]
        val body = cmd.obj("body") ?: return null
        val fade = ((body.int("transitiontime") ?: 0) / 600.0).roundToInt()
        val action = when {
            body.str("scene") != null -> AutoAction.Scene(body.str("scene")!!)
            body.bool("on") == false -> AutoAction.Off
            body.bool("on") == true || body.int("bri") != null ->
                AutoAction.On(((body.int("bri") ?: 254) / 2.54).roundToInt().coerceIn(1, 100), body.int("ct"))
            else -> return null
        }
        val lt = o.str("localtime") ?: return null
        val lead = if (kind == AutoKind.WAKE) fade.toLong() else 0L
        val whenever: AutoWhen = when {
            lt.startsWith("PT") -> {
                val d = parseDuration(lt.removePrefix("PT")) ?: return null
                AutoWhen.In(d.toMinutes().toInt())
            }
            lt.startsWith("W") -> {
                val mask = lt.substring(1, lt.indexOf('/')).toInt()
                val start = LocalTime.parse(lt.substringAfter("/T").take(8))
                val time = start.plusMinutes(lead)
                val wrapped = time < start
                val days = daysOf(mask).let { d -> if (wrapped) d.map { it.plus(1) }.toSet() else d }
                AutoWhen.Daily(time, days)
            }
            lt.length >= 19 && lt[4] == '-' -> AutoWhen.Daily(LocalDateTime.parse(lt.take(19)).toLocalTime().plusMinutes(lead), emptySet())
            else -> return null
        }
        return AutoSpec(kind, target, names(target) ?: target, action, whenever, fade)
    }

    /** "HH:MM:SS" to a duration. */
    fun parseDuration(s: String): Duration? {
        val p = s.take(8).split(':').mapNotNull { it.toIntOrNull() }
        if (p.size != 3) return null
        return Duration.ofHours(p[0].toLong()).plusMinutes(p[1].toLong()).plusSeconds(p[2].toLong())
    }

    /** "Weekdays", "Every day", "Mon, Wed", "Once". */
    fun describeDays(days: Set<DayOfWeek>): String {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        return when {
            days.isEmpty() -> "Once"
            days.size == 7 -> "Every day"
            days == weekdays -> "Weekdays"
            days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Weekends"
            else -> dayBits.keys.filter { it in days }.joinToString(", ") {
                it.name.take(3).lowercase().replaceFirstChar(Char::uppercase)
            }
        }
    }
}
