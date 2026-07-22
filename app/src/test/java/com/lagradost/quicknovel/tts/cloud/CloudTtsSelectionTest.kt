package com.lagradost.quicknovel.tts.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudTtsSelectionTest {
    private val male = CloudVoice("male", "Male")
    private val female = CloudVoice("female", "Female")
    private val catalog = CloudCatalog(
        catalogVersion = "test@1",
        models = listOf(
            CloudModel("standard", "Standard", "standard@1", "mp3", listOf(male, female)),
            CloudModel("high", "High", "high@1", "mp3", listOf(male, female)),
            CloudModel("ultra", "Ultra", "ultra@1", "mp3", listOf(male, female)),
        ),
    )

    @Test
    fun preservesVoiceAcrossQualityLevels() {
        val selection = catalog.resolveSelection("ultra", "female")

        assertEquals("ultra", selection?.model?.id)
        assertEquals("female", selection?.voice?.id)
    }

    @Test
    fun replacesRemovedPreferencesWithStandardMale() {
        val selection = catalog.resolveSelection("quicknovel-default", "alloy")

        assertEquals("standard", selection?.model?.id)
        assertEquals("male", selection?.voice?.id)
    }

    @Test
    fun returnsNullForAnEmptyCatalog() {
        assertNull(CloudCatalog("empty", emptyList()).resolveSelection("standard", "male"))
    }
}
