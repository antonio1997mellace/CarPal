package com.miogarage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
import kotlin.math.ceil

data class PumpPrice(val fuel: String, val price: Double, val self: Boolean)

/** [price] and [self] are the cheapest offer for the vehicle's fuel; [prices] is everything the station sells. */
data class Station(
    val id: Int, val name: String, val brand: String, val lat: Double, val lng: Double, val km: Double,
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
            s.optInt("id"), s.optString("name"), s.optString("brand"), loc.getDouble("lat"), loc.getDouble("lng"),
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

/** Street address of one station; the area search does not return it, so it is asked per station. */
private suspend fun fetchAddress(id: Int) = withContext(Dispatchers.IO) {
    runCatching {
        val c = URL("https://carburanti.mise.gov.it/ospzApi/registry/servicearea/$id").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            JSONObject(c.inputStream.use { it.readBytes().decodeToString() }).optString("address").ifBlank { null }
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}

/** Name, address and distance of a station; the address is downloaded the first time the station is shown. */
@Composable
private fun StationText(s: Station, addresses: MutableMap<Int, String>) {
    LaunchedEffect(s.id) { if (s.id !in addresses) fetchAddress(s.id)?.let { addresses[s.id] = it } }
    Text(s.name.ifBlank { s.brand }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(addresses[s.id] ?: "…", color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    // "PompeBianche" is how the ministry labels independent stations with no oil-company brand
    val brand = if (s.brand == "PompeBianche") "Indipendente" else s.brand
    Text(
        listOf(String.format(Locale.ITALY, "%.1f km", s.km), brand).filter { it.isNotBlank() }.joinToString(" · "),
        color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

private fun price(p: Double) = String.format(Locale.ITALY, "%.3f €", p)

private fun navigate(ctx: Context, s: Station) {
    val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${s.lat},${s.lng}")).setPackage("com.google.android.apps.maps")
    // without Google Maps installed, any other maps app can still show the place
    runCatching { ctx.startActivity(maps) }.onFailure {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${s.lat},${s.lng}?q=${s.lat},${s.lng}"))) }
    }
}

/** Orange price tag with a pointer underneath, anchored on the station. */
private fun priceIcon(ctx: Context, label: String): Drawable {
    val d = ctx.resources.displayMetrics.density
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * d; typeface = Typeface.DEFAULT_BOLD; color = 0xFF111112.toInt() }
    val orange = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF7A1A.toInt() }
    val h = 28 * d
    val tip = 7 * d
    val w = text.measureText(label) + 20 * d
    val bmp = Bitmap.createBitmap(ceil(w).toInt(), ceil(h + tip).toInt(), Bitmap.Config.ARGB_8888)
    Canvas(bmp).apply {
        drawRoundRect(0f, 0f, w, h, h / 2, h / 2, orange)
        drawPath(Path().apply { moveTo(w / 2 - tip, h - 1); lineTo(w / 2 + tip, h - 1); lineTo(w / 2, h + tip); close() }, orange)
        drawText(label, 10 * d, h / 2 - (text.ascent() + text.descent()) / 2, text)
    }
    return BitmapDrawable(ctx.resources, bmp)
}

/** White dot with a dark ring: the position the search was made from. */
private fun positionIcon(ctx: Context): Drawable {
    val r = 11 * ctx.resources.displayMetrics.density
    val bmp = Bitmap.createBitmap((r * 2).toInt(), (r * 2).toInt(), Bitmap.Config.ARGB_8888)
    Canvas(bmp).apply {
        drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111112.toInt() })
        drawCircle(r, r, r * 0.62f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
    }
    return BitmapDrawable(ctx.resources, bmp)
}

private fun newMap(ctx: Context): MapView {
    Configuration.getInstance().apply {
        userAgentValue = ctx.packageName // required by the OpenStreetMap tile servers
        osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
        osmdroidTileCache = File(osmdroidBasePath, "tiles")
    }
    return MapView(ctx).apply {
        setMultiTouchControls(true)
        // by default the map destroys its tiles and markers whenever its list row scrolls away; we detach it ourselves on exit
        setDestroyMode(false)
        // the map sits in a scrolling list: while a finger is on it, drags belong to the map
        setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
    }
}

/** Puts the search position and one price tag per station on [map], then frames them all. */
private fun showStations(map: MapView, center: GeoPoint, stations: List<Station>, onSelect: (Station) -> Unit) {
    map.overlays.clear()
    map.overlays += Marker(map).apply {
        position = center
        icon = positionIcon(map.context)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setOnMarkerClickListener { _, _ -> true }
    }
    stations.forEach { s ->
        map.overlays += Marker(map).apply {
            position = GeoPoint(s.lat, s.lng)
            icon = priceIcon(map.context, String.format(Locale.ITALY, "%.3f", s.price))
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setOnMarkerClickListener { _, _ -> onSelect(s); true }
        }
    }
    fun frame() {
        if (stations.isEmpty()) {
            map.controller.setZoom(14.0)
            map.controller.setCenter(center)
        } else map.zoomToBoundingBox(BoundingBox.fromGeoPoints(stations.map { GeoPoint(it.lat, it.lng) } + center), false, 100)
    }
    // framing needs the real size: on a map that has not been laid out yet it computes a broken zoom and the app hangs
    if (map.width > 0) frame() else map.addOnFirstLayoutListener { _, _, _, _, _ -> frame() }
    map.invalidate()
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
    val addresses = remember { mutableStateMapOf<Int, String>() }
    // one map for the whole tab: the list disposes rows that scroll away, and a fresh MapView each time is what froze the app
    val map = remember { newMap(ctx) }
    DisposableEffect(Unit) { onDispose { map.onDetach() } }

    fun select(s: Station) {
        selected = s
        map.controller.animateTo(GeoPoint(s.lat, s.lng))
    }

    fun search(lat: Double, lng: Double) = scope.launch {
        loading = true
        message = null
        runCatching { fetchStations(lat, lng, radius.substringBefore(' ').toInt(), fuel!!) }.fold(
            {
                stations = it
                center = GeoPoint(lat, lng)
                selected = null
                showStations(map, GeoPoint(lat, lng), it, ::select)
            },
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
                Chips(RADII, radius) {
                    radius = it
                    center?.let { c -> search(c.latitude, c.longitude) } // same place, new distance
                }
                SectionTitle("Ordina per")
                Chips(ORDERS, order) { order = it }
            }
        }
        val c = center
        if (c != null && sorted != null) {
            // kept as item 1 so a tap on a list row can scroll back to it
            item(key = "map") {
                // the same view comes back each time the row re-enters the list, so free it from its old holder first
                AndroidView({ (map.parent as? android.view.ViewGroup)?.removeView(map); map }, Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(20.dp)))
            }
            item {
                val s = selected
                if (s == null) {
                    Text("Tocca un prezzo sulla mappa per vedere tutti i carburanti. Mappa © OpenStreetMap.", color = Muted, style = MaterialTheme.typography.bodySmall)
                } else Panel {
                    StationText(s, addresses)
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
            Panel(onClick = { select(s); scope.launch { list.animateScrollToItem(1) } }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) { StationText(s, addresses) }
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
