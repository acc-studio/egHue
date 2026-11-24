package app.eghue.ui.onboarding

import android.app.Application
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.eghue.container
import app.eghue.hue.FoundBridge
import app.eghue.hue.HueClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OnboardingViewModel(app: Application) : AndroidViewModel(app) {
    data class UiState(
        val searching: Boolean = true,
        val bridges: List<FoundBridge> = emptyList(),
        val pairing: FoundBridge? = null,
        val secondsLeft: Int = 0,
        val error: String? = null,
        val checkingManual: Boolean = false,
        val manualOpen: Boolean = false,
    )

    private val c = app.container
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var searchJob: Job? = null
    private var pairJob: Job? = null
    private var autoJob: Job? = null

    init { search() }

    fun search() {
        searchJob?.cancel(); autoJob?.cancel()
        _state.update { it.copy(searching = true, bridges = emptyList(), error = null, pairing = null) }
        searchJob = viewModelScope.launch {
            c.discovery.discover().collect { b ->
                _state.update { s -> if (s.bridges.any { it.id == b.id }) s else s.copy(bridges = s.bridges + b) }
                // One bridge (the usual case): go straight to pairing after a short grace period.
                if (autoJob == null) autoJob = viewModelScope.launch {
                    delay(1_500)
                    val s = _state.value
                    if (s.bridges.size == 1 && s.pairing == null && !s.manualOpen) pair(s.bridges.first())
                }
            }
            _state.update { it.copy(searching = false) }
        }
    }

    fun pair(b: FoundBridge) {
        searchJob?.cancel()
        pairJob?.cancel()
        _state.update { it.copy(pairing = b, secondsLeft = PAIR_SECONDS, error = null, searching = false) }
        pairJob = viewModelScope.launch {
            val deadline = SystemClock.elapsedRealtime() + PAIR_SECONDS * 1000L
            var lastError: String? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                _state.update { it.copy(secondsLeft = ((deadline - SystemClock.elapsedRealtime()) / 1000).toInt()) }
                when (val r = runCatching { c.client.pair(b.host) }.getOrElse { HueClient.PairResult.Error(it.message ?: "Connection failed") }) {
                    is HueClient.PairResult.Success -> {
                        c.settings.saveBridge(b.host, b.id, b.name, r.appKey, r.clientKey, r.certPin)
                        return@launch
                    }
                    HueClient.PairResult.ButtonNotPressed -> lastError = null
                    is HueClient.PairResult.Error -> lastError = r.message
                }
                delay(1_000)
            }
            _state.update {
                it.copy(pairing = null, error = lastError?.let { e -> "Couldn't pair: $e" } ?: "Timed out. Press the round button on top of the bridge, then try again.")
            }
        }
    }

    fun toggleManual() = _state.update { it.copy(manualOpen = !it.manualOpen) }

    fun cancelPairing() {
        pairJob?.cancel()
        _state.update { it.copy(pairing = null) }
    }

    fun connectManual(input: String) {
        val host = input.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (host.isEmpty()) return
        _state.update { it.copy(checkingManual = true, error = null) }
        viewModelScope.launch {
            runCatching { c.client.config(host) }
                .onSuccess { cfg ->
                    _state.update { it.copy(checkingManual = false) }
                    pair(FoundBridge(cfg.id, cfg.name, host, cfg.model))
                }
                .onFailure { e ->
                    android.util.Log.w("egHue", "Manual connect to $host failed", e)
                    _state.update { it.copy(checkingManual = false, error = "No Hue Bridge answered at $host") }
                }
        }
    }

    companion object { const val PAIR_SECONDS = 60 }
}

@Composable
fun OnboardingScreen(vm: OnboardingViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Text("egHue", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            AnimatedContent(targetState = s.pairing != null, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "step") { pairing ->
                if (pairing) PairingStep(s, onCancel = vm::cancelPairing)
                else SearchStep(s, s.manualOpen, onManualToggle = vm::toggleManual, onPick = vm::pair, onSearch = vm::search, onManual = vm::connectManual)
            }
        }
    }
}

@Composable
private fun SearchStep(
    s: OnboardingViewModel.UiState,
    manual: Boolean,
    onManualToggle: () -> Unit,
    onPick: (FoundBridge) -> Unit,
    onSearch: () -> Unit,
    onManual: (String) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (s.searching) "Looking for your Hue Bridge…" else if (s.bridges.isEmpty()) "No bridge found" else "Choose your bridge",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Make sure your phone is on the same Wi-Fi as the bridge.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        if (s.searching && s.bridges.isEmpty()) {
            CircularProgressIndicator()
            Spacer(Modifier.height(28.dp))
        }
        s.bridges.forEach { b ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onPick(b) },
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Router, contentDescription = null)
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.name, style = MaterialTheme.typography.titleMedium)
                        Text(b.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
        s.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        if (!s.searching) {
            Button(onClick = onSearch) { Text("Search again") }
            Spacer(Modifier.height(8.dp))
        }
        TextButton(onClick = onManualToggle) { Text(if (manual) "Hide" else "Enter IP address instead") }
        if (manual) {
            var ip by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it },
                label = { Text("Bridge IP address") },
                placeholder = { Text("192.168.1.20") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onManual(ip) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { onManual(ip) }, enabled = ip.isNotBlank() && !s.checkingManual) {
                Text(if (s.checkingManual) "Checking…" else "Connect")
            }
            Text(
                "Find it in the Hue app under Settings › Bridges › ⓘ.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun PairingStep(s: OnboardingViewModel.UiState, onCancel: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Press the button on your bridge", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "It's the big round button on top of ${s.pairing?.name ?: "the bridge"}. egHue connects as soon as you press it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        BridgeIllustration()
        Spacer(Modifier.height(36.dp))
        LinearProgressIndicator(
            progress = { s.secondsLeft / OnboardingViewModel.PAIR_SECONDS.toFloat() },
            modifier = Modifier.fillMaxWidth(0.6f),
        )
        Spacer(Modifier.height(8.dp))
        Text("Waiting… ${s.secondsLeft}s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun BridgeIllustration() {
    val t = rememberInfiniteTransition(label = "pulse")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val body = MaterialTheme.colorScheme.surfaceContainerHighest
    val edge = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(180.dp)) {
            val side = size.minDimension * 0.72f
            val tl = Offset((size.width - side) / 2, (size.height - side) / 2)
            drawRoundRect(body, tl, Size(side, side), CornerRadius(side * 0.2f))
            drawRoundRect(edge, tl, Size(side, side), CornerRadius(side * 0.2f), style = Stroke(2.dp.toPx()))
            val r = side * 0.2f
            drawCircle(accent.copy(alpha = (1f - pulse) * 0.6f), r + pulse * side * 0.35f, center, style = Stroke(4.dp.toPx()))
            drawCircle(accent, r, center)
        }
    }
}
