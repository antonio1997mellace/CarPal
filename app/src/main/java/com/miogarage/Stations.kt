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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class PumpPrice(val fuel: String, val price: Double, val self: Boolean)

/** [price] and [self] are the cheapest offer for the vehicle's fuel; [prices] is everything the station sells. */
data class Station(
    val name: String, val brand: String, val lat: Double, val lng: Double, val km: Double,
    val price: Double, val self: Boolean, val prices: List<PumpPrice>,
)

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
        val prices = (0 until fuels.length()).map { fuels.getJSONObject(it) }.map { PumpPrice(it.getString("name"), it.getDouble("price"), it.optBoolean("isSelf")) }
        val best = prices.filter { it.fuel == fuel }.minByOrNull { it.price } ?: return@mapNotNull null
        val loc = s.getJSONObject("location")
        Station(
            s.optString("name"), s.optString("brand"), loc.getDouble("lat"), loc.getDouble("lng"),
            s.optString("distance").toDoubleOrNull() ?: 0.0, best.price, best.self, prices.sortedWith(compareBy({ it.fuel }, { it.price })),
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

private fun price(p: Double) = String.format(Locale.ITALY, "%.3f €", p)

private fun navigate(ctx: Context, s: Station) {
    val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${s.lat},${s.lng}")).setPackage("com.google.android.apps.maps")
    // without Google Maps installed, any other maps app can still show the place
    runCatching { ctx.startActivity(maps) }.onFailure {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${s.lat},${s.lng}?q=${s.lat},${s.lng}"))) }
    }
}

/** OpenStreetMap view with the search position and one price label per station; tapping a label selects the station. */
@Composable
private fun StationMap(center: GeoPoint, stations: List<Station>, onSelect: (Station) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(20.dp)),
        factory = { ctx ->
            Configuration.getInstance().apply {
                userAgentValue = ctx.packageName // required by the OpenStreetMap tile servers
                osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
                osmdroidTileCache = File(osmdroidBasePath, "tiles")
            }
            MapView(ctx).apply {
                setMultiTouchControls(true)
                // the map sits in a scrolling list: while a finger is on it, drags belong to the map
                setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
            }
        },
        update = { map ->
            val key = center to stations
            if (map.tag != key) { // rebuild and re-frame only for a new search, not on every recomposition
                map.tag = key
                map.overlays.clear()
                map.overlays += Marker(map).apply {
                    position = center
                    icon = ContextCompat.getDrawable(map.context, org.osmdroid.library.R.drawable.person)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "La tua posizione"
                    setOnMarkerClickListener { _, _ -> true }
                }
                stations.forEach { s ->
                    map.overlays += Marker(map).apply {
                        position = GeoPoint(s.lat, s.lng)
                        setTextIcon(String.format(Locale.ITALY, "%.3f", s.price))
                        title = "${s.brand} ${price(s.price)}"
                        setOnMarkerClickListener { _, _ -> onSelect(s); true }
                    }
                }
                map.post {
                    if (stations.isEmpty()) {
                        map.controller.setZoom(14.0)
                        map.controller.setCenter(center)
                    } else map.zoomToBoundingBox(BoundingBox.fromGeoPoints(stations.map { GeoPoint(it.lat, it.lng) } + center), false, 80)
                }
                map.invalidate()
            }
        },
        onRelease = { it.onDetach() },
    )
}

private val RADII = listOf("1 km", "3 km", "5 km", "10 km")
private val ORDERS = listOf("Più vicini", "Meno cari")

@Composable
fun Stations(v: Vehicle) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val fuel = pumpFuel(v.alimentazione)
    var address by rememberSaveable { mutableStateOf("") }
    var radius by rememberSaveable { mutableStateOf(RADII[2]) }
    var order by rememberSaveable { mutableStateOf(ORDERS[0]) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    // shortcut: results are not kept when you leave the tab or rotate; hoist them if that becomes annoying
    var stations by remember { mutableStateOf<List<Station>?>(null) }
    var center by remember { mutableStateOf<GeoPoint?>(null) }
    var selected by remember { mutableStateOf<Station?>(null) }

    fun search(lat: Double, lng: Double) = scope.launch {
        loading = true
        message = null
        runCatching { fetchStations(lat, lng, radius.substringBefore(' ').toInt(), fuel!!) }.fold(
            { stations = it; center = GeoPoint(lat, lng); selected = null },
            { message = "Non riesco a scaricare i prezzi. Controlla la connessione e riprova." },
        )
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
    val sorted = stations?.let { found -> if (order == ORDERS[0]) found.sortedBy { it.km } else found.sortedBy { it.price } }
    LazyColumn(state = list, contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
        val c = center
        if (c != null && sorted != null) {
            // kept as item 1 so a tap on a list row can scroll back to it
            item(key = "map") { StationMap(c, sorted) { selected = it } }
            item {
                val s = selected
                if (s == null) {
                    Text("Tocca un prezzo sulla mappa per vedere tutti i carburanti. Mappa © OpenStreetMap.", color = Muted, style = MaterialTheme.typography.bodySmall)
                } else Panel {
                    Text(s.brand.ifBlank { s.name }, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOf(s.name, String.format(Locale.ITALY, "%.1f km", s.km)).filter { it.isNotBlank() }.joinToString(" · "),
                        color = Muted, style = MaterialTheme.typography.bodySmall,
                    )
                    s.prices.forEach { p ->
                        Row {
                            Text("${p.fuel} · ${if (p.self) "self" else "servito"}", Modifier.weight(1f), color = if (p.fuel == fuel) Ink else Muted)
                            Text(price(p.price), fontWeight = FontWeight.SemiBold, color = if (p.fuel == fuel) Accent else Ink)
                        }
                    }
                    Button({ navigate(ctx, s) }, Modifier.fillMaxWidth()) { Text("Naviga con Google Maps") }
                }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        message?.let { item { Text(it, color = Bad) } }
        if (sorted != null && sorted.isEmpty() && !loading) item { Text("Nessun distributore di $fuel in questa zona: prova una distanza maggiore.", color = Muted) }
        items(sorted ?: emptyList()) { s ->
            // the details open under the map, so bring the map back into view
            Panel(onClick = { selected = s; scope.launch { list.animateScrollToItem(1) } }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.brand.ifBlank { s.name }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(s.name, String.format(Locale.ITALY, "%.1f km", s.km)).filter { it.isNotBlank() }.joinToString(" · "),
                            color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(price(s.price), color = Accent, fontWeight = FontWeight.ExtraBold)
                        Text(if (s.self) "self" else "servito", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (!sorted.isNullOrEmpty()) item {
            Text("Prezzi comunicati dai gestori al Ministero (Osservaprezzi carburanti).", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
