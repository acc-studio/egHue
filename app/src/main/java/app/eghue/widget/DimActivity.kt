package app.eghue.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eghue.container
import app.eghue.hue.HueRepository
import app.eghue.ui.MainActivity
import app.eghue.ui.components.BrightnessBar
import app.eghue.ui.theme.EgHueTheme
import kotlin.math.roundToInt

/**
 * The widget's dimmer: tapping a tile's percentage opens this small sheet over the home screen
 * with the same brightness bar as the app. Tap outside to close.
 */
class DimActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(EXTRA_KIND) ?: return finish()
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val c = container
        setContent {
            val mode by c.settings.theme.collectAsStateWithLifecycle()
            val dynamic by c.settings.dynamicColor.collectAsStateWithLifecycle()
            val home by c.repository.home.collectAsStateWithLifecycle()
            val repo = c.repository
            val group = if (kind == HueRepository.KIND_GROUP) home.group(id) else null
            val light = if (kind == HueRepository.KIND_LIGHT) home.light(id) else null
            val name = group?.name ?: light?.name
            val on = group?.on ?: light?.on ?: false
            val brightness = group?.brightness ?: light?.brightness ?: 0f
            val color = group?.color ?: light?.color ?: 0
            val enabled = group?.let { it.groupedLightId != null && it.reachableLights > 0 } ?: light?.reachable ?: false
            LaunchedEffect(name) { if (name == null && repo.loaded.value) finish() }
            EgHueTheme(mode, dynamic) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)
                            // Swallow taps on the sheet itself.
                            .clickable(remember { MutableInteractionSource() }, indication = null) { },
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(name ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (!enabled) "Unreachable" else if (on) "${brightness.roundToInt()}%" else "Off",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = on,
                                    enabled = enabled,
                                    onCheckedChange = { v -> if (group != null) repo.setGroupOn(id, v) else repo.setLightOn(id, v) },
                                )
                            }
                            BrightnessBar(
                                brightness, on, Color(color), enabled,
                                onChange = { v -> if (group != null) repo.setGroupBrightness(id, v) else repo.setLightBrightness(id, v) },
                            )
                            TextButton(
                                onClick = {
                                    startActivity(Intent(this@DimActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    finish()
                                },
                                modifier = Modifier.align(Alignment.End),
                            ) { Text("Open egHue") }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_ID = "id"

        fun intent(context: Context, kind: String, id: String): Intent =
            Intent(context, DimActivity::class.java)
                // A distinct URI per tile, so each tile's pending intent stays its own.
                .setData(Uri.parse("eghue://dim/$kind/$id"))
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
