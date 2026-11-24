package app.eghue.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eghue.AppContainer
import app.eghue.container
import app.eghue.data.ViewMode
import app.eghue.ui.full.FullApp
import app.eghue.ui.home.HomeScreen
import app.eghue.ui.onboarding.OnboardingScreen
import app.eghue.ui.settings.RemoteScreen
import app.eghue.ui.settings.SettingsScreen
import app.eghue.ui.theme.EgHueTheme
import app.eghue.ui.theme.isAppInDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val c = container
        c.updater.onActivityCreated(this)
        setContent { AppTheme(c) { AppRoot(c) } }
    }
}

/** Theme wrapper shared by the app and the widget configuration screen. */
@Composable
fun ComponentActivity.AppTheme(c: AppContainer, content: @Composable () -> Unit) {
    val mode by c.settings.theme.collectAsStateWithLifecycle()
    val dynamic by c.settings.dynamicColor.collectAsStateWithLifecycle()
    val dark = isAppInDarkTheme(mode)
    DisposableEffect(dark) {
        val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { }
    }
    EgHueTheme(mode, dynamic) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { content() }
    }
}

@Composable
private fun AppRoot(c: AppContainer) {
    val paired by c.settings.paired.collectAsStateWithLifecycle()
    val viewMode by c.settings.viewMode.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf("home") }
    if (!paired) screen = "home"
    AnimatedContent(
        targetState = if (!paired) "onboarding" else screen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen",
    ) { s ->
        when (s) {
            "onboarding" -> OnboardingScreen()
            "settings" -> {
                BackHandler { screen = "home" }
                SettingsScreen(c, onBack = { screen = "home" }, onOpenRemote = { screen = "remote:$it" })
            }
            else -> if (s.startsWith("remote:")) {
                // Remotes open from Settings, or from the Devices tab ("|home").
                val back = if (s.endsWith("|home")) "home" else "settings"
                BackHandler { screen = back }
                RemoteScreen(c, s.removePrefix("remote:").removeSuffix("|home"), onBack = { screen = back })
            } else if (viewMode == ViewMode.FULL) {
                FullApp(c, onOpenSettings = { screen = "settings" }, onOpenRemote = { screen = "remote:$it|home" })
            } else HomeScreen(c, onOpenSettings = { screen = "settings" })
        }
    }
}
