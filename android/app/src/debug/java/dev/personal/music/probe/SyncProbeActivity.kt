package dev.personal.music.probe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.personal.music.ui.MusicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Throwaway probe for the device-sync design. It answers, on a real phone and a real Drive:
 * - can a file in Google Drive be created/opened through the system picker (no Google Cloud)?
 * - does the permission survive restarts?
 * - how long do writes take to reach Drive, and do remote changes show up when reading?
 * Everything it does is logged on screen with timestamps.
 */
class SyncProbeActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("sync_probe", MODE_PRIVATE) }
    private val log = mutableStateListOf<String>()
    private var uri by mutableStateOf<Uri?>(null)
    private var autoRead by mutableStateOf(false)

    private val create = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        it?.let(::adopt)
    }
    private val open = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::adopt) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        uri = prefs.getString("uri", null)?.let(Uri::parse)
        add("Inicio. Archivo guardado: ${uri ?: "ninguno"}")
        uri?.let { u ->
            val held = contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission }
            add("Permiso persistente tras reinicio: ${if (held) "SÍ" else "NO"}")
        }
        setContent {
            MusicTheme {
                LaunchedEffect(autoRead) {
                    while (autoRead) { read(); delay(15_000) }
                }
                // Surface sets the theme's text color; without it text is black on a dark background.
                Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ create.launch("esputifai-sync.json") }) { Text("Crear") }
                        Button({ open.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Abrir") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ lifecycleScope.launch { write() } }, enabled = uri != null) { Text("Escribir") }
                        Button({ lifecycleScope.launch { read() } }, enabled = uri != null) { Text("Leer") }
                    }
                    Row {
                        Switch(autoRead, { autoRead = it }, enabled = uri != null)
                        Text("  Leer cada 15 s", Modifier.padding(top = 12.dp))
                    }
                    Button(::copyLog) { Text("Copiar registro") }
                    LazyColumn(Modifier.padding(top = 8.dp)) {
                        items(log.reversed()) { Text(it, Modifier.padding(vertical = 4.dp)) }
                    }
                }
                }
            }
        }
    }

    private fun adopt(picked: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(picked, flags) }
            .onFailure { add("ERROR al guardar el permiso: $it") }
        prefs.edit().putString("uri", picked.toString()).apply()
        uri = picked
        add("Archivo elegido. Proveedor: ${picked.authority}")
        add("Nombre: ${meta(picked)}")
    }

    private suspend fun write() {
        val u = uri ?: return
        val started = System.currentTimeMillis()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val previous = readText(u)?.let { runCatching { JSONObject(it).optInt("counter") }.getOrNull() } ?: 0
                val body = JSONObject()
                    .put("writer", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("writtenAt", stamp(System.currentTimeMillis()))
                    .put("counter", previous + 1)
                    .toString(2)
                // "wt" truncates: without it a shorter write leaves the old tail in place.
                contentResolver.openOutputStream(u, "wt")!!.use { it.write(body.toByteArray()) }
                body
            }
        }
        result.fold(
            { add("ESCRITO en ${System.currentTimeMillis() - started} ms:\n$it") },
            { add("ERROR al escribir: $it") },
        )
    }

    private suspend fun read() {
        val u = uri ?: return
        val started = System.currentTimeMillis()
        val result = withContext(Dispatchers.IO) { runCatching { readText(u) to meta(u) } }
        result.fold(
            { (text, meta) -> add("LEÍDO en ${System.currentTimeMillis() - started} ms ($meta):\n$text") },
            { add("ERROR al leer: $it") },
        )
    }

    private fun readText(u: Uri): String? =
        contentResolver.openInputStream(u)?.use { it.readBytes().decodeToString() }

    /** Display name, size and the provider's "last modified", to see when Drive notices changes. */
    private fun meta(u: Uri): String = runCatching {
        contentResolver.query(u, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use "sin metadatos"
            val name = c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            val size = c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE))
            val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val mod = if (modIdx >= 0 && !c.isNull(modIdx)) stamp(c.getLong(modIdx)) else "?"
            "$name, $size B, modificado $mod"
        } ?: "sin metadatos"
    }.getOrElse { "metadatos: $it" }

    private fun copyLog() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Registro prueba sync", log.joinToString("\n")))
        Toast.makeText(this, "Registro copiado", Toast.LENGTH_SHORT).show()
    }

    private fun add(line: String) {
        log += "[${stamp(System.currentTimeMillis())}] $line"
    }

    private fun stamp(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
}
