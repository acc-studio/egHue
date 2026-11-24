package app.eghue.hue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Per-resource command pipeline. The first command for a resource is sent immediately; anything
 * submitted while it's in flight (or during the cool-down after it) is merged and sent as one
 * request with the latest values. Taps feel instant and slider drags never flood the bridge.
 */
class CommandQueue(
    private val scope: CoroutineScope,
    private val send: suspend (type: String, id: String, body: JsonObject) -> Unit,
    private val onError: (type: String, id: String, error: Throwable) -> Unit,
    private val intervalMs: (type: String) -> Long = ::defaultInterval,
) {
    private val pending = HashMap<String, JsonObject>()
    private val active = HashSet<String>()

    fun submit(type: String, id: String, body: JsonObject) {
        val key = "$type/$id"
        synchronized(this) {
            pending[key] = pending[key]?.let { deepMerge(it, body) } ?: body
            if (!active.add(key)) return
        }
        scope.launch {
            try {
                while (true) {
                    val next = synchronized(this@CommandQueue) {
                        pending.remove(key) ?: run { active.remove(key); null }
                    } ?: break
                    try {
                        send(type, id, next)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onError(type, id, e)
                    }
                    delay(intervalMs(type))
                }
            } catch (e: CancellationException) {
                synchronized(this@CommandQueue) { active.remove(key); pending.remove(key) }
                throw e
            }
        }
    }

    companion object {
        // Hue guidance: ~10 light commands/s; grouped commands are fanned out by the bridge, so go slower.
        fun defaultInterval(type: String): Long = when (type) {
            "light" -> 100L
            "grouped_light" -> 300L
            else -> 0L
        }
    }
}
