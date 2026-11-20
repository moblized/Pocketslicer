@file:OptIn(ExperimentalMaterial3Api::class)

package org.moblized.pocketslicer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.moblized.pocketslicer.AppContainer
import org.moblized.pocketslicer.BuildConfig
import org.moblized.pocketslicer.data.AppSettings
import org.moblized.pocketslicer.data.Health
import org.moblized.pocketslicer.data.ThemeMode

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var health by remember { mutableStateOf<Health?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(settings.serverUrl, settings.token) {
        health = runCatching { container.api().health() }.getOrNull()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            SectionTitle("Server")
            ServerForm(container, buttonText = "Save and test") { h ->
                health = h
                scope.launch { snackbar.showSnackbar("Connected to PocketSlicer ${h.version}") }
            }

            SectionTitle("Appearance")
            ThemeMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = settings.theme == mode,
                            role = Role.RadioButton,
                            onClick = { scope.launch { container.settings.setTheme(mode) } },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.theme == mode, onClick = null)
                    Text(
                        when (mode) {
                            ThemeMode.SYSTEM -> "Match system"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                        },
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            Text(
                "The app icon switches to its dark version in dark mode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            SectionTitle("About")
            Text("App ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            val h = health
            if (h != null) {
                Text("Server ${h.version} · OrcaSlicer ${h.orcaVersion}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (h.printerConfigured) "Printer: ${h.printerName}" else "No printer set up on the server",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                "Slicing by OrcaSlicer (AGPL-3.0).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
            )
        }
    }
}
