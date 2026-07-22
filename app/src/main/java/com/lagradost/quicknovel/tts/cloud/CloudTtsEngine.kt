package com.lagradost.quicknovel.tts.cloud

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
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
    private val modelId: String,
    private val voiceId: String,
    private val onPreparing: () -> Unit,
    private val onPlaying: () -> Unit,
) : ReaderTtsEngine {
    private companion object {
        const val TAG = "CloudTTS"
    }

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

    override val isInitialized: Boolean get() = !released
    override fun register() = Unit
    override fun unregister() = Unit
    override fun setPitch(pitch: Float) = Unit

    override fun setSpeed(speed: Float) {
        this.speed = speed
        synchronized(playerLock) { player?.applyPlaybackSpeed(speed) }
    }

    override fun pause() {
        paused = true
        synchronized(playerLock) {
            runCatching { player?.takeIf { it.isPlaying }?.pause() }
        }
    }

    override fun resume() {
        paused = false
        synchronized(playerLock) {
            if (playbackStarted) runCatching { player?.start() }
        }
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
        val currentKey = requestKey(line.speakOutMsg)
        Log.i(
            TAG,
            "Playback requested key=${currentKey.take(12)} chars=${line.speakOutMsg.length} " +
                "prefetch=${upcoming.take(5).size}",
        )
        val current = prepared.getOrPut(currentKey) { prepare(line.speakOutMsg) }
        upcoming.take(5).forEach { next ->
            val key = requestKey(next.speakOutMsg)
            prepared.getOrPut(key) { prepare(next.speakOutMsg) }
        }

        if (!current.isCompleted) onPreparing()
        val audio = try {
            current.await()
        } catch (cancelled: CancellationException) {
            if (shouldCancel()) return TtsPlaybackResult.Interrupted
            throw cancelled
        } finally {
            prepared.remove(currentKey, current)
        }
        Log.i(TAG, "Audio prepared key=${currentKey.take(12)} bytes=${audio.length()}")
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
            Log.i(TAG, "MediaPlayer starting key=${currentKey.take(12)} speed=$speed")
            mediaPlayer.start()
            while (coroutineContext.isActive && !completed.get()) {
                if (shouldCancel()) return TtsPlaybackResult.Interrupted
                delay(50)
            }
            playbackError[0]?.let {
                throw CloudTtsException("playback_failed", "Cloud TTS audio could not be played.", cause = it)
            }
            Log.i(TAG, "Playback completed key=${currentKey.take(12)}")
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

    private fun prepare(text: String) = scope.async {
        resolveSlots.withPermit {
            val requestKey = requestKey(text)
            Log.i(TAG, "Preparing audio key=${requestKey.take(12)}")
            var result = repository.resolve(modelId, voiceId, text)
            cache.get(result.cacheKey)?.let {
                Log.i(TAG, "Audio cache hit key=${result.cacheKey.take(12)} bytes=${it.length()}")
                return@withPermit it
            }
            Log.i(TAG, "Audio cache miss key=${result.cacheKey.take(12)}")
            val bytes = try {
                repository.download(result.audio!!.url)
            } catch (error: CloudTtsException) {
                if (error.code != "signed_url_expired") throw error
                result = repository.resolve(modelId, voiceId, text)
                repository.download(result.audio!!.url)
            }
            cache.put(result.cacheKey, bytes)
        }
    }

    private fun requestKey(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest("$modelId\u0000$voiceId\u0000$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun MediaPlayer.applyPlaybackSpeed(value: Float) {
        playbackParams = (playbackParams ?: PlaybackParams()).setSpeed(value.coerceIn(0.1f, 7f))
    }
}
