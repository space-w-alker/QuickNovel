package com.lagradost.quicknovel.tts.cloud

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import com.lagradost.quicknovel.ReaderTtsEngine
import com.lagradost.quicknovel.TTSHelper
import com.lagradost.quicknovel.TtsPlaybackResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

class CloudTtsEngine(
    context: Context,
    private val repository: CloudTtsRepository,
    private val selection: CloudTtsSelection,
    private val generationSource: CloudTtsGenerationSource,
    private val onPreparing: () -> Unit,
    private val onPlaying: () -> Unit,
) : ReaderTtsEngine {
    private val cache = CloudTtsDiskCache(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolveSlots = Semaphore(3)
    private val prepared = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<File>>()
    private val playerLock = Any()
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var speed = 1f
    @Volatile private var released = false
    @Volatile private var paused = false
    @Volatile private var playbackStarted = false
    @Volatile private var chapter: CinematicChapterContext? = null

    override val isInitialized: Boolean get() = !released
    override fun register() = Unit
    override fun unregister() = Unit
    override fun setPitch(pitch: Float) = Unit

    override fun setChapter(context: CinematicChapterContext, playbackStartParagraphIndex: Int) {
        chapter = context
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        synchronized(playerLock) { player?.applyPlaybackSpeed(speed) }
    }

    override fun pause() {
        paused = true
        synchronized(playerLock) { runCatching { player?.takeIf { it.isPlaying }?.pause() } }
    }

    override fun resume() {
        paused = false
        synchronized(playerLock) { if (playbackStarted) runCatching { player?.start() } }
    }

    override fun interrupt() {
        synchronized(playerLock) {
            player?.setOnCompletionListener(null)
            player?.setOnErrorListener(null)
            runCatching { player?.stop() }
            player?.release()
            player = null
            playbackStarted = false
        }
        prepared.values.forEach { it.cancel() }
        prepared.clear()
    }

    override fun release() {
        released = true
        interrupt()
        scope.cancel()
    }

    override suspend fun play(
        line: TTSHelper.TTSLine,
        upcoming: List<TTSHelper.TTSLine>,
        shouldCancel: () -> Boolean,
    ): TtsPlaybackResult {
        coroutineContext.ensureActive()
        val chapterContext = chapter
        val chunks = CloudTtsChunker.split(line.speakOutMsg, selection.maxInputCharacters)
        val current = chunks.map { text ->
            val key = requestKey(text)
            key to prepared.getOrPut(key) { prepareAudio(text, chapterContext) }
        }
        upcoming.asSequence()
            .flatMap { CloudTtsChunker.split(it.speakOutMsg, selection.maxInputCharacters).asSequence() }
            .take(5)
            .forEach { text -> prepared.getOrPut(requestKey(text)) { prepareAudio(text, chapterContext) } }
        if (current.any { !it.second.isCompleted }) onPreparing()
        val files = try {
            current.map { (key, deferred) ->
                deferred.await().also { prepared.remove(key, deferred) }
            }
        } catch (cancelled: CancellationException) {
            if (shouldCancel()) return TtsPlaybackResult.Interrupted
            throw cancelled
        }
        for (file in files) {
            if (playFile(file, shouldCancel) == TtsPlaybackResult.Interrupted) {
                return TtsPlaybackResult.Interrupted
            }
        }
        return TtsPlaybackResult.Completed
    }

    private suspend fun playFile(audio: File, shouldCancel: () -> Boolean): TtsPlaybackResult {
        if (shouldCancel()) return TtsPlaybackResult.Interrupted
        val completed = AtomicBoolean(false)
        val playbackError = arrayOfNulls<Throwable>(1)
        val mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(audio.absolutePath)
            setOnCompletionListener { completed.set(true) }
            setOnErrorListener { _, what, extra ->
                playbackError[0] = IllegalStateException("MP3 playback failed ($what/$extra)")
                completed.set(true)
                true
            }
            prepare()
            applyPlaybackSpeed(speed)
        }
        synchronized(playerLock) { player = mediaPlayer }
        try {
            while (paused && !shouldCancel()) delay(50)
            if (shouldCancel()) return TtsPlaybackResult.Interrupted
            onPlaying()
            playbackStarted = true
            mediaPlayer.start()
            while (coroutineContext.isActive && !completed.get()) {
                if (shouldCancel()) return TtsPlaybackResult.Interrupted
                delay(50)
            }
            playbackError[0]?.let {
                throw CloudTtsException("playback_failed", "Cloud TTS audio could not be played.", cause = it)
            }
            return TtsPlaybackResult.Completed
        } finally {
            synchronized(playerLock) {
                if (player === mediaPlayer) {
                    player = null
                    playbackStarted = false
                    runCatching { mediaPlayer.stop() }
                    mediaPlayer.release()
                }
            }
        }
    }

    private fun prepareAudio(text: String, chapterContext: CinematicChapterContext?) = scope.async {
        resolveSlots.withPermit {
            var result = repository.resolve(selection, generationSource, text, chapterContext)
            cache.get(result.cacheKey)?.let { return@withPermit it }
            if (result.state == "upload_required") {
                val bytes = repository.generateByok(selection, text)
                val uploaded = repository.upload(
                    selection.request(text, CloudTtsGenerationSource.Byok, chapterContext),
                    bytes,
                )
                if (uploaded.state == "ready" && uploaded.cacheHit != true) {
                    return@withPermit cache.put(uploaded.cacheKey, bytes)
                }
                val winner = if (uploaded.state == "ready") uploaded
                else repository.resolve(selection, generationSource, text, chapterContext)
                return@withPermit cache.put(winner.cacheKey, repository.download(winner.audio!!.url))
            }
            val bytes = try {
                repository.download(result.audio!!.url)
            } catch (error: CloudTtsException) {
                if (error.code != "signed_url_expired") throw error
                result = repository.resolve(selection, generationSource, text, chapterContext)
                repository.download(result.audio!!.url)
            }
            cache.put(result.cacheKey, bytes)
        }
    }

    private fun requestKey(text: String): String {
        val identity = "${selection.provider.wireValue}\u0000${selection.modelId}\u0000" +
            "${selection.voiceId}\u0000$text"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun MediaPlayer.applyPlaybackSpeed(value: Float) {
        playbackParams = (playbackParams ?: PlaybackParams()).setSpeed(value.coerceIn(0.1f, 7f))
    }
}
