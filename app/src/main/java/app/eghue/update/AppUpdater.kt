package app.eghue.update

import android.content.Intent
import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.StateFlow

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val version: String) : UpdateState
    data class Downloading(val version: String, val progress: Float) : UpdateState
    /** Downloaded; installs when you leave the app (or now, if you tap Install). */
    data class ReadyToInstall(val version: String) : UpdateState
    data class Installing(val version: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Distribution-specific updater: GitHub Releases (sideloaded) or Google Play. */
interface AppUpdater {
    val state: StateFlow<UpdateState>
    val autoUpdate: StateFlow<Boolean>
    /** Whether the user can switch automatic updates off (Play manages this itself). */
    val canToggleAutoUpdate: Boolean
    /** One line shown in Settings explaining where updates come from. */
    val sourceDescription: String

    fun setAutoUpdate(enabled: Boolean)
    fun onActivityCreated(activity: ComponentActivity)
    fun checkNow()
    fun installNow()

    /** An intent to grant a permission updates need, or null if nothing is missing. */
    fun missingPermissionIntent(): Intent? = null
}
