package dev.personal.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.personal.music.sync.SyncStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cloud icon in the header of "Mis listas"; its look says how sync is doing. */
@Composable
fun SyncButton(status: SyncStatus, onClick: () -> Unit) {
    val (icon: ImageVector, tint) = when (status) {
        SyncStatus.Off -> Icons.Outlined.Cloud to MaterialTheme.colorScheme.onSurfaceVariant
        SyncStatus.Syncing -> Icons.Rounded.CloudSync to MaterialTheme.colorScheme.primary
        is SyncStatus.Synced -> Icons.Rounded.CloudDone to MaterialTheme.colorScheme.primary
        SyncStatus.NeedsSignIn, is SyncStatus.Failed -> Icons.Rounded.SyncProblem to MaterialTheme.colorScheme.error
    }
    IconButton(onClick = onClick) { Icon(icon, "Sincronización", tint = tint) }
}

/**
 * Turn sync on/off and see how it is doing. Its foot shows the app version: the app has no
 * settings or "about" screen, and the version matters most here (which app each device runs).
 */
@Composable
fun SyncDialog(
    status: SyncStatus,
    onTurnOn: () -> Unit,
    onSyncNow: () -> Unit,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sincronización") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (status == SyncStatus.Off) {
                    Text(
                        "Tus listas, la Albumteca y las reproducciones, iguales en todos tus dispositivos " +
                            "(móvil, tablet…) a través de tu Google Drive. Actívala en cada uno con la misma cuenta de Google.",
                    )
                    Text(
                        "La app solo usa una carpeta oculta suya en tu Drive: no puede ver tus archivos. " +
                            "Qué se guarda en el móvil lo decide cada dispositivo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = onTurnOn, modifier = Modifier.fillMaxWidth()) { Text("Sincronizar con Google") }
                } else {
                    Text(describe(status), color = if (status is SyncStatus.Failed || status == SyncStatus.NeedsSignIn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (status == SyncStatus.NeedsSignIn) {
                        FilledTonalButton(onClick = onTurnOn, modifier = Modifier.fillMaxWidth()) { Text("Iniciar sesión con Google") }
                    } else {
                        FilledTonalButton(onClick = onSyncNow, enabled = status != SyncStatus.Syncing, modifier = Modifier.fillMaxWidth()) {
                            Text("Sincronizar ahora")
                        }
                    }
                    TextButton(onClick = onTurnOff, modifier = Modifier.fillMaxWidth()) { Text("Desactivar en este dispositivo") }
                }
                Text(
                    "Esputifai ${appVersion()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

private fun describe(status: SyncStatus): String = when (status) {
    SyncStatus.Off -> ""
    SyncStatus.Syncing -> "Sincronizando…"
    SyncStatus.NeedsSignIn -> "Google pide iniciar sesión otra vez."
    is SyncStatus.Synced -> "Sincronizado a las ${clock(status.atMs)}" + when (status.devices) {
        0 -> ". Todavía no hay otros dispositivos: actívala también en ellos."
        1 -> " con 1 dispositivo más."
        else -> " con ${status.devices} dispositivos más."
    }
    is SyncStatus.Failed -> "No se ha podido sincronizar a las ${clock(status.atMs)}: ${status.message}. Se reintentará sola."
}

/** versionName from the installed package (no BuildConfig needed). */
@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
}

private fun clock(ms: Long) = SimpleDateFormat("HH:mm", Locale("es", "ES")).format(Date(ms))
