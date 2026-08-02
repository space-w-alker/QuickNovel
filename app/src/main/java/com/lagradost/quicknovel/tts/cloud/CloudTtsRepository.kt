package com.lagradost.quicknovel.tts.cloud

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.BuildConfig
import com.lagradost.quicknovel.CLOUD_TTS_INSTALLATION_ID
import com.lagradost.quicknovel.CLOUD_TTS_OPENROUTER_KEY
import com.lagradost.quicknovel.CLOUD_TTS_REFRESH_CREDENTIAL
import com.lagradost.quicknovel.CLOUD_TTS_SPEECHIFY_KEY
import com.lagradost.quicknovel.DataStore
import com.lagradost.quicknovel.DataStore.getKey
import com.lagradost.quicknovel.DataStore.setKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

class CloudTtsRepository(
    context: Context,
    private val api: CloudTtsApi = CloudTtsApi(),
    private val providerClient: CloudTtsProviderClient = CloudTtsProviderClient(),
) {
    private companion object {
        const val TAG = "CloudTTS"
    }

    private val appContext = context.applicationContext
    private val mapper = DataStore.mapper
    private val authMutex = Mutex()
    @Volatile private var accessToken: String? = null

    private val securePreferences by lazy {
        val key = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "cloud_tts_credentials",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val installationId: String by lazy {
        appContext.getKey<String>(CLOUD_TTS_INSTALLATION_ID) ?: UUID.randomUUID().toString().also {
            appContext.setKey(CLOUD_TTS_INSTALLATION_ID, it)
        }
    }

    suspend fun catalog(): CloudCatalog {
        Log.i(TAG, "Catalog request starting")
        return authenticated { token ->
            parseSuccess<CloudCatalog>(api.get("/v1/tts/catalog", token)).also {
                Log.i(TAG, "Catalog loaded models=${it.models.size}")
            }
        }
    }

    suspend fun resolve(
        selection: CloudTtsSelection,
        source: CloudTtsGenerationSource,
        text: String,
        chapter: CinematicChapterContext? = null,
    ): ResolveResult =
        authenticated { token ->
        Log.i(
            TAG,
            "Chunk resolve starting provider=${selection.provider.wireValue} " +
                "model=${selection.modelId} voice=${selection.voiceId} chars=${text.length}",
        )
        val request = selection.request(text, source, chapter)
        var result: ResolveResult = parseSuccess(
            api.post("/v1/tts/chunks:resolve", mapper.writeValueAsString(request), token)
        )
        while (result.state == "generating") {
            coroutineContext.ensureActive()
            val jobId = result.jobId ?: throw CloudTtsException(
                "generation_failed", "Cloud TTS returned an invalid generation job."
            )
            val baseDelay = result.retryAfterMs ?: 750L
            Log.i(TAG, "Chunk generating job=$jobId retryAfterMs=$baseDelay")
            delay((baseDelay + Random.nextLong(0, (baseDelay / 5).coerceAtLeast(1))).coerceAtMost(10_000))
            result = authenticated { refreshedToken ->
                parseSuccess(api.get("/v1/tts/jobs/$jobId", refreshedToken))
            }
        }
        if (result.state == "upload_required") return@authenticated result
        if (result.state != "ready" || result.audio == null) {
            throw CloudTtsException("generation_failed", "Cloud TTS audio was not available.")
        }
        Log.i(TAG, "Chunk ready cacheKey=${result.cacheKey.take(12)}")
        result
    }

    suspend fun resolveChapter(request: ResolveChapterRequest): CinematicManifest = authenticated { token ->
        parseSuccess(api.post("/v1/tts/chapters:resolve", mapper.writeValueAsString(request), token))
    }

    suspend fun pollChapter(jobId: String): CinematicManifest = authenticated { token ->
        parseSuccess(api.get("/v1/tts/chapter-jobs/$jobId", token))
    }

    suspend fun generateByok(selection: CloudTtsSelection, text: String): ByteArray = withContext(Dispatchers.IO) {
        val key = securePreferences.getString(providerKeyName(selection.provider), null)
            ?: throw CloudTtsException(
                "byok_key_required",
                "Add a ${selection.provider.name} API key in Cloud TTS settings.",
            )
        providerClient.generate(selection, text, key)
    }

    suspend fun upload(request: ResolveChunkRequest, audio: ByteArray): ResolveResult = authenticated { token ->
        parseSuccess(api.upload(
            "/v1/tts/chunks/upload",
            mapper.writeValueAsString(request.copy(generationSource = "byok")),
            audio,
            token,
        ))
    }

    fun hasApiKey(provider: CloudTtsProvider): Boolean =
        !securePreferences.getString(providerKeyName(provider), null).isNullOrBlank()

    fun setApiKey(provider: CloudTtsProvider, value: String) {
        securePreferences.edit().putString(providerKeyName(provider), value.trim()).apply()
    }

    fun clearApiKey(provider: CloudTtsProvider) {
        securePreferences.edit().remove(providerKeyName(provider)).apply()
    }

    suspend fun download(url: String): ByteArray = networkCall {
        Log.i(TAG, "Audio download starting")
        val response = api.download(url)
        if (response.status !in 200..299) {
            throw CloudTtsException(
                "signed_url_expired",
                "Cloud TTS audio link expired before it could be downloaded.",
                retryable = true,
            )
        }
        Log.i(TAG, "Audio download completed bytes=${response.body.size}")
        response.body
    }

    private suspend fun token(forceRefresh: Boolean = false): String = authMutex.withLock {
        if (!forceRefresh) accessToken?.let { return@withLock it }
        val refreshToken = securePreferences.getString(CLOUD_TTS_REFRESH_CREDENTIAL, null)
        Log.i(
            TAG,
            "Authentication starting flow=${if (refreshToken == null) "installation" else "refresh"} " +
                "forceRefresh=$forceRefresh",
        )
        var response = if (refreshToken == null) {
            api.post(
                "/v1/installations",
                mapper.writeValueAsString(InstallationRequest(installationId, BuildConfig.VERSION_NAME)),
            )
        } else {
            api.post(
                "/v1/installations/token",
                mapper.writeValueAsString(TokenRequest(installationId, refreshToken)),
            )
        }
        var credentials = try {
            parseSuccess<InstallationToken>(response)
        } catch (error: CloudTtsException) {
            if (refreshToken == null || error.code != "unauthorized") throw error
            Log.w(TAG, "Refresh credential no longer exists; registering installation again")
            securePreferences.edit().remove(CLOUD_TTS_REFRESH_CREDENTIAL).apply()
            response = api.post(
                "/v1/installations",
                mapper.writeValueAsString(InstallationRequest(installationId, BuildConfig.VERSION_NAME)),
            )
            parseSuccess(response)
        }
        credentials.refreshToken?.let {
            securePreferences.edit().putString(CLOUD_TTS_REFRESH_CREDENTIAL, it).apply()
        }
        Log.i(TAG, "Authentication completed refreshCredentialReceived=${credentials.refreshToken != null}")
        credentials.accessToken.also { accessToken = it }
    }

    private suspend fun <T> authenticated(block: suspend (String) -> T): T = networkCall {
        try {
            block(token())
        } catch (error: CloudTtsException) {
            if (error.code != "unauthorized") throw error
            Log.w(TAG, "Access token rejected; forcing credential refresh")
            accessToken = null
            block(token(forceRefresh = true))
        }
    }

    private suspend fun <T> networkCall(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (error: CloudTtsException) {
            Log.e(TAG, "Cloud TTS failure code=${error.code} retryable=${error.retryable}: ${error.message}", error)
            throw error
        } catch (error: IOException) {
            Log.e(
                TAG,
                "Cloud TTS transport failure ${error.javaClass.simpleName}: ${error.message}",
                error,
            )
            throw CloudTtsException(
                "network_failure",
                "Cloud TTS needs an internet connection. Check your connection and try again.",
                retryable = true,
                cause = error,
            )
        }
    }

    private inline fun <reified T> parseSuccess(response: CloudTtsApi.Response): T {
        if (response.status in 200..299) return mapper.readValue(response.body)
        val apiError = runCatching { mapper.readValue<ApiErrorEnvelope>(response.body).error }.getOrNull()
        Log.e(
            TAG,
            "Cloud TTS API rejected request status=${response.status} " +
                "code=${apiError?.code ?: "unknown"} retryable=${apiError?.retryable}",
        )
        throw CloudTtsException(
            apiError?.code ?: "http_${response.status}",
            apiError?.message ?: "Cloud TTS request failed. Please try again.",
            apiError?.retryable ?: (response.status >= 500),
        )
    }

    private fun providerKeyName(provider: CloudTtsProvider): String = when (provider) {
        CloudTtsProvider.OpenRouter -> CLOUD_TTS_OPENROUTER_KEY
        CloudTtsProvider.Speechify -> CLOUD_TTS_SPEECHIFY_KEY
    }
}
