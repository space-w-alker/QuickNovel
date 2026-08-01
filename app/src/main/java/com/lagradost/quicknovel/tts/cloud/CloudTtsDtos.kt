package com.lagradost.quicknovel.tts.cloud

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

enum class CloudTtsProvider(val wireValue: String) {
    OpenRouter("openrouter"),
    Speechify("speechify");

    companion object {
        fun fromWire(value: String): CloudTtsProvider =
            entries.firstOrNull { it.wireValue == value } ?: OpenRouter
    }
}

enum class CloudTtsGenerationSource(val wireValue: String) {
    Backend("backend"),
    Byok("byok");

    companion object {
        fun fromWire(value: String): CloudTtsGenerationSource =
            entries.firstOrNull { it.wireValue == value } ?: Backend
    }
}

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
    @JsonProperty("backend_generation_status") val backendGenerationStatus: String = "pending",
)

data class CloudVoice(
    val id: String,
    @JsonProperty("display_name") val displayName: String,
    val locale: String? = null,
    val gender: String = id,
    val voice: String = id,
)

data class CloudModel(
    val id: String,
    @JsonProperty("display_name") val displayName: String,
    @JsonProperty("cache_revision") val cacheRevision: String,
    @JsonProperty("output_format") val outputFormat: String,
    val provider: String = "openrouter",
    val model: String = id,
    @JsonProperty("max_input_characters") val maxInputCharacters: Int = 4000,
    val voices: List<CloudVoice> = emptyList(),
)

data class CloudProviderCapability(
    val id: String,
    @JsonProperty("display_name") val displayName: String,
    @JsonProperty("max_input_characters") val maxInputCharacters: Int,
    @JsonProperty("byok_supported") val byokSupported: Boolean = true,
    @JsonProperty("backend_available") val backendAvailable: Boolean = false,
)

data class CloudCatalog(
    @JsonProperty("catalog_version") val catalogVersion: String,
    val providers: List<CloudProviderCapability> = emptyList(),
    val models: List<CloudModel> = emptyList(),
    @JsonProperty("chapter_modes") val chapterModes: List<CloudChapterMode> = emptyList(),
) {
    val qualityChoices: List<CloudQualityChoice>
        get() = models.map { CloudQualityChoice(it.id, it.displayName, true) } +
            chapterModes.map { CloudQualityChoice(it.id, it.displayName, it.available) }
    fun resolveSelection(preferredModelId: String, preferredVoiceId: String): CloudTtsSelection? {
        val model = models.firstOrNull { it.id == preferredModelId }
            ?: models.firstOrNull { it.id == "standard" }
            ?: models.firstOrNull()
            ?: return null
        val voice = model.voices.firstOrNull { it.id == preferredVoiceId }
            ?: model.voices.firstOrNull { it.id == "male" }
            ?: model.voices.firstOrNull()
            ?: return null
        return CloudTtsSelection(
            provider = CloudTtsProvider.fromWire(model.provider),
            modelId = model.model,
            voiceId = voice.voice,
            maxInputCharacters = model.maxInputCharacters,
            presetModel = model,
            presetVoice = voice,
        )
    }
}

data class CloudQualityChoice(val id: String, val displayName: String, val available: Boolean)

data class CloudChapterMode(
    val id: String,
    @JsonProperty("display_name") val displayName: String,
    val available: Boolean = false,
    val locale: String = "en",
    @JsonProperty("chapter_scoped") val chapterScoped: Boolean = true,
)

data class CinematicChapterContext(
    val novelName: String,
    val chapterKey: String,
    val chapterTitle: String,
    val chapterIndex: Int,
    val paragraphs: List<TTSParagraph>,
)

data class TTSParagraph(val paragraphIndex: Int, val text: String, val startChar: Int, val endChar: Int)

data class ResolveChapterRequest(
    @JsonProperty("novel_name") val novelName: String,
    @JsonProperty("chapter_key") val chapterKey: String,
    @JsonProperty("chapter_title") val chapterTitle: String? = null,
    val paragraphs: List<ChapterParagraphRequest>,
    @JsonProperty("playback_start_paragraph_index") val playbackStartParagraphIndex: Int = 0,
)

data class ChapterParagraphRequest(
    @JsonProperty("paragraph_index") val paragraphIndex: Int,
    val text: String,
    @JsonProperty("start_char") val startChar: Int,
    @JsonProperty("end_char") val endChar: Int,
)

data class CinematicManifest(
    @JsonProperty("chapter_job_id") val chapterJobId: String,
    @JsonProperty("identity_hash") val identityHash: String? = null,
    val state: String,
    @JsonProperty("retry_after_ms") val retryAfterMs: Long? = null,
    @JsonProperty("playback_start_paragraph_index") val playbackStartParagraphIndex: Int = 0,
    @JsonProperty("playable_through_paragraph_index") val playableThroughParagraphIndex: Int? = null,
    @JsonProperty("first_gap_paragraph_index") val firstGapParagraphIndex: Int? = null,
    val paragraphs: List<CinematicParagraph> = emptyList(),
    val error: CinematicManifestError? = null,
)

data class CinematicManifestError(val code: String, val message: String)
data class CinematicParagraph(
    @JsonProperty("paragraph_index") val paragraphIndex: Int,
    val text: String,
    @JsonProperty("start_char") val startChar: Int,
    @JsonProperty("end_char") val endChar: Int,
    val status: String,
    val utterances: List<CinematicUtterance> = emptyList(),
)
data class CinematicUtterance(
    @JsonProperty("start_char") val startChar: Int,
    @JsonProperty("end_char") val endChar: Int,
    @JsonProperty("cleaned_text") val cleanedText: String,
    val chunks: List<CinematicChunk> = emptyList(),
)
data class CinematicChunk(
    @JsonProperty("cache_key") val cacheKey: String,
    val status: String,
    val retryable: Boolean = false,
    val audio: CloudAudio? = null,
)

data class CloudTtsSelection(
    val provider: CloudTtsProvider,
    val modelId: String,
    val voiceId: String,
    val maxInputCharacters: Int,
    val presetModel: CloudModel? = null,
    val presetVoice: CloudVoice? = null,
) {
    val isPreset: Boolean get() = presetModel != null && presetVoice != null

    fun request(
        text: String,
        source: CloudTtsGenerationSource,
        chapter: CinematicChapterContext? = null,
    ): ResolveChunkRequest =
        if (isPreset) {
            ResolveChunkRequest(
                quality = presetModel!!.id,
                gender = presetVoice!!.id,
                generationSource = source.wireValue,
                text = text,
                novelName = chapter?.novelName,
                chapterTitle = chapter?.chapterTitle,
                chapterKey = chapter?.chapterKey,
                chapterIndex = chapter?.chapterIndex,
            )
        } else {
            ResolveChunkRequest(
                provider = provider.wireValue,
                model = modelId,
                voice = voiceId,
                generationSource = source.wireValue,
                text = text,
                novelName = chapter?.novelName,
                chapterTitle = chapter?.chapterTitle,
                chapterKey = chapter?.chapterKey,
                chapterIndex = chapter?.chapterIndex,
            )
        }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ResolveChunkRequest(
    @JsonProperty("novel_name") val novelName: String? = null,
    @JsonProperty("chapter_title") val chapterTitle: String? = null,
    @JsonProperty("chapter_key") val chapterKey: String? = null,
    @JsonProperty("chapter_index") val chapterIndex: Int? = null,
    val quality: String? = null,
    val gender: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val voice: String? = null,
    @JsonProperty("generation_source") val generationSource: String = "backend",
    val text: String,
    @JsonProperty("chunker_version") val chunkerVersion: Int = 2,
)

data class CloudAudio(
    val url: String,
    @JsonProperty("expires_at") val expiresAt: String? = null,
    @JsonProperty("content_type") val contentType: String = "audio/mpeg",
    val bytes: Long? = null,
    @JsonProperty("duration_ms") val durationMs: Long? = null,
)

data class CanonicalSelection(val provider: String, val model: String, val voice: String)

data class ResolveResult(
    val state: String,
    @JsonProperty("cache_key") val cacheKey: String,
    @JsonProperty("cache_hit") val cacheHit: Boolean? = null,
    @JsonProperty("job_id") val jobId: String? = null,
    @JsonProperty("retry_after_ms") val retryAfterMs: Long? = null,
    val audio: CloudAudio? = null,
    val quota: CloudQuota? = null,
    val selection: CanonicalSelection? = null,
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
