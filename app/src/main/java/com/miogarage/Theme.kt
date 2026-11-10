package com.miogarage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Global so the palette below can be plain top-level names; set once per composition by GarageTheme.
var isDark by mutableStateOf(true)
    private set

private fun pick(dark: Long, light: Long) = Color(if (isDark) dark else light)

// Black, greys and one orange accent.
val Bg get() = pick(0xFF000000, 0xFFF4F4F5)
val Surface1 get() = pick(0xFF121213, 0xFFFFFFFF)
val Surface2 get() = pick(0xFF1E1E20, 0xFFE9E9EB)
val Line get() = pick(0xFF262628, 0xFFDEDEE1)
val Ink get() = pick(0xFFF5F5F7, 0xFF111112)
val Muted get() = pick(0xFF8E8E93, 0xFF636368)
val Accent get() = pick(0xFFFF7A1A, 0xFFC4530A)
val OnAccent get() = pick(0xFF111112, 0xFFFFFFFF) // dark text on the bright orange, white on the deeper light-theme one
val Steel get() = pick(0xFFA1A1A6, 0xFF8A8A8F) // neutral second series next to the accent
val Good get() = pick(0xFF3DDC97, 0xFF12905A)
val Warn get() = pick(0xFFFFC53D, 0xFFA86A00)
val Bad get() = pick(0xFFFF5C6C, 0xFFD43A4A)

// Status colours for text on the hero card, which is dark in both themes.
val HeroGood = Color(0xFF3DDC97)
val HeroWarn = Color(0xFFFFC53D)
val HeroBad = Color(0xFFFF8A95)

fun categoryColor(categoria: String): Color {
    val colors = listOf(Accent, Steel, pick(0xFFFFB866, 0xFFA85F00), pick(0xFFD1D1D6, 0xFF48484C))
    return colors[EXPENSE_CATEGORIES.indexOf(categoria).mod(colors.size)]
}

fun Status.color() = when (this) {
    Status.OK -> Good
    Status.WARNING -> Warn
    Status.EXPIRED -> Bad
}

private fun scheme() = (if (isDark) darkColorScheme() else lightColorScheme()).copy(
    primary = Accent, onPrimary = OnAccent,
    primaryContainer = pick(0xFF3D2410, 0xFFFFE2CC), onPrimaryContainer = pick(0xFFFFDCC2, 0xFF5A2600),
    secondary = Accent, onSecondary = OnAccent,
    secondaryContainer = pick(0xFF3D2410, 0xFFFFE2CC), onSecondaryContainer = pick(0xFFFFDCC2, 0xFF5A2600),
    tertiary = Steel,
    background = Bg, onBackground = Ink,
    surface = Bg, onSurface = Ink,
    surfaceVariant = Surface2, onSurfaceVariant = Muted,
    surfaceContainerLowest = Bg, surfaceContainerLow = Surface1, surfaceContainer = Surface1,
    surfaceContainerHigh = pick(0xFF1E1E20, 0xFFFFFFFF), surfaceContainerHighest = Surface2,
    outline = pick(0xFF3A3A3D, 0xFFB9B9BE), outlineVariant = Line,
    error = Bad, onError = Color.White,
)

@Composable
fun GarageTheme(content: @Composable () -> Unit) {
    isDark = when (Prefs.theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = scheme(),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp)),
        content = content,
    )
}

/** Bordered dark card: the base container of every list row and summary block. */
@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = Surface1, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Line),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}

/** Graphite card with an orange glow, used for the vehicle itself; dark in both themes. */
@Composable
fun Hero(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2C2C2F), Color(0xFF111112))))
            .drawBehind {
                val c = Offset(size.width, 0f)
                drawCircle(Brush.radialGradient(listOf(Color(0x59FF7A1A), Color.Transparent), c, size.height * 1.2f), size.height * 1.2f, c)
            }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp), content = content,
    )
}

/**
 * Half-dial odometer for the hero card, with the total km inside.
 * The orange arc is the progress inside the current 10,000 km, since an odometer has no maximum to fill towards.
 */
@Composable
fun Odometer(km: Int, modifier: Modifier = Modifier) {
    val orange = Color(0xFFFF7A1A) // fixed: the hero card is dark in both themes
    Box(modifier.size(152.dp, 80.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 8.dp.toPx()
            val r = 72.dp.toPx()
            val c = Offset(size.width / 2, r + w / 2)
            val box = Size(r * 2, r * 2)
            val stroke = Stroke(w, cap = StrokeCap.Round)
            drawArc(Color.White.copy(alpha = 0.12f), 180f, 180f, false, c - Offset(r, r), box, style = stroke)
            drawArc(orange, 180f, 180f * (km % 10_000) / 10_000f, false, c - Offset(r, r), box, style = stroke)
            for (i in 0..6) {
                val a = Math.toRadians(180.0 + i * 30)
                val dir = Offset(kotlin.math.cos(a).toFloat(), kotlin.math.sin(a).toFloat())
                drawLine(Color.White.copy(alpha = 0.3f), c + dir * (r - 12.dp.toPx()), c + dir * (r - 7.dp.toPx()), 1.5.dp.toPx(), StrokeCap.Round)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("KM", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
            Text(km.km(), color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, maxLines = 1)
        }
    }
}

/** Italian licence plate: white with the two blue side bands. */
@Composable
fun Plate(targa: String) {
    Row(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White).height(IntrinsicSize.Min)) {
        Box(Modifier.width(9.dp).fillMaxHeight().background(Color(0xFF0B3BA8)))
        Text(
            targa, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = Color(0xFF10141C),
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 2.sp, maxLines = 1,
        )
        Box(Modifier.width(9.dp).fillMaxHeight().background(Color(0xFF0B3BA8)))
    }
}

@Composable
fun Pill(text: String, color: Color, container: Color = color.copy(alpha = 0.14f)) {
    Text(
        text, Modifier.clip(CircleShape).background(container).padding(horizontal = 10.dp, vertical = 5.dp),
        color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1,
    )
}

@Composable
fun PillTabs(
    tabs: List<String>, selected: Int, modifier: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    container: Color = Surface1, height: Dp = 44.dp, onSelect: (Int) -> Unit,
) {
    Row(modifier.fillMaxWidth().clip(CircleShape).background(container).padding(4.dp)) {
        tabs.forEachIndexed { i, t ->
            val sel = i == selected
            Box(
                // width follows label length, so "Rifornimenti" fits next to "Spese" on narrow phones
                Modifier.weight(t.length + 3f).height(height).clip(CircleShape).background(if (sel) Accent else Color.Transparent)
                    .selectable(selected = sel, role = Role.Tab, onClick = { onSelect(i) }),
                Alignment.Center,
            ) {
                Text(t, color = if (sel) OnAccent else Muted, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
fun Dot(color: Color) = Box(Modifier.size(10.dp).clip(CircleShape).background(color))

/** Two-part proportion bar; falls back to an empty track when both values are zero. */
@Composable
fun SplitBar(a: Double, b: Double) {
    Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(Surface2)) {
        if (a > 0) Box(Modifier.weight(a.toFloat()).fillMaxHeight().background(Accent))
        if (b > 0) Box(Modifier.weight(b.toFloat()).fillMaxHeight().background(Steel))
    }
}

@Composable
fun BarChart(values: List<Double>, description: String, modifier: Modifier = Modifier) {
    val accent = Accent
    Canvas(modifier.height(56.dp).semantics { contentDescription = description }) {
        val max = values.max()
        val base = values.min() * 0.7 // not from zero: real consumption varies by a few km/l, which would look flat
        val gap = 6.dp.toPx()
        val w = ((size.width - gap * (values.size - 1)) / values.size).coerceAtMost(40.dp.toPx())
        values.forEachIndexed { i, v ->
            val h = ((v - base) / (max - base)).toFloat().coerceIn(0.08f, 1f) * size.height
            drawRoundRect(
                color = if (i == values.lastIndex) accent else accent.copy(alpha = 0.35f),
                topLeft = Offset(i * (w + gap), size.height - h), size = Size(w, h), cornerRadius = CornerRadius(6.dp.toPx()),
            )
        }
    }
}
