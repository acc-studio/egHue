package app.eghue.hue

import android.content.Context
import android.os.SystemClock
import app.eghue.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

sealed interface ConnState {
    data object NotPaired : ConnState
    data object Connecting : ConnState
    data object Connected : ConnState
    data class Offline(val reason: String) : ConnState
    data object Unauthorized : ConnState
}

/**
 * Single source of truth for bridge state.
 *
 * State = server resources (snapshot + live event stream) with short-lived optimistic overlays on
 * top, so the UI reacts the instant you tap and isn't jerked back by in-flight events while a
 * slider is moving. The last known state is cached on disk, so the app and widget open instantly.
 */
@OptIn(FlowPreview::class)
class HueRepository(
    context: Context,
    private val settings: Settings,
    private val client: HueClient,
    private val discovery: Discovery,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
) {
    private class Overlay(val patch: JsonObject, val until: Long)

    private val lock = Any()
    private var server: Map<String, JsonObject> = emptyMap()
    private val overlays = HashMap<String, Overlay>()

    private val _home = MutableStateFlow(HomeUi.Empty)
    val home: StateFlow<HomeUi> = _home.asStateFlow()

    private val _connection = MutableStateFlow<ConnState>(if (settings.paired.value) ConnState.Connecting else ConnState.NotPaired)
    val connection: StateFlow<ConnState> = _connection.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    /** True once any state (cache or live) is available. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    /** Set when the last widget action couldn't reach the bridge. */
    private val _widgetError = MutableStateFlow(false)
    val widgetError: StateFlow<Boolean> = _widgetError.asStateFlow()

    @Volatile private var lastSync = 0L
    @Volatile private var foreground = false
    private var loadJob: Job? = null
    private var loopJob: Job? = null
    private var stopJob: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val persistRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val snapshotFile = File(context.filesDir, "snapshot.json")

    private val queue = CommandQueue(scope, ::send, ::onCommandFailed)

    fun init() {
        loadJob = scope.launch(Dispatchers.IO) { loadSnapshot() }
        scope.launch { persistRequests.debounce(800).collect { withContext(Dispatchers.IO) { saveSnapshot() } } }
        scope.launch { network.changes.debounce(500).collect { if (foreground && settings.paired.value) restartLoop() } }
        scope.launch {
            settings.paired.drop(1).collect { paired ->
                if (paired) {
                    _connection.value = ConnState.Connecting
                    if (foreground) restartLoop()
                } else {
                    loopJob?.cancel()
                    synchronized(lock) { server = emptyMap(); overlays.clear() }
                    publish()
                    _loaded.value = false
                    _connection.value = ConnState.NotPaired
                    withContext(Dispatchers.IO) { snapshotFile.delete() }
                }
            }
        }
    }

    suspend fun ensureLoaded() { loadJob?.join() }

    // ---- Lifecycle & connection -----------------------------------------------------------------

    /** The app (not the widget) is visible: keep a live connection. Drop it shortly after leaving. */
    fun setForeground(fg: Boolean) {
        foreground = fg
        if (fg) {
            stopJob?.cancel()
            if (settings.paired.value && loopJob?.isActive != true) restartLoop()
        } else {
            stopJob = scope.launch {
                delay(10_000)
                loopJob?.cancel()
                if (settings.paired.value) _connection.value = ConnState.Connecting
            }
        }
    }

    fun reconnectNow() { if (settings.paired.value) restartLoop() }

    private fun restartLoop() {
        loopJob?.cancel()
        loopJob = scope.launch { connectionLoop() }
    }

    private suspend fun connectionLoop() {
        var failures = 0
        while (true) {
            val t = target() ?: run { _connection.value = ConnState.NotPaired; return }
            try {
                refresh(t)
                _connection.value = ConnState.Connected
                failures = 0
                client.stream(t, onOpen = {}, onEvents = ::applyEvents)
                // The bridge closed the stream; reconnect (and resync) right away.
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnauthorizedException) {
                _connection.value = ConnState.Unauthorized
                return
            } catch (e: Exception) {
                android.util.Log.w("egHue", "Bridge connection failed", e)
                failures++
                _connection.value = ConnState.Offline(describe(e))
                if (failures == 2 || failures % 4 == 0) rediscover()
            }
            val backoff = when (failures) { 0 -> 300L; 1 -> 1_000L; 2 -> 2_000L; 3 -> 4_000L; else -> 8_000L }
            withTimeoutOrNull(backoff) { wake.receive() }
        }
    }

    /** Looks for our bridge by id in case its IP address changed. */
    private suspend fun rediscover(): Boolean {
        val id = settings.bridgeId ?: return false
        val found = discovery.discover(timeoutMs = 10_000).firstOrNull { it.id.equals(id, ignoreCase = true) }
            ?: return false
        if (found.host != settings.bridgeHost) settings.updateHost(found.host)
        return true
    }

    /** Pull-to-refresh / settings "find bridge". Returns false if the bridge can't be reached. */
    suspend fun refreshNow(): Boolean {
        val t = target() ?: return false
        return try {
            refresh(t); true
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnauthorizedException) {
            _connection.value = ConnState.Unauthorized; false
        } catch (e: Exception) {
            if (rediscover()) { restartLoop(); true } else {
                _messages.tryEmit("Can't reach your Hue Bridge")
                false
            }
        } finally {
            if (foreground && loopJob?.isActive != true) restartLoop()
        }
    }

    /** Lightweight refresh for the widget when the app isn't holding a live connection. */
    fun refreshIfStale(maxAgeMs: Long) {
        if (loopJob?.isActive == true && _connection.value == ConnState.Connected) return
        if (SystemClock.elapsedRealtime() - lastSync < maxAgeMs) return
        val t = target() ?: return
        scope.launch { runCatching { refresh(t) } }
    }

    suspend fun refresh(t: HueClient.Target? = target()) {
        t ?: return
        val list = client.getAll(t)
        synchronized(lock) { server = list.mapNotNull { o -> o.str("id")?.let { it to o } }.toMap() }
        lastSync = SystemClock.elapsedRealtime()
        _loaded.value = true
        // The bridge is reachable again, so the widget's "can't reach" note no longer applies.
        _widgetError.value = false
        publish()
        persistRequests.tryEmit(Unit)
    }

    private fun applyEvents(events: JsonArray) {
        synchronized(lock) {
            val m = server.toMutableMap()
            for (container in events.objects()) {
                val kind = container.str("type")
                for (d in container.arr("data")?.objects().orEmpty()) {
                    val id = d.str("id") ?: continue
                    when (kind) {
                        "update" -> m[id]?.let { m[id] = deepMerge(it, d) }
                        "add" -> m[id] = d
                        "delete" -> m.remove(id)
                    }
                }
            }
            server = m
        }
        lastSync = SystemClock.elapsedRealtime()
        publish()
        persistRequests.tryEmit(Unit)
    }

    private fun target(): HueClient.Target? {
        val h = settings.bridgeHost ?: return null
        val k = settings.appKey ?: return null
        return HueClient.Target(h, k)
    }

    // ---- State publication ----------------------------------------------------------------------

    private fun publish() {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            overlays.entries.removeAll { it.value.until <= now }
            val merged = if (overlays.isEmpty()) server.values else server.map { (id, v) ->
                overlays[id]?.let { deepMerge(v, it.patch) } ?: v
            }
            _home.value = deriveHome(merged)
        }
    }

    private fun addOverlays(patches: Map<String, JsonObject>) {
        if (patches.isEmpty()) return
        val until = SystemClock.elapsedRealtime() + OVERLAY_MS
        synchronized(lock) {
            for ((id, p) in patches) {
                val existing = overlays[id]?.patch
                overlays[id] = Overlay(if (existing != null) deepMerge(existing, p) else p, until)
            }
        }
        publish()
        scope.launch { delay(OVERLAY_MS + 20); publish() }
    }

    private fun commitToServer(patches: Map<String, JsonObject>) {
        synchronized(lock) {
            val m = server.toMutableMap()
            for ((id, p) in patches) m[id]?.let { m[id] = deepMerge(it, p) }
            server = m
        }
        publish()
        persistRequests.tryEmit(Unit)
    }

    // ---- Commands -------------------------------------------------------------------------------

    fun toggleLight(id: String) {
        val l = _home.value.light(id) ?: return
        setLightOn(id, !l.on)
    }

    fun setLightOn(id: String, on: Boolean) {
        val l = _home.value.light(id)
        if (!on || l == null) return lightCommand(id, onBody(on))
        val body = switchOnBody(l, settings.followPowerOn.value)
        lightCommand(id, body, body.colorModeHint())
    }
    fun setLightBrightness(id: String, brightness: Float) = lightCommand(id, brightnessBody(brightness))
    fun setLightXy(id: String, xy: Xy) = lightCommand(id, xyBody(xy), CT_INACTIVE)
    fun setLightMirek(id: String, mirek: Int) = lightCommand(id, mirekBody(mirek), CT_ACTIVE)

    private fun lightCommand(id: String, body: JsonObject, local: JsonObject? = null) {
        addOverlays(mapOf(id to (local?.let { deepMerge(body, it) } ?: body)))
        queue.submit("light", id, body)
    }

    fun toggleGroup(groupId: String) {
        val g = _home.value.group(groupId) ?: return
        setGroupOn(groupId, !g.on)
    }

    fun setGroupOn(groupId: String, on: Boolean) {
        val g = _home.value.group(groupId) ?: return
        val perLight = if (on) switchOnBodies(g) else null
        if (perLight == null) return groupCommand(groupId, onBody(on))
        // Some lights have a power-on state to restore: switch each one on in that state.
        g.groupedLightId?.let { addOverlays(mapOf(it to onBody(true))) }
        perLight.forEach { (id, body) -> lightCommand(id, body, body.colorModeHint()) }
    }

    /** Per-light "on" commands for [g] when any of them needs more than a plain on, else null. */
    private fun switchOnBodies(g: GroupUi): Map<String, JsonObject>? {
        if (!settings.followPowerOn.value) return null
        val home = _home.value
        val bodies = g.lightIds.mapNotNull { id -> home.light(id)?.let { id to switchOnBody(it, true) } }.toMap()
        return bodies.takeIf { b -> b.values.any { it.setsState() } }
    }

    /** Sets the power-on behaviour of [lightIds] (only what each light supports is stored). */
    fun setPowerOn(lightIds: Collection<String>, powerOn: PowerOn) {
        val home = _home.value
        for (id in lightIds) {
            val l = home.light(id) ?: continue
            if (l.powerOn == null) continue
            lightCommand(id, powerUpBody(powerOn, l))
        }
    }
    fun setGroupBrightness(groupId: String, brightness: Float) = groupCommand(groupId, brightnessBody(brightness))
    fun setGroupXy(groupId: String, xy: Xy) = groupCommand(groupId, xyBody(xy), CT_INACTIVE)
    fun setGroupMirek(groupId: String, mirek: Int) = groupCommand(groupId, mirekBody(mirek), CT_ACTIVE)

    private fun groupCommand(groupId: String, body: JsonObject, local: JsonObject? = null) {
        val g = _home.value.group(groupId) ?: return
        val gl = g.groupedLightId ?: return
        addOverlays(groupPatches(gl, g.lightIds, body, local))
        queue.submit("grouped_light", gl, body)
    }

    fun allOff() {
        val home = _home.value
        val body = onBody(false)
        val gl = home.homeGroupedLightId
        if (gl != null) {
            val patches = HashMap<String, JsonObject>()
            home.lights.forEach { patches[it.id] = body }
            (home.rooms + home.zones).mapNotNull { it.groupedLightId }.forEach { patches[it] = body }
            patches[gl] = body
            addOverlays(patches)
            queue.submit("grouped_light", gl, body)
        } else {
            home.rooms.forEach { setGroupOn(it.id, false) }
        }
    }

    fun recallScene(sceneId: String) {
        val scene = synchronized(lock) { server[sceneId] } ?: return
        val home = _home.value
        val patches = HashMap<String, JsonObject>()
        // Optimistically show the scene's target states and mark it active.
        for (a in scene.arr("actions")?.objects().orEmpty()) {
            val rid = a.ref("target")?.rid ?: continue
            val action = a.obj("action") ?: continue
            val ct = if (action.obj("color_temperature") != null) CT_ACTIVE else if (action.obj("color") != null) CT_INACTIVE else null
            patches[rid] = ct?.let { deepMerge(memberPatch(action), it) } ?: memberPatch(action)
        }
        val groupId = scene.ref("group")?.rid
        home.group(groupId ?: "")?.groupedLightId?.let { patches[it] = onBody(true) }
        home.scenesFor(groupId ?: "").forEach { s ->
            patches[s.id] = buildJsonObject {
                putJsonObject("status") { put("active", if (s.id == sceneId) "static" else "inactive") }
            }
        }
        addOverlays(patches)
        queue.submit("scene", sceneId, RECALL_BODY)
    }

    /**
     * Switches [groupId] to [preset]. The first use in a room saves the preset as a bridge scene
     * (so it also works on remotes and in other Hue apps); later uses recall that scene, rewriting it
     * first if the room's lights or the preset's design have changed since it was saved.
     */
    fun applyPreset(groupId: String, preset: Preset) {
        scope.launch {
            try {
                val sceneId = ensurePresetScene(groupId, preset) ?: return@launch
                recallScene(sceneId)
                retireOldPresets(groupId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnauthorizedException) {
                _connection.value = ConnState.Unauthorized
            } catch (e: Exception) {
                android.util.Log.w("egHue", "Couldn't save preset ${preset.title}", e)
                _messages.tryEmit(if (e is HueApiException) "Bridge said: ${e.message}" else "Couldn't reach your Hue Bridge")
            }
        }
    }

    /** Sets [groupId]'s lights to [preset] without saving anything (the scene editor's starting points). */
    fun previewPreset(groupId: String, preset: Preset) {
        val home = _home.value
        val g = home.group(groupId) ?: return
        val lights = g.lightIds.mapNotNull(home::light)
        preset.actions(lights).forEach { (id, action) -> lightCommand(id, action, action.colorModeHint()) }
    }

    private suspend fun ensurePresetScene(groupId: String, preset: Preset): String? {
        val t = target() ?: return null
        val home = _home.value
        val g = home.group(groupId) ?: return null
        val lights = g.lightIds.mapNotNull(home::light)
        if (lights.isEmpty()) return null
        val actions = preset.actions(lights)
        val actionsJson = SceneCapture.actionsJson(actions)
        val saved = home.scenesFor(groupId).firstOrNull { it.preset == preset }
        if (saved != null) {
            if (saved.appdata == preset.appdata && saved.targets == actions.keys) return saved.id
            val body = buildJsonObject {
                put("actions", actionsJson)
                putJsonObject("metadata") { put("name", saved.name); put("appdata", preset.appdata) }
            }
            client.put(t, "scene", saved.id, body)
            storeScene(saved.id, saved.name, g, preset.appdata, actionsJson)
            return saved.id
        }
        // A scene of the same name in this room (e.g. the user's own "Evening") would clash.
        val taken = home.scenesFor(groupId).map { it.name.lowercase() }.toSet()
        val name = if (preset.title.lowercase() in taken) "${preset.title} · egHue" else preset.title
        val body = buildJsonObject {
            putJsonObject("metadata") { put("name", name); put("appdata", preset.appdata) }
            putJsonObject("group") { put("rid", g.id); put("rtype", if (g.kind == GroupKind.ZONE) "zone" else "room") }
            put("actions", actionsJson)
        }
        val id = client.create(t, "scene", body)
        storeScene(id, name, g, preset.appdata, actionsJson)
        return id
    }

    /** Deletes scenes saved for presets egHue no longer has (they're hidden in the app already). */
    private suspend fun retireOldPresets(groupId: String) {
        val t = target() ?: return
        for (s in _home.value.scenesFor(groupId).filter { it.retired }) {
            runCatching { client.delete(t, "scene", s.id) }.onSuccess { forget(s.id) }
        }
    }

    /** Removes a resource from local state (after deleting it on the bridge). */
    private fun forget(id: String) {
        synchronized(lock) { server = server - id; overlays.remove(id) }
        publish()
        persistRequests.tryEmit(Unit)
    }

    /** Puts a scene we just wrote into local state, so it can be recalled before the bridge's event arrives. */
    private fun storeScene(id: String, name: String, g: GroupUi, appdata: String?, actions: JsonArray) {
        val scene = buildJsonObject {
            put("id", id)
            put("type", "scene")
            putJsonObject("metadata") { put("name", name); appdata?.let { put("appdata", it) } }
            putJsonObject("group") { put("rid", g.id); put("rtype", if (g.kind == GroupKind.ZONE) "zone" else "room") }
            put("actions", actions)
            putJsonObject("status") { put("active", "inactive") }
        }
        synchronized(lock) {
            val existing = server[id]
            server = server + (id to (existing?.let { deepMerge(it, scene) } ?: scene))
        }
        publish()
        persistRequests.tryEmit(Unit)
    }

    // ---- Devices --------------------------------------------------------------------------------

    /** Writes [body] to a resource and keeps it locally. */
    private suspend fun write(what: String, type: String, id: String, body: JsonObject): Boolean = sceneOp(what) { t ->
        client.put(t, type, id, body)
        commitToServer(mapOf(id to body))
        true
    } ?: false

    /** Renames a light (and its device, which is what the Hue app shows). */
    suspend fun renameLight(lightId: String, name: String): Boolean {
        val l = _home.value.light(lightId) ?: return false
        val body = buildJsonObject { putJsonObject("metadata") { put("name", name) } }
        val ok = l.deviceId?.let { write("rename the light", "device", it, body) } ?: true
        return ok && write("rename the light", "light", lightId, body)
    }

    /** Sets a light's fixture type (archetype), which also decides its role in presets. */
    suspend fun setLightArchetype(lightId: String, archetype: String): Boolean {
        val l = _home.value.light(lightId) ?: return false
        val body = buildJsonObject { putJsonObject("metadata") { put("archetype", archetype) } }
        l.deviceId?.let { write("change the fixture type", "device", it, body) }
        return write("change the fixture type", "light", lightId, body)
    }

    suspend fun renameGroup(groupId: String, name: String): Boolean {
        val g = _home.value.group(groupId) ?: return false
        val body = buildJsonObject { putJsonObject("metadata") { put("name", name) } }
        return write("rename it", if (g.kind == GroupKind.ZONE) "zone" else "room", groupId, body)
    }

    /** Makes the light blink so you can find it. */
    fun identify(lightId: String) {
        val dev = _home.value.light(lightId)?.deviceId ?: return
        scope.launch { sceneOp("blink the light") { t -> client.put(t, "device", dev, IDENTIFY_BODY) } }
    }

    /** Plays [effect] ("candle", "fire", …) on a light, or stops it with null. */
    fun setEffect(lightId: String, effect: String?) {
        val body = buildJsonObject {
            if (effect != null) putJsonObject("on") { put("on", true) }
            putJsonObject("effects") { put("effect", effect ?: "no_effect") }
        }
        lightCommand(lightId, body, buildJsonObject { putJsonObject("effects") { put("status", effect ?: "no_effect") } })
    }

    suspend fun setMotionEnabled(motionId: String, enabled: Boolean): Boolean =
        write("change the sensor", "motion", motionId, buildJsonObject { put("enabled", enabled) })

    // ---- Automations (bridge schedules) ---------------------------------------------------------

    private val _automations = MutableStateFlow<List<Automation>>(emptyList())
    /** The bridge's schedules, as of the last [loadAutomations]. */
    val automations: StateFlow<List<Automation>> = _automations.asStateFlow()

    /** Display name for a legacy address such as "/groups/3". */
    fun nameOfV1(address: String): String? {
        if (address == "/groups/0") return "Whole home"
        val home = _home.value
        return (home.rooms + home.zones).firstOrNull { it.idV1 == address }?.name
            ?: home.lights.firstOrNull { it.idV1 == address }?.name
    }

    suspend fun loadAutomations(): Boolean = sceneOp("load automations") { t ->
        val all = client.v1Get(t, "schedules")
        _automations.value = all.entries.mapNotNull { (id, v) ->
            (v as? JsonObject)?.let { Automations.parse(id, it, ::nameOfV1) }
        }.sortedWith(compareBy({ it.kind.ordinal }, { it.name.lowercase() }))
        true
    } ?: false

    /** Creates an automation, or rewrites [existingId]. */
    suspend fun saveAutomation(existingId: String?, spec: AutoSpec): Boolean = sceneOp("save the automation") { t ->
        val body = Automations.toJson(spec, t.key)
        if (existingId == null) client.v1Post(t, "schedules", body)
        else client.v1Put(t, "schedules/$existingId", body)
        loadAutomations()
        true
    } ?: false

    suspend fun setAutomationEnabled(id: String, enabled: Boolean): Boolean = sceneOp("change the automation") { t ->
        _automations.value = _automations.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        client.v1Put(t, "schedules/$id", buildJsonObject { put("status", if (enabled) "enabled" else "disabled") })
        true
    } ?: run { scope.launch { loadAutomations() }; false }

    suspend fun deleteAutomation(id: String): Boolean = sceneOp("delete the automation") { t ->
        client.v1Delete(t, "schedules/$id")
        _automations.value = _automations.value.filterNot { it.id == id }
        true
    } ?: false

    /** Switches [targetV1] off in [minutes], replacing any sleep timer already running for it. */
    suspend fun startSleepTimer(targetV1: String, minutes: Int): Boolean {
        cancelSleepTimer(targetV1)
        val spec = AutoSpec(
            AutoKind.TIMER, targetV1, nameOfV1(targetV1) ?: "lights", AutoAction.Off, AutoWhen.In(minutes),
            // Fade over the last minute rather than snapping off.
            fadeMinutes = 1,
        )
        return saveAutomation(null, spec)
    }

    suspend fun cancelSleepTimer(targetV1: String) {
        sleepTimerFor(targetV1)?.let { deleteAutomation(it.id) }
    }

    fun sleepTimerFor(targetV1: String): Automation? = _automations.value.firstOrNull {
        it.kind == AutoKind.TIMER && it.enabled && it.spec?.targetV1 == targetV1 &&
            (it.endsAt == null || it.endsAt.isAfter(java.time.Instant.now()))
    }

    // ---- Your own scenes -------------------------------------------------------------------------

    /** Saves [groupId]'s lights as they are now as a new scene called [name]. Returns its id. */
    suspend fun saveCurrentAsScene(groupId: String, name: String): String? = sceneOp("save the scene") { t ->
        val home = _home.value
        val g = home.group(groupId) ?: return@sceneOp null
        val lights = g.lightIds.mapNotNull(home::light)
        if (lights.isEmpty()) return@sceneOp null
        val actions = SceneCapture.actions(lights)
        val body = buildJsonObject {
            putJsonObject("metadata") { put("name", name); put("appdata", Preset.SCENE_TAG + "1") }
            putJsonObject("group") { put("rid", g.id); put("rtype", if (g.kind == GroupKind.ZONE) "zone" else "room") }
            put("actions", actions)
        }
        val id = client.create(t, "scene", body)
        storeScene(id, name, g, Preset.SCENE_TAG + "1", actions)
        // The lights already look like this, so the new scene is the active one.
        markActive(groupId, id)
        id
    }

    /** Rewrites [sceneId] with its room's lights as they are now (and optionally a new name). */
    suspend fun updateSceneFromCurrent(sceneId: String, name: String?): Boolean = sceneOp("update the scene") { t ->
        val home = _home.value
        val scene = synchronized(lock) { server[sceneId] } ?: return@sceneOp false
        val groupId = scene.ref("group")?.rid ?: return@sceneOp false
        val g = home.group(groupId) ?: return@sceneOp false
        val lights = g.lightIds.mapNotNull(home::light)
        val actions = SceneCapture.actions(lights)
        val body = buildJsonObject {
            put("actions", actions)
            if (name != null) putJsonObject("metadata") { put("name", name) }
        }
        client.put(t, "scene", sceneId, body)
        val local = buildJsonObject {
            put("actions", actions)
            if (name != null) putJsonObject("metadata") { put("name", name) }
        }
        synchronized(lock) { server[sceneId]?.let { server = server + (sceneId to deepMerge(it, local)) } }
        publish()
        persistRequests.tryEmit(Unit)
        markActive(groupId, sceneId)
        true
    } ?: false

    suspend fun renameScene(sceneId: String, name: String): Boolean = sceneOp("rename the scene") { t ->
        val body = buildJsonObject { putJsonObject("metadata") { put("name", name) } }
        client.put(t, "scene", sceneId, body)
        synchronized(lock) { server[sceneId]?.let { server = server + (sceneId to deepMerge(it, body)) } }
        publish()
        persistRequests.tryEmit(Unit)
        true
    } ?: false

    suspend fun deleteScene(sceneId: String): Boolean = sceneOp("delete the scene") { t ->
        client.delete(t, "scene", sceneId)
        forget(sceneId)
        true
    } ?: false

    /** Shows [sceneId] as the active scene of [groupId] until the bridge says otherwise. */
    private fun markActive(groupId: String, sceneId: String) {
        addOverlays(_home.value.scenesFor(groupId).associate { s ->
            s.id to buildJsonObject { putJsonObject("status") { put("active", if (s.id == sceneId) "static" else "inactive") } }
        })
    }

    /** Runs a bridge write, turning failures into a message. Returns null on failure. */
    private suspend fun <T> sceneOp(what: String, block: suspend (HueClient.Target) -> T?): T? {
        val t = target() ?: return null
        return try {
            block(t)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnauthorizedException) {
            _connection.value = ConnState.Unauthorized
            null
        } catch (e: Exception) {
            android.util.Log.w("egHue", "Couldn't $what", e)
            _messages.tryEmit(if (e is HueApiException) "Couldn't $what: ${e.message}" else "Couldn't reach your Hue Bridge")
            null
        }
    }

    private fun groupPatches(groupedLightId: String, members: List<String>, body: JsonObject, local: JsonObject?): Map<String, JsonObject> {
        val full = local?.let { deepMerge(body, it) } ?: body
        val member = memberPatch(full)
        return buildMap {
            put(groupedLightId, full)
            members.forEach { put(it, member) }
        }
    }

    private fun membersOf(groupedLightId: String): List<String> {
        val home = _home.value
        if (groupedLightId == home.homeGroupedLightId) return home.lights.map { it.id }
        return home.groupByGroupedLight(groupedLightId)?.lightIds.orEmpty()
    }

    private suspend fun send(type: String, id: String, body: JsonObject) {
        val t = target() ?: return
        client.put(t, type, id, body)
        when (type) {
            "light" -> commitToServer(mapOf(id to body))
            "grouped_light" -> commitToServer(groupPatches(id, membersOf(id), body, null))
            "scene" -> if (_connection.value != ConnState.Connected) scope.launch { delay(700); runCatching { refresh() } }
        }
    }

    private fun onCommandFailed(type: String, id: String, error: Throwable) {
        synchronized(lock) { overlays.clear() }
        publish()
        if (error is UnauthorizedException) {
            _connection.value = ConnState.Unauthorized
            return
        }
        _messages.tryEmit(if (error is HueApiException) "Bridge said: ${error.message}" else "Couldn't reach your Hue Bridge")
        if (foreground) wake.trySend(Unit) else scope.launch { runCatching { refresh() } }
    }

    // ---- Remotes --------------------------------------------------------------------------------

    /** Reads how [remote]'s buttons are set up on the bridge. */
    suspend fun loadRemote(remote: RemoteUi): RemoteSetup {
        val t = target() ?: throw IOException("Not connected to a bridge")
        val sensor = remote.sensorV1 ?: throw IOException("This remote can't be set up here")
        val rules = client.v1Get(t, "rules")
        return RemoteRules.parse(sensor, rules, V1Index(_home.value))
    }

    /**
     * Writes [updated] for [remote]. New rules are created first; old rules for the changed
     * buttons are removed only once everything new is in place, so a failure leaves the remote
     * working as before.
     */
    suspend fun saveRemote(remote: RemoteUi, original: RemoteSetup, updated: RemoteSetup) {
        val t = target() ?: throw IOException("Not connected to a bridge")
        val sensor = remote.sensorV1 ?: throw IOException("This remote can't be set up here")
        val home = _home.value
        val index = V1Index(home)
        val group = updated.targetGroupId?.let(index::groupV1)
        val targetChanged = updated.targetGroupId != original.targetGroupId
        val turnOn = group?.let { g ->
            val lights = home.group(updated.targetGroupId!!)?.lightIds?.mapNotNull(home::light).orEmpty()
            RemoteRules.turnOnActions(g, lights, settings.followPowerOn.value, index)
        }

        // Which button gestures need rewriting.
        val changed = mutableSetOf<ButtonKey>()
        for (b in remote.buttons) {
            val newPress = updated.action(b, Gesture.PRESS)
            val newHold = updated.action(b, Gesture.HOLD)
            val oldHold = original.action(b, Gesture.HOLD)
            if (newHold != oldHold || (targetChanged && newHold.usesTarget)) changed += ButtonKey(b, Gesture.HOLD)
            // The press event depends on whether a hold action exists, so rewrite it when that flips.
            val holdPresenceFlipped = (newHold == ButtonAction.Nothing) != (oldHold == ButtonAction.Nothing)
            if (newPress != original.action(b, Gesture.PRESS) || (targetChanged && newPress.usesTarget) ||
                (holdPresenceFlipped && newPress !is ButtonAction.Keep)
            ) changed += ButtonKey(b, Gesture.PRESS)
        }
        changed.removeAll { updated.actions[it] is ButtonAction.Keep }
        if (changed.isEmpty()) return

        val created = mutableListOf<String>()
        try {
            val specs = mutableListOf<RemoteRules.Spec>()
            for (key in changed) {
                val action = updated.action(key.button, key.gesture)
                var memory: String? = null
                if (action is ButtonAction.Cycle) {
                    memory = original.cycleSensors[key.button]
                        ?: ("/sensors/" + client.v1Post(t, "sensors", cycleSensorJson(sensor, key.button)))
                    specs += RemoteRules.cycleReset(sensor, key.button, memory)
                }
                specs += RemoteRules.build(
                    sensor, key.button, key.gesture, action, updated.action(key.button, Gesture.HOLD),
                    group, turnOn, index, memory,
                )
            }
            for (spec in specs) created += client.v1Post(t, "rules", spec.toJson())
        } catch (e: Exception) {
            created.forEach { runCatching { client.v1Delete(t, "rules/$it") } }
            throw e
        }
        // Now retire the old rules for the changed gestures (and their scene-cycle timers).
        val old = changed.flatMap { original.ruleIds[it].orEmpty() }.toMutableSet()
        changed.filter { it.gesture == Gesture.PRESS }.forEach { k ->
            original.cycleSensors[k.button]?.let { m -> old += original.resetRules[m].orEmpty() }
        }
        old.forEach { runCatching { client.v1Delete(t, "rules/$it") } }
    }

    private fun cycleSensorJson(sensor: String, button: Int) = buildJsonObject {
        put("name", "egHue cycle ${sensor.substringAfterLast('/')} b$button")
        put("type", "CLIPGenericStatus")
        put("modelid", "egHueCycle")
        put("manufacturername", "egHue")
        put("swversion", "1.0")
        put("uniqueid", "eghue-cycle-${sensor.substringAfterLast('/')}-$button-${System.currentTimeMillis() % 100000}")
        put("recycle", true)
    }

    // ---- Widget path ----------------------------------------------------------------------------

    /**
     * Toggles a room/zone ("g") or light ("l") on behalf of the widget, where the process may have
     * just been started. Shows the change optimistically via [onOptimistic], then waits for the bridge.
     */
    suspend fun widgetToggle(kind: String, id: String, onOptimistic: suspend () -> Unit): Boolean {
        ensureLoaded()
        val t = target() ?: return false
        // Without the app's live stream, state may be old: resync everything (lights included, since
        // room brightness is derived from them) before deciding what the toggle should do.
        if (SystemClock.elapsedRealtime() - lastSync > 15_000) withTimeoutOrNull(2_500) { runCatching { refresh(t) } }
        return try {
            if (kind == KIND_LIGHT) {
                val l = _home.value.light(id) ?: return false
                val body = if (l.on) onBody(false) else switchOnBody(l, settings.followPowerOn.value)
                addOverlays(mapOf(id to body))
                onOptimistic()
                client.put(t, "light", id, body)
                commitToServer(mapOf(id to body))
            } else {
                val g = _home.value.group(id) ?: return false
                val gl = g.groupedLightId ?: return false
                val perLight = if (!g.on) switchOnBodies(g) else null
                if (perLight != null) {
                    addOverlays(perLight + (gl to onBody(true)))
                    onOptimistic()
                    coroutineScope { perLight.map { (lid, b) -> async { client.put(t, "light", lid, b) } }.awaitAll() }
                    commitToServer(perLight)
                } else {
                    val body = onBody(!g.on)
                    addOverlays(groupPatches(gl, g.lightIds, body, null))
                    onOptimistic()
                    client.put(t, "grouped_light", gl, body)
                    commitToServer(groupPatches(gl, g.lightIds, body, null))
                }
            }
            _widgetError.value = false
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) { overlays.clear() }
            publish()
            _widgetError.value = true
            false
        }
    }

    // ---- Snapshot cache -------------------------------------------------------------------------

    private fun loadSnapshot() {
        val list = runCatching {
            if (!snapshotFile.exists()) return
            HueJson.parseToJsonElement(snapshotFile.readText()).jsonArray.objects()
        }.getOrNull() ?: return
        var applied = false
        synchronized(lock) {
            if (server.isEmpty()) {
                server = list.mapNotNull { o -> o.str("id")?.let { it to o } }.toMap()
                applied = true
            }
        }
        if (applied) {
            publish()
            if (list.isNotEmpty()) _loaded.value = true
        }
    }

    private fun saveSnapshot() {
        val values = synchronized(lock) { server.values.toList() }
        if (values.isEmpty()) return
        runCatching {
            val tmp = File(snapshotFile.parentFile, snapshotFile.name + ".tmp")
            tmp.writeText(JsonArray(values).toString())
            tmp.renameTo(snapshotFile)
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is ConnectException, is NoRouteToHostException, is SocketTimeoutException, is UnknownHostException ->
            if (network.hasLan) "Can't reach your Hue Bridge" else "Not connected to Wi-Fi"
        is SSLException -> "Secure connection to the bridge failed"
        // Low-level socket errors (e.g. "Binding socket … EPERM") mean nothing to people.
        is SocketException -> if (network.hasLan) "Can't reach your Hue Bridge" else "Not connected to Wi-Fi"
        is IOException -> e.message ?: "Connection problem"
        else -> "Connection problem"
    }

    companion object {
        const val KIND_GROUP = "g"
        const val KIND_LIGHT = "l"
        private const val OVERLAY_MS = 1_500L
        private val IDENTIFY_BODY = buildJsonObject { putJsonObject("identify") { put("action", "identify") } }
    }
}
