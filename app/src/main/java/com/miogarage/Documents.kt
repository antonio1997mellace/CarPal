package com.miogarage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Documents are plain files in filesDir/docs/<vehicleId>/<slot>/: the file name is the document name, so no table is needed.
// shortcut: not included in the JSON backup and not cleaned up by a backup import; move to a zip backup if that is needed.
val DOC_SLOTS = listOf("Libretto", "Assicurazione", "Revisione", "Patente")
const val MISC = "Varie"

fun docsDir(ctx: Context, vehicleId: Long) = File(ctx.filesDir, "docs/$vehicleId")

/** File name safe to store: no path separators or reserved characters, never blank. */
fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").trim().trim('.').ifBlank { "documento" }

/** [name], or "name (2).ext", "name (3).ext"… so a new file never overwrites one in [taken]. */
fun uniqueName(name: String, taken: Collection<String>): String {
    if (name !in taken) return name
    val base = name.substringBeforeLast('.')
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    return generateSequence(2) { it + 1 }.map { "$base ($it)$ext" }.first { it !in taken }
}

private suspend fun importDoc(ctx: Context, uri: Uri, dir: File, replace: Boolean) = withContext(Dispatchers.IO) {
    runCatching {
        val cr = ctx.contentResolver
        var name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "documento"
        // the viewer app is chosen from the extension, so add one when the source has none
        if ('.' !in name) MimeTypeMap.getSingleton().getExtensionFromMimeType(cr.getType(uri))?.let { name += ".$it" }
        dir.mkdirs()
        val tmp = File(dir.parentFile, ".tmp")
        try {
            cr.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            // the old file goes only after the new one is fully copied
            if (replace) dir.listFiles()?.forEach { it.delete() }
            check(tmp.renameTo(File(dir, uniqueName(safeName(name), dir.list()!!.toList()))))
        } finally {
            tmp.delete() // no-op after a successful rename; clears a half-copied file otherwise
        }
    }.isSuccess
}

private fun openDoc(ctx: Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, ctx.contentResolver.getType(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(view) }.onFailure { Toast.makeText(ctx, "Nessuna app per aprire questo file", Toast.LENGTH_LONG).show() }
}

@Composable
fun Documents(vehicleId: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember(vehicleId) { docsDir(ctx, vehicleId) }
    var version by remember { mutableIntStateOf(0) } // bumped after every change to re-read the folders
    val files = remember(version) {
        (DOC_SLOTS + MISC).associateWith { File(root, it).listFiles()?.sortedBy { f -> f.name.lowercase() } ?: emptyList() }
    }
    var target by rememberSaveable { mutableStateOf(MISC) }
    var toDelete by remember { mutableStateOf<File?>(null) }
    var toRename by remember { mutableStateOf<File?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            if (!importDoc(ctx, uri, File(root, target), replace = target != MISC)) Toast.makeText(ctx, "Caricamento non riuscito", Toast.LENGTH_LONG).show()
            version++
        }
    }
    fun load(slot: String) {
        target = slot
        pick.launch(arrayOf("application/pdf", "image/*"))
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DOC_SLOTS.chunked(2).forEach { pair ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { slot ->
                    val f = files.getValue(slot).firstOrNull()
                    Panel(Modifier.weight(1f).fillMaxHeight(), onClick = { if (f == null) load(slot) else openDoc(ctx, f) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Dot(if (f == null) Line else Good)
                            Text(slot, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        if (f == null) {
                            Text("Tocca per caricare", color = Muted, style = MaterialTheme.typography.bodySmall)
                        } else Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.name, Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton({ toDelete = f }) { Icon(Icons.Default.Delete, "Elimina $slot", tint = Muted) }
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle(MISC) }
            TextButton({ load(MISC) }) { Text("Aggiungi") }
        }
        if (files.getValue(MISC).isEmpty()) {
            Text("Scontrini, ricevute e altri documenti: aggiungili qui e rinominali per ritrovarli.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        files.getValue(MISC).forEach { f ->
            Panel(onClick = { openDoc(ctx, f) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton({ toRename = f }) { Icon(Icons.Default.Edit, "Rinomina ${f.name}", tint = Muted) }
                    IconButton({ toDelete = f }) { Icon(Icons.Default.Delete, "Elimina ${f.name}", tint = Muted) }
                }
            }
        }
    }

    toDelete?.let { f ->
        ConfirmDialog("Eliminare ${f.name}?", onDismiss = { toDelete = null }) {
            f.delete()
            toDelete = null
            version++
        }
    }
    toRename?.let { f ->
        var name by remember(f) { mutableStateOf(f.nameWithoutExtension) }
        AlertDialog(
            onDismissRequest = { toRename = null },
            title = { Text("Rinomina") },
            text = { Field(name, { name = it }, "Nome", cap = KeyboardCapitalization.Sentences) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val ext = if (f.extension.isEmpty()) "" else ".${f.extension}"
                    f.renameTo(File(f.parentFile, uniqueName(safeName(name) + ext, f.parentFile!!.list()!!.toList() - f.name)))
                    toRename = null
                    version++
                }) { Text("Salva") }
            },
            dismissButton = { TextButton({ toRename = null }) { Text("Annulla") } },
        )
    }
}
