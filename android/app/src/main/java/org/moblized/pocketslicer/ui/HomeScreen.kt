@file:OptIn(ExperimentalMaterial3Api::class)

package org.moblized.pocketslicer.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.moblized.pocketslicer.AppContainer
import org.moblized.pocketslicer.data.Job
import org.moblized.pocketslicer.data.PrinterStatus
import org.moblized.pocketslicer.util.describeUri
import org.moblized.pocketslicer.util.formatDuration
import org.moblized.pocketslicer.util.formatGrams
import org.moblized.pocketslicer.util.formatMinutes
import org.moblized.pocketslicer.util.isSupportedModel
import org.moblized.pocketslicer.util.parseHexColor
import org.moblized.pocketslicer.util.timeAgo

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    data class State(
        val status: PrinterStatus? = null,
        val jobs: List<Job> = emptyList(),
        val loadError: String? = null,
        val uploading: Boolean = false,
        val uploadName: String = "",
        val uploadProgress: Float = 0f,
        val uploadError: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun refreshJobs() {
        viewModelScope.launch {
            try {
                val jobs = container.api().jobs().jobs
                _state.update { it.copy(jobs = jobs, loadError = null) }
            } catch (e: Exception) {
                _state.update { it.copy(loadError = e.message) }
            }
        }
    }

    suspend fun refreshStatus() {
        try {
            val status = container.api().printerStatus()
            _state.update { it.copy(status = status) }
        } catch (e: Exception) {
            _state.update { it.copy(status = PrinterStatus(configured = true, connected = false, error = e.message)) }
        }
    }

    fun upload(uri: Uri, onDone: (String) -> Unit) {
        if (_state.value.uploading) return
        val resolver = container.app.contentResolver
        val file = describeUri(resolver, uri)
        if (!isSupportedModel(file.name)) {
            _state.update { it.copy(uploadError = "PocketSlicer opens STL, 3MF and OBJ files. \"${file.name}\" isn't one of those.") }
            return
        }
        _state.update { it.copy(uploading = true, uploadName = file.name, uploadProgress = 0f, uploadError = null) }
        viewModelScope.launch {
            try {
                val model = container.api().upload(resolver, uri, file.name, file.size) { p ->
                    _state.update { it.copy(uploadProgress = p) }
                }
                _state.update { it.copy(uploading = false) }
                onDone(model.id)
            } catch (e: Exception) {
                _state.update { it.copy(uploading = false, uploadError = e.message ?: "Upload failed") }
            }
        }
    }

    fun dismissUploadError() = _state.update { it.copy(uploadError = null) }
}

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenModel: (String) -> Unit,
    onOpenJob: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val vm: HomeViewModel = viewModel { HomeViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val pending by container.pendingImport.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.upload(uri, onOpenModel)
    }

    LaunchedEffect(pending) {
        if (pending != null) {
            container.consumeImport()?.let { vm.upload(it, onOpenModel) }
        }
    }
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.refreshJobs()
            while (true) {
                vm.refreshStatus()
                delay(10_000)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PocketSlicer", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { vm.refreshJobs() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PrinterCard(state.status) }
            item {
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Open a model", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "STL, 3MF or OBJ. You can also share a file to PocketSlicer from another app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )
            }
            item { SectionTitle("Recent slices") }
            state.loadError?.let { msg -> item { ErrorCard(msg) } }
            if (state.jobs.isEmpty() && state.loadError == null) {
                item {
                    Text("Nothing sliced yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(state.jobs, key = { it.id }) { job -> JobRow(job) { onOpenJob(job.id) } }
        }
    }

    if (state.uploading) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Uploading") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.uploadName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (state.uploadProgress >= 0.999f) {
                        Text("Preparing the model on the server…", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { state.uploadProgress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
        )
    }
    state.uploadError?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissUploadError,
            confirmButton = { TextButton(onClick = vm::dismissUploadError) { Text("OK") } },
            title = { Text("Couldn't open the model") },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun PrinterCard(status: PrinterStatus?) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Print, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    status?.name?.ifBlank { null } ?: "Printer",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                when {
                    status == null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    status.connected -> StateChip(status.state)
                }
            }
            when {
                status == null -> Text("Checking…", style = MaterialTheme.typography.bodySmall)
                !status.configured -> Text(
                    "No printer set up on the server yet. Add PRINTER_IP, PRINTER_SERIAL and PRINTER_ACCESS_CODE to the server to print from here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                !status.connected -> Text(
                    status.error ?: "Can't reach the printer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    if (status.isBusy) {
                        Text(status.task.ifBlank { "Printing" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(
                            progress = { (status.progress ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${status.progress ?: 0}% · ${formatMinutes(status.remainingMin)} left" +
                                (if (status.layer != null && status.totalLayers != null) " · layer ${status.layer}/${status.totalLayers}" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val trays = status.ams.flatMap { it.trays }
                    if (trays.isNotEmpty() || status.external.loaded) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("AMS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            trays.forEach { tray ->
                                ColorDot(if (tray.loaded) parseHexColor(tray.color) else null, size = 22)
                            }
                            if (status.external.loaded) {
                                Text("Ext", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                ColorDot(parseHexColor(status.external.color), size = 22)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StateChip(state: String) {
    val label = when (state) {
        "IDLE" -> "Ready"
        "RUNNING" -> "Printing"
        "PREPARE" -> "Preparing"
        "PAUSE" -> "Paused"
        "FINISH" -> "Finished"
        "FAILED" -> "Failed"
        else -> state.lowercase().replaceFirstChar { it.uppercase() }
    }
    val color = when (state) {
        "FAILED" -> MaterialTheme.colorScheme.error
        "RUNNING", "PREPARE" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(label, style = MaterialTheme.typography.labelLarge, color = color)
}

@Composable
private fun JobRow(job: Job, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = when {
                job.isDone -> Icons.Filled.CheckCircle
                job.isFailed -> Icons.Filled.ErrorOutline
                else -> Icons.Filled.Schedule
            }
            Icon(
                icon,
                contentDescription = job.status,
                tint = if (job.isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(job.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = when {
                    job.isDone && job.result != null ->
                        "${formatDuration(job.result.printTimeS)} · ${formatGrams(job.result.weightG)}" +
                            (if (job.printedAt != null) " · sent to printer" else "")
                    job.isFailed -> "Failed"
                    else -> "Slicing ${job.progress.toInt()}%"
                }
                Text(
                    "$detail · ${timeAgo(job.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
