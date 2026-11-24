package app.eghue.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.eghue.hue.ColorUtil
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Text colour that reads well on [background]. */
fun contentColorOn(background: Color): Color =
    if (ColorUtil.prefersDarkText(background.toArgb())) Color(0xFF1E1B18) else Color.White

/**
 * The home-screen tile. Tap toggles, horizontal drag dims, long-press (or the chevron) opens detail.
 * When on, the tile takes the light's colour and fills left-to-right with its brightness.
 */
@Composable
fun ControlTile(
    name: String,
    icon: ImageVector,
    on: Boolean,
    brightness: Float,
    color: Color,
    status: String,
    enabled: Boolean,
    dimmable: Boolean,
    onToggle: () -> Unit,
    onBrightness: (Float) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 108.dp,
) {
    val view = LocalView.current
    val scheme = MaterialTheme.colorScheme
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val shownOn = on || dragValue != null
    val shownBri = dragValue ?: brightness

    val offBg = scheme.surfaceContainerHigh
    val base by animateColorAsState(if (shownOn) lerp(scheme.surfaceContainerHighest, color, 0.45f) else offBg, tween(160), label = "base")
    val fill by animateColorAsState(if (shownOn) lerp(scheme.surfaceContainerHighest, color, 0.85f) else offBg, tween(160), label = "fill")
    val fraction by animateFloatAsState(
        targetValue = if (shownOn) (shownBri / 100f).coerceIn(0.04f, 1f) else 0f,
        animationSpec = if (dragValue != null) snap() else tween(220),
        label = "fraction",
    )
    val content = if (shownOn) contentColorOn(if (fraction > 0.45f) fill else base) else scheme.onSurface
    val subContent = if (shownOn) content.copy(alpha = 0.8f) else scheme.onSurfaceVariant

    val currentOn by rememberUpdatedState(on)
    val currentBri by rememberUpdatedState(brightness)
    val toggle by rememberUpdatedState(onToggle)
    val setBri by rememberUpdatedState(onBrightness)
    val open by rememberUpdatedState(onOpen)

    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(24.dp))
            .background(base)
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                contentDescription = name
                stateDescription = if (on) "On, ${brightness.roundToInt()} percent" else "Off"
                onClick(label = if (on) "Turn off" else "Turn on") { toggle(); true }
                onLongClick(label = "Open controls") { open(); true }
            }
            .pointerInput(enabled) {
                detectTapGestures(
                    onTap = {
                        if (enabled) {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            toggle()
                        }
                    },
                    onLongPress = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        open()
                    },
                )
            }
            .pointerInput(enabled, dimmable) {
                if (!enabled || !dimmable) return@pointerInput
                var lastTick = -1
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragValue = if (currentOn) currentBri else 1f
                        lastTick = -1
                    },
                    onDragEnd = { dragValue = null },
                    onDragCancel = { dragValue = null },
                ) { change, dx ->
                    change.consume()
                    val v = ((dragValue ?: currentBri) + dx / size.width * 100f).coerceIn(1f, 100f)
                    dragValue = v
                    val tick = (v / 10).toInt()
                    if (tick != lastTick) {
                        if (lastTick >= 0) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        lastTick = tick
                    }
                    setBri(v)
                }
            },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(fill))
        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.padding(top = 6.dp).size(24.dp))
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .pointerInput(Unit) { detectTapGestures { open() } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = "Open $name", tint = subContent)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (dragValue != null) "${shownBri.roundToInt()}%" else status,
                style = MaterialTheme.typography.bodySmall,
                color = subContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Big horizontal brightness bar: tap or drag anywhere. */
@Composable
fun BrightnessBar(
    value: Float,
    on: Boolean,
    color: Color,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val scheme = MaterialTheme.colorScheme
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = drag ?: if (on) value else 0f
    val current by rememberUpdatedState(shown)
    val change by rememberUpdatedState(onChange)
    val fraction by animateFloatAsState(
        (shown / 100f).coerceIn(0f, 1f),
        if (drag != null) snap() else tween(200),
        label = "bri",
    )
    val fill = lerp(scheme.surfaceContainerHighest, color, 0.85f)
    val text = if (fraction > 0.3f) contentColorOn(fill) else scheme.onSurface
    Box(
        modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerHighest)
            .semantics { contentDescription = "Brightness ${shown.roundToInt()} percent" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { o ->
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    change((o.x / size.width * 100f).coerceIn(1f, 100f))
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var lastTick = -1
                detectHorizontalDragGestures(
                    onDragStart = { drag = current.coerceAtLeast(1f); lastTick = -1 },
                    onDragEnd = { drag = null },
                    onDragCancel = { drag = null },
                ) { ch, dx ->
                    ch.consume()
                    val v = ((drag ?: current) + dx / size.width * 100f).coerceIn(1f, 100f)
                    drag = v
                    val tick = (v / 10).toInt()
                    if (tick != lastTick) {
                        if (lastTick >= 0) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        lastTick = tick
                    }
                    change(v)
                }
            },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(fill))
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.LightMode, contentDescription = null, tint = text)
            Text(
                if (on || drag != null) "${shown.roundToInt()}%" else "Off",
                style = MaterialTheme.typography.titleMedium,
                color = text,
            )
        }
    }
}

/** Colour-temperature bar, warm on the left, cool on the right. */
@Composable
fun TemperatureBar(
    mirek: Int?,
    mirekMin: Int,
    mirekMax: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val change by rememberUpdatedState(onChange)
    var drag by remember { mutableStateOf<Float?>(null) }
    val range = (mirekMax - mirekMin).coerceAtLeast(1)
    val pos = drag ?: mirek?.let { ((mirekMax - it).toFloat() / range).coerceIn(0f, 1f) }
    val stops = remember(mirekMin, mirekMax) {
        (0..6).map { i -> Color(ColorUtil.mirekToRgb(mirekMax - range * i / 6)) }
    }
    fun mirekAt(p: Float) = (mirekMax - p.coerceIn(0f, 1f) * range).roundToInt()
    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(stops))
            .semantics { contentDescription = "Colour temperature" }
            .pointerInput(Unit) { detectTapGestures { o -> change(mirekAt(o.x / size.width)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = o.x / size.width },
                    onDragEnd = { drag = null },
                    onDragCancel = { drag = null },
                ) { ch, _ ->
                    ch.consume()
                    val p = (ch.position.x / size.width).coerceIn(0f, 1f)
                    drag = p
                    change(mirekAt(p))
                }
            },
    ) {
        if (pos != null) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.height * 0.32f
                val x = (r + 6f) + pos * (size.width - 2 * (r + 6f))
                drawCircle(Color.White, r, Offset(x, size.height / 2), style = Stroke(width = 5f))
                drawCircle(Color.Black.copy(alpha = 0.25f), r + 3f, Offset(x, size.height / 2), style = Stroke(width = 1.5f))
            }
        }
    }
}

private val wheelHues = listOf(
    Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
)

/** Hue/saturation wheel. Angle = hue, distance from centre = saturation. */
@Composable
fun ColorWheel(
    current: Color?,
    onPick: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pick by rememberUpdatedState(onPick)
    var dragColor by remember { mutableStateOf<Color?>(null) }
    val shown = dragColor ?: current
    val hsv = remember(shown) {
        shown?.let { FloatArray(3).also { a -> android.graphics.Color.colorToHSV(it.toArgb(), a) } }
    }
    fun colorAt(p: Offset, w: Float, h: Float): Color {
        val cx = w / 2; val cy = h / 2
        val r = min(cx, cy)
        val dx = p.x - cx; val dy = p.y - cy
        val angle = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
        val sat = (hypot(dx, dy) / r).coerceIn(0f, 1f)
        return Color.hsv(angle, sat, 1f)
    }
    Canvas(
        modifier
            .widthIn(max = 300.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { contentDescription = "Colour wheel" }
            .pointerInput(Unit) {
                detectTapGestures { o -> pick(colorAt(o, size.width.toFloat(), size.height.toFloat())) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o ->
                        val c = colorAt(o, size.width.toFloat(), size.height.toFloat())
                        dragColor = c; pick(c)
                    },
                    onDragEnd = { dragColor = null },
                    onDragCancel = { dragColor = null },
                ) { ch, _ ->
                    ch.consume()
                    val c = colorAt(ch.position, size.width.toFloat(), size.height.toFloat())
                    dragColor = c
                    pick(c)
                }
            },
    ) {
        val r = size.minDimension / 2
        drawCircle(Brush.sweepGradient(wheelHues, center), r, center)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, r), r, center)
        if (hsv != null) {
            val a = Math.toRadians(hsv[0].toDouble())
            val d = hsv[1] * (r - 14.dp.toPx() / 2)
            val p = Offset(center.x + (cos(a) * d).toFloat(), center.y + (sin(a) * d).toFloat())
            drawCircle(shown!!, 13.dp.toPx(), p)
            drawCircle(Color.White, 14.dp.toPx(), p, style = Stroke(width = 3.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = 0.2f), 15.5.dp.toPx(), p, style = Stroke(width = 1.dp.toPx()))
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp),
    )
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

/** One light on a [MultiColorWheel]. */
data class WheelDot(val id: String, val color: Color, val label: String, val on: Boolean)

/**
 * A room's colour wheel with one dot per light (as in the Hue app). Drag a dot to change that light;
 * tap or drag anywhere else to set every light to that colour.
 */
@Composable
fun MultiColorWheel(
    dots: List<WheelDot>,
    onPickOne: (String, Color) -> Unit,
    onPickAll: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pickOne by rememberUpdatedState(onPickOne)
    val pickAll by rememberUpdatedState(onPickAll)
    val current by rememberUpdatedState(dots)
    // While dragging, the dragged dot ("*" = all) follows the finger rather than the (lagging) bridge.
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragColor by remember { mutableStateOf<Color?>(null) }

    fun colorAt(p: Offset, w: Float, h: Float): Color {
        val cx = w / 2; val cy = h / 2
        val r = min(cx, cy)
        val dx = p.x - cx; val dy = p.y - cy
        val angle = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
        return Color.hsv(angle, (hypot(dx, dy) / r).coerceIn(0f, 1f), 1f)
    }
    fun posOf(c: Color, center: Offset, r: Float, inset: Float): Offset {
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }
        val a = Math.toRadians(hsv[0].toDouble())
        val d = hsv[1] * (r - inset)
        return Offset(center.x + (cos(a) * d).toFloat(), center.y + (sin(a) * d).toFloat())
    }
    fun dotAt(p: Offset, w: Float, h: Float, grab: Float): String? {
        val center = Offset(w / 2, h / 2)
        val r = min(w, h) / 2
        return current.minByOrNull { (posOf(it.color, center, r, grab / 2) - p).getDistance() }
            ?.takeIf { (posOf(it.color, center, r, grab / 2) - p).getDistance() <= grab }?.id
    }

    Canvas(
        modifier
            .widthIn(max = 300.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { contentDescription = "Colour wheel with a dot for each light" }
            .pointerInput(Unit) {
                detectTapGestures { o -> pickAll(colorAt(o, size.width.toFloat(), size.height.toFloat())) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        dragging = dotAt(o, w, h, 28.dp.toPx()) ?: "*"
                        dragColor = colorAt(o, w, h)
                    },
                    onDragEnd = { dragging = null; dragColor = null },
                    onDragCancel = { dragging = null; dragColor = null },
                ) { ch, _ ->
                    ch.consume()
                    val c = colorAt(ch.position, size.width.toFloat(), size.height.toFloat())
                    dragColor = c
                    when (val d = dragging) { "*", null -> pickAll(c); else -> pickOne(d, c) }
                }
            },
    ) {
        val r = size.minDimension / 2
        drawCircle(Brush.sweepGradient(wheelHues, center), r, center)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, r), r, center)
        val inset = 14.dp.toPx() / 2
        for (dot in dots) {
            val c = if (dragColor != null && (dragging == "*" || dragging == dot.id)) dragColor!! else dot.color
            val p = posOf(c, center, r, inset)
            val big = dragging == dot.id
            val rad = (if (big) 16 else 12).dp.toPx()
            drawCircle(if (dot.on) c else c.copy(alpha = 0.45f), rad, p)
            drawCircle(Color.White, rad + 1.dp.toPx(), p, style = Stroke(width = 3.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = 0.2f), rad + 2.5.dp.toPx(), p, style = Stroke(width = 1.dp.toPx()))
        }
    }
}
