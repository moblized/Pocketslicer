package org.moblized.pocketslicer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moblized.pocketslicer.AppContainer
import org.moblized.pocketslicer.data.FilamentPreset
import org.moblized.pocketslicer.data.ModelInfo
import org.moblized.pocketslicer.data.PrinterOptions
import org.moblized.pocketslicer.data.PrinterPreset
import org.moblized.pocketslicer.data.ProcessPreset
import org.moblized.pocketslicer.data.SliceRequest
import org.moblized.pocketslicer.viewer.ModelTransform
import org.moblized.pocketslicer.viewer.StlMesh

data class PlateState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val model: ModelInfo? = null,
    val mesh: StlMesh? = null,
    val printers: List<PrinterPreset> = emptyList(),
    val printer: PrinterPreset? = null,
    val options: PrinterOptions? = null,
    val optionsLoading: Boolean = false,
    val process: String = "",
    val filament: String = "",
    val bedType: String = "Textured PEI Plate",
    val transform: ModelTransform = ModelTransform(),
    val copies: Int = 1,
    val autoOrient: Boolean = false,
    // null = use the quality preset's own value
    val supports: Boolean? = null,
    val supportType: String = "tree",
    val infill: Int? = null,
    val brim: String? = null,
    val walls: Int? = null,
    val slicing: Boolean = false,
    val sliceError: String? = null,
) {
    val processPreset: ProcessPreset? get() = options?.processes?.firstOrNull { it.name == process }
    val filamentPreset: FilamentPreset? get() = options?.filaments?.firstOrNull { it.name == filament }
    val effectiveSupports: Boolean get() = supports ?: (processPreset?.supports ?: false)
    val effectiveInfill: Int get() = infill ?: (processPreset?.infill ?: 15)
    val effectiveBrim: String get() = brim ?: (processPreset?.brim ?: "auto")
    val effectiveWalls: Int get() = walls ?: (processPreset?.walls ?: 2)
}

class PlateViewModel(
    private val container: AppContainer,
    private val modelId: String,
    private val fromJobId: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(PlateState())
    val state: StateFlow<PlateState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            try {
                val api = container.api()
                val saved = container.settings.current()
                val model = api.model(modelId)
                val bytes = api.preview(modelId)
                val mesh = withContext(Dispatchers.Default) { StlMesh.parse(bytes) }
                val printers = api.printers()
                val previous = fromJobId?.let { runCatching { api.job(it).request }.getOrNull() }

                val printerName = listOfNotNull(previous?.printer, saved.printer.ifBlank { null }, printers.default)
                    .firstOrNull { name -> printers.printers.any { it.name == name } }
                    ?: printers.printers.firstOrNull()?.name
                    ?: throw IllegalStateException("The server has no Bambu Lab printer presets.")
                val printer = printers.printers.first { it.name == printerName }
                val options = api.options(printerName)

                _state.update {
                    var s = it.copy(
                        loading = false,
                        model = model,
                        mesh = mesh,
                        printers = printers.printers,
                        printer = printer,
                        options = options,
                        process = pick(options.processes.map { p -> p.name }, previous?.process, saved.process, options.defaultProcess),
                        filament = pick(options.filaments.map { f -> f.name }, previous?.filament, saved.filament, options.defaultFilament),
                        bedType = pick(options.bedTypes.map { b -> b.key }, previous?.bedType, saved.bedType, options.defaultBedType),
                    )
                    if (previous != null) {
                        s = s.copy(
                            transform = ModelTransform(
                                previous.rotateX.toInt(), previous.rotateY.toInt(), previous.rotateZ.toInt(), previous.scale,
                            ),
                            copies = previous.copies,
                            autoOrient = previous.autoOrient,
                            supports = previous.supports,
                            supportType = previous.supportType ?: "tree",
                            infill = previous.infill,
                            brim = previous.brim,
                            walls = previous.wallLoops,
                        )
                    }
                    s
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = e.message ?: "Couldn't load the model") }
            }
        }
    }

    private fun pick(available: List<String>, vararg wanted: String?): String =
        wanted.firstOrNull { !it.isNullOrBlank() && it in available } ?: available.firstOrNull().orEmpty()

    fun selectPrinter(printer: PrinterPreset) {
        if (printer.name == _state.value.printer?.name) return
        _state.update { it.copy(printer = printer, optionsLoading = true) }
        viewModelScope.launch {
            try {
                val options = container.api().options(printer.name)
                val saved = container.settings.current()
                _state.update {
                    it.copy(
                        options = options,
                        optionsLoading = false,
                        process = pick(options.processes.map { p -> p.name }, saved.process, options.defaultProcess),
                        filament = pick(options.filaments.map { f -> f.name }, it.filament, saved.filament, options.defaultFilament),
                        bedType = pick(options.bedTypes.map { b -> b.key }, it.bedType, options.defaultBedType),
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(optionsLoading = false, sliceError = e.message) }
            }
        }
    }

    fun selectProcess(name: String) = _state.update {
        // A new quality preset brings its own defaults; drop overrides that match them.
        it.copy(process = name)
    }

    fun selectFilament(name: String) = _state.update { it.copy(filament = name) }
    fun selectBedType(key: String) = _state.update { it.copy(bedType = key) }

    fun rotate(axis: Char) = _state.update {
        val t = it.transform
        val next = when (axis) {
            'x' -> t.copy(rotateX = (t.rotateX + 90) % 360)
            'y' -> t.copy(rotateY = (t.rotateY + 90) % 360)
            else -> t.copy(rotateZ = (t.rotateZ + 90) % 360)
        }
        it.copy(transform = next)
    }

    fun setScale(scale: Float) = _state.update { it.copy(transform = it.transform.copy(scale = scale.coerceIn(0.05f, 10f))) }
    fun resetTransform() = _state.update { it.copy(transform = ModelTransform(), autoOrient = false) }
    fun setCopies(n: Int) = _state.update { it.copy(copies = n.coerceIn(1, 50)) }
    fun setAutoOrient(on: Boolean) = _state.update { it.copy(autoOrient = on) }

    fun setSupports(on: Boolean) = _state.update {
        it.copy(supports = if (on == (it.processPreset?.supports ?: false)) null else on)
    }

    fun setSupportType(type: String) = _state.update { it.copy(supportType = type) }

    fun setInfill(value: Int) = _state.update {
        it.copy(infill = if (value == it.processPreset?.infill) null else value)
    }

    fun setBrim(value: String) = _state.update {
        it.copy(brim = if (value == it.processPreset?.brim) null else value)
    }

    fun setWalls(value: Int) = _state.update {
        it.copy(walls = if (value == it.processPreset?.walls) null else value)
    }

    fun dismissError() = _state.update { it.copy(sliceError = null) }

    fun slice(onStarted: (String) -> Unit) {
        val s = _state.value
        val printer = s.printer ?: return
        if (s.slicing) return
        _state.update { it.copy(slicing = true, sliceError = null) }
        viewModelScope.launch {
            try {
                val supportsOn = s.effectiveSupports
                val req = SliceRequest(
                    modelId = modelId,
                    printer = printer.name,
                    process = s.process,
                    filament = s.filament,
                    rotateX = s.transform.rotateX.toFloat(),
                    rotateY = s.transform.rotateY.toFloat(),
                    rotateZ = s.transform.rotateZ.toFloat(),
                    scale = s.transform.scale,
                    copies = s.copies,
                    autoOrient = s.autoOrient,
                    bedType = s.bedType,
                    supports = s.supports,
                    supportType = if (supportsOn) s.supportType else null,
                    infill = s.infill,
                    brim = s.brim,
                    wallLoops = s.walls,
                )
                container.settings.savePresets(printer.name, s.process, s.filament, s.bedType)
                val job = container.api().slice(req)
                _state.update { it.copy(slicing = false) }
                onStarted(job.id)
            } catch (e: Exception) {
                _state.update { it.copy(slicing = false, sliceError = e.message ?: "Couldn't start slicing") }
            }
        }
    }
}
