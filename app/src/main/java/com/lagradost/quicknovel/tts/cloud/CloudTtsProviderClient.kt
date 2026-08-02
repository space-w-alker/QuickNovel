package com.lagradost.quicknovel.tts.cloud

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.DataStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class CloudTtsProviderClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build(),
) {
    private companion object {
        const val MAX_AUDIO_BYTES = 50 * 1024 * 1024
    }
    private val mapper = DataStore.mapper
    private val json = "application/json; charset=utf-8".toMediaType()

    fun generate(selection: CloudTtsSelection, text: String, apiKey: String): ByteArray {
        val request = when (selection.provider) {
            CloudTtsProvider.OpenRouter -> Request.Builder()
                .url("https://openrouter.ai/api/v1/audio/speech")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "audio/mpeg")
                .header("X-Title", "QuickNovel")
                .post(mapper.writeValueAsBytes(mapOf(
                    "model" to selection.modelId,
                    "voice" to selection.voiceId,
                    "input" to text,
                    "response_format" to "mp3",
                )).toRequestBody(json))
                .build()
            CloudTtsProvider.Speechify -> Request.Builder()
                .url("https://api.sws.speechify.com/v1/audio/speech")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .post(mapper.writeValueAsBytes(mapOf(
                    "model" to selection.modelId,
                    "voice_id" to selection.voiceId,
                    "input" to text,
                    "audio_format" to "mp3",
                )).toRequestBody(json))
                .build()
        }
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful) {
                    throw CloudTtsException(
                        "provider_${response.code}",
                        "${selection.provider.name} rejected speech generation (${response.code}).",
                        response.code >= 500 || response.code == 429,
                    )
                }
                val audio = when (selection.provider) {
                    CloudTtsProvider.OpenRouter -> {
                        if (response.header("Content-Type")?.substringBefore(';') !in setOf("audio/mpeg", "audio/mp3")) {
                            throw CloudTtsException("invalid_provider_audio", "OpenRouter returned an unsupported audio format.")
                        }
                        body
                    }
                    CloudTtsProvider.Speechify -> {
                        val payload: SpeechifySpeechResponse = mapper.readValue(body)
                        if (payload.audioFormat != "mp3" || payload.audioData.isBlank()) {
                            throw CloudTtsException("invalid_provider_audio", "Speechify returned an invalid MP3 response.")
                        }
                        runCatching { android.util.Base64.decode(payload.audioData, android.util.Base64.DEFAULT) }
                            .getOrElse {
                                throw CloudTtsException("invalid_provider_audio", "Speechify returned invalid audio data.")
                            }
                    }
                }
                if (audio.isEmpty() || audio.size > MAX_AUDIO_BYTES) {
                    throw CloudTtsException(
                        "invalid_provider_audio",
                        "The provider returned empty or oversized audio.",
                    )
                }
                return audio
            }
        } catch (error: CloudTtsException) {
            throw error
        } catch (error: IOException) {
            throw CloudTtsException(
                "provider_network_failure",
                "Could not reach ${selection.provider.name}. Check your connection and try again.",
                retryable = true,
                cause = error,
            )
        }
    }

    private data class SpeechifySpeechResponse(
        @JsonProperty("audio_data") val audioData: String = "",
        @JsonProperty("audio_format") val audioFormat: String = "",
    )
}
