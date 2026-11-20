package org.moblized.pocketslicer.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Health(
    val ok: Boolean = false,
    val version: String = "",
    @SerialName("orca_version") val orcaVersion: String = "",
    @SerialName("printer_configured") val printerConfigured: Boolean = false,
    @SerialName("printer_name") val printerName: String = "",
    @SerialName("default_printer") val defaultPrinter: String = "",
    val error: String? = null,
)

@Serializable
data class Bed(
    @SerialName("min_x") val minX: Float = 0f,
    @SerialName("min_y") val minY: Float = 0f,
    @SerialName("max_x") val maxX: Float = 256f,
    @SerialName("max_y") val maxY: Float = 256f,
    val height: Float = 250f,
) {
    val width: Float get() = maxX - minX
    val depth: Float get() = maxY - minY
    val centerX: Float get() = (minX + maxX) / 2f
    val centerY: Float get() = (minY + maxY) / 2f
}

@Serializable
data class PrinterPreset(
    val name: String,
    val model: String = "",
    val nozzle: Float = 0.4f,
    val bed: Bed = Bed(),
    @SerialName("default_process") val defaultProcess: String = "",
    @SerialName("default_filament") val defaultFilament: String = "",
)

@Serializable
data class PrintersResponse(
    val printers: List<PrinterPreset> = emptyList(),
    val default: String = "",
)

@Serializable
data class ProcessPreset(
    val name: String,
    @SerialName("layer_height") val layerHeight: Float = 0.2f,
    val infill: Int = 15,
    val supports: Boolean = false,
    val walls: Int = 2,
    val brim: String = "auto",
)

@Serializable
data class FilamentPreset(
    val name: String,
    val type: String = "",
    val vendor: String = "",
    val color: String = "",
)

@Serializable
data class BedType(val key: String, val label: String)

@Serializable
data class PrinterOptions(
    val printer: PrinterPreset,
    val processes: List<ProcessPreset> = emptyList(),
    val filaments: List<FilamentPreset> = emptyList(),
    @SerialName("bed_types") val bedTypes: List<BedType> = emptyList(),
    @SerialName("default_process") val defaultProcess: String = "",
    @SerialName("default_filament") val defaultFilament: String = "",
    @SerialName("default_bed_type") val defaultBedType: String = "Textured PEI Plate",
)

@Serializable
data class Size3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f)

@Serializable
data class ModelInfo(
    val id: String,
    val name: String = "model",
    @SerialName("file_name") val fileName: String = "",
    val size: Size3 = Size3(),
    val faces: Int = 0,
    @SerialName("preview_faces") val previewFaces: Int = 0,
    val watertight: Boolean = true,
)

@Serializable
data class SliceRequest(
    @SerialName("model_id") val modelId: String,
    val printer: String,
    val process: String,
    val filament: String,
    @SerialName("rotate_x") val rotateX: Float = 0f,
    @SerialName("rotate_y") val rotateY: Float = 0f,
    @SerialName("rotate_z") val rotateZ: Float = 0f,
    val scale: Float = 1f,
    val copies: Int = 1,
    @SerialName("auto_orient") val autoOrient: Boolean = false,
    @SerialName("bed_type") val bedType: String = "Textured PEI Plate",
    val supports: Boolean? = null,
    @SerialName("support_type") val supportType: String? = null,
    val infill: Int? = null,
    val brim: String? = null,
    @SerialName("wall_loops") val wallLoops: Int? = null,
)

@Serializable
data class FilamentUse(
    val type: String = "",
    val color: String = "",
    @SerialName("used_m") val usedM: Double = 0.0,
    @SerialName("used_g") val usedG: Double = 0.0,
)

@Serializable
data class SliceResult(
    @SerialName("print_time_s") val printTimeS: Long = 0,
    @SerialName("weight_g") val weightG: Double = 0.0,
    @SerialName("length_m") val lengthM: Double = 0.0,
    val filaments: List<FilamentUse> = emptyList(),
    val warnings: List<String> = emptyList(),
    val layers: Int = 0,
    @SerialName("output_bytes") val outputBytes: Long = 0,
)

@Serializable
data class Job(
    val id: String,
    @SerialName("model_id") val modelId: String = "",
    val name: String = "",
    val status: String = "",
    val progress: Float = 0f,
    val message: String = "",
    val error: String? = null,
    @SerialName("created_at") val createdAt: Double = 0.0,
    val result: SliceResult? = null,
    @SerialName("printed_at") val printedAt: Double? = null,
    val request: SliceRequest? = null,
) {
    val isRunning: Boolean get() = status == "queued" || status == "slicing"
    val isDone: Boolean get() = status == "done"
    val isFailed: Boolean get() = status == "failed"
}

@Serializable
data class JobsResponse(val jobs: List<Job> = emptyList())

@Serializable
data class Tray(
    val slot: Int,
    val index: Int = 0,
    val loaded: Boolean = false,
    val type: String = "",
    val name: String = "",
    val color: String = "",
    val remain: Int = -1,
)

@Serializable
data class AmsUnit(
    val id: Int = 0,
    val humidity: String? = null,
    val trays: List<Tray> = emptyList(),
)

@Serializable
data class ExternalSpool(
    val loaded: Boolean = false,
    val type: String = "",
    val color: String = "",
)

@Serializable
data class PrinterStatus(
    val configured: Boolean = false,
    val connected: Boolean = false,
    val name: String = "",
    val error: String? = null,
    val state: String = "",
    val progress: Int? = null,
    @SerialName("remaining_min") val remainingMin: Int? = null,
    val layer: Int? = null,
    @SerialName("total_layers") val totalLayers: Int? = null,
    val task: String = "",
    @SerialName("nozzle_temp") val nozzleTemp: Float? = null,
    @SerialName("bed_temp") val bedTemp: Float? = null,
    val ams: List<AmsUnit> = emptyList(),
    @SerialName("tray_now") val trayNow: Int = -1,
    val external: ExternalSpool = ExternalSpool(),
) {
    val isBusy: Boolean get() = state in setOf("RUNNING", "PREPARE", "PAUSE", "SLICING")
}

@Serializable
data class PrintRequest(
    @SerialName("ams_slot") val amsSlot: Int? = null,
    val timelapse: Boolean = false,
    @SerialName("bed_leveling") val bedLeveling: Boolean = true,
    @SerialName("flow_cali") val flowCali: Boolean = false,
)

@Serializable
data class PrintResponse(
    val ok: Boolean = false,
    val file: String = "",
    val acknowledged: Boolean = false,
)
