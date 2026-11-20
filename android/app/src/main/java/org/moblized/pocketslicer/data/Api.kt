package org.moblized.pocketslicer.data

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class ApiException(message: String, val code: Int = 0) : Exception(message)

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
}

val SharedHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(300, TimeUnit.SECONDS)
    .build()

/** Client for the PocketSlicer server running on the TrueNAS box. */
class PocketApi(baseUrl: String, private val token: String, private val http: OkHttpClient = SharedHttp) {

    private val base: HttpUrl = normalizeUrl(baseUrl)
        ?: throw ApiException("That server address doesn't look right.")

    private fun url(vararg segments: String): HttpUrl {
        val b = base.newBuilder()
        segments.forEach { b.addPathSegment(it) }
        return b.build()
    }

    private fun request(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $token")

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiException("Can't reach the server: ${e.message ?: e.javaClass.simpleName}")
        }
        if (!response.isSuccessful) {
            val message = errorMessage(response)
            response.close()
            throw ApiException(message, response.code)
        }
        response
    }

    private suspend fun <T> get(serializer: KSerializer<T>, vararg segments: String): T =
        decode(serializer, request(url(*segments)).get().build())

    private suspend fun <T> decode(serializer: KSerializer<T>, request: Request): T {
        val response = execute(request)
        return withContext(Dispatchers.IO) {
            response.use { ApiJson.decodeFromString(serializer, it.body?.string().orEmpty()) }
        }
    }

    private fun <T> jsonBody(serializer: KSerializer<T>, value: T): RequestBody =
        ApiJson.encodeToString(serializer, value).toRequestBody(JSON)

    suspend fun health(): Health = get(Health.serializer(), "api", "health")

    suspend fun printers(): PrintersResponse = get(PrintersResponse.serializer(), "api", "printers")

    suspend fun options(printer: String): PrinterOptions =
        get(PrinterOptions.serializer(), "api", "printers", printer, "options")

    suspend fun model(id: String): ModelInfo = get(ModelInfo.serializer(), "api", "models", id)

    suspend fun preview(id: String): ByteArray {
        val response = execute(request(url("api", "models", id, "preview.stl")).get().build())
        return withContext(Dispatchers.IO) { response.use { it.body?.bytes() ?: ByteArray(0) } }
    }

    suspend fun upload(
        resolver: ContentResolver,
        uri: Uri,
        fileName: String,
        size: Long,
        onProgress: (Float) -> Unit,
    ): ModelInfo {
        val fileBody = object : RequestBody() {
            override fun contentType(): MediaType = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = size
            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(uri) ?: throw IOException("Can't open the file")
                input.source().use { source ->
                    var sent = 0L
                    val buffer = okio.Buffer()
                    while (true) {
                        val read = source.read(buffer, 64 * 1024)
                        if (read == -1L) break
                        sink.write(buffer, read)
                        sent += read
                        if (size > 0) onProgress((sent.toFloat() / size).coerceIn(0f, 1f))
                    }
                }
            }
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, fileBody)
            .build()
        return decode(ModelInfo.serializer(), request(url("api", "models")).post(body).build())
    }

    suspend fun slice(req: SliceRequest): Job =
        decode(Job.serializer(), request(url("api", "jobs")).post(jsonBody(SliceRequest.serializer(), req)).build())

    suspend fun job(id: String): Job = get(Job.serializer(), "api", "jobs", id)

    suspend fun jobs(): JobsResponse = get(JobsResponse.serializer(), "api", "jobs")

    suspend fun printerStatus(): PrinterStatus = get(PrinterStatus.serializer(), "api", "printer", "status")

    suspend fun print(jobId: String, req: PrintRequest): PrintResponse = decode(
        PrintResponse.serializer(),
        request(url("api", "jobs", jobId, "print")).post(jsonBody(PrintRequest.serializer(), req)).build(),
    )

    suspend fun downloadJob(jobId: String, out: OutputStream) {
        val response = execute(request(url("api", "jobs", jobId, "file")).get().build())
        withContext(Dispatchers.IO) {
            response.use { r ->
                val body = r.body ?: throw ApiException("Empty download")
                body.byteStream().use { it.copyTo(out) }
            }
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun normalizeUrl(raw: String): HttpUrl? {
            var text = raw.trim()
            if (text.isEmpty()) return null
            if (!text.startsWith("http://") && !text.startsWith("https://")) text = "https://$text"
            val parsed = text.toHttpUrlOrNull() ?: return null
            // Drop a trailing slash so path segments append cleanly.
            val segments = parsed.pathSegments.filter { it.isNotEmpty() }
            val b = parsed.newBuilder().encodedPath("/")
            segments.forEach { b.addPathSegment(it) }
            return b.build()
        }

        private fun errorMessage(response: Response): String {
            val text = try {
                response.peekBody(64 * 1024).string()
            } catch (e: IOException) {
                ""
            }
            val detail = try {
                when (val d = ApiJson.parseToJsonElement(text).jsonObject["detail"]) {
                    is JsonPrimitive -> d.contentOrNull
                    is JsonArray -> d.joinToString("; ") { item ->
                        (item as? JsonObject)?.get("msg")?.jsonPrimitive?.contentOrNull ?: item.toString()
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
            return when {
                !detail.isNullOrBlank() -> detail
                response.code == 401 -> "The server rejected the API token."
                response.code == 404 -> "Not found on the server (is the address right?)."
                else -> "Server error ${response.code}"
            }
        }
    }
}
