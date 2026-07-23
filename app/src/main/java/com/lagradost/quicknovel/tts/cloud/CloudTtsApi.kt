package com.lagradost.quicknovel.tts.cloud

import android.os.SystemClock
import android.util.Log
import com.lagradost.quicknovel.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Dedicated client using the platform's normal TLS validation. */
class CloudTtsApi(
    baseUrl: String = BuildConfig.CLOUD_TTS_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    private companion object {
        const val TAG = "CloudTTS"
    }

    private val root = baseUrl.trimEnd('/')
    private val json = "application/json; charset=utf-8".toMediaType()

    data class Response(val status: Int, val body: ByteArray, val retryAfterMs: Long?)

    fun post(path: String, body: String, token: String? = null): Response = execute(
        Request.Builder().url("$root$path").post(body.toRequestBody(json)).apply {
            token?.let { header("Authorization", "Bearer $it") }
        }.build()
    )

    fun get(path: String, token: String? = null): Response = execute(
        Request.Builder().url("$root$path").get().apply {
            token?.let { header("Authorization", "Bearer $it") }
        }.build()
    )

    fun upload(path: String, metadata: String, audio: ByteArray, token: String): Response {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata", metadata)
            .addFormDataPart("audio", "speech.mp3", audio.toRequestBody("audio/mpeg".toMediaType()))
            .build()
        return execute(
            Request.Builder().url("$root$path").post(body)
                .header("Authorization", "Bearer $token")
                .build()
        )
    }

    fun download(url: String): Response = execute(Request.Builder().url(url).get().build())

    private fun execute(request: Request): Response {
        val safeUrl = request.url.newBuilder().query(null).build()
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "HTTP ${request.method} $safeUrl starting")
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.bytes() ?: ByteArray(0)
                Log.i(
                    TAG,
                    "HTTP ${request.method} $safeUrl completed status=${response.code} " +
                        "bytes=${body.size} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                )
                Response(
                    response.code,
                    body,
                    response.header("Retry-After")?.toLongOrNull()?.times(1000),
                )
            }
        } catch (error: Exception) {
            Log.e(
                TAG,
                "HTTP ${request.method} $safeUrl failed after " +
                    "${SystemClock.elapsedRealtime() - startedAt}ms: " +
                    "${error.javaClass.simpleName}: ${error.message}",
                error,
            )
            throw error
        }
    }
}
