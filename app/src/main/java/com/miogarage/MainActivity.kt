package com.miogarage

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val dao = GarageDb.get(this).dao()
        setContent { MaterialTheme { App(dao) } }
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
private fun today() = LocalDate.now().toEpochDay()

private fun Status.color() = when (this) {
    Status.OK -> Color(0xFF2E7D32)
    Status.WARNING -> Color(0xFFF9A825)
    Status.EXPIRED -> Color(0xFFC62828)
}

@Composable
private fun App(dao: GarageDao) {
    var screen by remember { mutableStateOf<Screen>(Screen.Vehicles) }
    BackHandler(screen != Screen.Vehicles) { screen = Screen.Vehicles }
    when (val s = screen) {
        Screen.Vehicles -> VehicleList(dao, onOpen = { screen = Screen.Detail(it) }, onAdd = { screen = Screen.VehicleForm(null) })
        is Screen.VehicleForm -> VehicleFormScreen(dao, s.vehicle, onDone = { screen = Screen.Vehicles })
        is Screen.Detail -> VehicleDetail(dao, s.id, onBack = { screen = Screen.Vehicles }, onEdit = { screen = Screen.VehicleForm(it) })
    }
}

// ---------- Vehicles ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VehicleList(dao: GarageDao, onOpen: (Long) -> Unit, onAdd: () -> Unit) {
    val vehicles by dao.vehicles().collectAsState(null)
    Scaffold(
        topBar = { TopAppBar(title = { Text("MioGarage") }) },
        floatingActionButton = { FloatingActionButton(onAdd) { Icon(Icons.Default.Add, "Aggiungi veicolo") } },
    ) { pad ->
        val list = vehicles ?: return@Scaffold
        if (list.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { Text("Aggiungi la tua prima auto con +") }
        }
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { v ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(v.id) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${v.marca} ${v.modello}", style = MaterialTheme.typography.titleMedium)
                        Text("${v.targa} · ${v.alimentazione}${v.anno?.let { " · $it" } ?: ""}")
                    }
                }
            }
        }
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

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (existing == null) "Nuovo veicolo" else "Modifica veicolo") },
            navigationIcon = { IconButton(onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(targa, { targa = it.uppercase() }, label = { Text("Targa *") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(marca, { marca = it }, label = { Text("Marca *") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(modello, { modello = it }, label = { Text("Modello") }, modifier = Modifier.fillMaxWidth())
            NumberField(anno, { anno = it }, "Anno", KeyboardType.Number)
            NumberField(km, { km = it }, "Km all'inserimento *", KeyboardType.Number)
            Text("Alimentazione")
            Chips(FUEL_TYPES, alim) { alim = it }
            Button(
                enabled = valid,
                onClick = {
                    val v = Vehicle(existing?.id ?: 0, targa.trim(), marca.trim(), modello.trim(), anno.toIntOrNull(), alim, km.toInt())
                    scope.launch { dao.save(v); onDone() }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Salva") }
            if (existing != null) {
                OutlinedButton({ confirmDelete = true }, Modifier.fillMaxWidth()) { Text("Elimina veicolo") }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VehicleDetail(dao: GarageDao, id: Long, onBack: () -> Unit, onEdit: (Vehicle) -> Unit) {
    val vehicle by remember(id) { dao.vehicle(id) }.collectAsState(null)
    val deadlines by remember(id) { dao.deadlines(id) }.collectAsState(emptyList())
    val fuel by remember(id) { dao.fuel(id) }.collectAsState(emptyList())
    val expenses by remember(id) { dao.expenses(id) }.collectAsState(emptyList())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    val v = vehicle ?: return
    val tabs = listOf("Riepilogo", "Scadenze", "Pieni", "Spese")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${v.marca} ${v.modello}".trim()) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
                actions = { IconButton({ onEdit(v) }) { Icon(Icons.Default.Edit, "Modifica veicolo") } },
            )
        },
        floatingActionButton = {
            if (tab > 0) FloatingActionButton({ adding = true }) { Icon(Icons.Default.Add, "Aggiungi") }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            TabRow(tab) { tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) } }
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
    val km = currentKm(v, fuel, expenses)
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${v.targa} · ${"%,d".format(Locale.ITALY, km)} km", style = MaterialTheme.typography.titleMedium)

        Card(Modifier.fillMaxWidth().clickable { goTo(1) }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Scadenze", fontWeight = FontWeight.Bold)
                if (deadlines.isEmpty()) Text("Nessuna scadenza inserita")
                deadlines.take(3).forEach { DeadlineRow(it, today) }
            }
        }
        Card(Modifier.fillMaxWidth().clickable { goTo(2) }) {
            Column(Modifier.padding(16.dp)) {
                Text("Consumi", fontWeight = FontWeight.Bold)
                Text(avgKmPerLiter(fuel)?.let { String.format(Locale.ITALY, "%.1f km/l di media", it) } ?: "Servono almeno 2 pieni")
            }
        }
        Card(Modifier.fillMaxWidth().clickable { goTo(3) }) {
            Column(Modifier.padding(16.dp)) {
                Text("Costo totale (TCO)", fontWeight = FontWeight.Bold)
                Text(totalCost(fuel, expenses).eur(), style = MaterialTheme.typography.headlineSmall)
                Text("Carburante ${fuel.sumOf { it.importo }.eur()} · Altre spese ${expenses.sumOf { it.importo }.eur()}")
                costPerKm(v, fuel, expenses)?.let { Text(String.format(Locale.ITALY, "%.3f €/km", it)) }
            }
        }
    }
}

@Composable
private fun DeadlineRow(d: Deadline, today: Long) {
    val left = daysLeft(d, today)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(status(d, today).color()))
        Spacer(Modifier.width(8.dp))
        Text("${d.tipo} · ${d.data.date()}", Modifier.weight(1f))
        Text(if (left < 0) "scaduta" else "tra $left gg")
    }
}

// ---------- Lists ----------

@Composable
private fun <T> EntryList(items: List<T>, empty: String, onDelete: (T) -> Unit, row: @Composable (T) -> Unit) {
    var toDelete by remember { mutableStateOf<T?>(null) }
    if (items.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text(empty) }
    LazyColumn(contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { item ->
            Card(Modifier.fillMaxWidth().clickable { toDelete = item }) { Box(Modifier.padding(16.dp)) { row(item) } }
        }
    }
    toDelete?.let { ConfirmDelete("Eliminare questa voce?", onDismiss = { toDelete = null }) { onDelete(it); toDelete = null } }
}

@Composable
private fun DeadlineList(dao: GarageDao, items: List<Deadline>) {
    val scope = rememberCoroutineScope()
    val today = today()
    EntryList(items, "Nessuna scadenza", { scope.launch { dao.delete(it) } }) { DeadlineRow(it, today) }
}

@Composable
private fun FuelList(dao: GarageDao, items: List<FuelEntry>) {
    val scope = rememberCoroutineScope()
    EntryList(items, "Nessun rifornimento", { scope.launch { dao.delete(it) } }) { f ->
        Column {
            Text("${f.data.date()} · ${"%,d".format(Locale.ITALY, f.km)} km", fontWeight = FontWeight.Bold)
            Text(String.format(Locale.ITALY, "%.2f l · %s · %.3f €/l", f.litri, f.importo.eur(), f.importo / f.litri))
        }
    }
}

@Composable
private fun ExpenseList(dao: GarageDao, items: List<Expense>) {
    val scope = rememberCoroutineScope()
    EntryList(items, "Nessuna spesa", { scope.launch { dao.delete(it) } }) { e ->
        Column {
            Row {
                Text("${e.categoria} · ${e.data.date()}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(e.importo.eur())
            }
            val extra = listOfNotNull(e.km?.let { "%,d km".format(Locale.ITALY, it) }, e.note.takeIf { it.isNotBlank() })
            if (extra.isNotEmpty()) Text(extra.joinToString(" · "))
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
        NumberField(litri, { litri = it }, "Litri")
        NumberField(importo, { importo = it }, "Importo €")
        NumberField(km, { km = it }, "Km contachilometri (ultimo: $lastKm)", KeyboardType.Number)
        if (k != null && k < lastKm) Text("Attenzione: km inferiori all'ultimo pieno", color = MaterialTheme.colorScheme.error)
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
        NumberField(importo, { importo = it }, "Importo €")
        NumberField(km, { km = it }, "Km (facoltativo)", KeyboardType.Number)
        OutlinedTextField(note, { note = it }, label = { Text("Note / officina") }, modifier = Modifier.fillMaxWidth())
    }
}

// ---------- Small shared widgets ----------

@Composable
private fun FormDialog(title: String, valid: Boolean, close: () -> Unit, save: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) },
        confirmButton = { TextButton(save, enabled = valid) { Text("Salva") } },
        dismissButton = { TextButton(close) { Text("Annulla") } },
    )
}

@Composable
private fun ConfirmDelete(text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onConfirm) { Text("Elimina") } },
        dismissButton = { TextButton(onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, type: KeyboardType = KeyboardType.Decimal) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Chips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { FilterChip(it == selected, { onSelect(it) }, label = { Text(it) }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, epochDay: Long, onChange: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text("$label: ${epochDay.date()}") }
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
