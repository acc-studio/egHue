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
)

data class SceneUi(
    val id: String,
    val name: String,
    val groupId: String,
    val colors: List<Int>,
    val active: Boolean,
)

data class HomeUi(
    val rooms: List<GroupUi>,
    val zones: List<GroupUi>,
    val lights: List<LightUi>,
    val scenes: Map<String, List<SceneUi>>,
    val homeGroupedLightId: String?,
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
        val brightness = gl?.obj("dimming")?.dbl("brightness")?.toFloat()
            ?: onLights.map { it.brightness }.average().takeUnless { it.isNaN() }?.toFloat() ?: 0f
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
        )
    }

    val rooms = ofType("room").mapNotNull { deriveGroup(it, GroupKind.ROOM) }.sortedWith(compareBy(nameOrder) { it.name })
    val zones = ofType("zone").mapNotNull { deriveGroup(it, GroupKind.ZONE) }.sortedWith(compareBy(nameOrder) { it.name })

    val scenes = ofType("scene").mapNotNull { s -> deriveScene(s) }
        .groupBy { it.groupId }
        .mapValues { (_, list) -> list.sortedWith(compareBy(nameOrder) { it.name }) }

    val homeGl = ofType("bridge_home").firstOrNull()?.refs("services")?.firstOrNull { it.rtype == "grouped_light" }?.rid

    return HomeUi(rooms, zones, lights, scenes, homeGl)
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
    )
}
