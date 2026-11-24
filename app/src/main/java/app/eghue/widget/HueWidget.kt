package app.eghue.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartActivity as actionStartIntent
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.eghue.R
import app.eghue.container
import app.eghue.hue.ColorUtil
import app.eghue.hue.HomeUi
import app.eghue.hue.HueRepository
import app.eghue.ui.MainActivity
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One tappable row in the widget. */
data class WidgetItem(val kind: String, val id: String, val name: String, val on: Boolean, val brightness: Int, val color: Int, val available: Boolean)

fun resolveItems(home: HomeUi, selection: List<String>): List<WidgetItem> = selection.mapNotNull { key ->
    val kind = key.substringBefore(':')
    val id = key.substringAfter(':')
    when (kind) {
        HueRepository.KIND_GROUP -> home.group(id)?.let {
            WidgetItem(kind, id, it.name, it.on, it.brightness.roundToInt(), it.color, it.groupedLightId != null && it.reachableLights > 0)
        }
        HueRepository.KIND_LIGHT -> home.light(id)?.let {
            WidgetItem(kind, id, it.name, it.on, it.brightness.roundToInt(), it.color, it.reachable)
        }
        HueRepository.KIND_SCENE -> home.scenes.values.flatten().firstOrNull { it.id == id }?.let { s ->
            val room = home.group(s.groupId)?.name
            WidgetItem(kind, id, if (room != null) "${s.name} · $room" else s.name, s.active, 0, ColorUtil.average(s.colors), true)
        }
        else -> null
    }
}

/**
 * The home-screen widget: a tidy stack of rooms/zones/lights. One tap toggles. That's it.
 * The tile glows in the light's actual colour when on, and is muted when off.
 */
class HueWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = context.container
        c.repository.ensureLoaded()
        c.repository.refreshIfStale(60_000)
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            val home by c.repository.home.collectAsState()
            val selection by c.widgetPrefs.flow(widgetId).collectAsState()
            val paired by c.settings.paired.collectAsState()
            val error by c.repository.widgetError.collectAsState()
            GlanceTheme {
                WidgetBody(context, widgetId, paired, resolveItems(home, selection), selection.isEmpty(), error)
            }
        }
    }
}

@Composable
private fun WidgetBody(context: Context, widgetId: Int, paired: Boolean, items: List<WidgetItem>, emptySelection: Boolean, error: Boolean) {
    val size = LocalSize.current
    var root = GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground).padding(6.dp)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) root = root.cornerRadius(android.R.dimen.system_app_widget_background_radius)
    Box(root, contentAlignment = Alignment.Center) {
        when {
            !paired -> Message("Tap to connect egHue", GlanceModifier.clickable(actionStartActivity<MainActivity>()))
            emptySelection || items.isEmpty() -> Message(
                if (emptySelection) "Tap to choose rooms" else "Loading…",
                GlanceModifier.clickable(actionStartIntent(configureIntent(context, widgetId))),
            )
            else -> Tiles(items, size, error)
        }
    }
}

@Composable
private fun Message(text: String, modifier: GlanceModifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun Tiles(items: List<WidgetItem>, size: DpSize, error: Boolean) {
    val gap = 5.dp
    val columns = when {
        size.width >= 330.dp -> 3
        size.width >= 200.dp -> 2
        else -> 1
    }.coerceAtMost(items.size)
    val rows = items.chunked(columns)
    val footer = if (error) 18.dp else 0.dp
    val available = size.height - 12.dp - footer
    val fit = (available - gap * (rows.size - 1)) / rows.size
    val tileHeight: Dp = fit.coerceIn(38.dp, 76.dp)
    val scrolls = ceil((tileHeight * rows.size + gap * (rows.size - 1)).value) > available.value + 1
    Column(GlanceModifier.fillMaxSize()) {
        LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
            items(rows.withIndex().toList(), itemId = { it.index.toLong() }) { (index, row) ->
                Column {
                    Row(GlanceModifier.fillMaxWidth().height(tileHeight)) {
                        row.forEachIndexed { i, item ->
                            if (i > 0) Spacer(GlanceModifier.width(gap))
                            Tile(item, GlanceModifier.defaultWeight().height(tileHeight), compact = tileHeight < 50.dp)
                        }
                        repeat(columns - row.size) {
                            Spacer(GlanceModifier.width(gap))
                            Spacer(GlanceModifier.defaultWeight())
                        }
                    }
                    if (index < rows.size - 1 || scrolls) Spacer(GlanceModifier.height(gap))
                }
            }
        }
        if (error) {
            Text(
                "Can't reach the bridge",
                style = TextStyle(color = GlanceTheme.colors.error, fontSize = 11.sp),
                modifier = GlanceModifier.fillMaxWidth().padding(start = 6.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun Tile(item: WidgetItem, modifier: GlanceModifier, compact: Boolean) {
    val scene = item.kind == HueRepository.KIND_SCENE
    val lit = ColorUtil.blend(item.color, 0xFFFFFFFF.toInt(), 0.12f)
    // Scene tiles always wear their colours: they're buttons, not switches.
    val coloured = item.on || scene
    val bg: ColorProvider = if (coloured) ColorProvider(Color(lit)) else GlanceTheme.colors.secondaryContainer
    val fg: ColorProvider = if (coloured) {
        ColorProvider(if (ColorUtil.prefersDarkText(lit)) Color(0xFF1E1B18) else Color.White)
    } else GlanceTheme.colors.onSecondaryContainer
    val state = when {
        scene -> if (item.on) "✓" else "▶"
        !item.available -> "—"
        item.on -> "${item.brightness}%"
        else -> "Off"
    }
    Box(
        modifier.clickable(
            actionRunCallback<ToggleAction>(
                actionParametersOf(ToggleAction.KindKey to item.kind, ToggleAction.IdKey to item.id)
            )
        ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_tile),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(bg),
        )
        Row(
            GlanceModifier.fillMaxSize().padding(horizontal = if (compact) 12.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                item.name,
                maxLines = 1,
                style = TextStyle(color = fg, fontSize = if (compact) 14.sp else 15.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(state, maxLines = 1, style = TextStyle(color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium))
        }
    }
}

private fun configureIntent(context: Context, widgetId: Int) =
    Intent(context, WidgetConfigActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Runs when a tile is tapped. Works even if the app process was not running. */
class ToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val kind = parameters[KindKey] ?: return
        val id = parameters[IdKey] ?: return
        val c = context.container
        c.repository.widgetToggle(kind, id) { HueWidget().updateAll(context) }
        HueWidget().updateAll(context)
    }

    companion object {
        val KindKey = ActionParameters.Key<String>("kind")
        val IdKey = ActionParameters.Key<String>("id")
    }
}

class HueWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HueWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { context.container.widgetPrefs.remove(it) }
    }
}
