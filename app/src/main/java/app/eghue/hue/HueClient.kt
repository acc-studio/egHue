package app.eghue.hue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.cert.X509Certificate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class UnauthorizedException : IOException("The bridge no longer accepts this app's key")
class HueApiException(message: String) : IOException(message)

/** Thin client for the Hue bridge's local CLIP v2 API. */
class HueClient(
    private val http: OkHttpClient,
    private val sse: OkHttpClient,
    private val probe: OkHttpClient,
) {
    data class Target(val host: String, val key: String)
    data class BridgeConfig(val id: String, val name: String, val model: String?)

    sealed interface PairResult {
        data class Success(val appKey: String, val clientKey: String?, val certPin: String?) : PairResult
        data object ButtonNotPressed : PairResult
        data class Error(val message: String) : PairResult
    }

    private val jsonType = "application/json".toMediaType()

    /** Unauthenticated bridge identity; doubles as "is there a Hue bridge at this address?". */
    suspend fun config(host: String, fast: Boolean = false): BridgeConfig = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://$host/api/config").build()
        (if (fast) probe else http).newCall(req).await().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val o = HueJson.parseToJsonElement(resp.body!!.string()).jsonObject
            val id = o.str("bridgeid") ?: throw IOException("Not a Hue bridge")
            BridgeConfig(id.uppercase(), o.str("name") ?: "Hue Bridge", o.str("modelid"))
        }
    }

    /** One pairing attempt. Succeeds only within ~30s of the bridge's link button being pressed. */
    suspend fun pair(host: String): PairResult = withContext(Dispatchers.IO) { pairBlocking(host) }

    private suspend fun pairBlocking(host: String): PairResult {
        val body = buildJsonObject {
            put("devicetype", "egHue#android")
            put("generateclientkey", true)
        }.toString().toRequestBody(jsonType)
        val req = Request.Builder().url("https://$host/api").post(body).build()
        return http.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) return PairResult.Error("HTTP ${resp.code}")
            val first = HueJson.parseToJsonElement(resp.body!!.string()).jsonArray.firstOrNull() as? JsonObject
                ?: return PairResult.Error("Unexpected reply from bridge")
            first.obj("success")?.let { s ->
                val key = s.str("username") ?: return PairResult.Error("Bridge sent no key")
                val pin = (resp.handshake?.peerCertificates?.firstOrNull() as? X509Certificate)
                    ?.let(BridgeTls::publicKeyPin)
                return PairResult.Success(key, s.str("clientkey"), pin)
            }
            val err = first.obj("error")
            if (err?.int("type") == 101) PairResult.ButtonNotPressed
            else PairResult.Error(err?.str("description") ?: "Pairing failed")
        }
    }

    suspend fun getAll(t: Target): List<JsonObject> = getData(t, "/clip/v2/resource")

    /** Every resource of one type, e.g. all lights: much smaller and faster than [getAll]. */
    suspend fun getType(t: Target, type: String): List<JsonObject> = getData(t, "/clip/v2/resource/$type")

    suspend fun get(t: Target, type: String, id: String): JsonObject? =
        getData(t, "/clip/v2/resource/$type/$id").firstOrNull()

    private suspend fun getData(t: Target, path: String): List<JsonObject> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://${t.host}$path").header(KEY_HEADER, t.key).build()
        http.newCall(req).await().use { resp ->
            checkAuth(resp)
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val o = HueJson.parseToJsonElement(resp.body!!.string()).jsonObject
            o.arr("data")?.objects().orEmpty()
        }
    }

    suspend fun put(t: Target, type: String, id: String, body: JsonObject): Unit = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://${t.host}/clip/v2/resource/$type/$id")
            .header(KEY_HEADER, t.key)
            .put(body.toString().toRequestBody(jsonType))
            .build()
        http.newCall(req).await().use { resp ->
            checkAuth(resp)
            val text = resp.body?.string().orEmpty()
            val o = runCatching { HueJson.parseToJsonElement(text).jsonObject }.getOrNull()
            val errors = o?.arr("errors")?.objects().orEmpty()
            val data = o?.arr("data")?.objects().orEmpty()
            if (!resp.isSuccessful || (errors.isNotEmpty() && data.isEmpty())) {
                throw HueApiException(errors.firstOrNull()?.str("description") ?: "HTTP ${resp.code}")
            }
        }
    }

    /** POST /clip/v2/resource/<type>; returns the new resource's id. */
    suspend fun create(t: Target, type: String, body: JsonObject): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://${t.host}/clip/v2/resource/$type")
            .header(KEY_HEADER, t.key)
            .post(body.toString().toRequestBody(jsonType))
            .build()
        http.newCall(req).await().use { resp ->
            checkAuth(resp)
            val text = resp.body?.string().orEmpty()
            val o = runCatching { HueJson.parseToJsonElement(text).jsonObject }.getOrNull()
            val id = o?.arr("data")?.objects()?.firstNotNullOfOrNull { it.str("rid") }
            if (!resp.isSuccessful || id == null) {
                throw HueApiException(o?.arr("errors")?.objects()?.firstOrNull()?.str("description") ?: "HTTP ${resp.code}")
            }
            id
        }
    }

    suspend fun delete(t: Target, type: String, id: String): Unit = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://${t.host}/clip/v2/resource/$type/$id")
            .header(KEY_HEADER, t.key)
            .delete()
            .build()
        http.newCall(req).await().use { resp ->
            checkAuth(resp)
            // Already gone counts as deleted.
            if (!resp.isSuccessful && resp.code != 404) {
                val o = runCatching { HueJson.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject }.getOrNull()
                throw HueApiException(o?.arr("errors")?.objects()?.firstOrNull()?.str("description") ?: "HTTP ${resp.code}")
            }
        }
    }

    // ---- Legacy (v1) API: switch rules and memory sensors ---------------------------------------

    /** GET /api/<key>/<path>; returns the JSON object (e.g. all rules keyed by id). */
    suspend fun v1Get(t: Target, path: String): JsonObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://${t.host}/api/${t.key}/$path").build()
        http.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            when (val e = HueJson.parseToJsonElement(resp.body!!.string())) {
                is JsonObject -> e
                is JsonArray -> throw v1Error(e)
                else -> throw IOException("Unexpected reply from bridge")
            }
        }
    }

    /** POST /api/<key>/<path>; returns the new resource's id. */
    suspend fun v1Post(t: Target, path: String, body: JsonObject): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://${t.host}/api/${t.key}/$path")
            .post(body.toString().toRequestBody(jsonType)).build()
        http.newCall(req).await().use { resp ->
            val arr = runCatching { HueJson.parseToJsonElement(resp.body!!.string()).jsonArray }.getOrNull()
                ?: throw IOException("HTTP ${resp.code}")
            arr.objects().firstNotNullOfOrNull { it.obj("success")?.str("id") } ?: throw v1Error(arr)
        }
    }

    /** PUT /api/<key>/<path>. */
    suspend fun v1Put(t: Target, path: String, body: JsonObject): Unit = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://${t.host}/api/${t.key}/$path")
            .put(body.toString().toRequestBody(jsonType)).build()
        http.newCall(req).await().use { resp ->
            val arr = runCatching { HueJson.parseToJsonElement(resp.body!!.string()).jsonArray }.getOrNull()
                ?: throw IOException("HTTP ${resp.code}")
            if (arr.objects().none { it.containsKey("success") }) throw v1Error(arr)
        }
    }

    suspend fun v1Delete(t: Target, path: String): Unit = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://${t.host}/api/${t.key}/$path").delete().build()
        http.newCall(req).await().use { resp ->
            val arr = runCatching { HueJson.parseToJsonElement(resp.body!!.string()).jsonArray }.getOrNull()
                ?: throw IOException("HTTP ${resp.code}")
            // Already gone (type 3) counts as deleted.
            if (arr.objects().none { it.containsKey("success") } && arr.objects().none { it.obj("error")?.int("type") == 3 }) {
                throw v1Error(arr)
            }
        }
    }

    private fun v1Error(arr: JsonArray): IOException {
        val err = arr.objects().firstNotNullOfOrNull { it.obj("error") }
        return if (err?.int("type") == 1) UnauthorizedException()
        else HueApiException(err?.str("description") ?: "Bridge rejected the request")
    }

    /** Streams bridge events until the connection closes. Each callback gets one SSE payload. */
    suspend fun stream(t: Target, onOpen: () -> Unit, onEvents: (JsonArray) -> Unit) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://${t.host}/eventstream/clip/v2")
            .header(KEY_HEADER, t.key)
            .header("Accept", "text/event-stream")
            .build()
        val call = sse.newCall(req)
        coroutineScope {
            val canceller = launch { try { awaitCancellation() } finally { call.cancel() } }
            try {
                call.execute().use { resp ->
                    checkAuth(resp)
                    if (!resp.isSuccessful) throw IOException("Event stream HTTP ${resp.code}")
                    onOpen()
                    val source = resp.body!!.source()
                    val parser = SseParser()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        val data = parser.feed(line) ?: continue
                        val arr = runCatching { HueJson.parseToJsonElement(data).jsonArray }.getOrNull() ?: continue
                        onEvents(arr)
                    }
                }
            } finally {
                canceller.cancel()
            }
        }
    }

    private fun checkAuth(resp: Response) {
        if (resp.code == 401 || resp.code == 403) throw UnauthorizedException()
    }

    companion object {
        const val KEY_HEADER = "hue-application-key"
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
