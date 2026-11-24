package app.eghue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.eghue.hue.ColorUtil
import app.eghue.hue.SceneUi

/** Background brush showing a scene's colours. */
fun sceneBrush(colors: List<Int>): Brush {
    val c = colors.ifEmpty { listOf(ColorUtil.WARM_WHITE) }.map { Color(it) }
    return if (c.size == 1) Brush.linearGradient(listOf(c[0], c[0])) else Brush.linearGradient(c)
}

/** Scene tiles, two per row. [onAdd] adds a trailing "save current look" tile. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SceneGrid(
    scenes: List<SceneUi>,
    onRecall: (String) -> Unit,
    onLongPress: ((SceneUi) -> Unit)? = null,
    addLabel: String? = null,
    onAdd: (() -> Unit)? = null,
) {
    val count = scenes.size + if (onAdd != null) 1 else 0
    FlowRow(
        maxItemsInEachRow = 2,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        scenes.forEach { s ->
            SceneChip(
                s,
                onClick = { onRecall(s.id) },
                onLongClick = onLongPress?.let { f -> { f(s) } },
                modifier = Modifier.weight(1f),
            )
        }
        if (onAdd != null) AddChip(addLabel ?: "Save current look", onAdd, Modifier.weight(1f))
        if (count % 2 == 1) Spacer(Modifier.weight(1f))
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SceneChip(scene: SceneUi, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, height: Dp = 72.dp) {
    val outline = MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(18.dp))
            .background(sceneBrush(scene.colors))
            .then(if (scene.active) Modifier.border(3.dp, outline, RoundedCornerShape(18.dp)) else Modifier)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = "Activate ${scene.name}",
                onLongClickLabel = if (onLongClick != null) "More options" else null,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))))
        Text(
            scene.name,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
        if (scene.active) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = "Active",
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp),
            )
        }
    }
}

@Composable
private fun AddChip(label: String, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .height(72.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.align(Alignment.TopStart).padding(12.dp).size(20.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
    }
}

/** A small round swatch of a scene's colours. */
@Composable
fun SceneSwatch(colors: List<Int>, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(modifier.size(size).clip(CircleShape).background(sceneBrush(colors)))
}
