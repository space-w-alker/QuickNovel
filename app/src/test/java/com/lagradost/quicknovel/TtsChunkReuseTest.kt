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

    @Test
    fun cloudParagraphsMergeSentencesButStopAtNewlines() {
        val text = "First sentence. Second sentence?\nNext paragraph: still together. Yes."

        val paragraphs = TTSHelper.ttsParseParagraphs(text, 7)

        assertEquals(2, paragraphs.size)
        assertEquals("First sentence. Second sentence?", paragraphs[0].speakOutMsg)
        assertEquals(0, paragraphs[0].startChar)
        assertEquals(text.indexOf('\n'), paragraphs[0].endChar)
        assertEquals("Next paragraph: still together. Yes.", paragraphs[1].speakOutMsg)
        assertEquals(text.indexOf("Next"), paragraphs[1].startChar)
        assertEquals(text.length, paragraphs[1].endChar)
        assertEquals(7, paragraphs[1].index)
    }
}
