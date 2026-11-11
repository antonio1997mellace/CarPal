package com.miogarage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class Station(val name: String, val brand: String, val lat: Double, val lng: Double, val km: Double, val price: Double, val self: Boolean)

/** Name of the vehicle's fuel in the ministry price list; null when there is no pump price (electric). */
fun pumpFuel(alimentazione: String) = when (alimentazione) {
    "Diesel" -> "Gasolio"
    "GPL", "Metano" -> alimentazione
    "Elettrica" -> null
    else -> "Benzina" // petrol and hybrids
}

/** Stations selling [fuel], each with its cheapest price for it (self-service or attended). */
fun parseStations(json: String, fuel: String): List<Station> {
    val results = JSONObject(json).getJSONArray("results")
    return (0 until results.length()).mapNotNull { i ->
        val s = results.getJSONObject(i)
        val fuels = s.getJSONArray("fuels")
        val best = (0 until fuels.length()).map { fuels.getJSONObject(it) }.filter { it.getString("name") == fuel }.minByOrNull { it.getDouble("price") }
            ?: return@mapNotNull null
        val loc = s.getJSONObject("location")
        Station(
            s.optString("name"), s.optString("brand"), loc.getDouble("lat"), loc.getDouble("lng"),
            s.optString("distance").toDoubleOrNull() ?: 0.0, best.getDouble("price"), best.optBoolean("isSelf"),
        )
    }
}

// shortcut: this is the endpoint behind the ministry's own "Osservaprezzi carburanti" site, not a documented API;
// if it changes, switch to the daily open-data CSV files on mimit.gov.it.
private suspend fun fetchStations(lat: Double, lng: Double, radiusKm: Int, fuel: String) = withContext(Dispatchers.IO) {
    val c = URL("https://carburanti.mise.gov.it/ospzApi/search/zone").openConnection() as HttpURLConnection
    try {
        c.requestMethod = "POST"
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write("""{"points":[{"lat":$lat,"lng":$lng}],"radius":$radiusKm}""".toByteArray()) }
        parseStations(c.inputStream.use { it.readBytes().decodeToString() }, fuel)
    } finally {
        c.disconnect()
    }
}

private val RADII = listOf("5 km", "10 km", "20 km")
private val ORDERS = listOf("Più vicini", "Meno cari")

@Composable
fun Stations(v: Vehicle) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val fuel = pumpFuel(v.alimentazione)
    var address by rememberSaveable { mutableStateOf("") }
    var radius by rememberSaveable { mutableStateOf(RADII[0]) }
    var order by rememberSaveable { mutableStateOf(ORDERS[0]) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    // shortcut: results are not kept when you swipe two tabs away or rotate; hoist them if that becomes annoying
    var stations by remember { mutableStateOf<List<Station>?>(null) }

    fun search(lat: Double, lng: Double) = scope.launch {
        loading = true
        message = null
        runCatching { fetchStations(lat, lng, radius.substringBefore(' ').toInt(), fuel!!) }
            .fold({ stations = it }, { message = "Non riesco a scaricare i prezzi. Controlla la connessione e riprova." })
        loading = false
    }

    fun locate() {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { lm.isProviderEnabled(it) }
        if (provider == null) {
            message = "La posizione del telefono è spenta: accendila o scrivi un indirizzo."
            return
        }
        loading = true
        message = null
        @Suppress("MissingPermission") // called only after the permission result below
        LocationManagerCompat.getCurrentLocation(lm, provider, null as android.os.CancellationSignal?, ContextCompat.getMainExecutor(ctx)) { loc: android.location.Location? ->
            if (loc == null) {
                loading = false
                message = "Posizione non disponibile: riprova o scrivi un indirizzo."
            } else search(loc.latitude, loc.longitude)
        }
    }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate() else message = "Senza il permesso di posizione puoi cercare scrivendo un indirizzo."
    }

    fun searchAddress() = scope.launch {
        loading = true
        message = null
        val hit = withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION") // the async variant needs API 33
            runCatching { Geocoder(ctx, Locale.ITALY).getFromLocationName(address, 1)?.firstOrNull() }.getOrNull()
        }
        if (hit == null) {
            loading = false
            message = "Indirizzo non trovato."
        } else search(hit.latitude, hit.longitude)
    }

    if (fuel == null) {
        Text("Per le auto elettriche non ci sono prezzi alla pompa.", Modifier.padding(24.dp), color = Muted)
        return
    }
    val sorted = stations?.let { list -> if (order == ORDERS[0]) list.sortedBy { it.km } else list.sortedBy { it.price } }
    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel {
                SectionTitle("Distributori di $fuel vicino a")
                Field(address, { address = it }, "Indirizzo o città", cap = KeyboardCapitalization.Words)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button({ searchAddress() }, Modifier.weight(1f), enabled = address.isNotBlank() && !loading) { Text("Cerca") }
                    OutlinedButton(
                        { askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                        Modifier.weight(1f), enabled = !loading,
                    ) { Text("Dove sono io") }
                }
                SectionTitle("Distanza massima")
                Chips(RADII, radius) { radius = it }
                SectionTitle("Ordina per")
                Chips(ORDERS, order) { order = it }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        message?.let { item { Text(it, color = Bad) } }
        if (sorted != null && sorted.isEmpty() && !loading) item { Text("Nessun distributore di $fuel in questa zona: prova una distanza maggiore.", color = Muted) }
        items(sorted ?: emptyList()) { s ->
            Panel(onClick = {
                val label = Uri.encode(s.name.ifBlank { s.brand })
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${s.lat},${s.lng}?q=${s.lat},${s.lng}($label)"))) }
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.brand.ifBlank { s.name }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(s.name, String.format(Locale.ITALY, "%.1f km", s.km)).filter { it.isNotBlank() }.joinToString(" · "),
                            color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(String.format(Locale.ITALY, "%.3f €", s.price), color = Accent, fontWeight = FontWeight.ExtraBold)
                        Text(if (s.self) "self" else "servito", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (!sorted.isNullOrEmpty()) item {
            Text("Prezzi comunicati dai gestori al Ministero (Osservaprezzi carburanti). Tocca un distributore per aprirlo nelle mappe.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
