package com.lagradost.quicknovel.tts.cloud

import com.lagradost.quicknovel.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
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

    fun download(url: String): Response = execute(Request.Builder().url(url).get().build())

    private fun execute(request: Request): Response = client.newCall(request).execute().use { response ->
        Response(
            response.code,
            response.body?.bytes() ?: ByteArray(0),
            response.header("Retry-After")?.toLongOrNull()?.times(1000),
        )
    }
}
