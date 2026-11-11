package com.miogarage

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
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

/** Expense categories that count as maintenance history, e.g. for a buyer of the car. */
val MAINTENANCE = listOf("Riparazione", "Tagliando", "Gomme", "Carrozzeria", "Ricambi", "Revisione")

/** A4 page writer shared by the reports: flows text top to bottom and opens a new page when one is full. */
private class Report(title: String, private val v: Vehicle, km: Int) {
    private val doc = PdfDocument()
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f }
    val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
    private val h1 = Paint(bold).apply { textSize = 18f }
    private val h2 = Paint(bold).apply { textSize = 12f }
    val grey = Paint(text).apply { color = 0xFF666666.toInt() }
    val left = 40f
    val right = 555f
    private var pageNo = 0
    private lateinit var page: PdfDocument.Page
    private var y = 0f

    init {
        newPage()
        page.canvas.drawText(title, left, y, h1)
        line(18f)
        page.canvas.drawText("${v.marca} ${v.modello}".trim(), left, y, h2)
        pair("Targa", v.targa)
        v.anno?.let { pair("Anno", it.toString()) }
        pair("Alimentazione", v.alimentazione)
        pair("Chilometri attuali", "${km.km()} km")
        pair("Documento creato il", today().date())
    }

    private fun newPage() {
        if (pageNo > 0) doc.finishPage(page)
        page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNo).create())
        page.canvas.drawText("MioGarage · ${v.targa} · pagina $pageNo", left, 815f, grey)
        y = 50f
    }

    private fun line(height: Float = 15f) {
        y += height
        if (y > 790f) newPage()
    }

    private fun fit(s: String, p: Paint, width: Float) =
        if (p.measureText(s) <= width) s else s.take(p.breakText(s, true, width - p.measureText("…"), null)) + "…"

    fun heading(s: String) {
        line(26f)
        page.canvas.drawText(s, left, y, h2)
        line(4f)
        page.canvas.drawLine(left, y, right, y, grey)
    }

    fun pair(label: String, value: String) {
        line()
        page.canvas.drawText(label, left, y, grey)
        page.canvas.drawText(value, left + 130f, y, text)
    }

    /** One table row; [cols] are the x of each column and every cell is cut to its column width. */
    fun row(cols: List<Float>, cells: List<String>, p: Paint = text) {
        line()
        cells.forEachIndexed { i, s -> page.canvas.drawText(fit(s, p, (cols.getOrNull(i + 1) ?: right) - cols[i] - 6f), cols[i], y, p) }
    }

    fun note(s: String) {
        line()
        page.canvas.drawText(s, left, y, grey)
    }

    fun finish(out: OutputStream) {
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }
}

/** Vehicle data, maintenance jobs newest first, totals per category and deadlines. */
fun writeMaintenancePdf(out: OutputStream, v: Vehicle, deadlines: List<Deadline>, fuel: List<FuelEntry>, expenses: List<Expense>) {
    val jobs = expenses.filter { it.categoria in MAINTENANCE }.sortedByDescending { it.data }
    val r = Report("Storico manutenzione", v, currentKm(v, fuel, expenses))
    r.heading("Interventi (${jobs.size})")
    val cols = listOf(r.left, r.left + 62f, r.left + 150f, r.left + 210f, r.left + 275f)
    r.row(cols, listOf("Data", "Intervento", "Km", "Importo", "Note / officina"), r.bold)
    if (jobs.isEmpty()) r.note("Nessun intervento registrato.")
    jobs.forEach { r.row(cols, listOf(it.data.date(), it.categoria, it.km?.km() ?: "—", it.importo.eur(), it.note)) }
    if (jobs.isNotEmpty()) {
        r.heading("Totali")
        jobs.groupBy { it.categoria }.forEach { (cat, list) -> r.pair(cat, list.sumOf { it.importo }.eur()) }
        r.pair("Totale manutenzione", jobs.sumOf { it.importo }.eur())
    }
    if (deadlines.isNotEmpty()) {
        r.heading("Scadenze")
        deadlines.sortedBy { it.data }.forEach { r.pair(it.tipo, it.data.date()) }
    }
    r.finish(out)
}

/** Vehicle data, every refuel newest first with price per litre and consumption, and the totals. */
fun writeFuelPdf(out: OutputStream, v: Vehicle, fuel: List<FuelEntry>, expenses: List<Expense>) {
    val kmPerLitre = consumptionSeries(fuel).toMap()
    val r = Report("Storico rifornimenti", v, currentKm(v, fuel, expenses))
    r.heading("Rifornimenti (${fuel.size})")
    val cols = listOf(r.left, r.left + 70f, r.left + 150f, r.left + 220f, r.left + 300f, r.left + 380f)
    r.row(cols, listOf("Data", "Km", "Litri", "Importo", "€/litro", "km/l"), r.bold)
    if (fuel.isEmpty()) r.note("Nessun rifornimento registrato.")
    fuel.sortedByDescending { it.km }.forEach { f ->
        r.row(cols, listOf(f.data.date(), f.km.km(), f.litri.dec(2), f.importo.eur(), (f.importo / f.litri).dec(3), kmPerLitre[f]?.dec(1) ?: "—"))
    }
    if (fuel.isNotEmpty()) {
        val litres = fuel.sumOf { it.litri }
        val cost = fuel.sumOf { it.importo }
        r.heading("Totali")
        r.pair("Litri totali", litres.dec(1))
        r.pair("Spesa totale", cost.eur())
        r.pair("Prezzo medio", "${(cost / litres).dec(3)} €/litro")
        avgKmPerLiter(fuel)?.let { r.pair("Consumo medio", "${it.dec(1)} km/l") }
    }
    r.finish(out)
}

@Composable
fun PdfSection(v: Vehicle, deadlines: List<Deadline>, fuel: List<FuelEntry>, expenses: List<Expense>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var write by remember { mutableStateOf<(OutputStream) -> Unit>({}) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            message = runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use(write) } }
                .fold({ "PDF salvato." }, { "Creazione del PDF non riuscita." })
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            Text("Storico manutenzione", fontWeight = FontWeight.SemiBold)
            Text(
                "Dati del veicolo e km attuali, tutti gli interventi (${MAINTENANCE.joinToString(", ").lowercase()}) con data, km, importo e note, " +
                    "i totali per tipo e le scadenze.",
                color = Muted, style = MaterialTheme.typography.bodySmall,
            )
            Button({
                write = { writeMaintenancePdf(it, v, deadlines, fuel, expenses) }
                save.launch("manutenzione-${v.targa}.pdf")
            }, Modifier.fillMaxWidth()) { Text("Crea PDF manutenzione") }
        }
        Panel {
            Text("Storico rifornimenti", fontWeight = FontWeight.SemiBold)
            Text(
                "Dati del veicolo e tutti i rifornimenti con data, km, litri, importo, prezzo al litro e consumo, più i totali e il consumo medio.",
                color = Muted, style = MaterialTheme.typography.bodySmall,
            )
            Button({
                write = { writeFuelPdf(it, v, fuel, expenses) }
                save.launch("rifornimenti-${v.targa}.pdf")
            }, Modifier.fillMaxWidth()) { Text("Crea PDF rifornimenti") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
