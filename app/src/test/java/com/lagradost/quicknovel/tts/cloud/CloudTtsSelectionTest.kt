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
            CloudModel("standard", "Standard", "standard@1", "mp3", voices = listOf(male, female)),
            CloudModel("high", "High", "high@1", "mp3", voices = listOf(male, female)),
            CloudModel("ultra", "Ultra", "ultra@1", "mp3", voices = listOf(male, female)),
        ),
    )

    @Test
    fun preservesVoiceAcrossQualityLevels() {
        val selection = catalog.resolveSelection("ultra", "female")

        assertEquals("ultra", selection?.presetModel?.id)
        assertEquals("female", selection?.presetVoice?.id)
        assertEquals("openrouter", selection?.provider?.wireValue)
    }

    @Test
    fun replacesRemovedPreferencesWithStandardMale() {
        val selection = catalog.resolveSelection("quicknovel-default", "alloy")

        assertEquals("standard", selection?.presetModel?.id)
        assertEquals("male", selection?.presetVoice?.id)
    }

    @Test
    fun returnsNullForAnEmptyCatalog() {
        assertNull(CloudCatalog("empty", emptyList()).resolveSelection("standard", "male"))
    }

    @Test
    fun emitsMutuallyExclusivePresetAndDirectRequests() {
        val preset = catalog.resolveSelection("high", "female")!!
            .request("Hello", CloudTtsGenerationSource.Backend)
        assertEquals("high", preset.quality)
        assertEquals("female", preset.gender)
        assertNull(preset.provider)
        assertEquals(2, preset.chunkerVersion)

        val direct = CloudTtsSelection(
            CloudTtsProvider.Speechify,
            "simba-3.0",
            "george",
            2000,
        ).request("Hello", CloudTtsGenerationSource.Byok)
        assertEquals("speechify", direct.provider)
        assertEquals("simba-3.0", direct.model)
        assertEquals("george", direct.voice)
        assertNull(direct.quality)
        assertEquals("byok", direct.generationSource)
    }
}
