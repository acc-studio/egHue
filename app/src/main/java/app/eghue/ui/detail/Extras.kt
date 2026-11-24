package app.eghue.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.eghue.hue.FixtureRole
import app.eghue.hue.GroupKind
import app.eghue.hue.GroupUi
import app.eghue.hue.HueRepository
import app.eghue.hue.LightUi
import app.eghue.hue.roleOf
import app.eghue.ui.components.NameDialog
import app.eghue.ui.components.SectionTitle
import kotlinx.coroutines.launch

/** Hue fixture types people actually use, with plain names. */
val fixtureTypes = listOf(
    "classic_bulb" to "Bulb", "sultan_bulb" to "Large bulb", "candle_bulb" to "Candle bulb", "spot_bulb" to "Spot bulb",
    "ceiling_round" to "Ceiling, round", "ceiling_square" to "Ceiling, square", "recessed_ceiling" to "Recessed ceiling",
    "pendant_round" to "Pendant, round", "pendant_long" to "Pendant, long",
    "floor_shade" to "Floor lamp", "floor_lantern" to "Floor lantern", "table_shade" to "Table lamp",
    "table_wash" to "Table wash", "desk_lamp" to "Desk lamp", "wall_shade" to "Wall lamp", "wall_spot" to "Wall spot",
    "hue_lightstrip" to "Light strip", "hue_play" to "Light bar", "hue_bloom" to "Bloom", "hue_iris" to "Iris",
    "hue_go" to "Go", "string_light" to "String lights",
)

fun effectLabel(e: String) = e.replace('_', ' ').replaceFirstChar(Char::uppercase)

fun roleLabel(r: FixtureRole) = when (r) {
    FixtureRole.CEILING -> "ceiling light"
    FixtureRole.PENDANT -> "pendant"
    FixtureRole.LAMP -> "lamp"
    FixtureRole.ACCENT -> "accent"
}

/** Full view: effects, blink, rename and fixture type for one light. */
@Composable
fun LightExtras(l: LightUi, repo: HueRepository) {
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    if (l.effects.isNotEmpty()) {
        SectionTitle("Effects")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = l.effect == null, onClick = { repo.setEffect(l.id, null) }, label = { Text("None") })
            l.effects.forEach { e ->
                FilterChip(selected = l.effect == e, onClick = { repo.setEffect(l.id, e) }, label = { Text(effectLabel(e)) })
            }
        }
    }
    SectionTitle("Light")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { repo.identify(l.id) }, label = { Text("Blink") }, leadingIcon = { Icon(Icons.Outlined.FlashOn, null) })
        AssistChip(onClick = { renaming = true }, label = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) })
        AssistChip(
            onClick = { typing = true },
            label = { Text(fixtureTypes.firstOrNull { it.first == l.archetype }?.second ?: "Fixture type") },
            leadingIcon = { Icon(Icons.Outlined.Category, null) },
        )
    }
    Text(
        listOfNotNull(l.productName, "used as a ${roleLabel(roleOf(l))} in presets").joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (renaming) NameDialog("Rename light", l.name, "Rename", onDismiss = { renaming = false }, onConfirm = { n ->
        renaming = false; scope.launch { repo.renameLight(l.id, n) }
    })
    if (typing) AlertDialog(
        onDismissRequest = { typing = false },
        title = { Text("Fixture type") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                fixtureTypes.forEach { (key, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { typing = false; scope.launch { repo.setLightArchetype(l.id, key) } }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = l.archetype == key, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { typing = false }) { Text("Close") } },
    )
}

/** Full view: rename a room or zone. */
@Composable
fun GroupExtras(g: GroupUi, repo: HueRepository) {
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { renaming = true },
        label = { Text("Rename ${if (g.kind == GroupKind.ROOM) "room" else "zone"}") },
        leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
    )
    if (renaming) NameDialog("Rename", g.name, "Rename", onDismiss = { renaming = false }, onConfirm = { n ->
        renaming = false; scope.launch { repo.renameGroup(g.id, n) }
    })
}
