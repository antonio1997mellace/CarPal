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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Bg = Color(0xFF0A0D12)
val Surface1 = Color(0xFF131821)
val Surface2 = Color(0xFF1B2230)
val Line = Color(0xFF263043)
val Ink = Color(0xFFEEF2F8)
val Muted = Color(0xFF8B97AB)
val Accent = Color(0xFF4F8CFF)
val Violet = Color(0xFFA78BFA)
val Good = Color(0xFF3DDC97)
val Warn = Color(0xFFFFC53D)
val Bad = Color(0xFFFF5C6C)

private val CategoryColors = listOf(Accent, Violet, Color(0xFF2DD4BF), Color(0xFFFF8A4C), Color(0xFFF472B6), Color(0xFF38BDF8))
fun categoryColor(categoria: String) = CategoryColors[EXPENSE_CATEGORIES.indexOf(categoria).mod(CategoryColors.size)]

fun Status.color() = when (this) {
    Status.OK -> Good
    Status.WARNING -> Warn
    Status.EXPIRED -> Bad
}

private val Scheme = darkColorScheme(
    primary = Accent, onPrimary = Color.White,
    primaryContainer = Color(0xFF1C3466), onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Accent, onSecondary = Color.White,
    secondaryContainer = Color(0xFF1C3466), onSecondaryContainer = Color(0xFFDCE7FF),
    tertiary = Violet,
    background = Bg, onBackground = Ink,
    surface = Bg, onSurface = Ink,
    surfaceVariant = Surface2, onSurfaceVariant = Muted,
    surfaceContainerLowest = Bg, surfaceContainerLow = Surface1, surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2, surfaceContainerHighest = Surface2,
    outline = Color(0xFF3A465C), outlineVariant = Line,
    error = Bad, onError = Color.White,
)

@Composable
fun GarageTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = Scheme,
    shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp)),
    content = content,
)

/** Bordered dark card: the base container of every list row and summary block. */
@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = Surface1, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Line),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}

/** Blue gradient card used for the vehicle itself. */
@Composable
fun Hero(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3B6CF6), Color(0xFF16254D))))
            .drawBehind { drawCircle(Color.White.copy(alpha = 0.07f), radius = size.height * 0.9f, center = Offset(size.width, 0f)) }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp), content = content,
    )
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
fun PillTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(CircleShape).background(Surface1).padding(4.dp)) {
        tabs.forEachIndexed { i, t ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).height(44.dp).clip(CircleShape).background(if (sel) Accent else Color.Transparent)
                    .selectable(selected = sel, role = Role.Tab, onClick = { onSelect(i) }),
                Alignment.Center,
            ) {
                Text(t, color = if (sel) Color.White else Muted, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
        if (b > 0) Box(Modifier.weight(b.toFloat()).fillMaxHeight().background(Violet))
    }
}

@Composable
fun BarChart(values: List<Double>, description: String) {
    Canvas(Modifier.fillMaxWidth().height(72.dp).semantics { contentDescription = description }) {
        val max = values.max()
        val base = values.min() * 0.7 // not from zero: real consumption varies by a few km/l, which would look flat
        val gap = 6.dp.toPx()
        val w = ((size.width - gap * (values.size - 1)) / values.size).coerceAtMost(40.dp.toPx())
        values.forEachIndexed { i, v ->
            val h = ((v - base) / (max - base)).toFloat().coerceIn(0.08f, 1f) * size.height
            drawRoundRect(
                color = if (i == values.lastIndex) Accent else Accent.copy(alpha = 0.35f),
                topLeft = Offset(i * (w + gap), size.height - h), size = Size(w, h), cornerRadius = CornerRadius(6.dp.toPx()),
            )
        }
    }
}
