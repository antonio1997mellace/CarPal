package com.miogarage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App preferences, loaded once in App.onCreate and observable from Compose. */
object Prefs {
    private lateinit var prefs: SharedPreferences
    var theme by mutableStateOf("dark") // "dark" | "light" | "system"
        private set
    var notifications by mutableStateOf(true)
        private set
    var onboarded by mutableStateOf(false) // the first-launch sign-in screen has been answered
        private set
    var account by mutableStateOf<String?>(null) // Google account the garage is synced with
        private set

    /** Fingerprints of this phone and of the account right after the last sync, see [decide]. */
    val syncedLocal get() = prefs.getString("syncedLocal", null)
    val syncedRemote get() = prefs.getString("syncedRemote", null)
    var lastSync by mutableStateOf(0L) // epoch millis of the last time phone and account were found or made equal
        private set

    fun load(ctx: Context) {
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        theme = prefs.getString("theme", "dark")!!
        notifications = prefs.getBoolean("notifications", true)
        onboarded = prefs.getBoolean("onboarded", false)
        account = prefs.getString("account", null)
        lastSync = prefs.getLong("syncedAt", 0)
    }

    fun onboard() {
        onboarded = true
        prefs.edit().putBoolean("onboarded", true).apply()
    }

    fun signIn(email: String) {
        account = email
        lastSync = 0
        // another account's history says nothing about this one
        prefs.edit().putString("account", email).remove("syncedLocal").remove("syncedRemote").remove("syncedAt").apply()
    }

    fun signOut() {
        account = null
        lastSync = 0
        prefs.edit().remove("account").remove("syncedLocal").remove("syncedRemote").remove("syncedAt").apply()
    }

    fun synced(local: String, remote: String) {
        lastSync = System.currentTimeMillis()
        prefs.edit().putString("syncedLocal", local).putString("syncedRemote", remote).putLong("syncedAt", lastSync).apply()
    }

    fun chooseTheme(value: String) {
        theme = value
        prefs.edit().putString("theme", value).apply()
    }

    fun enableNotifications(value: Boolean) {
        notifications = value
        prefs.edit().putBoolean("notifications", value).apply()
    }
}

private val THEMES = listOf("Scuro" to "dark", "Chiaro" to "light", "Come il telefono" to "system")

@Composable
fun SettingsScreen(dao: GarageDao, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var toImport by remember { mutableStateOf<Backup?>(null) }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            message = runCatching {
                val json = dao.backup().toJson()
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) } }
            }.fold({ "Dati esportati." }, { "Esportazione non riuscita." })
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { parseBackup(ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }) }
            }.fold({ toImport = it }, { message = "File non valido: non è un backup di MioGarage." })
        }
    }

    Scaffold(topBar = { TopBar("Impostazioni", onBack) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AccountPanel()

            Panel {
                SectionTitle("Tema")
                Chips(THEMES.map { it.first }, THEMES.first { it.second == Prefs.theme }.first) { label ->
                    Prefs.chooseTheme(THEMES.first { it.first == label }.second)
                }
            }

            Panel {
                SectionTitle("Notifiche")
                Row(
                    Modifier.fillMaxWidth().toggleable(Prefs.notifications, role = Role.Switch) { on ->
                        Prefs.enableNotifications(on)
                        if (on && Build.VERSION.SDK_INT >= 33) askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Avvisi sulle scadenze", fontWeight = FontWeight.SemiBold)
                        Text("30 giorni prima, poi ogni giorno dell'ultima settimana", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(Prefs.notifications, onCheckedChange = null)
                }
                TextButton({
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                    )
                }) { Text("Apri le impostazioni di Android") }
            }

            Panel {
                SectionTitle("Backup dei dati")
                Text(
                    "Esporta veicoli, scadenze, rifornimenti e spese in un file. Sul nuovo telefono installa l'app e importa lo stesso file.",
                    color = Muted, style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button({ export.launch("miogarage-backup.json") }, Modifier.weight(1f)) { Text("Esporta") }
                    OutlinedButton({ import.launch(arrayOf("*/*")) }, Modifier.weight(1f)) { Text("Importa") }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    toImport?.let { b ->
        ConfirmDialog(
            "Il backup contiene ${b.vehicles.size} veicoli. Importandolo, tutti i dati presenti ora nell'app vengono sostituiti.",
            confirm = "Sostituisci", onDismiss = { toImport = null },
        ) {
            toImport = null
            scope.launch { message = runCatching { dao.replaceAll(b) }.fold({ "Dati importati." }, { "Importazione non riuscita." }) }
        }
    }
}
