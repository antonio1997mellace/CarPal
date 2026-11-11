package com.miogarage

import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

// ---------- Spare parts tab ----------

/** Search words the shops understand: "Fiat Panda 2015". */
fun partsQuery(v: Vehicle) = listOfNotNull(v.marca, v.modello.ifBlank { null }, v.anno?.toString()).joinToString(" ")

@Composable
fun Parts(v: Vehicle, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            SectionTitle("Dati del veicolo")
            listOf(
                "Targa" to v.targa, "Marca" to v.marca, "Modello" to v.modello.ifBlank { "—" }, "Anno" to (v.anno?.toString() ?: "—"),
                "Alimentazione" to v.alimentazione,
            ).forEach { (label, value) ->
                Row {
                    Text(label, Modifier.weight(1f), color = Muted)
                    Text(value, fontWeight = FontWeight.SemiBold)
                }
            }
            OutlinedButton(onEdit, Modifier.fillMaxWidth()) { Text("Modifica i dati") }
        }
        SectionTitle("Cerca ricambi per ${partsQuery(v)}")
        // shortcut: both shops are opened on a text search; their own vehicle filters need internal model codes we do not have
        BigButton("Apri AUTODOC") { open("https://www.auto-doc.it/search?keyword=" + Uri.encode(partsQuery(v))) }
        BigButton("Apri Amazon") { open("https://www.amazon.it/s?i=automotive&k=" + Uri.encode("ricambi " + partsQuery(v))) }
        Text("Si aprono nel browser con marca, modello e anno già nella ricerca.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

// ---------- PDF ----------

/** Expense categories that count as maintenance history, e.g. for a buyer of the car. */
val MAINTENANCE = listOf("Riparazione", "Tagliando", "Gomme", "Carrozzeria", "Ricambi", "Revisione")

/** A4 report: vehicle data, maintenance jobs newest first, totals per category, deadlines and fuel summary. */
fun writeHistoryPdf(out: OutputStream, v: Vehicle, deadlines: List<Deadline>, fuel: List<FuelEntry>, expenses: List<Expense>) {
    val jobs = expenses.filter { it.categoria in MAINTENANCE }.sortedByDescending { it.data }
    val doc = PdfDocument()
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f }
    val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
    val title = Paint(bold).apply { textSize = 18f }
    val grey = Paint(text).apply { color = 0xFF666666.toInt() }
    val left = 40f
    val right = 555f
    var pageNo = 0
    lateinit var page: PdfDocument.Page
    var y = 0f

    fun newPage() {
        if (pageNo > 0) doc.finishPage(page)
        page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNo).create())
        page.canvas.drawText("MioGarage · ${v.targa} · pagina $pageNo", left, 815f, grey)
        y = 50f
    }
    fun line(height: Float = 15f) {
        y += height
        if (y > 790f) newPage()
    }
    fun fit(s: String, p: Paint, width: Float) = if (p.measureText(s) <= width) s else s.take(p.breakText(s, true, width - p.measureText("…"), null)) + "…"
    fun heading(s: String) {
        line(26f)
        page.canvas.drawText(s, left, y, bold.apply { textSize = 12f })
        bold.textSize = 10f
        line(4f)
        page.canvas.drawLine(left, y, right, y, grey)
    }
    fun pair(label: String, value: String) {
        line()
        page.canvas.drawText(label, left, y, grey)
        page.canvas.drawText(value, left + 130f, y, text)
    }

    newPage()
    page.canvas.drawText("Storico manutenzione", left, y, title)
    line(18f)
    page.canvas.drawText("${v.marca} ${v.modello}".trim(), left, y, bold.apply { textSize = 12f })
    bold.textSize = 10f
    pair("Targa", v.targa)
    v.anno?.let { pair("Anno", it.toString()) }
    pair("Alimentazione", v.alimentazione)
    pair("Chilometri attuali", "${currentKm(v, fuel, expenses).km()} km")
    pair("Documento creato il", today().date())

    heading("Interventi (${jobs.size})")
    val cols = listOf(left, left + 62f, left + 150f, left + 210f, left + 275f) // date, category, km, amount, notes
    line()
    listOf("Data", "Intervento", "Km", "Importo", "Note / officina").forEachIndexed { i, h -> page.canvas.drawText(h, cols[i], y, bold) }
    if (jobs.isEmpty()) {
        line()
        page.canvas.drawText("Nessun intervento registrato.", left, y, grey)
    }
    jobs.forEach { e ->
        line()
        listOf(e.data.date(), e.categoria, e.km?.km() ?: "—", e.importo.eur(), fit(e.note, text, right - cols[4]))
            .forEachIndexed { i, s -> page.canvas.drawText(s, cols[i], y, text) }
    }

    if (jobs.isNotEmpty()) {
        heading("Totali")
        jobs.groupBy { it.categoria }.forEach { (cat, list) -> pair(cat, list.sumOf { it.importo }.eur()) }
        pair("Totale manutenzione", jobs.sumOf { it.importo }.eur())
    }

    if (deadlines.isNotEmpty()) {
        heading("Scadenze")
        deadlines.sortedBy { it.data }.forEach { pair(it.tipo, it.data.date()) }
    }

    if (fuel.isNotEmpty()) {
        heading("Rifornimenti")
        pair("Numero", fuel.size.toString())
        pair("Litri totali", fuel.sumOf { it.litri }.dec(1))
        pair("Spesa totale", fuel.sumOf { it.importo }.eur())
        avgKmPerLiter(fuel)?.let { pair("Consumo medio", "${it.dec(1)} km/l") }
    }

    doc.finishPage(page)
    doc.writeTo(out)
    doc.close()
}

@Composable
fun PdfSection(v: Vehicle, deadlines: List<Deadline>, fuel: List<FuelEntry>, expenses: List<Expense>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            message = runCatching {
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { writeHistoryPdf(it, v, deadlines, fuel, expenses) } }
            }.fold({ "PDF salvato." }, { "Creazione del PDF non riuscita." })
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            Text("Storico manutenzione", fontWeight = FontWeight.SemiBold)
            Text(
                "Dati del veicolo e km attuali, tutti gli interventi (${MAINTENANCE.joinToString(", ").lowercase()}) con data, km, importo e note, " +
                    "i totali per tipo, le scadenze e un riepilogo dei rifornimenti.",
                color = Muted, style = MaterialTheme.typography.bodySmall,
            )
            Button({ save.launch("storico-${v.targa}.pdf") }, Modifier.fillMaxWidth()) { Text("Crea PDF") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
