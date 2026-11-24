package app.eghue.update

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.eghue.data.Settings
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

fun createUpdater(app: Application, settings: Settings, http: OkHttpClient, scope: CoroutineScope): AppUpdater =
    PlayUpdater(app)

/**
 * Google Play build. Play installs updates automatically in the background; on top of that we
 * use in-app (flexible) updates so a new version downloads while you use the app and installs
 * the next time you leave it.
 */
class PlayUpdater(private val app: Application) : AppUpdater {
    private val manager = AppUpdateManagerFactory.create(app)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()
    override val autoUpdate: StateFlow<Boolean> = MutableStateFlow(true)
    override val canToggleAutoUpdate = false
    override val sourceDescription = "Updates are delivered by Google Play."
    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null

    private val listener = InstallStateUpdatedListener { s ->
        when (s.installStatus()) {
            InstallStatus.DOWNLOADING -> {
                val total = s.totalBytesToDownload()
                _state.value = UpdateState.Downloading("", if (total > 0) s.bytesDownloaded().toFloat() / total else 0f)
            }
            InstallStatus.DOWNLOADED -> {
                _state.value = UpdateState.ReadyToInstall("")
                if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) manager.completeUpdate()
            }
            InstallStatus.INSTALLED -> _state.value = UpdateState.UpToDate
            InstallStatus.FAILED -> _state.value = UpdateState.Failed("Update failed")
            else -> Unit
        }
    }

    init {
        manager.registerListener(listener)
        // A downloaded update installs as soon as the user leaves the app.
        ProcessLifecycleOwner.get().lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && _state.value is UpdateState.ReadyToInstall) manager.completeUpdate()
        })
    }

    override fun setAutoUpdate(enabled: Boolean) = Unit

    override fun onActivityCreated(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
        check(startFlow = true)
    }

    override fun checkNow() {
        _state.value = UpdateState.Checking
        check(startFlow = true)
    }

    override fun installNow() {
        if (_state.value is UpdateState.ReadyToInstall) manager.completeUpdate() else check(startFlow = true)
    }

    private fun check(startFlow: Boolean) {
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                when {
                    info.installStatus() == InstallStatus.DOWNLOADED -> _state.value = UpdateState.ReadyToInstall("")
                    info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                        info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> {
                        _state.value = UpdateState.Available("")
                        val l = launcher
                        if (startFlow && l != null) {
                            runCatching { manager.startUpdateFlowForResult(info, l, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()) }
                        }
                    }
                    else -> _state.value = UpdateState.UpToDate
                }
            }
            .addOnFailureListener { _state.value = UpdateState.Failed("Couldn't reach Google Play") }
    }
}
