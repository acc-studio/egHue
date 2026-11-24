package app.eghue.hue

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt

/*
 * Remote (switch) button behaviour.
 *
 * The bridge runs switch buttons through its rule engine ("/api/<key>/rules"): each rule watches a
 * switch's `buttonevent` (e.g. 1000 = button 1 pressed) and sends commands to a room or light.
 * This is the format the Hue app uses for dimmer switches set up on the bridge, and the bridge
 * executes it by itself, so remotes keep working with every app closed.
 *
 * egHue reads a remote's rules into simple per-button actions. Anything it doesn't recognise is
 * shown as "Keep current" and left untouched. On save, only buttons the user changed are rewritten.
 */

enum class Gesture { PRESS, HOLD }

sealed interface ButtonAction {
    data object Nothing : ButtonAction
    data object Toggle : ButtonAction
    data object On : ButtonAction
    data object Off : ButtonAction
    data object AllOff : ButtonAction
    /** Press: one step brighter. Hold: keeps brightening while held. */
    data object Brighter : ButtonAction
    data object Dimmer : ButtonAction
    data class Scene(val sceneId: String) : ButtonAction
    data class Cycle(val sceneIds: List<String>) : ButtonAction
    /** Existing behaviour egHue doesn't model; kept exactly as it is. */
    data class Keep(val description: String) : ButtonAction

    /** Whether this action acts on the remote's target room/zone. */
    val usesTarget: Boolean get() = this is Toggle || this is On || this is Off || this is Brighter ||
        this is Dimmer || this is Scene || this is Cycle
}

data class ButtonKey(val button: Int, val gesture: Gesture)

/** A remote's button behaviour as egHue understands it, plus the rules it came from. */
data class RemoteSetup(
    /** v2 room/zone id the buttons control (null if none/unknown). */
    val targetGroupId: String?,
    val actions: Map<ButtonKey, ButtonAction>,
    /** Rule ids per button/gesture, so a change replaces exactly those rules. */
    val ruleIds: Map<ButtonKey, List<String>> = emptyMap(),
    /** Memory sensor (e.g. "/sensors/4") used for scene cycling, per button. */
    val cycleSensors: Map<Int, String> = emptyMap(),
    /** Timer rules that reset a cycle memory sensor, keyed by that sensor. */
    val resetRules: Map<String, List<String>> = emptyMap(),
) {
    fun action(button: Int, gesture: Gesture): ButtonAction = actions[ButtonKey(button, gesture)] ?: ButtonAction.Nothing
}

/** Lookups between the v2 world (ids the UI uses) and legacy v1 ids used by rules. */
class V1Index(home: HomeUi) {
    private val groupToV1 = (home.rooms + home.zones).mapNotNull { g -> g.idV1?.let { g.id to it } }.toMap()
    private val v1ToGroup = groupToV1.entries.associate { (k, v) -> v to k }
    private val sceneToV1 = home.scenes.values.flatten().mapNotNull { s -> s.idV1?.let { s.id to it.removePrefix("/scenes/") } }.toMap()
    private val v1ToScene = sceneToV1.entries.associate { (k, v) -> v to k }
    val lightV1 = home.lights.mapNotNull { l -> l.idV1?.let { l.id to it } }.toMap()

    fun groupV1(groupId: String): String? = groupToV1[groupId]
    fun groupFromV1(path: String): String? = v1ToGroup[path]
    fun sceneV1(sceneId: String): String? = sceneToV1[sceneId]
    fun sceneFromV1(v1: String): String? = v1ToScene[v1]
}

object RemoteRules {
    private val groupAction = Regex("^(/groups/\\d+)/action$")
    private val lightState = Regex("^/lights/\\d+/state$")
    private val sensorState = Regex("^(/sensors/\\d+)/state$")

    /** Reads [rules] (the bridge's `/rules` object) into a [RemoteSetup] for the switch [sensor]. */
    fun parse(sensor: String, rules: JsonObject, index: V1Index): RemoteSetup {
        val mine = rules.mapNotNull { (id, r) ->
            val rule = r as? JsonObject ?: return@mapNotNull null
            val conds = rule.arr("conditions")?.objects().orEmpty()
            if (conds.none { it.str("address")?.startsWith("$sensor/state/") == true }) return@mapNotNull null
            id to rule
        }
        val byKey = LinkedHashMap<ButtonKey, MutableList<Pair<String, JsonObject>>>()
        val resetRules = HashMap<String, MutableList<String>>()
        for ((id, rule) in mine) {
            val code = rule.arr("conditions")?.objects()
                ?.firstOrNull { it.str("address") == "$sensor/state/buttonevent" && it.str("operator") == "eq" }
                ?.str("value")?.toIntOrNull()
            if (code == null) {
                // E.g. "10 s after the last press, reset the scene-cycle counter".
                val target = rule.arr("actions")?.objects()?.firstNotNullOfOrNull { a ->
                    sensorState.find(a.str("address") ?: "")?.groupValues?.get(1)
                }
                if (target != null) resetRules.getOrPut(target) { mutableListOf() } += id
                continue
            }
            val gesture = when (code % 1000) { 0, 2 -> Gesture.PRESS; 1, 3 -> Gesture.HOLD; else -> continue }
            byKey.getOrPut(ButtonKey(code / 1000, gesture)) { mutableListOf() } += id to rule
        }

        val groups = HashMap<String, Int>()
        val actions = LinkedHashMap<ButtonKey, ButtonAction>()
        val cycleSensors = HashMap<Int, String>()
        for ((key, list) in byKey) {
            val (action, group, memory) = classify(list.map { it.second }, key.gesture, index)
            actions[key] = action
            if (group != null && group != "/groups/0") groups.merge(group, 1, Int::plus)
            if (memory != null) cycleSensors[key.button] = memory
        }
        val target = groups.maxByOrNull { it.value }?.key?.let(index::groupFromV1)
        return RemoteSetup(
            targetGroupId = target,
            actions = actions,
            ruleIds = byKey.mapValues { (_, l) -> l.map { it.first } },
            cycleSensors = cycleSensors,
            resetRules = resetRules,
        )
    }

    private data class Classified(val action: ButtonAction, val group: String?, val memory: String?)

    private fun classify(rules: List<JsonObject>, gesture: Gesture, index: V1Index): Classified {
        val conds = rules.map { it.arr("conditions")?.objects().orEmpty() }
        val acts = rules.map { it.arr("actions")?.objects().orEmpty() }
        fun groupOf(a: JsonObject) = groupAction.find(a.str("address") ?: "")?.groupValues?.get(1)
        val firstGroup = acts.flatten().firstNotNullOfOrNull(::groupOf)

        // Scene cycling: rules keyed on a memory sensor's status.
        val memory = conds.flatten().firstNotNullOfOrNull { c ->
            val a = c.str("address") ?: return@firstNotNullOfOrNull null
            if (a.endsWith("/state/status") && a.startsWith("/sensors/")) a.removeSuffix("/state/status") else null
        }
        if (memory != null) {
            val ordered = rules.indices.sortedBy { i ->
                conds[i].firstOrNull { it.str("address") == "$memory/state/status" }?.str("value")?.toIntOrNull() ?: 0
            }
            val scenes = ordered.map { i ->
                acts[i].firstNotNullOfOrNull { a -> a.obj("body")?.str("scene")?.let(index::sceneFromV1) }
            }
            return if (scenes.all { it != null }) Classified(ButtonAction.Cycle(scenes.filterNotNull()), firstGroup, memory)
            else Classified(ButtonAction.Keep("Cycles through ${rules.size} light settings"), firstGroup, memory)
        }

        // Toggle: one rule turns on when nothing is on, the other turns off when something is.
        if (rules.size == 2) {
            val anyOn = conds.map { cs -> cs.firstOrNull { it.str("address")?.endsWith("/state/any_on") == true }?.str("value") }
            if (anyOn.toSet() == setOf("true", "false")) return Classified(ButtonAction.Toggle, firstGroup, null)
        }

        if (rules.size != 1) return Classified(ButtonAction.Keep("Custom (${rules.size} rules)"), firstGroup, null)
        val a = acts.single()
        // egHue's "switch on with power-on settings" writes one command per light.
        if (a.isNotEmpty() && a.all { lightState.matches(it.str("address") ?: "") && it.obj("body")?.bool("on") == true }) {
            return Classified(ButtonAction.On, null, null)
        }
        if (a.size != 1) return Classified(ButtonAction.Keep("Custom"), firstGroup, null)
        val group = groupOf(a.single()) ?: return Classified(ButtonAction.Keep("Custom"), null, null)
        val body = a.single().obj("body") ?: return Classified(ButtonAction.Keep("Custom"), group, null)
        val briInc = body.int("bri_inc")
        val action = when {
            body.str("scene") != null -> index.sceneFromV1(body.str("scene")!!)?.let { ButtonAction.Scene(it) }
                ?: ButtonAction.Keep("Recalls a scene")
            briInc != null && briInc > 0 -> ButtonAction.Brighter
            briInc != null && briInc < 0 -> ButtonAction.Dimmer
            body.bool("on") == false -> if (group == "/groups/0") ButtonAction.AllOff else ButtonAction.Off
            body.bool("on") == true && body.keys.all { it == "on" || it == "transitiontime" } -> ButtonAction.On
            body.bool("on") == true -> ButtonAction.Keep("Turns on with a set colour")
            else -> ButtonAction.Keep("Custom")
        }
        return Classified(action, group, null)
    }

    /** A rule to create: `POST /rules` body. */
    data class Spec(val name: String, val conditions: JsonArray, val actions: JsonArray) {
        fun toJson() = buildJsonObject {
            put("name", name)
            put("conditions", conditions)
            put("actions", actions)
            put("status", "enabled")
        }
    }

    /** Event code for a gesture. Press waits for release only when the button also has a hold action. */
    fun eventCode(button: Int, gesture: Gesture, action: ButtonAction, holdAction: ButtonAction): Int = when (gesture) {
        Gesture.PRESS -> button * 1000 + if (holdAction == ButtonAction.Nothing) 0 else 2
        Gesture.HOLD -> button * 1000 + if (action == ButtonAction.Brighter || action == ButtonAction.Dimmer) 1 else 3
    }

    /**
     * Builds the rules for one button gesture.
     * @param group v1 group path the remote controls, e.g. "/groups/81".
     * @param turnOn actions that switch the target on (one group command, or per-light power-on states).
     * @param memory memory sensor for scene cycling (required for [ButtonAction.Cycle]).
     */
    fun build(
        sensor: String,
        button: Int,
        gesture: Gesture,
        action: ButtonAction,
        holdAction: ButtonAction,
        group: String?,
        turnOn: JsonArray?,
        index: V1Index,
        memory: String? = null,
    ): List<Spec> {
        if (action == ButtonAction.Nothing || action is ButtonAction.Keep) return emptyList()
        val code = eventCode(button, gesture, action, holdAction)
        val tag = "egHue ${sensor.substringAfterLast('/')} b$button ${if (gesture == Gesture.PRESS) "p" else "h"}"
        fun trigger(extra: List<JsonObject> = emptyList()) = buildJsonArray {
            addJsonObject { put("address", "$sensor/state/buttonevent"); put("operator", "eq"); put("value", code.toString()) }
            addJsonObject { put("address", "$sensor/state/lastupdated"); put("operator", "dx") }
            extra.forEach { add(it) }
        }
        fun groupCmd(g: String, body: JsonObject) = buildJsonObject {
            put("address", "$g/action"); put("method", "PUT"); put("body", body)
        }
        fun actions(vararg a: JsonObject) = JsonArray(a.toList())
        val g = group
        val needsGroup = action.usesTarget
        if (needsGroup && g == null) return emptyList()
        val hold = gesture == Gesture.HOLD
        val on = turnOn ?: actions(groupCmd(g ?: "/groups/0", buildJsonObject { put("on", true) }))
        return when (action) {
            ButtonAction.On -> listOf(Spec(tag, trigger(), on))
            ButtonAction.Off -> listOf(Spec(tag, trigger(), actions(groupCmd(g!!, buildJsonObject { put("on", false) }))))
            ButtonAction.AllOff -> listOf(Spec(tag, trigger(), actions(groupCmd("/groups/0", buildJsonObject { put("on", false) }))))
            ButtonAction.Toggle -> listOf(
                Spec("$tag on", trigger(listOf(anyOn(g!!, false))), on),
                Spec("$tag off", trigger(listOf(anyOn(g, true))), actions(groupCmd(g, buildJsonObject { put("on", false) }))),
            )
            ButtonAction.Brighter -> listOf(Spec(tag, trigger(), actions(groupCmd(g!!, buildJsonObject {
                put("on", true); put("bri_inc", if (hold) 56 else 30); put("transitiontime", 9)
            }))))
            ButtonAction.Dimmer -> listOf(Spec(tag, trigger(), actions(groupCmd(g!!, buildJsonObject {
                put("bri_inc", if (hold) -56 else -30); put("transitiontime", 9)
            }))))
            is ButtonAction.Scene -> {
                val v1 = index.sceneV1(action.sceneId) ?: return emptyList()
                listOf(Spec(tag, trigger(), actions(groupCmd(g!!, buildJsonObject { put("scene", v1) }))))
            }
            is ButtonAction.Cycle -> {
                val scenes = action.sceneIds.mapNotNull(index::sceneV1)
                if (scenes.isEmpty() || memory == null) return emptyList()
                scenes.mapIndexed { i, v1 ->
                    val cond = buildJsonObject { put("address", "$memory/state/status"); put("operator", "eq"); put("value", i.toString()) }
                    Spec("$tag ${i + 1}", trigger(listOf(cond)), actions(
                        buildJsonObject {
                            put("address", "$memory/state"); put("method", "PUT")
                            putJsonObject("body") { put("status", (i + 1) % scenes.size) }
                        },
                        groupCmd(g!!, buildJsonObject { put("scene", v1) }),
                    ))
                }
            }
            ButtonAction.Nothing, is ButtonAction.Keep -> emptyList()
        }
    }

    /** "10 s after the last press, start the scene cycle from the beginning again." */
    fun cycleReset(sensor: String, button: Int, memory: String) = Spec(
        "egHue ${sensor.substringAfterLast('/')} b$button reset",
        buildJsonArray {
            addJsonObject { put("address", "$sensor/state/lastupdated"); put("operator", "ddx"); put("value", "PT00:00:10") }
        },
        buildJsonArray {
            addJsonObject { put("address", "$memory/state"); put("method", "PUT"); putJsonObject("body") { put("status", 0) } }
        },
    )

    private fun anyOn(group: String, value: Boolean) = buildJsonObject {
        put("address", "$group/state/any_on"); put("operator", "eq"); put("value", value.toString())
    }

    /**
     * Commands that switch [lights] on with their power-on settings, as rule actions. Falls back to
     * one group command when there's nothing special to set (or too many lights for one rule).
     */
    fun turnOnActions(group: String, lights: List<LightUi>, followPowerOn: Boolean, index: V1Index): JsonArray {
        val groupOn = JsonArray(listOf(buildJsonObject { put("address", "$group/action"); put("method", "PUT"); putJsonObject("body") { put("on", true) } }))
        if (!followPowerOn) return groupOn
        val bodies = lights.map { it to switchOnBody(it, true) }
        if (bodies.none { it.second.setsState() } || bodies.size > 8 || bodies.any { index.lightV1[it.first.id] == null }) return groupOn
        return JsonArray(bodies.map { (l, b) ->
            buildJsonObject {
                put("address", "${index.lightV1[l.id]}/state")
                put("method", "PUT")
                put("body", toV1LightState(b))
            }
        })
    }

    /** Converts a CLIP v2 light command body to the v1 light state format used in rules. */
    fun toV1LightState(body: JsonObject): JsonObject = buildJsonObject {
        put("on", body.obj("on")?.bool("on") ?: true)
        body.obj("dimming")?.dbl("brightness")?.let { put("bri", (it * 2.54).roundToInt().coerceIn(1, 254)) }
        body.obj("color_temperature")?.int("mirek")?.let { put("ct", it) }
        body.obj("color")?.obj("xy")?.let { xy ->
            putJsonArray("xy") { add(xy.dbl("x") ?: 0.0); add(xy.dbl("y") ?: 0.0) }
        }
    }
}

/** Human labels for a remote's buttons. */
fun buttonLabel(modelId: String?, control: Int, count: Int): String {
    val m = modelId ?: ""
    return when {
        m == "RWL020" || m == "RWL021" -> listOf("On", "Brighter", "Dimmer", "Off").getOrNull(control - 1)
        m.startsWith("RWL02") -> listOf("Power", "Brighter", "Dimmer", "Hue").getOrNull(control - 1)
        m == "ROM001" -> "Button"
        else -> null
    } ?: if (count == 1) "Button" else "Button $control"
}
