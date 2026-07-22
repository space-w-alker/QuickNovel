package com.lagradost.quicknovel.tts.cloud

import com.fasterxml.jackson.annotation.JsonProperty

data class CloudQuota(
    @JsonProperty("characters_remaining") val charactersRemaining: Int = 0,
    @JsonProperty("requests_remaining") val requestsRemaining: Int = 0,
    @JsonProperty("resets_at") val resetsAt: String? = null,
)

data class InstallationRequest(
    @JsonProperty("installation_id") val installationId: String,
    @JsonProperty("app_version") val appVersion: String,
    val platform: String = "android",
)

data class TokenRequest(
    @JsonProperty("installation_id") val installationId: String,
    @JsonProperty("refresh_token") val refreshToken: String,
)

data class InstallationToken(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("access_token_expires_at") val accessTokenExpiresAt: String? = null,
    @JsonProperty("refresh_token") val refreshToken: String? = null,
    val quota: CloudQuota? = null,
)

data class CloudVoice(
    val id: String,
    @JsonProperty("display_name") val displayName: String,
    val locale: String? = null,
)

data class CloudModel(
    val id: String,
    @JsonProperty("display_name") val displayName: String,
    @JsonProperty("cache_revision") val cacheRevision: String,
    @JsonProperty("output_format") val outputFormat: String,
    val voices: List<CloudVoice> = emptyList(),
)

data class CloudCatalog(
    @JsonProperty("catalog_version") val catalogVersion: String,
    val models: List<CloudModel> = emptyList(),
) {
    fun resolveSelection(preferredModelId: String, preferredVoiceId: String): CloudTtsSelection? {
        val model = models.firstOrNull { it.id == preferredModelId }
            ?: models.firstOrNull { it.id == "standard" }
            ?: models.firstOrNull()
            ?: return null
        val voice = model.voices.firstOrNull { it.id == preferredVoiceId }
            ?: model.voices.firstOrNull { it.id == "male" }
            ?: model.voices.firstOrNull()
            ?: return null
        return CloudTtsSelection(model, voice)
    }
}

data class CloudTtsSelection(val model: CloudModel, val voice: CloudVoice)

data class ResolveChunkRequest(
    @JsonProperty("model_id") val modelId: String,
    @JsonProperty("voice_id") val voiceId: String,
    val text: String,
    @JsonProperty("chunker_version") val chunkerVersion: Int = 1,
)

data class CloudAudio(
    val url: String,
    @JsonProperty("expires_at") val expiresAt: String? = null,
    @JsonProperty("content_type") val contentType: String = "audio/mpeg",
    val bytes: Long? = null,
    @JsonProperty("duration_ms") val durationMs: Long? = null,
)

data class ResolveResult(
    val state: String,
    @JsonProperty("cache_key") val cacheKey: String,
    @JsonProperty("cache_hit") val cacheHit: Boolean? = null,
    @JsonProperty("job_id") val jobId: String? = null,
    @JsonProperty("retry_after_ms") val retryAfterMs: Long? = null,
    val audio: CloudAudio? = null,
    val quota: CloudQuota? = null,
)

data class ApiErrorEnvelope(val error: CloudApiError)
data class CloudApiError(
    val code: String = "generation_failed",
    val message: String = "Cloud TTS could not generate this audio.",
    val retryable: Boolean = false,
)

class CloudTtsException(
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)
