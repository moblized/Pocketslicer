@file:OptIn(ExperimentalMaterial3Api::class)

package org.moblized.pocketslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.moblized.pocketslicer.AppContainer
import org.moblized.pocketslicer.data.Health
import org.moblized.pocketslicer.data.PocketApi

/** Checks a server address and token; returns the server's health on success. */
suspend fun testServer(url: String, token: String): Health {
    val api = PocketApi(url, token)
    val health = api.health()
    api.printers() // proves the token works
    return health
}

@Composable
fun ServerForm(
    container: AppContainer,
    buttonText: String,
    onSaved: (Health) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val s = container.settings.current()
        if (url.isEmpty()) url = s.serverUrl
        if (token.isEmpty()) token = s.token
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; error = null },
            label = { Text("Server address") },
            placeholder = { Text("https://pocketslicer.moblized.org") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; error = null },
            label = { Text("API token") },
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showToken) "Hide token" else "Show token",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { ErrorCard(it) }
        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val health = testServer(url, token)
                        container.settings.saveServer(PocketApi.normalizeUrl(url).toString(), token)
                        onSaved(health)
                    } catch (e: Exception) {
                        error = e.message ?: "Couldn't connect"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && url.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(buttonText)
            }
        }
    }
}

@Composable
fun SetupScreen(container: AppContainer, onDone: () -> Unit) {
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Spacer(Modifier.height(32.dp))
            Text("PocketSlicer", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(
                "Slice on your server with OrcaSlicer and send prints to your Bambu Lab printer.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Enter your PocketSlicer server's address and the API_TOKEN you set in its TrueNAS app settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            ServerForm(container, buttonText = "Connect", onSaved = { onDone() })
            Spacer(Modifier.height(24.dp))
        }
    }
}
