package app.eghue.update

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.eghue.BuildConfig
import app.eghue.R
import app.eghue.container
import app.eghue.data.Settings
import app.eghue.hue.HueJson
import app.eghue.hue.arr
import app.eghue.hue.await
import app.eghue.hue.dbl
import app.eghue.hue.objects
import app.eghue.hue.str
import app.eghue.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

fun createUpdater(app: Application, settings: Settings, http: OkHttpClient, scope: CoroutineScope): AppUpdater =
    GithubUpdater(app, settings, http, scope)

/**
 * Self-updater for sideloaded installs. Polls the GitHub "latest release" for this repo, downloads
 * the APK, verifies it's a newer build of this same app, and installs it with PackageInstaller.
 * On Android 12+ updates then install silently (no prompt) once "install unknown apps" is allowed.
 * If you're using the app, installation waits until you leave it so it never closes under you.
 */
class GithubUpdater(
    private val app: Application,
    private val settings: Settings,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) : AppUpdater {
    private data class Release(val version: String, val apkUrl: String)

    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val _auto = MutableStateFlow(prefs.getBoolean(K_AUTO, true))
    override val autoUpdate: StateFlow<Boolean> = _auto.asStateFlow()
    override val canToggleAutoUpdate = true
    override val sourceDescription = "Updates come from egHue's GitHub releases (${BuildConfig.UPDATE_REPO})."

    private val mutex = Mutex()
    @Volatile private var readyFile: Pair<String, File>? = null

    init {
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
                // A downloaded update installs as soon as the user leaves the app.
                if (e == Lifecycle.Event.ON_STOP) readyFile?.let { (v, f) -> scope.launch { install(v, f) } }
            })
        }
        runCatching { UpdateWorker.schedule(app) }
    }

    override fun setAutoUpdate(enabled: Boolean) {
        prefs.edit { putBoolean(K_AUTO, enabled) }
        _auto.value = enabled
    }

    override fun onActivityCreated(activity: ComponentActivity) {
        if (Build.VERSION.SDK_INT >= 33 && settings.paired.value && !prefs.getBoolean(K_ASKED_NOTIF, false) &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
            prefs.edit { putBoolean(K_ASKED_NOTIF, true) }
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (System.currentTimeMillis() - prefs.getLong(K_LAST_CHECK, 0) > 30 * 60_000L) {
            scope.launch { run(userInitiated = false) }
        }
    }

    override fun checkNow() { scope.launch { run(userInitiated = true) } }

    override fun installNow() {
        scope.launch {
            val ready = readyFile
            if (ready != null) install(ready.first, ready.second) else run(userInitiated = true, forceInstall = true)
        }
    }

    override fun missingPermissionIntent(): Intent? {
        if (app.packageManager.canRequestPackageInstalls()) return null
        return Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Check → download → install. Called from the UI, on app start and by [UpdateWorker]. */
    suspend fun run(userInitiated: Boolean, forceInstall: Boolean = false) = mutex.withLock {
        val s = _state.value
        if (s is UpdateState.Downloading || s is UpdateState.Installing) return@withLock
        if (BuildConfig.UPDATE_REPO.startsWith("OWNER/")) {
            if (userInitiated) _state.value = UpdateState.Failed("Updates aren't configured for this build.")
            return@withLock
        }
        if (userInitiated) _state.value = UpdateState.Checking
        val rel = try {
            fetchLatest()
        } catch (e: Exception) {
            if (userInitiated) _state.value = UpdateState.Failed("Couldn't check for updates. Are you online?")
            return@withLock
        }
        prefs.edit { putLong(K_LAST_CHECK, System.currentTimeMillis()) }
        if (rel == null) { _state.value = UpdateState.UpToDate; return@withLock }

        readyFile?.takeIf { it.first == rel.version && it.second.exists() }?.let { (v, f) ->
            if (forceInstall || !isForeground()) install(v, f) else _state.value = UpdateState.ReadyToInstall(v)
            return@withLock
        }

        _state.value = UpdateState.Available(rel.version)
        if (!_auto.value && !forceInstall) {
            if (!userInitiated) notify(R.string.update_available_title, app.getString(R.string.update_available_text, rel.version), launchAppIntent())
            return@withLock
        }
        val file = try {
            download(rel)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("Download failed. Will try again later.")
            return@withLock
        }
        if (!verify(file)) {
            file.delete()
            _state.value = UpdateState.Failed("The downloaded update wasn't valid.")
            return@withLock
        }
        readyFile = rel.version to file
        if (forceInstall || userInitiated || !isForeground()) install(rel.version, file)
        else _state.value = UpdateState.ReadyToInstall(rel.version)
    }

    private suspend fun fetchLatest(): Release? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "egHue-updater")
            .build()
        return http.newCall(req).await().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val o = HueJson.parseToJsonElement(resp.body!!.string()).jsonObject
            val version = o.str("tag_name")?.removePrefix("v") ?: return null
            if (!Versions.isNewer(version, BuildConfig.VERSION_NAME)) return null
            val assets = o.arr("assets")?.objects().orEmpty().filter { it.str("name")?.endsWith(".apk") == true }
            val asset = assets.firstOrNull { it.str("name")?.contains("github") == true } ?: assets.firstOrNull() ?: return null
            Release(version, asset.str("browser_download_url") ?: return null)
        }
    }

    private suspend fun download(rel: Release): File = withContext(Dispatchers.IO) {
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "egHue-${rel.version}.apk")
        val req = Request.Builder().url(rel.apkUrl).header("User-Agent", "egHue-updater").build()
        http.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body!!
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        _state.value = UpdateState.Downloading(rel.version, total?.let { done.toFloat() / it } ?: 0f)
                    }
                }
            }
        }
        out
    }

    /** Same package and a higher version code. (Android itself checks the signature on install.) */
    private fun verify(file: File): Boolean {
        val info = app.packageManager.getPackageArchiveInfo(file.path, 0) ?: return false
        val current = app.packageManager.getPackageInfo(app.packageName, 0)
        return info.packageName == app.packageName &&
            PackageInfoCompat.getLongVersionCode(info) > PackageInfoCompat.getLongVersionCode(current)
    }

    private suspend fun install(version: String, file: File) = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext
        readyFile = null
        _state.value = UpdateState.Installing(version)
        try {
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(app, InstallReceiver::class.java).putExtra(EXTRA_VERSION, version)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(app, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("Couldn't start the update: ${e.message}")
        }
    }

    internal fun onInstallResult(context: Context, intent: Intent) {
        val version = intent.getStringExtra(EXTRA_VERSION) ?: ""
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (isForeground()) {
                    runCatching { context.startActivity(confirm) }
                } else {
                    notify(R.string.update_ready_title, context.getString(R.string.update_ready_text, version), confirm)
                }
                _state.value = UpdateState.ReadyToInstall(version)
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = UpdateState.Available(version)
            else -> _state.value = UpdateState.Failed(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { "Update failed: $it" } ?: "Update failed"
            )
        }
    }

    private fun isForeground() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun launchAppIntent() = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun notify(titleRes: Int, text: String, target: Intent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val pi = PendingIntent.getActivity(app, 1, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bulb)
            .setContentTitle(app.getString(titleRes))
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, n) }
    }

    companion object {
        private const val K_AUTO = "auto"
        private const val K_LAST_CHECK = "last_check"
        private const val K_ASKED_NOTIF = "asked_notifications"
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 42
        const val EXTRA_VERSION = "version"
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.container.updater as? GithubUpdater)?.onInstallResult(context, intent)
    }
}

/** Background update checks every 6 hours. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext.container.updater as? GithubUpdater)?.run(userInitiated = false)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("egHue-update-check", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
