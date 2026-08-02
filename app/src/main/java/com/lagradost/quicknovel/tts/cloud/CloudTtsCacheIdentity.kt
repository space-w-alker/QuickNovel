package com.lagradost.quicknovel.tts.cloud

import java.security.MessageDigest

/** Mirrors the documented server identity algorithm for contract tests and diagnostics. */
object CloudTtsCacheIdentity {
    fun create(
        normalizedText: String,
        provider: String,
        model: String,
        voice: String,
        outputFormat: String = "mp3",
    ): String {
        val input = listOf(
            "quicknovel-tts-cache-v4",
            normalizedText,
            provider,
            model,
            voice,
            outputFormat,
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
