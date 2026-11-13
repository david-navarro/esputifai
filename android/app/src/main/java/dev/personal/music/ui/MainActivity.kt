package dev.personal.music.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import dev.personal.music.MusicApp

class MainActivity : ComponentActivity() {

    private val viewModel: MusicViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Android 13+: the media notification needs this permission to show.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MusicTheme { MusicScreen(viewModel) }
        }
        // Not on recreation (rotation…): the link was already handled.
        if (savedInstanceState == null) handleLink(intent)
    }

    // singleTop: a link tapped while the app is open arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** A shared-playlist link (see PlaylistShare) opens the "¿Importar?" dialog. */
    private fun handleLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.dataString?.let(viewModel::receiveShared)
    }

    // Pending downloads can only (re)start while the app is visible (Android 12+ rule).
    override fun onStart() {
        super.onStart()
        (application as MusicApp).offline.inForeground = true
        (application as MusicApp).sync.setForeground(true)
    }

    override fun onStop() {
        (application as MusicApp).offline.inForeground = false
        (application as MusicApp).sync.setForeground(false)
        super.onStop()
    }
}
