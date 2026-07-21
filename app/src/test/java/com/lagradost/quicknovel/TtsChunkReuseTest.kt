package com.lagradost.quicknovel

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsChunkReuseTest {
    @Test
    fun cloudLineUsesExistingChunkTextAndOffsets() {
        val text = "First sentence. Second sentence?"
        val lines = TTSHelper.ttsParseText(text, 7)

        assertEquals("First sentence.", lines[0].speakOutMsg)
        assertEquals(0, lines[0].startChar)
        assertEquals(15, lines[0].endChar)
        assertEquals(7, lines[0].index)
        assertEquals(text.substring(lines[1].startChar, lines[1].endChar), lines[1].speakOutMsg)
    }
}
