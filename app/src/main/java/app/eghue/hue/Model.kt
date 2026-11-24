package app.eghue.hue

import kotlinx.serialization.json.JsonObject

enum class GroupKind { ROOM, ZONE }

data class LightUi(
    val id: String,
    val name: String,
    val archetype: String?,
    val on: Boolean,
    /** 0..100 (Hue's percentage). */
    val brightness: Float,
    val dimmable: Boolean,
    val supportsColor: Boolean,
    val supportsCt: Boolean,
    val mirek: Int?,
    val mirekMin: Int,
    val mirekMax: Int,
    val xy: Xy?,
    /** True when the light is in colour-temperature mode rather than xy colour mode. */
    val ctActive: Boolean,
    val reachable: Boolean,
    /** Display colour of the light's current colour at full brightness. */
    val color: Int,
    /** What the light does when it gets power (wall switch, power cut). Null if unsupported. */
    val powerOn: PowerOn? = null,
    /** Legacy (v1) id, e.g. "/lights/3"; used for remote rules. */
    val idV1: String? = null,
)

/** Hue "power-on behaviour" (`powerup`). Custom values are only meaningful for [PowerOn.CUSTOM]. */
data class PowerOn(
    val preset: String,
    val brightness: Float? = null,
    val mirek: Int? = null,
    val xy: Xy? = null,
) {
    companion object {
        const val SAFETY = "safety"
        const val POWERFAIL = "powerfail"
        const val LAST_ON_STATE = "last_on_state"
        const val CUSTOM = "custom"
        /** Hue's "safety" preset: warm white at full brightness. */
        const val SAFETY_MIREK = 366
    }
}

/** A Hue remote (dimmer switch, smart button, tap dial, ...). */
data class RemoteUi(
    val id: String,
    val name: String,
    val modelId: String?,
    val productName: String?,
    val battery: Int?,
    /** Legacy sensor id ("/sensors/2") that bridge rules use for this remote's buttons. */
    val sensorV1: String?,
    /** Button control ids (1-based), in order. */
    val buttons: List<Int>,
    val reachable: Boolean,
)

data class GroupUi(
    val id: String,
    val kind: GroupKind,
    val name: String,
    val archetype: String?,
    val groupedLightId: String?,
    val on: Boolean,
    val brightness: Float,
    val lightIds: List<String>,
    val lightsOn: Int,
    val reachableLights: Int,
    val color: Int,
    val dimmable: Boolean,
    val supportsColor: Boolean,
    val supportsCt: Boolean,
    val mirekMin: Int,
    val mirekMax: Int,
    val mirek: Int?,
    val xy: Xy?,
    val ctActive: Boolean,
    val idV1: String? = null,
)

data class SceneUi(
    val id: String,
    val name: String,
    val groupId: String,
    val colors: List<Int>,
    val active: Boolean,
    val idV1: String? = null,
)

data class HomeUi(
    val rooms: List<GroupUi>,
    val zones: List<GroupUi>,
    val lights: List<LightUi>,
    val scenes: Map<String, List<SceneUi>>,
    val homeGroupedLightId: String?,
    val remotes: List<RemoteUi> = emptyList(),
) {
    private val lightIndex: Map<String, LightUi> by lazy { lights.associateBy { it.id } }
    private val groupIndex: Map<String, GroupUi> by lazy { (rooms + zones).associateBy { it.id } }

    fun light(id: String): LightUi? = lightIndex[id]
    fun group(id: String): GroupUi? = groupIndex[id]
    fun groupByGroupedLight(groupedLightId: String): GroupUi? =
        (rooms + zones).firstOrNull { it.groupedLightId == groupedLightId }
    fun scenesFor(groupId: String): List<SceneUi> = scenes[groupId].orEmpty()
    val isEmpty: Boolean get() = rooms.isEmpty() && zones.isEmpty() && lights.isEmpty()

    companion object {
        val Empty = HomeUi(emptyList(), emptyList(), emptyList(), emptyMap(), null)
    }
}

private val nameOrder: Comparator<String> = String.CASE_INSENSITIVE_ORDER

/** Builds the UI model from raw CLIP v2 resources. Pure and cheap; runs on every state change. */
fun deriveHome(resources: Collection<JsonObject>): HomeUi {
    val byType = resources.groupBy { it.str("type") ?: "" }
    fun ofType(t: String) = byType[t].orEmpty()

    val devices = ofType("device").mapNotNull { d -> d.str("id")?.let { it to d } }.toMap()
    val connected = ofType("zigbee_connectivity").mapNotNull { z ->
        val owner = z.ref("owner")?.rid ?: return@mapNotNull null
        owner to (z.str("status") == null || z.str("status") == "connected")
    }.toMap()

    val lights = ofType("light").mapNotNull { l -> deriveLight(l, devices, connected) }
        .sortedWith(compareBy(nameOrder) { it.name })
    val lightMap = lights.associateBy { it.id }

    val groupedLights = ofType("grouped_light").mapNotNull { g -> g.str("id")?.let { it to g } }.toMap()

    fun memberLights(children: List<Ref>): List<String> = children.flatMap { c ->
        when (c.rtype) {
            "light" -> listOf(c.rid)
            "device" -> devices[c.rid]?.refs("services")?.filter { it.rtype == "light" }?.map { it.rid }.orEmpty()
            else -> emptyList()
        }
    }.filter { it in lightMap }.distinct()

    fun deriveGroup(o: JsonObject, kind: GroupKind): GroupUi? {
        val id = o.str("id") ?: return null
        val meta = o.obj("metadata")
        val glId = o.refs("services").firstOrNull { it.rtype == "grouped_light" }?.rid
        val gl = glId?.let { groupedLights[it] }
        val members = memberLights(o.refs("children"))
        val ml = members.mapNotNull { lightMap[it] }
        val onLights = ml.filter { it.on }
        val on = gl?.obj("on")?.bool("on") ?: onLights.isNotEmpty()
        // Hue's group brightness is the average of the lights that are on, and 0 while all are off.
        // Derive it from the lights so a stale group value (e.g. 0 just after switching the group on
        // without a live event stream) can't win; an off group shows the level it will come back at.
        fun avg(ls: List<LightUi>) = ls.map { it.brightness }.average().takeUnless { it.isNaN() }?.toFloat()
        val brightness = avg(onLights)
            ?: gl?.obj("dimming")?.dbl("brightness")?.toFloat()?.takeIf { it > 0f }
            ?: avg(ml) ?: 0f
        val ctLights = ml.filter { it.supportsCt }
        val lead = onLights.firstOrNull() ?: ml.firstOrNull()
        return GroupUi(
            id = id,
            kind = kind,
            name = meta?.str("name") ?: if (kind == GroupKind.ROOM) "Room" else "Zone",
            archetype = meta?.str("archetype"),
            groupedLightId = glId,
            on = on,
            brightness = brightness,
            lightIds = members,
            lightsOn = onLights.size,
            reachableLights = ml.count { it.reachable },
            color = ColorUtil.average(onLights.map { it.color }),
            dimmable = ml.any { it.dimmable },
            supportsColor = ml.any { it.supportsColor },
            supportsCt = ctLights.isNotEmpty(),
            mirekMin = ctLights.minOfOrNull { it.mirekMin } ?: 153,
            mirekMax = ctLights.maxOfOrNull { it.mirekMax } ?: 500,
            mirek = lead?.mirek,
            xy = lead?.xy,
            ctActive = lead?.ctActive ?: true,
            idV1 = o.str("id_v1"),
        )
    }

    val rooms = ofType("room").mapNotNull { deriveGroup(it, GroupKind.ROOM) }.sortedWith(compareBy(nameOrder) { it.name })
    val zones = ofType("zone").mapNotNull { deriveGroup(it, GroupKind.ZONE) }.sortedWith(compareBy(nameOrder) { it.name })

    val scenes = ofType("scene").mapNotNull { s -> deriveScene(s) }
        .groupBy { it.groupId }
        .mapValues { (_, list) -> list.sortedWith(compareBy(nameOrder) { it.name }) }

    val homeGl = ofType("bridge_home").firstOrNull()?.refs("services")?.firstOrNull { it.rtype == "grouped_light" }?.rid

    val buttons = ofType("button")
    val batteries = ofType("device_power").mapNotNull { p ->
        val owner = p.ref("owner")?.rid ?: return@mapNotNull null
        p.obj("power_state")?.int("battery_level")?.let { owner to it }
    }.toMap()
    val remotes = devices.values.mapNotNull { d ->
        val buttonIds = d.refs("services").filter { it.rtype == "button" }.map { it.rid }.toSet()
        if (buttonIds.isEmpty()) return@mapNotNull null
        val own = buttons.filter { it.str("id") in buttonIds }
        val id = d.str("id") ?: return@mapNotNull null
        RemoteUi(
            id = id,
            name = d.obj("metadata")?.str("name") ?: "Remote",
            modelId = d.obj("product_data")?.str("model_id"),
            productName = d.obj("product_data")?.str("product_name"),
            battery = batteries[id],
            sensorV1 = own.firstNotNullOfOrNull { it.str("id_v1") } ?: d.str("id_v1"),
            buttons = own.mapNotNull { it.obj("metadata")?.int("control_id") }.sorted().ifEmpty { (1..own.size).toList() },
            reachable = connected[id] ?: true,
        )
    }.sortedWith(compareBy(nameOrder) { it.name })

    return HomeUi(rooms, zones, lights, scenes, homeGl, remotes)
}

private fun deriveLight(l: JsonObject, devices: Map<String, JsonObject>, connected: Map<String, Boolean>): LightUi? {
    val id = l.str("id") ?: return null
    val ownerId = l.ref("owner")?.rid
    val device = ownerId?.let { devices[it] }
    val meta = l.obj("metadata")
    val on = l.obj("on")?.bool("on") ?: false
    val dimming = l.obj("dimming")
    val ct = l.obj("color_temperature")
    val color = l.obj("color")
    val mirek = ct?.int("mirek")
    val ctValid = ct?.bool("mirek_valid") ?: (mirek != null)
    val schema = ct?.obj("mirek_schema")
    val xy = color?.obj("xy")?.let { o ->
        val x = o.dbl("x"); val y = o.dbl("y")
        if (x != null && y != null) Xy(x, y) else null
    }
    val ctActive = ct != null && ctValid && mirek != null
    val display = when {
        ctActive -> ColorUtil.mirekToRgb(mirek!!)
        xy != null -> ColorUtil.xyToRgb(xy)
        else -> ColorUtil.WARM_WHITE
    }
    return LightUi(
        id = id,
        name = meta?.str("name") ?: device?.obj("metadata")?.str("name") ?: "Light",
        archetype = meta?.str("archetype") ?: device?.obj("metadata")?.str("archetype"),
        on = on,
        brightness = dimming?.dbl("brightness")?.toFloat() ?: if (on) 100f else 0f,
        dimmable = dimming != null,
        supportsColor = color != null,
        supportsCt = ct != null,
        mirek = mirek,
        mirekMin = schema?.int("mirek_minimum") ?: 153,
        mirekMax = schema?.int("mirek_maximum") ?: 500,
        xy = xy,
        ctActive = ctActive,
        reachable = ownerId?.let { connected[it] } ?: true,
        color = display,
        powerOn = l.obj("powerup")?.let(::parsePowerOn),
        idV1 = l.str("id_v1"),
    )
}

private fun deriveScene(s: JsonObject): SceneUi? {
    val id = s.str("id") ?: return null
    val group = s.ref("group")?.rid ?: return null
    val colors = LinkedHashSet<Int>()
    for (a in s.arr("actions")?.objects().orEmpty()) {
        val action = a.obj("action") ?: continue
        if (action.obj("on")?.bool("on") == false) continue
        val c = action.obj("color")?.obj("xy")?.let { o ->
            val x = o.dbl("x"); val y = o.dbl("y")
            if (x != null && y != null) ColorUtil.xyToRgb(Xy(x, y)) else null
        } ?: action.obj("color_temperature")?.int("mirek")?.let { ColorUtil.mirekToRgb(it) }
        if (c != null) colors += c
    }
    if (colors.isEmpty()) {
        for (p in s.obj("palette")?.arr("color")?.objects().orEmpty()) {
            val o = p.obj("color")?.obj("xy") ?: continue
            val x = o.dbl("x"); val y = o.dbl("y")
            if (x != null && y != null) colors += ColorUtil.xyToRgb(Xy(x, y))
        }
    }
    val status = s.obj("status")?.str("active") ?: "inactive"
    return SceneUi(
        id = id,
        name = s.obj("metadata")?.str("name") ?: "Scene",
        groupId = group,
        colors = colors.take(4),
        active = status != "inactive",
        idV1 = s.str("id_v1"),
    )
}

fun parsePowerOn(p: JsonObject): PowerOn {
    val preset = p.str("preset") ?: PowerOn.SAFETY
    if (preset != PowerOn.CUSTOM) return PowerOn(preset)
    val bri = p.obj("dimming")?.takeIf { it.str("mode") == "dimming" }?.obj("dimming")?.dbl("brightness")?.toFloat()
    val color = p.obj("color")
    val mirek = color?.takeIf { it.str("mode") == "color_temperature" }?.obj("color_temperature")?.int("mirek")
    val xy = color?.takeIf { it.str("mode") == "color" }?.obj("color")?.obj("xy")?.let { o ->
        val x = o.dbl("x"); val y = o.dbl("y")
        if (x != null && y != null) Xy(x, y) else null
    }
    return PowerOn(preset, bri, mirek, xy)
}
