package com.lagradost.quicknovel.tts.cloud

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import com.lagradost.quicknovel.ReaderTtsEngine
import com.lagradost.quicknovel.TTSHelper
import com.lagradost.quicknovel.TtsPlaybackResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

class CinematicTtsEngine(
    context: Context,
    private val repository: CloudTtsRepository,
    private val onPreparing: () -> Unit,
    private val onPlaying: () -> Unit,
    private val onUtterance: (TTSHelper.TTSLine) -> Unit = {},
    private val prefetchNext: suspend (Int) -> CinematicChapterContext? = { null },
) : ReaderTtsEngine {
    private val cache = CloudTtsDiskCache(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var speed = 1f
    @Volatile private var paused = false
    @Volatile private var released = false
    @Volatile private var chapter: CinematicChapterContext? = null
    @Volatile private var playbackStart = 0
    private var manifest: CinematicManifest? = null
    private val prefetchTracker = CinematicPrefetchTracker()

    override val isInitialized: Boolean get() = !released
    override fun register() = Unit
    override fun unregister() = Unit
    override fun setPitch(pitch: Float) = Unit
    override fun setSpeed(speed: Float) { this.speed = speed; synchronized(lock) { player?.applySpeed(speed) } }
    override fun pause() { paused = true; synchronized(lock) { runCatching { player?.pause() } } }
    override fun resume() { paused = false; synchronized(lock) { runCatching { player?.start() } } }
    override fun setChapter(context: CinematicChapterContext, playbackStartParagraphIndex: Int) {
        if (chapter?.chapterKey != context.chapterKey) manifest = null
        chapter = context
        playbackStart = playbackStartParagraphIndex
    }
    override fun interrupt() {
        synchronized(lock) { runCatching { player?.stop() }; player?.release(); player = null }
    }
    override fun release() { released = true; interrupt(); scope.cancel() }

    override suspend fun play(
        line: TTSHelper.TTSLine,
        upcoming: List<TTSHelper.TTSLine>,
        shouldCancel: () -> Boolean,
    ): TtsPlaybackResult {
        coroutineContext.ensureActive()
        val context = chapter ?: throw CloudTtsException("cinematic_chapter_missing", "Cinematic chapter data is unavailable.")
        if (!CinematicIdentity.isCompatibleEnglish(context.paragraphs.joinToString("\n") { it.text })) {
            throw CloudTtsException("unsupported_cinematic_language", "Cinematic mode currently supports English chapters only.")
        }
        val paragraphIndex = context.paragraphs.indexOfFirst { it.startChar == line.startChar }.takeIf { it >= 0 }
            ?: throw CloudTtsException("cinematic_paragraph_missing", "Cinematic paragraph data changed.")
        var current = manifest ?: repository.resolveChapter(context.request(playbackStart)).also { manifest = it }
        while ((current.playableThroughParagraphIndex ?: -1) < paragraphIndex && current.state !in setOf("failed", "ready")) {
            onPreparing()
            delay((current.retryAfterMs ?: 750L).coerceIn(100, 10_000))
            if (shouldCancel()) return TtsPlaybackResult.Interrupted
            current = repository.pollChapter(current.chapterJobId).also { manifest = it }
        }
        if ((current.playableThroughParagraphIndex ?: -1) < paragraphIndex) {
            throw CloudTtsException(
                current.error?.code ?: "cinematic_gap",
                current.error?.message ?: "Cinematic playback reached an audio gap. Retry to resume generation.",
                retryable = true,
            )
        }
        scope.launch {
            val next = prefetchNext(context.chapterIndex) ?: return@launch
            val request = next.request(0)
            val derivedRevision = CinematicIdentity.contentHash(request.paragraphs)
            if (!prefetchTracker.shouldSubmit(next.chapterKey, derivedRevision)) return@launch
            try { repository.resolveChapter(request) } catch (_: Exception) { /* playback remains independent */ }
        }
        val paragraph = current.paragraphs.firstOrNull { it.paragraphIndex == paragraphIndex }
            ?: throw CloudTtsException("cinematic_manifest_invalid", "Cinematic paragraph is missing.")
        if (paragraph.utterances.flatMap { it.chunks }.none { it.status == "ready" }) {
            throw CloudTtsException("cinematic_gap", "Cinematic paragraph audio is incomplete.", retryable = true)
        }
        for (utterance in paragraph.utterances) {
            val valid = utterance.startChar >= 0 && utterance.endChar <= paragraph.text.length && utterance.endChar > utterance.startChar
            onUtterance(TTSHelper.TTSLine(
                utterance.cleanedText,
                if (valid) paragraph.startChar + utterance.startChar else paragraph.startChar,
                if (valid) paragraph.startChar + utterance.endChar else paragraph.endChar,
                context.chapterIndex,
            ))
            for (chunk in utterance.chunks.filter { it.status == "ready" }) {
            var ready = chunk
            val file = cache.get(ready.cacheKey) ?: run {
                var audio = ready.audio ?: throw CloudTtsException("cinematic_gap", "Cinematic audio is incomplete.", true)
                val bytes = try { repository.download(audio.url) } catch (error: CloudTtsException) {
                    if (error.code != "signed_url_expired") throw error
                    current = repository.pollChapter(current.chapterJobId).also { manifest = it }
                    ready = current.paragraphs.first { it.paragraphIndex == paragraphIndex }.utterances
                        .flatMap { it.chunks }.first { it.cacheKey == ready.cacheKey }
                    audio = ready.audio ?: throw error
                    repository.download(audio.url)
                }
                cache.put(ready.cacheKey, bytes)
            }
            if (playFile(file, shouldCancel) == TtsPlaybackResult.Interrupted) return TtsPlaybackResult.Interrupted
            }
        }
        return TtsPlaybackResult.Completed
    }

    private suspend fun playFile(file: File, shouldCancel: () -> Boolean): TtsPlaybackResult {
        if (shouldCancel()) return TtsPlaybackResult.Interrupted
        val completed = AtomicBoolean(false)
        val mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(file.absolutePath); setOnCompletionListener { completed.set(true) }
            setOnErrorListener { _, _, _ -> completed.set(true); true }; prepare(); applySpeed(speed)
        }
        synchronized(lock) { player = mediaPlayer }
        try {
            while (paused && !shouldCancel()) delay(50)
            if (shouldCancel()) return TtsPlaybackResult.Interrupted
            onPlaying(); mediaPlayer.start()
            while (!completed.get()) { if (shouldCancel()) return TtsPlaybackResult.Interrupted; delay(50) }
            return TtsPlaybackResult.Completed
        } finally {
            synchronized(lock) { if (player === mediaPlayer) player = null }
            runCatching { mediaPlayer.stop() }; mediaPlayer.release()
        }
    }

    private fun MediaPlayer.applySpeed(value: Float) {
        playbackParams = (playbackParams ?: PlaybackParams()).setSpeed(value.coerceIn(0.1f, 7f))
    }
}

fun CinematicChapterContext.request(start: Int): ResolveChapterRequest = ResolveChapterRequest(
    novelName = novelName, chapterKey = chapterKey, chapterTitle = chapterTitle,
    paragraphs = paragraphs.map { ChapterParagraphRequest(it.paragraphIndex, it.text, it.startChar, it.endChar) },
    playbackStartParagraphIndex = start,
)

fun CinematicManifest.ttsLines(renderedChapter: String): List<TTSHelper.TTSLine> = paragraphs.flatMap { paragraph ->
    paragraph.utterances.map { utterance ->
        val start = paragraph.startChar + utterance.startChar
        val end = paragraph.startChar + utterance.endChar
        val valid = paragraph.startChar >= 0 && paragraph.endChar <= renderedChapter.length &&
            paragraph.endChar >= paragraph.startChar && renderedChapter.substring(paragraph.startChar, paragraph.endChar) == paragraph.text &&
            start >= paragraph.startChar && end <= paragraph.endChar && end > start
        TTSHelper.TTSLine(
            utterance.cleanedText,
            if (valid) start else paragraph.startChar,
            if (valid) end else paragraph.endChar,
            paragraph.paragraphIndex,
        )
    }
}

class CinematicPrefetchTracker {
    private val submitted = mutableSetOf<String>()
    fun shouldSubmit(chapterKey: String, revision: String): Boolean = submitted.add("$chapterKey\u0000$revision")
}
