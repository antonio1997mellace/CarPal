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
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the app is always dark, so the system bars always get light icons
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(bars, bars)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val dao = GarageDb.get(this).dao()
        setContent { GarageTheme { GarageApp(dao) } }
    }
}

private sealed interface Screen {
    data object Vehicles : Screen
    data class VehicleForm(val vehicle: Vehicle?) : Screen
    data class Detail(val id: Long) : Screen
}

private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private fun Long.date() = LocalDate.ofEpochDay(this).format(dateFmt)
private fun Double.eur() = String.format(Locale.ITALY, "%.2f €", this)
private fun Double.dec(digits: Int) = String.format(Locale.ITALY, "%.${digits}f", this)
private fun Int.km() = String.format(Locale.ITALY, "%,d", this)
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
        Screen.Vehicles -> VehicleList(dao, onOpen = { screen = Screen.Detail(it) }, onAdd = { screen = Screen.VehicleForm(null) })
        is Screen.VehicleForm -> VehicleFormScreen(dao, s.vehicle, onDone = { screen = Screen.Vehicles })
        is Screen.Detail -> VehicleDetail(dao, s.id, onBack = { screen = Screen.Vehicles }, onEdit = { screen = Screen.VehicleForm(it) })
    }
}

// ---------- Vehicles ----------

@Composable
private fun VehicleList(dao: GarageDao, onOpen: (Long) -> Unit, onAdd: () -> Unit) {
    val vehicles by remember { dao.vehicles() }.collectAsState(null)
    val deadlines by remember { dao.allDeadlinesFlow() }.collectAsState(emptyList())
    val today = today()
    Scaffold(floatingActionButton = { if (!vehicles.isNullOrEmpty()) AddFab("Aggiungi auto", onAdd) }) { pad ->
        val list = vehicles ?: return@Scaffold
        if (list.isEmpty()) {
            EmptyGarage(Modifier.padding(pad), onAdd)
            return@Scaffold
        }
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp, 20.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text("Il mio garage", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold)
                Text(if (list.size == 1) "1 veicolo" else "${list.size} veicoli", color = Muted)
                Spacer(Modifier.height(6.dp))
            }
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
                            expired > 0 -> Pill(if (expired == 1) "1 scaduta" else "$expired scadute", Bad, dark)
                            warning > 0 -> Pill("$warning in scadenza", Warn, dark)
                            mine.isEmpty() -> Pill("Nessuna scadenza", Color.White.copy(alpha = 0.8f), dark)
                            else -> Pill("Tutto in regola", Good, dark)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyGarage(modifier: Modifier, onAdd: () -> Unit) {
    Column(modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Box(Modifier.size(104.dp).clip(CircleShape).background(Surface1), Alignment.Center) { Text("🚗", fontSize = 46.sp) }
        Spacer(Modifier.height(24.dp))
        Text("Il garage è vuoto", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text("Aggiungi la tua auto per tenere sotto controllo scadenze, pieni e spese.", color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        BigButton("Aggiungi auto", onClick = onAdd)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VehicleFormScreen(dao: GarageDao, existing: Vehicle?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
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
            Field(marca, { marca = it }, "Marca *")
            Field(modello, { modello = it }, "Modello")
            Field(anno, { anno = it }, "Anno", KeyboardType.Number)
            Field(km, { km = it }, "Km all'inserimento *", KeyboardType.Number)
            Text("Alimentazione", color = Muted, style = MaterialTheme.typography.labelLarge)
            Chips(FUEL_TYPES, alim) { alim = it }
            Spacer(Modifier.height(4.dp))
            BigButton("Salva", enabled = valid) {
                val v = Vehicle(existing?.id ?: 0, targa.trim(), marca.trim(), modello.trim(), anno.toIntOrNull(), alim, km.toInt())
                scope.launch { dao.save(v); onDone() }
            }
            if (existing != null) {
                TextButton({ confirmDelete = true }, Modifier.fillMaxWidth()) { Text("Elimina veicolo", color = Bad) }
            }
        }
    }
    if (confirmDelete && existing != null) {
        ConfirmDelete("Eliminare ${existing.targa} e tutto il suo storico?", onDismiss = { confirmDelete = false }) {
            scope.launch { dao.delete(existing); onDone() }
        }
    }
}

// ---------- Vehicle detail ----------

@Composable
private fun VehicleDetail(dao: GarageDao, id: Long, onBack: () -> Unit, onEdit: (Vehicle) -> Unit) {
    val vehicle by remember(id) { dao.vehicle(id) }.collectAsState(null)
    val deadlines by remember(id) { dao.deadlines(id) }.collectAsState(emptyList())
    val fuel by remember(id) { dao.fuel(id) }.collectAsState(emptyList())
    val expenses by remember(id) { dao.expenses(id) }.collectAsState(emptyList())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    val v = vehicle ?: return

    Scaffold(
        topBar = {
            Column {
                TopBar("${v.marca} ${v.modello}".trim(), onBack) { IconButton({ onEdit(v) }) { Icon(Icons.Default.Edit, "Modifica veicolo") } }
                PillTabs(listOf("Riepilogo", "Scadenze", "Pieni", "Spese"), tab) { tab = it }
            }
        },
        floatingActionButton = { if (tab > 0) AddFab(listOf("Scadenza", "Pieno", "Spesa")[tab - 1]) { adding = true } },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> Summary(v, deadlines, fuel, expenses) { tab = it }
                1 -> DeadlineList(dao, deadlines)
                2 -> FuelList(dao, fuel)
                3 -> ExpenseList(dao, expenses)
            }
        }
    }
    if (adding) when (tab) {
        1 -> DeadlineDialog(dao, v.id) { adding = false }
        2 -> FuelDialog(dao, v.id, fuel.maxOfOrNull { it.km } ?: v.km) { adding = false }
        3 -> ExpenseDialog(dao, v.id) { adding = false }
    }
}

@Composable
private fun Summary(v: Vehicle, deadlines: List<Deadline>, fuel: List<FuelEntry>, expenses: List<Expense>, goTo: (Int) -> Unit) {
    val today = today()
    val series = remember(fuel) { consumptionSeries(fuel).map { it.second }.takeLast(12) }
    val fuelCost = fuel.sumOf { it.importo }
    val otherCost = expenses.sumOf { it.importo }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Hero {
            Column {
                Text("CONTACHILOMETRI", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(currentKm(v, fuel, expenses).km(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Color.White)
                    Text(" km", Modifier.padding(bottom = 5.dp), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.titleMedium)
                }
            }
            Plate(v.targa)
        }

        Panel(onClick = { goTo(1) }) {
            SectionTitle("Scadenze")
            if (deadlines.isEmpty()) Text("Nessuna scadenza inserita", color = Muted)
            deadlines.take(3).forEach { DeadlineRow(it, today) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatTile(Modifier.weight(1f), "Consumo medio", avgKmPerLiter(fuel)?.dec(1), "km/l", "Servono 2 pieni") { goTo(2) }
            StatTile(Modifier.weight(1f), "Costo al km", costPerKm(v, fuel, expenses)?.dec(3), "€/km", "Ancora nessun dato") { goTo(3) }
        }

        if (series.size >= 2) Panel(onClick = { goTo(2) }) {
            SectionTitle("Andamento consumi")
            BarChart(series, "Consumi degli ultimi ${series.size} pieni, l'ultimo ${series.last().dec(1)} chilometri al litro")
            Text("Ultimi ${series.size} pieni · km/l", color = Muted, style = MaterialTheme.typography.bodySmall)
        }

        Panel(onClick = { goTo(3) }) {
            SectionTitle("Costo totale")
            Text((fuelCost + otherCost).eur(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            SplitBar(fuelCost, otherCost)
            LegendRow(Accent, "Carburante", fuelCost)
            LegendRow(Violet, "Manutenzione e altro", otherCost)
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, color = Muted, style = MaterialTheme.typography.labelLarge)

@Composable
private fun LegendRow(color: Color, label: String, amount: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Text(label, Modifier.padding(start = 10.dp).weight(1f))
        Text(amount.eur(), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatTile(modifier: Modifier, label: String, value: String?, unit: String, empty: String, onClick: () -> Unit) {
    Panel(modifier, onClick) {
        SectionTitle(label)
        if (value == null) {
            Text("—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = Muted)
            Text(empty, color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            Text(unit, color = Accent, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
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

// ---------- Lists ----------

@Composable
private fun <T> EntryList(items: List<T>, empty: String, onDelete: (T) -> Unit, row: @Composable (T) -> Unit) {
    var toDelete by remember { mutableStateOf<T?>(null) }
    if (items.isEmpty()) Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) { Text(empty, color = Muted, textAlign = TextAlign.Center) }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items) { item -> Panel(onClick = { toDelete = item }) { row(item) } }
        if (items.isNotEmpty()) item {
            Text("Tocca una voce per eliminarla", Modifier.fillMaxWidth().padding(top = 6.dp), color = Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
    toDelete?.let { ConfirmDelete("Eliminare questa voce?", onDismiss = { toDelete = null }) { onDelete(it); toDelete = null } }
}

@Composable
private fun DeadlineList(dao: GarageDao, items: List<Deadline>) {
    val scope = rememberCoroutineScope()
    val today = today()
    EntryList(items, "Nessuna scadenza.\nAggiungi bollo, revisione e assicurazione per ricevere gli avvisi.", { scope.launch { dao.delete(it) } }) {
        DeadlineRow(it, today)
    }
}

@Composable
private fun FuelList(dao: GarageDao, items: List<FuelEntry>) {
    val scope = rememberCoroutineScope()
    val kmPerLiter = remember(items) { consumptionSeries(items).associate { it.first.id to it.second } }
    EntryList(items, "Nessun rifornimento.\nRegistra i pieni per calcolare i consumi.", { scope.launch { dao.delete(it) } }) { f ->
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
}

@Composable
private fun ExpenseList(dao: GarageDao, items: List<Expense>) {
    val scope = rememberCoroutineScope()
    EntryList(items, "Nessuna spesa.\nRegistra tagliandi, gomme, lavaggi e pedaggi.", { scope.launch { dao.delete(it) } }) { e ->
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
}

// ---------- Add dialogs ----------

@Composable
private fun DeadlineDialog(dao: GarageDao, vehicleId: Long, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tipo by remember { mutableStateOf(DEADLINE_TYPES[0]) }
    var data by remember { mutableLongStateOf(today()) }
    FormDialog("Nuova scadenza", valid = true, close = close, save = { scope.launch { dao.insert(Deadline(0, vehicleId, tipo, data)); close() } }) {
        Chips(DEADLINE_TYPES, tipo) { tipo = it }
        DateField("Data scadenza", data) { data = it }
    }
}

@Composable
private fun FuelDialog(dao: GarageDao, vehicleId: Long, lastKm: Int, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableLongStateOf(today()) }
    var litri by remember { mutableStateOf("") }
    var importo by remember { mutableStateOf("") }
    var km by remember { mutableStateOf("") }
    val l = parseNum(litri)
    val i = parseNum(importo)
    val k = km.toIntOrNull()
    FormDialog(
        "Nuovo rifornimento",
        valid = l != null && l > 0 && i != null && i >= 0 && k != null && k >= 0,
        close = close,
        save = { scope.launch { dao.insert(FuelEntry(0, vehicleId, data, l!!, i!!, k!!)); close() } },
    ) {
        DateField("Data", data) { data = it }
        Field(litri, { litri = it }, "Litri", KeyboardType.Decimal)
        Field(importo, { importo = it }, "Importo €", KeyboardType.Decimal)
        Field(km, { km = it }, "Km (ultimo: ${lastKm.km()})", KeyboardType.Number)
        if (k != null && k < lastKm) Text("Attenzione: km inferiori all'ultimo pieno", color = Bad, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ExpenseDialog(dao: GarageDao, vehicleId: Long, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var categoria by remember { mutableStateOf(EXPENSE_CATEGORIES[0]) }
    var data by remember { mutableLongStateOf(today()) }
    var importo by remember { mutableStateOf("") }
    var km by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val i = parseNum(importo)
    FormDialog(
        "Nuova spesa",
        valid = i != null && i >= 0 && (km.isBlank() || km.toIntOrNull() != null),
        close = close,
        save = { scope.launch { dao.insert(Expense(0, vehicleId, data, categoria, i!!, km.toIntOrNull(), note.trim())); close() } },
    ) {
        Chips(EXPENSE_CATEGORIES, categoria) { categoria = it }
        DateField("Data", data) { data = it }
        Field(importo, { importo = it }, "Importo €", KeyboardType.Decimal)
        Field(km, { km = it }, "Km (facoltativo)", KeyboardType.Number)
        Field(note, { note = it }, "Note / officina")
    }
}

// ---------- Small shared widgets ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
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
        containerColor = Accent, contentColor = Color.White, shape = CircleShape,
    )
}

@Composable
private fun BigButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().height(54.dp), enabled = enabled, shape = RoundedCornerShape(16.dp)) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun FormDialog(title: String, valid: Boolean, close: () -> Unit, save: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) },
        confirmButton = { Button(save, enabled = valid) { Text("Salva") } },
        dismissButton = { TextButton(close) { Text("Annulla", color = Muted) } },
    )
}

@Composable
private fun ConfirmDelete(text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text, color = Ink) },
        confirmButton = { TextButton(onConfirm) { Text("Elimina", color = Bad) } },
        dismissButton = { TextButton(onDismiss) { Text("Annulla", color = Muted) } },
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, type: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Chips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { FilterChip(it == selected, { onSelect(it) }, label = { Text(it) }, shape = CircleShape) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, epochDay: Long, onChange: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton({ open = true }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
        Text("$label: ${epochDay.date()}", color = Ink)
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = epochDay * 86_400_000)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton({ state.selectedDateMillis?.let { onChange(it / 86_400_000) }; open = false }) { Text("OK") }
            },
        ) { DatePicker(state) }
    }
}
