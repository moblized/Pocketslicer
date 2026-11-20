@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package org.moblized.pocketslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.moblized.pocketslicer.AppContainer
import org.moblized.pocketslicer.data.Bed
import org.moblized.pocketslicer.data.FilamentPreset
import org.moblized.pocketslicer.data.PrinterPreset
import org.moblized.pocketslicer.data.ProcessPreset
import org.moblized.pocketslicer.util.formatMm
import org.moblized.pocketslicer.util.parseHexColor
import org.moblized.pocketslicer.viewer.Placement
import org.moblized.pocketslicer.viewer.PlateViewer
import java.util.Locale
import kotlin.math.roundToInt

private enum class Picker { PRINTER, PROCESS, FILAMENT, PLATE }

@Composable
fun PlateScreen(
    container: AppContainer,
    modelId: String,
    fromJobId: String?,
    dark: Boolean,
    onBack: () -> Unit,
    onSliced: (String) -> Unit,
) {
    val vm: PlateViewModel = viewModel(key = "plate-$modelId-$fromJobId") { PlateViewModel(container, modelId, fromJobId) }
    val s by vm.state.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf<Picker?>(null) }

    val bed = s.printer?.bed ?: Bed()
    val mesh = s.mesh
    val placement = remember(mesh, s.transform, bed) {
        mesh?.let { Placement.compute(it.min, it.max, s.transform, bed) }
    }
    val fits = placement?.fits(bed) ?: true

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(s.model?.name ?: "Model", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        bottomBar = {
            if (!s.loading && s.loadError == null) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.processPreset?.let { "${it.name.substringBefore(" @")} · ${s.filamentPreset?.type ?: ""}" } ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!fits) {
                                Text("Too big for the plate", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Button(
                            onClick = { vm.slice(onSliced) },
                            enabled = !s.slicing && s.process.isNotBlank() && s.filament.isNotBlank() && !s.optionsLoading,
                            modifier = Modifier.height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            if (s.slicing) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Layers, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Slice")
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        when {
            s.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading model…")
                }
            }
            s.loadError != null -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                ErrorCard(s.loadError ?: "") {
                    TextButton(onClick = vm::load) { Text("Try again") }
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.fillMaxWidth().weight(0.46f)) {
                    PlateViewer(
                        mesh = mesh,
                        bed = bed,
                        placement = placement,
                        dark = dark,
                        resetKey = s.printer?.name,
                        modifier = Modifier.fillMaxSize(),
                    )
                    placement?.let { p ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            modifier = Modifier.padding(10.dp).align(Alignment.TopStart),
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(
                                    "${formatMm(p.sizeX)} × ${formatMm(p.sizeY)} × ${formatMm(p.sizeZ)} mm",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                if (s.copies > 1) {
                                    Text("× ${s.copies} copies", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    if (!fits) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.padding(10.dp).align(Alignment.TopEnd),
                        ) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(Modifier.width(6.dp))
                                Text("Doesn't fit", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                    Text(
                        "Drag to turn · pinch to zoom · double-tap to reset",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp),
                    )
                }
                HorizontalDivider()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(0.54f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    ObjectControls(s, vm)
                    PrintSettings(s, vm) { picker = it }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    when (picker) {
        Picker.PRINTER -> PickerDialog(
            title = "Printer",
            items = s.printers,
            selected = { it.name == s.printer?.name },
            label = { it.name },
            supporting = { p: PrinterPreset -> "${p.bed.width.roundToInt()} × ${p.bed.depth.roundToInt()} × ${p.bed.height.roundToInt()} mm" },
            onPick = { vm.selectPrinter(it); picker = null },
            onDismiss = { picker = null },
        )
        Picker.PROCESS -> PickerDialog(
            title = "Quality",
            items = s.options?.processes.orEmpty(),
            selected = { it.name == s.process },
            label = { it.name },
            supporting = { p: ProcessPreset ->
                String.format(Locale.US, "%.2f mm layers · %d%% infill · %d walls", p.layerHeight, p.infill, p.walls)
            },
            onPick = { vm.selectProcess(it.name); picker = null },
            onDismiss = { picker = null },
        )
        Picker.FILAMENT -> PickerDialog(
            title = "Filament",
            items = s.options?.filaments.orEmpty(),
            selected = { it.name == s.filament },
            label = { it.name },
            supporting = { f: FilamentPreset -> listOf(f.type, f.vendor).filter { it.isNotBlank() }.joinToString(" · ") },
            leading = { f: FilamentPreset -> ColorDot(parseHexColor(f.color)) },
            onPick = { vm.selectFilament(it.name); picker = null },
            onDismiss = { picker = null },
        )
        Picker.PLATE -> PickerDialog(
            title = "Build plate",
            items = s.options?.bedTypes.orEmpty(),
            selected = { it.key == s.bedType },
            label = { it.label },
            onPick = { vm.selectBedType(it.key); picker = null },
            onDismiss = { picker = null },
            searchable = false,
        )
        null -> Unit
    }

    s.sliceError?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
            title = { Text("Couldn't slice") },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun ObjectControls(s: PlateState, vm: PlateViewModel) {
    SectionTitle("Model")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf('x', 'y', 'z').forEach { axis ->
            OutlinedButton(onClick = { vm.rotate(axis) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                Text("${axis.uppercaseChar()} +90°")
            }
        }
        IconButton(onClick = vm::resetTransform) { Icon(Icons.Filled.Restore, contentDescription = "Reset position") }
    }
    val t = s.transform
    if (t.rotateX != 0 || t.rotateY != 0 || t.rotateZ != 0) {
        Text(
            "Rotated X ${t.rotateX}° · Y ${t.rotateY}° · Z ${t.rotateZ}°",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text("Scale", modifier = Modifier.width(64.dp))
        Slider(
            value = t.scale * 100f,
            onValueChange = { vm.setScale((it / 5f).roundToInt() * 5f / 100f) },
            valueRange = 10f..400f,
            modifier = Modifier.weight(1f),
        )
        Text("${(t.scale * 100).roundToInt()}%", modifier = Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(50, 100, 150, 200).forEach { pct ->
            FilterChip(
                selected = (t.scale * 100).roundToInt() == pct,
                onClick = { vm.setScale(pct / 100f) },
                label = { Text("$pct%") },
            )
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("Copies", modifier = Modifier.weight(1f))
        Stepper(value = s.copies, range = 1..50, onChange = vm::setCopies)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("Auto-orient")
            Text("Let OrcaSlicer pick the best side down", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = s.autoOrient, onCheckedChange = vm::setAutoOrient)
    }
}

@Composable
private fun PrintSettings(s: PlateState, vm: PlateViewModel, open: (Picker) -> Unit) {
    SectionTitle("Print settings")
    ChoiceRow(label = "Printer", value = s.printer?.name ?: "—", onClick = { open(Picker.PRINTER) })
    if (s.optionsLoading) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Loading presets…", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    ChoiceRow(
        label = "Quality",
        value = s.process.ifBlank { "—" },
        supporting = s.processPreset?.let { String.format(Locale.US, "%.2f mm layers", it.layerHeight) },
        onClick = { open(Picker.PROCESS) },
    )
    ChoiceRow(
        label = "Filament",
        value = s.filament.ifBlank { "—" },
        leading = { ColorDot(parseHexColor(s.filamentPreset?.color)) },
        onClick = { open(Picker.FILAMENT) },
    )
    ChoiceRow(
        label = "Build plate",
        value = s.options?.bedTypes?.firstOrNull { it.key == s.bedType }?.label ?: s.bedType,
        onClick = { open(Picker.PLATE) },
    )

    SectionTitle("Supports")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Generate supports", modifier = Modifier.weight(1f))
        Switch(checked = s.effectiveSupports, onCheckedChange = vm::setSupports)
    }
    if (s.effectiveSupports) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.supportType == "tree", onClick = { vm.setSupportType("tree") }, label = { Text("Tree") })
            FilterChip(selected = s.supportType == "normal", onClick = { vm.setSupportType("normal") }, label = { Text("Normal") })
        }
    }

    SectionTitle("Strength")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Infill", modifier = Modifier.width(64.dp))
        Slider(
            value = s.effectiveInfill.toFloat(),
            onValueChange = { vm.setInfill((it / 5f).roundToInt() * 5) },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f),
        )
        Text("${s.effectiveInfill}%", modifier = Modifier.width(56.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Walls", modifier = Modifier.weight(1f))
        Stepper(value = s.effectiveWalls, range = 1..10, onChange = vm::setWalls)
    }

    SectionTitle("Brim")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("auto" to "Auto", "none" to "None", "outer" to "Outer only").forEach { (key, label) ->
            FilterChip(selected = s.effectiveBrim == key, onClick = { vm.setBrim(key) }, label = { Text(label) })
        }
    }
    val changed = listOfNotNull(
        s.supports?.let { "supports" }, s.infill?.let { "infill" }, s.walls?.let { "walls" }, s.brim?.let { "brim" },
    )
    if (changed.isNotEmpty()) {
        Text(
            "Changed from the quality preset: ${changed.joinToString(", ")}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
