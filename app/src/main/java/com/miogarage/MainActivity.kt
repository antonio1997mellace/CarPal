package com.miogarage

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val dao = GarageDb.get(this).dao()
        setContent {
            GarageTheme {
                val dark = isDark
                // system bar icons must contrast with the app theme, not the phone's
                LaunchedEffect(dark) {
                    val t = android.graphics.Color.TRANSPARENT
                    val bars = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
                    enableEdgeToEdge(bars, bars)
                }
                GarageApp(dao)
            }
        }
    }
}

private sealed interface Screen {
    data object Vehicles : Screen
    data object Settings : Screen
    data class VehicleForm(val vehicle: Vehicle?) : Screen
    data class Detail(val id: Long) : Screen
}

private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private fun Long.date() = LocalDate.ofEpochDay(this).format(dateFmt)
private fun Double.eur() = String.format(Locale.ITALY, "%.2f €", this)
private fun Double.dec(digits: Int) = String.format(Locale.ITALY, "%.${digits}f", this)
private fun Double.input() = toBigDecimal().stripTrailingZeros().toPlainString().replace('.', ',')
fun Int.km() = String.format(Locale.ITALY, "%,d", this)
private fun today() = LocalDate.now().toEpochDay()

private fun deadlineLabel(left: Long) = when {
    left < 0 -> "scaduta da ${-left} gg"
    left == 0L -> "scade oggi"
    else -> "tra $left gg"
}

@Composable
fun GarageApp(dao: GarageDao) {
    var screen by remember { mutableStateOf<Screen>(Screen.Vehicles) }
    BackHandler(screen != Screen.Vehicles) { screen = Screen.Vehicles }
    when (val s = screen) {
        Screen.Vehicles -> VehicleList(
            dao, onOpen = { screen = Screen.Detail(it) }, onAdd = { screen = Screen.VehicleForm(null) }, onSettings = { screen = Screen.Settings },
        )
        Screen.Settings -> SettingsScreen(dao, onBack = { screen = Screen.Vehicles })
        is Screen.VehicleForm -> VehicleFormScreen(dao, s.vehicle, onDone = { screen = Screen.Vehicles })
        is Screen.Detail -> VehicleDetail(dao, s.id, onBack = { screen = Screen.Vehicles }, onEdit = { screen = Screen.VehicleForm(it) })
    }
}

// ---------- Vehicles ----------

@Composable
private fun VehicleList(dao: GarageDao, onOpen: (Long) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit) {
    val vehicles by remember { dao.vehicles() }.collectAsState(null)
    val deadlines by remember { dao.allDeadlinesFlow() }.collectAsState(emptyList())
    val today = today()
    Scaffold(floatingActionButton = { if (!vehicles.isNullOrEmpty()) AddFab("Aggiungi auto", onAdd) }) { pad ->
        val list = vehicles ?: return@Scaffold
        Column(Modifier.padding(pad)) {
            Row(Modifier.padding(16.dp, 20.dp, 4.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Il mio garage", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold)
                    Text(
                        when (list.size) {
                            0 -> "Nessun veicolo"
                            1 -> "1 veicolo"
                            else -> "${list.size} veicoli"
                        },
                        color = Muted,
                    )
                }
                IconButton(onSettings) { Icon(Icons.Default.Settings, "Impostazioni") }
            }
            if (list.isEmpty()) {
                EmptyGarage(onAdd)
                return@Column
            }
            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(list, key = { it.id }) { v ->
                    val mine = deadlines.filter { it.vehicleId == v.id }
                    val expired = mine.count { status(it, today) == Status.EXPIRED }
                    val warning = mine.count { status(it, today) == Status.WARNING }
                    Hero(onClick = { onOpen(v.id) }) {
                        Column {
                            Text(
                                "${v.marca} ${v.modello}".trim(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold,
                                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(listOfNotNull(v.alimentazione, v.anno?.toString()).joinToString(" · "), color = Color.White.copy(alpha = 0.7f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Plate(v.targa)
                            Spacer(Modifier.weight(1f))
                            val dark = Color.Black.copy(alpha = 0.35f)
                            when {
                                expired > 0 -> Pill(if (expired == 1) "1 scaduta" else "$expired scadute", HeroBad, dark)
                                warning > 0 -> Pill("$warning in scadenza", HeroWarn, dark)
                                mine.isEmpty() -> Pill("Nessuna scadenza", Color.White.copy(alpha = 0.8f), dark)
                                else -> Pill("Tutto in regola", HeroGood, dark)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyGarage(onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Box(Modifier.size(104.dp).clip(CircleShape).background(Surface1), Alignment.Center) { Text("🚗", fontSize = 46.sp) }
        Spacer(Modifier.height(24.dp))
        Text("Il garage è vuoto", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Aggiungi la tua auto per tenere sotto controllo scadenze, rifornimenti e spese. Se hai un backup, importalo dalle impostazioni.",
            color = Muted, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        BigButton("Aggiungi auto", onClick = onAdd)
    }
}

@Composable
private fun VehicleFormScreen(dao: GarageDao, existing: Vehicle?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var targa by remember { mutableStateOf(existing?.targa ?: "") }
    var marca by remember { mutableStateOf(existing?.marca ?: "") }
    var modello by remember { mutableStateOf(existing?.modello ?: "") }
    var anno by remember { mutableStateOf(existing?.anno?.toString() ?: "") }
    var alim by remember { mutableStateOf(existing?.alimentazione ?: FUEL_TYPES[0]) }
    var km by remember { mutableStateOf(existing?.km?.toString() ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    val valid = targa.isNotBlank() && marca.isNotBlank() && km.toIntOrNull()?.let { it >= 0 } == true &&
        (anno.isBlank() || anno.toIntOrNull() in 1900..2100)

    Scaffold(topBar = { TopBar(if (existing == null) "Nuovo veicolo" else "Modifica veicolo", onDone) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(targa, { targa = it.uppercase() }, "Targa *")
            // capitalised here too: not every keyboard honours the capitalisation hint
            Field(marca, { marca = it.replaceFirstChar { c -> c.uppercase() } }, "Marca *", cap = KeyboardCapitalization.Words)
            Field(modello, { modello = it.replaceFirstChar { c -> c.uppercase() } }, "Modello", cap = KeyboardCapitalization.Words)
            Field(anno, { anno = it }, "Anno", KeyboardType.Number)
            Field(km, { km = it }, "Km all'inserimento *", KeyboardType.Number)
            Text("Alimentazione", color = Muted, style = MaterialTheme.typography.labelLarge)
            Chips(FUEL_TYPES, alim) { alim = it }
            Spacer(Modifier.height(4.dp))
            BigButton("Salva", enabled = valid) {
                val v = Vehicle(existing?.id ?: 0, targa.trim(), marca.trim().replaceFirstChar { it.uppercase() }, modello.trim().replaceFirstChar { it.uppercase() }, anno.toIntOrNull(), alim, km.toInt())
                scope.launch { dao.save(v); onDone() }
            }
            if (existing != null) {
                TextButton({ confirmDelete = true }, Modifier.fillMaxWidth()) { Text("Elimina veicolo", color = Bad) }
            }
        }
    }
    if (confirmDelete && existing != null) {
        ConfirmDialog("Eliminare ${existing.targa} e tutto il suo storico?", onDismiss = { confirmDelete = false }) {
            scope.launch { dao.delete(existing); docsDir(ctx, existing.id).deleteRecursively(); onDone() }
        }
    }
}

// ---------- Vehicle detail ----------

private val SECTIONS = listOf("Scadenze", "Rifornimenti", "Spese", "Documenti", "Chilometri") // the last one opens from the odometer, not from a tile
private val PERIODS = listOf("Sempre", "Settimana", "Mese", "Anno")
// rolling periods ending today, so a figure is never empty on the 1st of the month
private fun periodStart(period: Int, today: Long) = listOf(Long.MIN_VALUE, today - 6, today - 29, today - 364)[period]
private val SECTION_ICONS = listOf(R.drawable.ic_scadenze, R.drawable.ic_rifornimenti, R.drawable.ic_spese, R.drawable.ic_documenti)

@Composable
private fun VehicleDetail(dao: GarageDao, id: Long, onBack: () -> Unit, onEdit: (Vehicle) -> Unit) {
    val vehicle by remember(id) { dao.vehicle(id) }.collectAsState(null)
    val deadlines by remember(id) { dao.deadlines(id) }.collectAsState(emptyList())
    val fuel by remember(id) { dao.fuel(id) }.collectAsState(emptyList())
    val expenses by remember(id) { dao.expenses(id) }.collectAsState(emptyList())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    val v = vehicle ?: return
    val lastKm = fuel.maxOfOrNull { it.km } ?: v.km
    // the summary is the landing page: back from a section returns to it, not to the garage
    BackHandler(tab != 0) { tab = 0 }

    Scaffold(
        topBar = {
            if (tab == 0) TopBar("${v.marca} ${v.modello}".trim(), onBack) { IconButton({ onEdit(v) }) { Icon(Icons.Default.Edit, "Modifica veicolo") } }
            else TopBar(SECTIONS[tab - 1], { tab = 0 })
        },
        floatingActionButton = { if (tab in 1..3) AddFab(listOf("Scadenza", "Rifornimento", "Spesa")[tab - 1]) { adding = true } },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> Summary(v, deadlines, fuel, expenses) { tab = it }
                1 -> DeadlineList(dao, deadlines)
                2 -> FuelList(dao, fuel)
                3 -> ExpenseList(dao, expenses)
                4 -> Documents(v.id)
                5 -> KmHistory(v, fuel, expenses)
            }
        }
    }
    if (adding) when (tab) {
        1 -> DeadlineDialog(dao, v.id, null) { adding = false }
        2 -> FuelDialog(dao, v.id, null, lastKm) { adding = false }
        3 -> ExpenseDialog(dao, v.id, null) { adding = false }
    }
}

/** Sized to fit a normal phone without scrolling; the scroll only kicks in on small screens or large fonts. */
@Composable
private fun Summary(v: Vehicle, deadlines: List<Deadline>, fuel: List<FuelEntry>, expenses: List<Expense>, goTo: (Int) -> Unit) {
    val today = today()
    val series = remember(fuel) { consumptionSeries(fuel).map { it.second }.takeLast(12) }
    val avg = avgKmPerLiter(fuel)
    var period by rememberSaveable { mutableIntStateOf(0) }
    val from = periodStart(period, today)
    val fuelCost = fuel.filter { it.data >= from }.sumOf { it.importo }
    val otherCost = expenses.filter { it.data >= from }.sumOf { it.importo }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 4.dp, 16.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hero(onClick = { goTo(5) }) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Odometer(currentKm(v, fuel, expenses))
                Plate(v.targa)
            }
        }

        SECTION_ICONS.indices.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { i ->
                    Surface({ goTo(i + 1) }, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp), color = Surface1, border = BorderStroke(1.dp, Line)) {
                        Row(Modifier.padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(SECTION_ICONS[i]), null, Modifier.size(22.dp), tint = Accent)
                            Text(SECTIONS[i], Modifier.weight(1f).padding(start = 10.dp), fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
                        }
                    }
                }
            }
        }

        Panel(onClick = { goTo(1) }) {
            SectionTitle("Prossime scadenze")
            if (deadlines.isEmpty()) Text("Nessuna scadenza inserita", color = Muted)
            deadlines.take(2).forEach { DeadlineRow(it, today) }
        }

        Panel(onClick = { goTo(2) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column {
                    SectionTitle("Consumo medio")
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(avg?.dec(1) ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = if (avg == null) Muted else Ink)
                        if (avg != null) Text(" km/l", Modifier.padding(bottom = 4.dp), color = Accent, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                    if (avg == null) Text("Servono 2 rifornimenti", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                if (series.size >= 2) {
                    BarChart(series, "Consumi degli ultimi ${series.size} rifornimenti, l'ultimo ${series.last().dec(1)} chilometri al litro", Modifier.weight(1f))
                }
            }
        }

        Panel(onClick = { goTo(3) }) {
            SectionTitle(listOf("Costo totale", "Costo degli ultimi 7 giorni", "Costo degli ultimi 30 giorni", "Costo degli ultimi 12 mesi")[period])
            PillTabs(PERIODS, period, Modifier, Surface2, 36.dp) { period = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((fuelCost + otherCost).eur(), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                // €/km needs the km driven, which is only known over the whole history
                if (period == 0) costPerKm(v, fuel, expenses)?.let { Pill("${it.dec(3)} €/km", Accent) }
            }
            SplitBar(fuelCost, otherCost)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Legend(Modifier.weight(1f), Accent, "Carburante", fuelCost)
                Legend(Modifier.weight(1f), Steel, "Altro", otherCost)
            }
        }
    }
}

@Composable
private fun KmHistory(v: Vehicle, fuel: List<FuelEntry>, expenses: List<Expense>) {
    var period by rememberSaveable { mutableIntStateOf(0) }
    val readings = remember(fuel, expenses) { kmReadings(fuel, expenses) }
    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel {
                SectionTitle(listOf("Km percorsi in totale", "Km percorsi negli ultimi 7 giorni", "Km percorsi negli ultimi 30 giorni", "Km percorsi negli ultimi 12 mesi")[period])
                PillTabs(PERIODS, period, Modifier, Surface2, 36.dp) { period = it }
                Text("${kmDriven(v, fuel, expenses, periodStart(period, today())).km()} km", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Calcolati dai km che inserisci in rifornimenti e spese.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        item { SectionTitle("Cronologia") }
        items(readings.size) { i ->
            val r = readings[i]
            KmRow(r.data.date(), r.fonte, r.km, r.km - (readings.getOrNull(i + 1)?.km ?: v.km))
        }
        item { KmRow("Inizio", "Registrazione del veicolo", v.km, null) }
    }
}

@Composable
private fun KmRow(date: String, source: String, km: Int, delta: Int?) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(date, fontWeight = FontWeight.SemiBold)
                Text(source, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${km.km()} km", fontWeight = FontWeight.SemiBold)
                if (delta != null && delta > 0) Text("+${delta.km()} km", color = Accent, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) = Text(text, color = Muted, style = MaterialTheme.typography.labelLarge)

@Composable
private fun Legend(modifier: Modifier, color: Color, label: String, amount: Double) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Text("$label ${amount.eur()}", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DeadlineRow(d: Deadline, today: Long) {
    val color = status(d, today).color()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(4.dp, 36.dp).clip(CircleShape).background(color))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(d.tipo, fontWeight = FontWeight.SemiBold)
            Text(d.data.date(), color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Pill(deadlineLabel(daysLeft(d, today)), color)
    }
}

// ---------- Lists: tap a row to edit it ----------

@Composable
private fun <T> EntryList(items: List<T>, empty: String, onClick: (T) -> Unit, row: @Composable (T) -> Unit) {
    if (items.isEmpty()) Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) { Text(empty, color = Muted, textAlign = TextAlign.Center) }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items) { item -> Panel(onClick = { onClick(item) }) { row(item) } }
        if (items.isNotEmpty()) item {
            Text("Tocca una voce per modificarla", Modifier.fillMaxWidth().padding(top = 6.dp), color = Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DeadlineList(dao: GarageDao, items: List<Deadline>) {
    var editing by remember { mutableStateOf<Deadline?>(null) }
    val today = today()
    EntryList(items, "Nessuna scadenza.\nAggiungi bollo, revisione e assicurazione per ricevere gli avvisi.", { editing = it }) { DeadlineRow(it, today) }
    editing?.let { DeadlineDialog(dao, it.vehicleId, it) { editing = null } }
}

@Composable
private fun FuelList(dao: GarageDao, items: List<FuelEntry>) {
    var editing by remember { mutableStateOf<FuelEntry?>(null) }
    val kmPerLiter = remember(items) { consumptionSeries(items).associate { it.first.id to it.second } }
    EntryList(items, "Nessun rifornimento.\nRegistrali per calcolare i consumi.", { editing = it }) { f ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.data.date(), fontWeight = FontWeight.SemiBold)
                Text("${f.km.km()} km · ${f.litri.dec(2)} l a ${(f.importo / f.litri).dec(3)} €/l", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.padding(start = 8.dp), horizontalAlignment = Alignment.End) {
                Text(f.importo.eur(), fontWeight = FontWeight.Bold)
                kmPerLiter[f.id]?.let { Text("${it.dec(1)} km/l", color = Accent, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
    editing?.let { FuelDialog(dao, it.vehicleId, it, lastKm = 0) { editing = null } }
}

@Composable
private fun ExpenseList(dao: GarageDao, items: List<Expense>) {
    var editing by remember { mutableStateOf<Expense?>(null) }
    EntryList(items, "Nessuna spesa.\nRegistra riparazioni, gomme, lavaggi e pedaggi.", { editing = it }) { e ->
        val color = categoryColor(e.categoria)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), Alignment.Center) {
                Text(e.categoria.take(1), color = color, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                Text(e.categoria, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(e.data.date(), e.km?.let { "${it.km()} km" }, e.note.takeIf { it.isNotBlank() }).joinToString(" · "),
                    color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(e.importo.eur(), fontWeight = FontWeight.Bold)
        }
    }
    editing?.let { ExpenseDialog(dao, it.vehicleId, it) { editing = null } }
}

// ---------- Add / edit dialogs: `existing == null` means a new entry ----------

@Composable
private fun DeadlineDialog(dao: GarageDao, vehicleId: Long, existing: Deadline?, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tipo by remember { mutableStateOf(existing?.tipo ?: DEADLINE_TYPES[0]) }
    var data by remember { mutableLongStateOf(existing?.data ?: today()) }
    FormDialog(
        if (existing == null) "Nuova scadenza" else "Modifica scadenza", valid = true, close = close,
        save = { scope.launch { dao.save(Deadline(existing?.id ?: 0, vehicleId, tipo, data)); close() } },
        onDelete = existing?.let { { scope.launch { dao.delete(it); close() } } },
    ) {
        Chips(DEADLINE_TYPES, tipo) { tipo = it }
        DateField("Data scadenza", data) { data = it }
    }
}

@Composable
private fun FuelDialog(dao: GarageDao, vehicleId: Long, existing: FuelEntry?, lastKm: Int, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableLongStateOf(existing?.data ?: today()) }
    var litri by remember { mutableStateOf(existing?.litri?.input() ?: "") }
    var importo by remember { mutableStateOf(existing?.importo?.input() ?: "") }
    var km by remember { mutableStateOf(existing?.km?.toString() ?: "") }
    val l = parseNum(litri)
    val i = parseNum(importo)
    val k = km.toIntOrNull()
    FormDialog(
        if (existing == null) "Nuovo rifornimento" else "Modifica rifornimento",
        valid = l != null && l > 0 && i != null && i >= 0 && k != null && k >= 0,
        close = close,
        save = { scope.launch { dao.save(FuelEntry(existing?.id ?: 0, vehicleId, data, l!!, i!!, k!!)); close() } },
        onDelete = existing?.let { { scope.launch { dao.delete(it); close() } } },
    ) {
        DateField("Data", data) { data = it }
        Field(litri, { litri = it }, "Litri", KeyboardType.Decimal)
        Field(importo, { importo = it }, "Importo €", KeyboardType.Decimal)
        Field(km, { km = it }, if (existing == null) "Km (ultimo: ${lastKm.km()})" else "Km", KeyboardType.Number)
        if (existing == null && k != null && k < lastKm) {
            Text("Attenzione: km inferiori all'ultimo rifornimento", color = Bad, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ExpenseDialog(dao: GarageDao, vehicleId: Long, existing: Expense?, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var categoria by remember { mutableStateOf(existing?.categoria ?: EXPENSE_CATEGORIES[0]) }
    var data by remember { mutableLongStateOf(existing?.data ?: today()) }
    var importo by remember { mutableStateOf(existing?.importo?.input() ?: "") }
    var km by remember { mutableStateOf(existing?.km?.toString() ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var scadenza by remember { mutableStateOf<Long?>(null) }
    val hasDeadline = categoria in DEADLINE_TYPES && categoria != "Altro"
    val i = parseNum(importo)
    FormDialog(
        if (existing == null) "Nuova spesa" else "Modifica spesa",
        valid = i != null && i >= 0 && (km.isBlank() || km.toIntOrNull() != null),
        close = close,
        save = {
            scope.launch {
                dao.save(Expense(existing?.id ?: 0, vehicleId, data, categoria, i!!, km.toIntOrNull(), note.trim()))
                scadenza?.takeIf { hasDeadline }?.let { due ->
                    // one deadline per type: paying the renewal moves the existing one instead of adding a duplicate
                    val old = dao.deadlines(vehicleId).first().firstOrNull { it.tipo == categoria }
                    dao.save(Deadline(old?.id ?: 0, vehicleId, categoria, due))
                }
                close()
            }
        },
        onDelete = existing?.let { { scope.launch { dao.delete(it); close() } } },
    ) {
        Chips(EXPENSE_CATEGORIES, categoria) { categoria = it }
        DateField("Data", data) { data = it }
        if (hasDeadline) {
            DateField("Prossima scadenza", scadenza) { scadenza = it }
            if (scadenza != null) TextButton({ scadenza = null }) { Text("Togli scadenza") }
        }
        Field(importo, { importo = it }, "Importo €", KeyboardType.Decimal)
        Field(km, { km = it }, "Km (facoltativo)", KeyboardType.Number)
        Field(note, { note = it }, "Note / officina")
    }
}

// ---------- Small shared widgets ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
        actions = actions,
    )
}

@Composable
private fun AddFab(text: String, onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick, icon = { Icon(Icons.Default.Add, null) }, text = { Text(text, fontWeight = FontWeight.SemiBold) },
        containerColor = Accent, contentColor = OnAccent, shape = CircleShape,
    )
}

@Composable
private fun BigButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().height(54.dp), enabled = enabled, shape = RoundedCornerShape(16.dp)) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

/** `onDelete` adds an "Elimina" button (with its own confirmation) for existing entries. */
@Composable
private fun FormDialog(
    title: String, valid: Boolean, close: () -> Unit, save: () -> Unit, onDelete: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) },
        confirmButton = { Button(save, enabled = valid) { Text("Salva") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton({ confirmDelete = true }) { Text("Elimina", color = Bad) }
                TextButton(close) { Text("Annulla", color = Muted) }
            }
        },
    )
    if (confirmDelete && onDelete != null) ConfirmDialog("Eliminare questa voce?", onDismiss = { confirmDelete = false }, onConfirm = onDelete)
}

@Composable
fun ConfirmDialog(text: String, confirm: String = "Elimina", onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text, color = Ink) },
        confirmButton = { TextButton(onConfirm) { Text(confirm, color = Bad) } },
        dismissButton = { TextButton(onDismiss) { Text("Annulla", color = Muted) } },
    )
}

@Composable
fun Field(
    value: String, onChange: (String) -> Unit, label: String, type: KeyboardType = KeyboardType.Text,
    cap: KeyboardCapitalization = KeyboardCapitalization.None,
) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(capitalization = cap, keyboardType = type), modifier = Modifier.fillMaxWidth(),
    )
}

/** Wrapping grid of choices, packed so long and short labels share rows and the grid takes the fewest rows. */
@Composable
fun Chips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    Layout(content = {
        options.forEach { FilterChip(it == selected, { onSelect(it) }, label = { Text(it) }, shape = CircleShape) }
    }) { measurables, constraints ->
        val chips = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val rows = packRows(chips.map { it.width }, constraints.maxWidth, gap)
        val heights = rows.map { row -> row.maxOf { chips[it].height } }
        layout(constraints.maxWidth, heights.sum()) {
            var y = 0
            rows.forEachIndexed { r, row ->
                var x = 0
                row.forEach { chips[it].placeRelative(x, y); x += chips[it].width + gap }
                y += heights[r]
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, epochDay: Long?, onChange: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton({ open = true }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
        Text("$label: ${epochDay?.date() ?: "non impostata"}", color = Ink)
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (epochDay ?: today()) * 86_400_000)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton({ state.selectedDateMillis?.let { onChange(it / 86_400_000) }; open = false }) { Text("OK") }
            },
        ) { DatePicker(state) }
    }
}
