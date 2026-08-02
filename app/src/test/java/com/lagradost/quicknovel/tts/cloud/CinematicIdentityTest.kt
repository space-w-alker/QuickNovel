package com.lagradost.quicknovel.tts.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class CinematicIdentityTest {
    @Test fun normalizationAndStableKeysMatchTheServerContract() {
        assertEquals("the novel", CinematicIdentity.normalizeNovelName("  Ｔhe—NOVEL!!!  "))
        assertTrue(CinematicIdentity.chapterKey("provider\u0000https://example/chapter").matches(Regex("[a-f0-9]{64}")))
    }

    @Test fun rejectsObviouslyIncompatibleChapterContentBeforeSubmission() {
        assertTrue(CinematicIdentity.isCompatibleEnglish("This is an English chapter."))
        assertFalse(CinematicIdentity.isCompatibleEnglish("这是一个中文章节。"))
    }

    @Test fun contentIdentityUsesUtf16OffsetsAndExactText() {
        val emoji = ChapterParagraphRequest(0, "A😀B", 0, 4)
        assertEquals(4, emoji.text.length)
        val first = CinematicIdentity.contentHash(listOf(emoji))
        assertNotEquals(first, CinematicIdentity.contentHash(listOf(emoji.copy(endChar = 3))))
        assertNotEquals(first, CinematicIdentity.contentHash(listOf(emoji.copy(text = "A😀b"))))
    }
}
