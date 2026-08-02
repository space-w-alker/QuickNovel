package com.lagradost.quicknovel.tts.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CloudTtsCacheIdentityTest {
    @Test
    fun identityChangesOnlyForSynthesisInputs() {
        val base = CloudTtsCacheIdentity.create("Hello world.", "openrouter", "model@1", "alloy")

        // Playback speed is intentionally not an input and therefore reuses the same MP3.
        val atDifferentPlaybackSpeed = CloudTtsCacheIdentity.create("Hello world.", "openrouter", "model@1", "alloy")
        assertEquals(base, atDifferentPlaybackSpeed)
        assertNotEquals(base, CloudTtsCacheIdentity.create("Hello world!", "openrouter", "model@1", "alloy"))
        assertNotEquals(base, CloudTtsCacheIdentity.create("Hello world.", "openrouter", "model@2", "alloy"))
        assertNotEquals(base, CloudTtsCacheIdentity.create("Hello world.", "openrouter", "model@1", "nova"))
        assertNotEquals(base, CloudTtsCacheIdentity.create("Hello world.", "speechify", "model@1", "alloy"))
        assertNotEquals(base, CloudTtsCacheIdentity.create("Hello world.", "openrouter", "model@1", "alloy", "wav"))
    }
}
