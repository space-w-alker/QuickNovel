package com.lagradost.quicknovel.tts.cloud

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class CinematicDownloadQueueTest {
    @Test fun startsLookaheadDownloadsConcurrentlyAndDeduplicatesByCacheKey() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val started = ConcurrentHashMap.newKeySet<String>()
        val queue = CinematicDownloadQueue(
            scope = scope,
            loader = { _, chunk ->
                started += chunk.cacheKey
                release.await()
                File(chunk.cacheKey)
            },
            concurrency = 2,
        )
        val first = chunk('a')
        val second = chunk('b')

        try {
            val firstDownload = queue.prepare("job", first)
            val duplicate = queue.prepare("job", first)
            val secondDownload = queue.prepare("job", second)

            withTimeout(1_000) {
                while (started.size < 2) kotlinx.coroutines.yield()
            }
            assertEquals(setOf(first.cacheKey, second.cacheKey), started)
            assertSame(firstDownload, duplicate)

            release.complete(Unit)
            assertEquals(File(first.cacheKey), queue.await(first.cacheKey, firstDownload))
            assertEquals(File(second.cacheKey), queue.await(second.cacheKey, secondDownload))
        } finally {
            queue.clear()
            scope.cancel()
        }
    }

    private fun chunk(character: Char) = CinematicChunk(
        cacheKey = character.toString().repeat(64),
        status = "ready",
        audio = CloudAudio("https://audio/$character"),
    )
}
