package org.moblized.pocketslicer

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.moblized.pocketslicer.data.ApiException
import org.moblized.pocketslicer.data.PocketApi
import org.moblized.pocketslicer.data.SettingsStore

class PocketSlicerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Shared app-wide objects. */
class AppContainer(val app: Application) {
    val settings = SettingsStore(app)

    private var cachedKey: Pair<String, String>? = null
    private var cachedApi: PocketApi? = null

    /** Icon style to apply once the app is in the background (null = no change pending). */
    @Volatile var pendingDarkIcon: Boolean? = null

    /** A file someone opened or shared into the app, waiting to be uploaded. */
    private val _pendingImport = MutableStateFlow<Uri?>(null)
    val pendingImport: StateFlow<Uri?> = _pendingImport.asStateFlow()

    fun offerImport(uri: Uri) {
        _pendingImport.value = uri
    }

    fun consumeImport(): Uri? {
        val uri = _pendingImport.value
        _pendingImport.value = null
        return uri
    }

    suspend fun api(): PocketApi {
        val s = settings.current()
        if (!s.isConfigured) throw ApiException("Set up your server first.")
        val key = s.serverUrl to s.token
        synchronized(this) {
            if (key != cachedKey || cachedApi == null) {
                cachedApi = PocketApi(s.serverUrl, s.token)
                cachedKey = key
            }
            return cachedApi!!
        }
    }
}
