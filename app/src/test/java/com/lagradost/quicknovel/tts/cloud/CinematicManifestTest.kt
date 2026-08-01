package com.lagradost.quicknovel.tts.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CinematicManifestTest {
    private val manifest = CinematicManifest(
        chapterJobId = "job", state = "partially_ready", playbackStartParagraphIndex = 0,
        playableThroughParagraphIndex = 0, firstGapParagraphIndex = 1,
        paragraphs = listOf(CinematicParagraph(0, "Hello world.", 2, 14, "ready", listOf(
            CinematicUtterance(0, 5, "Hello", listOf(CinematicChunk("a".repeat(64), "ready", audio = CloudAudio("https://audio")))),
        ))),
    )

    @Test fun mapsRelativeUtf16OffsetsAndFallsBackWhenRenderedTextChanged() {
        val valid = manifest.ttsLines("__Hello world.__").single()
        assertEquals(2, valid.startChar); assertEquals(7, valid.endChar)
        val fallback = manifest.ttsLines("__Changed text__").single()
        assertEquals(2, fallback.startChar); assertEquals(14, fallback.endChar)
    }

    @Test fun manifestRepresentsAContiguousPrefixAndGap() {
        assertEquals(0, manifest.playableThroughParagraphIndex)
        assertEquals(1, manifest.firstGapParagraphIndex)
        assertFalse(manifest.state == "ready")
    }

    @Test fun nextOnlyPrefetchIsDeduplicatedByIdentityAndRevision() {
        val tracker = CinematicPrefetchTracker()
        assertTrue(tracker.shouldSubmit("chapter-2", "revision-1"))
        assertFalse(tracker.shouldSubmit("chapter-2", "revision-1"))
        assertTrue(tracker.shouldSubmit("chapter-2", "revision-2"))
    }
}
