package app.eghue.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.eghue.hue.GroupUi
import app.eghue.hue.HueRepository
import app.eghue.hue.SceneUi
import app.eghue.ui.components.ConfirmDialog
import app.eghue.ui.components.NameDialog
import app.eghue.ui.components.SceneSwatch
import kotlinx.coroutines.launch

/** Long-press menu for a scene: edit, update to the current look, rename, delete. */
@Composable
fun SceneActions(scene: SceneUi, group: GroupUi, repo: HueRepository, onDismiss: () -> Unit, onEdit: ((String) -> Unit)?) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf("menu") }
    when (step) {
        "rename" -> NameDialog(
            title = "Rename scene",
            initial = scene.name,
            confirmLabel = "Rename",
            onDismiss = onDismiss,
            onConfirm = { name -> onDismiss(); scope.launch { repo.renameScene(scene.id, name) } },
        )
        "delete" -> ConfirmDialog(
            title = "Delete “${scene.name}”?",
            text = "It's removed from your bridge, so it also disappears from the Hue app and any remote that uses it.",
            confirmLabel = "Delete",
            onDismiss = onDismiss,
            onConfirm = { onDismiss(); scope.launch { repo.deleteScene(scene.id) } },
        )
        "update" -> ConfirmDialog(
            title = "Update “${scene.name}”?",
            text = "Saves how ${group.name}'s lights look right now into this scene.",
            confirmLabel = "Update",
            onDismiss = onDismiss,
            onConfirm = { onDismiss(); scope.launch { repo.updateSceneFromCurrent(scene.id, null) } },
        )
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { SceneSwatch(scene.colors) },
            title = { Text(scene.name) },
            text = {
                Column {
                    if (onEdit != null) MenuRow(Icons.Outlined.Tune, "Edit lights") { onDismiss(); onEdit(scene.id) }
                    MenuRow(Icons.Outlined.CameraAlt, "Update to current look") { step = "update" }
                    MenuRow(Icons.Outlined.DriveFileRenameOutline, "Rename") { step = "rename" }
                    MenuRow(Icons.Outlined.Delete, "Delete") { step = "delete" }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
    }
}

@Composable
fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
