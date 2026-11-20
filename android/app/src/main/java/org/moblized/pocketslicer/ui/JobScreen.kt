@file:OptIn(ExperimentalMaterial3Api::class)

package org.moblized.pocketslicer.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moblized.pocketslicer.AppContainer
import org.moblized.pocketslicer.data.Health
import org.moblized.pocketslicer.data.Job
import org.moblized.pocketslicer.data.PrintRequest
import org.moblized.pocketslicer.data.PrinterStatus
import org.moblized.pocketslicer.util.formatDuration
import org.moblized.pocketslicer.util.formatGrams
import org.moblized.pocketslicer.util.parseHexColor
import java.util.Locale

/** -1 means the external spool; 0..15 are AMS slots. */
private const val EXTERNAL = -1

class JobViewModel(private val container: AppContainer, private val jobId: String) : ViewModel() {
    data class State(
        val job: Job? = null,
        val error: String? = null,
        val health: Health? = null,
        val status: PrinterStatus? = null,
        val statusLoading: Boolean = false,
        val slot: Int? = null,
        val timelapse: Boolean = false,
        val bedLeveling: Boolean = true,
        val flowCali: Boolean = false,
        val sending: Boolean = false,
        val sendError: String? = null,
        val sent: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { container.api().health() }.getOrNull()?.let { h -> _state.update { it.copy(health = h) } }
        }
        viewModelScope.launch {
            while (true) {
                try {
                    val job = container.api().job(jobId)
                    _state.update { it.copy(job = job, error = null) }
                    if (!job.isRunning) break
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message) }
                }
                delay(800)
            }
        }
    }

    fun loadPrinter() {
        _state.update { it.copy(statusLoading = true, sendError = null, sent = false) }
        viewModelScope.launch {
            try {
                val status = container.api().printerStatus()
                _state.update { s ->
                    val wanted = s.job?.result?.filaments?.firstOrNull()?.type.orEmpty()
                    val trays = status.ams.flatMap { it.trays }.filter { it.loaded }
                    val defaultSlot = s.slot
                        ?: trays.firstOrNull { it.type.equals(wanted, ignoreCase = true) }?.slot
                        ?: trays.firstOrNull()?.slot
                        ?: EXTERNAL
                    s.copy(status = status, statusLoading = false, slot = defaultSlot)
                }
            } catch (e: Exception) {
                _state.update { it.copy(statusLoading = false, status = PrinterStatus(configured = true, error = e.message)) }
            }
        }
    }

    fun setSlot(slot: Int) = _state.update { it.copy(slot = slot) }
    fun setTimelapse(on: Boolean) = _state.update { it.copy(timelapse = on) }
    fun setBedLeveling(on: Boolean) = _state.update { it.copy(bedLeveling = on) }
    fun setFlowCali(on: Boolean) = _state.update { it.copy(flowCali = on) }

    fun send() {
        val s = _state.value
        if (s.sending) return
        _state.update { it.copy(sending = true, sendError = null) }
        viewModelScope.launch {
            try {
                val slot = s.slot
                container.api().print(
                    jobId,
                    PrintRequest(
                        amsSlot = if (slot == null || slot == EXTERNAL) null else slot,
                        timelapse = s.timelapse,
                        bedLeveling = s.bedLeveling,
                        flowCali = s.flowCali,
                    ),
                )
                val job = runCatching { container.api().job(jobId) }.getOrNull()
                _state.update { it.copy(sending = false, sent = true, job = job ?: it.job) }
            } catch (e: Exception) {
                _state.update { it.copy(sending = false, sendError = e.message ?: "Couldn't send the print") }
            }
        }
    }

    suspend fun saveTo(uri: Uri): String? = try {
        val resolver = container.app.contentResolver
        val out = withContext(Dispatchers.IO) { resolver.openOutputStream(uri) }
            ?: throw IllegalStateException("Can't write to that location")
        out.use { container.api().downloadJob(jobId, it) }
        null
    } catch (e: Exception) {
        e.message ?: "Save failed"
    }
}

@Composable
fun JobScreen(
    container: AppContainer,
    jobId: String,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val vm: JobViewModel = viewModel(key = "job-$jobId") { JobViewModel(container, jobId) }
    val s by vm.state.collectAsStateWithLifecycle()
    var showPrint by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val job = s.job

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            scope.launch {
                val error = vm.saveTo(uri)
                snackbar.showSnackbar(error ?: "Saved")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(job?.name ?: "Slice", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                job == null && s.error != null -> ErrorCard(s.error ?: "")
                job == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Loading…")
                }
                job.isRunning -> SlicingCard(job)
                job.isFailed -> {
                    ErrorCard("Slicing failed.\n\n${job.error ?: "No details from OrcaSlicer."}")
                    OutlinedButton(onClick = { onEdit(job.modelId) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Change settings")
                    }
                }
                job.isDone -> {
                    ResultCard(job)
                    val printerReady = s.health?.printerConfigured != false
                    Button(
                        onClick = { showPrint = true; vm.loadPrinter() },
                        enabled = printerReady,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Filled.Print, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (s.health?.printerName.isNullOrBlank()) "Print" else "Print on ${s.health?.printerName}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (!printerReady) {
                        Text(
                            "No printer is set up on the server, so you can save the file instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { saver.launch("${job.name}.gcode.3mf") }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Save, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Save .3mf")
                        }
                        OutlinedButton(onClick = { onEdit(job.modelId) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Change")
                        }
                    }
                    TextButton(onClick = onHome, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Back to home")
                    }
                }
            }
        }
    }

    if (showPrint && job != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showPrint = false }, sheetState = sheetState) {
            PrintSheet(s, vm, onClose = { showPrint = false })
        }
    }
}

@Composable
private fun SlicingCard(job: Job) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Slicing", style = MaterialTheme.typography.titleLarge)
            if (job.status == "queued") {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { (job.progress / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                "${job.message.ifBlank { "Working" }} · ${job.progress.toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResultCard(job: Job) {
    val r = job.result ?: return
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Ready to print", style = MaterialTheme.typography.titleMedium)
            }
            Row {
                Stat("Print time", formatDuration(r.printTimeS), Modifier.weight(1f))
                Stat("Filament", formatGrams(r.weightG), Modifier.weight(1f))
            }
            Row {
                Stat("Length", String.format(Locale.US, "%.2f m", r.lengthM), Modifier.weight(1f))
                Stat("Layers", if (r.layers > 0) "${r.layers}" else "—", Modifier.weight(1f))
            }
            job.request?.let { req ->
                Text(
                    "${req.process.substringBefore(" @")} · ${req.filament.substringBefore(" @")}" +
                        (if (req.copies > 1) " · ${req.copies} copies" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (job.printedAt != null) {
                Text("Sent to the printer", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    r.warnings.forEach { w ->
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(w, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PrintSheet(s: JobViewModel.State, vm: JobViewModel, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Send to printer", style = MaterialTheme.typography.titleLarge)
        val status = s.status
        when {
            s.sent -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Print started. Watch it on the home screen.", style = MaterialTheme.typography.bodyLarge)
                }
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
            s.statusLoading || status == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Checking the printer…")
            }
            !status.connected -> {
                ErrorCard(status.error ?: "Can't reach the printer.") {
                    TextButton(onClick = vm::loadPrinter) { Text("Try again") }
                }
            }
            else -> {
                if (status.isBusy) {
                    ErrorCard("The printer is busy (${status.state.lowercase()} ${status.progress ?: 0}%). Wait for it to finish.") {
                        TextButton(onClick = vm::loadPrinter) { Text("Check again") }
                    }
                }
                SectionTitle("Filament")
                val wanted = s.job?.result?.filaments?.firstOrNull()?.type.orEmpty()
                status.ams.forEach { unit ->
                    unit.trays.forEach { tray ->
                        SlotRow(
                            selected = s.slot == tray.slot,
                            enabled = tray.loaded,
                            color = if (tray.loaded) parseHexColor(tray.color) else null,
                            title = if (tray.loaded) tray.name.ifBlank { tray.type } else "Empty",
                            subtitle = "AMS ${unit.id + 1} · slot ${tray.index + 1}" +
                                (if (tray.loaded && wanted.isNotBlank() && !tray.type.equals(wanted, true)) " · sliced for $wanted" else ""),
                            onClick = { vm.setSlot(tray.slot) },
                        )
                    }
                }
                SlotRow(
                    selected = s.slot == EXTERNAL,
                    enabled = true,
                    color = parseHexColor(status.external.color),
                    title = "External spool" + if (status.external.type.isNotBlank()) " · ${status.external.type}" else "",
                    subtitle = "Spool holder on the back",
                    onClick = { vm.setSlot(EXTERNAL) },
                )
                SectionTitle("Options")
                ToggleRow("Bed leveling", s.bedLeveling, vm::setBedLeveling)
                ToggleRow("Flow calibration", s.flowCali, vm::setFlowCali)
                ToggleRow("Timelapse", s.timelapse, vm::setTimelapse)
                s.sendError?.let { ErrorCard(it) }
                Button(
                    onClick = vm::send,
                    enabled = !s.sending && !status.isBusy,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (s.sending) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Uploading to the printer…")
                    } else {
                        Icon(Icons.Filled.Print, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start print", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SlotRow(
    selected: Boolean,
    enabled: Boolean,
    color: androidx.compose.ui.graphics.Color?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        ColorDot(color, size = 24)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
