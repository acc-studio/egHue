package app.eghue.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.glance.appwidget.updateAll
import app.eghue.container
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps widgets current while the app isn't running. The bridge can't push to a phone, and Android
 * holds back broadcasts (like "phone unlocked") from apps it has frozen, so widgets resync on a
 * non-waking alarm instead: every few minutes while the phone is awake, never while it sleeps, and
 * straight away when the screen comes on if a resync came due while it was off.
 */
object WidgetSync {
    private const val INTERVAL_MS = 5 * 60_000L

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME, // not _WAKEUP: costs nothing while the phone sleeps
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            INTERVAL_MS,
            pending(context),
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context))
    }

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, WidgetSyncReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class WidgetSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        val c = context.container
        c.scope.launch {
            try {
                if (c.settings.paired.value) {
                    c.repository.ensureLoaded()
                    withTimeoutOrNull(8_000) { c.repository.syncIfStale(60_000) }
                    HueWidget().updateAll(context)
                }
            } finally {
                done.finish()
            }
        }
    }
}
