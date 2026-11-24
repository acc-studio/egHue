package app.eghue

import app.eghue.shortcuts.LauncherShortcuts

import android.app.Application
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import app.eghue.data.Settings
import app.eghue.hue.BridgeTls
import app.eghue.hue.Discovery
import app.eghue.hue.HueClient
import app.eghue.hue.HueRepository
import app.eghue.hue.LanSocketFactory
import app.eghue.hue.NetworkMonitor
import app.eghue.update.AppUpdater
import app.eghue.update.createUpdater
import app.eghue.widget.HueWidget
import app.eghue.widget.WidgetPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class EgHueApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

val Context.container: AppContainer get() = (applicationContext as EgHueApp).container

/** Hand-rolled dependency container; the app is small enough not to need a DI framework. */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(app)
    val network = NetworkMonitor(app)
    val widgetPrefs = WidgetPrefs(app)

    /** For the public internet (update checks, Hue cloud discovery). Normal TLS validation. */
    val internetHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val tls = BridgeTls(bridgeId = { settings.bridgeId }, pinnedKey = { settings.certPin })

    /** For the bridge on the LAN: short timeouts, kept-alive connections, bridge identity check. */
    private val bridgeHttp: OkHttpClient = internetHttp.newBuilder()
        .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .sslSocketFactory(tls.socketFactory, tls.trustManager)
        .hostnameVerifier(tls.hostnameVerifier)
        .socketFactory(LanSocketFactory { network.networkFor(settings.bridgeHost)?.socketFactory })
        .build()

    private val sseHttp = bridgeHttp.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    private val probeHttp = bridgeHttp.newBuilder()
        .socketFactory(LanSocketFactory { null })
        .connectTimeout(900, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .callTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    val client = HueClient(bridgeHttp, sseHttp, probeHttp)
    val discovery = Discovery(app, internetHttp, client, network)
    val repository = HueRepository(app, settings, client, discovery, network, scope)
    val updater: AppUpdater = createUpdater(app, settings, internetHttp, scope)

    @OptIn(FlowPreview::class)
    fun start() {
        repository.init()
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> repository.setForeground(true)
                Lifecycle.Event.ON_STOP -> repository.setForeground(false)
                else -> Unit
            }
        })
        // Launcher-icon shortcuts follow the room list.
        scope.launch {
            repository.home.map { LauncherShortcuts.fingerprint(it) to it }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .debounce(1_000)
                .collect { (_, home) -> if (home.rooms.isNotEmpty()) LauncherShortcuts.publish(app, home) }
        }
        // Keep home-screen widgets in step with changes made in the app or by other Hue apps.
        scope.launch {
            repository.home.drop(1)
                .map { widgetPrefs.fingerprint(it) }
                .distinctUntilChanged()
                .debounce(250)
                .collect {
                    val ids = runCatching { GlanceAppWidgetManager(app).getGlanceIds(HueWidget::class.java) }.getOrDefault(emptyList())
                    if (ids.isNotEmpty()) runCatching { HueWidget().updateAll(app) }
                }
        }
    }
}
