package org.moblized.pocketslicer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.moblized.pocketslicer.data.AppSettings
import org.moblized.pocketslicer.data.ThemeMode
import org.moblized.pocketslicer.ui.AppNavigation
import org.moblized.pocketslicer.ui.PocketSlicerTheme
import org.moblized.pocketslicer.util.IconSwitcher

class MainActivity : ComponentActivity() {

    private val container: AppContainer get() = (application as PocketSlicerApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { Root(container) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) container.offerImport(uri)
    }
}

@Composable
private fun Root(container: AppContainer) {
    val loaded: AppSettings? by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val systemDark = isSystemInDarkTheme()
    val settings = loaded ?: return
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    LaunchedEffect(dark) {
        // Keep the launcher icon in step with the app's light/dark look.
        runCatching { IconSwitcher.apply(container.app, dark) }
    }
    PocketSlicerTheme(dark) {
        AppNavigation(container = container, startConfigured = settings.isConfigured, dark = dark)
    }
}
