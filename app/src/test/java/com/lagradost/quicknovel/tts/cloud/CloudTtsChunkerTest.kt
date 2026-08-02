package com.lagradost.quicknovel.tts.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTtsChunkerTest {
    @Test
    fun splitsAtNaturalBoundariesWithinProviderLimit() {
        val text = "First sentence. Second sentence is longer. Third sentence."
        val chunks = CloudTtsChunker.split(text, 30)

        assertEquals(text.replace(Regex("\\s+"), ""), chunks.joinToString("").replace(Regex("\\s+"), ""))
        assertTrue(chunks.all { it.length <= 30 })
        assertTrue(chunks.first().endsWith("."))
    }

    @Test
    fun hardSplitsTextWithoutBoundaries() {
        val chunks = CloudTtsChunker.split("abcdefghij", 4)
        assertEquals(listOf("abcd", "efgh", "ij"), chunks)
    }
}
