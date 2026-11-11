package com.miogarage

import android.accounts.Account
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.work.*
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Google account sync: the garage (data file + documents) is mirrored in the app's private folder of the user's Drive.
// shortcut: whole-garage sync, one side replaces the other; move to per-record merge if several people edit the same garage.

private val SCOPE get() = Scope("https://www.googleapis.com/auth/drive.appdata") // the app's own hidden folder, not the user's files
private const val DATA = "garage.json"
private const val API = "https://www.googleapis.com/drive/v3"

enum class SyncAction { NONE, UPLOAD, DOWNLOAD, ASK }

/** One string that changes whenever any file (name to md5) is added, removed or modified. */
fun fingerprint(files: Map<String, String>) = files.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" }

/**
 * What a sync must do, from both sides now and as they were after the last sync (null: never synced on this phone).
 * [remote] is null when the account has no garage yet.
 */
fun decide(local: String, remote: String?, syncedLocal: String?, syncedRemote: String?, localEmpty: Boolean) = when {
    remote == null -> if (localEmpty) SyncAction.NONE else SyncAction.UPLOAD
    local == remote -> SyncAction.NONE
    remote == syncedRemote -> SyncAction.UPLOAD // only this phone changed
    local == syncedLocal -> SyncAction.DOWNLOAD // only the account changed
    localEmpty && syncedLocal == null -> SyncAction.DOWNLOAD // fresh install
    else -> SyncAction.ASK // both changed: replacing either side silently would lose data
}

/** True for "docs/<vehicleId>/<slot>/<file>" with a safe file name: remote names become local paths. */
fun isDocPath(name: String): Boolean {
    val p = name.split('/')
    return p.size == 4 && p[0] == "docs" && p[1].toLongOrNull() != null && p[2] in DOC_SLOTS + MISC && p[3] == safeName(p[3])
}

private fun md5(f: File): String {
    val d = MessageDigest.getInstance("MD5")
    f.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            d.update(buf, 0, n)
        }
    }
    return d.digest().joinToString("") { "%02x".format(it) }
}

private class RemoteFile(val id: String, val name: String, val md5: String)

/** The few Drive REST calls needed, on the app data folder only. Every call throws on a failed request. */
private class Drive(private val token: String) {
    private fun open(url: String, method: String = "GET"): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        if (method == "PATCH") { // HttpURLConnection has no PATCH; Google APIs accept this override
            c.requestMethod = "POST"
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        } else c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun text(c: HttpURLConnection) = try {
        c.inputStream.use { it.readBytes().decodeToString() } // throws on any non-2xx answer
    } finally {
        c.disconnect()
    }

    fun email(): String = JSONObject(text(open("$API/about?fields=user(emailAddress)"))).getJSONObject("user").getString("emailAddress")

    fun list(): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        var page: String? = null
        do {
            val next = page?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: ""
            val o = JSONObject(text(open("$API/files?spaces=appDataFolder&pageSize=1000&fields=nextPageToken,files(id,name,md5Checksum)$next")))
            val a = o.getJSONArray("files")
            for (i in 0 until a.length()) a.getJSONObject(i).let { out += RemoteFile(it.getString("id"), it.getString("name"), it.optString("md5Checksum")) }
            page = o.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out
    }

    fun download(id: String, to: File) {
        val c = open("$API/files/$id?alt=media")
        try {
            c.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
    }

    fun delete(id: String) {
        text(open("$API/files/$id", "DELETE"))
    }

    /** Replaces the content of [id], or creates [name] in the app folder when [id] is null. */
    fun upload(name: String, id: String?, f: File) {
        val upload = "https://www.googleapis.com/upload/drive/v3/files"
        val c = if (id != null) open("$upload/$id?uploadType=media", "PATCH") else open("$upload?uploadType=multipart", "POST")
        c.doOutput = true
        c.setChunkedStreamingMode(0) // documents are streamed, not held in memory
        val boundary = "miogarage-${System.nanoTime()}"
        c.setRequestProperty("Content-Type", if (id != null) "application/octet-stream" else "multipart/related; boundary=$boundary")
        c.outputStream.use { out ->
            if (id == null) {
                val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray(listOf("appDataFolder")))
                out.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            }
            f.inputStream().use { it.copyTo(out) }
            if (id == null) out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        text(c)
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resumeWithException(it) }
}

private fun google(account: String) = Account(account, "com.google")

/** [account] null lets the user pick one. */
private fun authRequest(account: String?) = AuthorizationRequest.builder().setRequestedScopes(listOf(SCOPE))
    .apply { if (account != null) setAccount(google(account)) }.build()

/** The data file freshly exported plus every stored document, keyed by the name used on Drive. */
private suspend fun localFiles(ctx: Context, dao: GarageDao): Pair<Map<String, File>, Boolean> {
    val backup = dao.backup()
    val data = File(ctx.cacheDir, DATA).apply { writeText(backup.toJson()) }
    val docs = File(ctx.filesDir, "docs").walkTopDown().filter { it.isFile }
        .associateBy { it.relativeTo(ctx.filesDir).invariantSeparatorsPath }.filterKeys(::isDocPath)
    return (mapOf(DATA to data) + docs) to backup.vehicles.isEmpty()
}

private suspend fun sync(ctx: Context, account: String, force: SyncAction?): SyncAction {
    // no token means Google wants the user on screen again (access revoked, new phone): they must sign in from Settings
    val token = checkNotNull(Identity.getAuthorizationClient(ctx).authorize(authRequest(account)).await().accessToken)
    val drive = Drive(token)
    val dao = GarageDb.get(ctx).dao()
    val (local, localEmpty) = localFiles(ctx, dao)
    val localMd5 = local.mapValues { md5(it.value) }
    val listed = drive.list()
    val remote = listed.associateBy { it.name }
    val l = fingerprint(localMd5)
    val r = if (DATA in remote) fingerprint(remote.mapValues { it.value.md5 }) else null

    val action = force ?: decide(l, r, Prefs.syncedLocal, Prefs.syncedRemote, localEmpty)
    when (action) {
        SyncAction.UPLOAD -> {
            // the data file goes last, so an interrupted upload never leaves data pointing at missing documents
            local.entries.sortedBy { it.key == DATA }.forEach { (name, f) ->
                if (remote[name]?.md5 != localMd5[name]) drive.upload(name, remote[name]?.id, f)
            }
            listed.filter { it.name !in local || it !== remote[it.name] }.forEach { drive.delete(it.id) }
            Prefs.synced(l, l)
        }
        SyncAction.DOWNLOAD -> {
            val data = File(ctx.cacheDir, "remote.json")
            drive.download(remote.getValue(DATA).id, data)
            val backup = parseBackup(data.readText()) // validated before anything local is touched
            remote.values.filter { isDocPath(it.name) && localMd5[it.name] != it.md5 }.forEach {
                val part = File(ctx.cacheDir, "doc.part")
                drive.download(it.id, part)
                val dest = File(ctx.filesDir, it.name)
                dest.parentFile!!.mkdirs()
                dest.delete()
                check(part.renameTo(dest))
            }
            local.keys.filter { it != DATA && it !in remote }.forEach { File(ctx.filesDir, it).delete() }
            dao.replaceAll(backup)
            Prefs.synced(fingerprint(localFiles(ctx, dao).first.mapValues { md5(it.value) }), r!!)
        }
        SyncAction.NONE -> if (r != null) Prefs.synced(l, r)
        SyncAction.ASK -> {}
    }
    return action
}

object Sync {
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /** Both sides changed: the user has to choose which one to keep. */
    var conflict by mutableStateOf(false)
        private set
    private val mutex = Mutex() // the app and the background worker never sync at the same time

    fun later() {
        conflict = false
    }

    /** [force] is the side chosen by the user when both changed. Does nothing when signed out. */
    suspend fun run(ctx: Context, force: SyncAction? = null) {
        val account = Prefs.account ?: return
        mutex.withLock {
            busy = true
            conflict = false
            runCatching { withContext(Dispatchers.IO) { sync(ctx.applicationContext, account, force) } }.fold(
                {
                    conflict = it == SyncAction.ASK
                    message = if (conflict) "In attesa della tua scelta." else "Sincronizzato."
                },
                { message = "Sincronizzazione non riuscita. Controlla la connessione; se continua, esci e accedi di nuovo." },
            )
            busy = false
        }
    }

    /** Uploads the changes once the phone is online, even if the app has been closed meanwhile. Calls made within a few seconds become one sync. */
    fun schedule(ctx: Context) {
        if (Prefs.account == null) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "sync", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(3, TimeUnit.SECONDS).build(),
        )
    }

    /** Completes a sign-in from the access [token] Google returned; false when there is none. */
    suspend fun connect(ctx: Context, token: String?): Boolean {
        val email = token?.let { runCatching { withContext(Dispatchers.IO) { Drive(it).email() } }.getOrNull() }
        if (email == null) {
            message = "Accesso non riuscito."
            return false
        }
        Prefs.signIn(email)
        run(ctx)
        return true
    }

    /** The garage stays on this phone and in the account; only the link between the two is removed. */
    suspend fun signOut(ctx: Context) {
        val account = Prefs.account ?: return
        Prefs.signOut()
        message = null
        // without revoking, the next sign-in would silently reuse this account instead of letting the user choose
        runCatching {
            Identity.getAuthorizationClient(ctx).revokeAccess(RevokeAccessRequest.builder().setAccount(google(account)).setScopes(listOf(SCOPE)).build()).await()
        }
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Sync.run(applicationContext) // a conflict waits for the next time the app is opened
        return Result.success()
    }
}

/** Returns the function that starts the Google sign-in; [onDone] gets true once the account is connected. */
@Composable
fun rememberGoogleSignIn(onDone: (Boolean) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { Identity.getAuthorizationClient(ctx) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val token = runCatching { client.getAuthorizationResultFromIntent(result.data).accessToken }.getOrNull() // throws when the user cancels
        scope.launch { onDone(Sync.connect(ctx, token)) }
    }
    return {
        scope.launch {
            val r = runCatching { client.authorize(authRequest(null)).await() }.getOrNull()
            val ask = r?.pendingIntent
            if (ask != null) consent.launch(IntentSenderRequest.Builder(ask.intentSender).build()) else onDone(Sync.connect(ctx, r?.accessToken))
        }
    }
}

/** First-launch screen: sign in, or go on with the data on this phone only. */
@Composable
fun LoginScreen(onDone: () -> Unit) {
    val signIn = rememberGoogleSignIn { if (it) onDone() }
    Surface(Modifier.fillMaxSize(), color = Bg) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("MioGarage", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold)
            Text(
                "Accedi con Google per salvare il garage nel tuo spazio Drive e ritrovarlo su tutti i tuoi telefoni.",
                color = Muted, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            BigButton("Accedi con Google", enabled = !Sync.busy, onClick = signIn)
            TextButton(onDone) { Text("Continua senza account") }
            Sync.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
        }
    }
}

private val syncTime = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

/** Settings panel to sign in and out. */
@Composable
fun AccountPanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val signIn = rememberGoogleSignIn {}
    Panel {
        SectionTitle("Account")
        val account = Prefs.account
        if (account == null) {
            Text("Accedi con Google per sincronizzare il garage e i documenti tra i tuoi telefoni.", color = Muted, style = MaterialTheme.typography.bodySmall)
            Button(signIn, Modifier.fillMaxWidth(), enabled = !Sync.busy) { Text("Accedi con Google") }
        } else {
            Text(account, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ scope.launch { Sync.run(ctx) } }, Modifier.weight(1f), enabled = !Sync.busy) { Text("Sincronizza ora") }
                OutlinedButton({ scope.launch { Sync.signOut(ctx) } }, Modifier.weight(1f), enabled = !Sync.busy) { Text("Esci") }
            }
            val last = Prefs.lastSync
            Text(
                if (last == 0L) "Non ancora sincronizzato." else "Ultima sincronizzazione: " + syncTime.format(Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Uscendo, i dati restano su questo telefono e nel tuo account.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Sync.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Shown over any screen when both the phone and the account changed since the last sync. */
@Composable
fun SyncConflictDialog() {
    if (!Sync.conflict) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = Sync::later,
        title = { Text("Dati diversi") },
        text = { Text("Il garage su questo telefono e quello salvato nel tuo account Google sono diversi. Quale vuoi tenere? L'altro viene sostituito.", color = Ink) },
        confirmButton = { TextButton({ scope.launch { Sync.run(ctx, SyncAction.UPLOAD) } }) { Text("Questo telefono") } },
        dismissButton = { TextButton({ scope.launch { Sync.run(ctx, SyncAction.DOWNLOAD) } }) { Text("Account Google") } },
    )
}
