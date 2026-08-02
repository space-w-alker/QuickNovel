package com.lagradost.quicknovel.tts.cloud

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

object CinematicIdentity {
    fun normalizeNovelName(value: String): String {
        val source = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val output = StringBuilder()
        var separated = false
        source.codePoints().forEach { codePoint ->
            val type = Character.getType(codePoint)
            val separator = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) ||
                type in setOf(
                    Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
                    Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
                    Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                    Character.OTHER_PUNCTUATION.toInt(), Character.MATH_SYMBOL.toInt(),
                    Character.CURRENCY_SYMBOL.toInt(), Character.MODIFIER_SYMBOL.toInt(), Character.OTHER_SYMBOL.toInt(),
                )
            if (separator) separated = output.isNotEmpty()
            else {
                if (separated) output.append(' ')
                output.appendCodePoint(codePoint)
                separated = false
            }
        }
        return output.toString().trim().also { require(it.isNotEmpty() && it.length <= 200) }
    }

    fun chapterKey(sourceIdentity: String): String = sha256(sourceIdentity.toByteArray(StandardCharsets.UTF_8))

    fun isCompatibleEnglish(text: String): Boolean {
        var letters = 0
        var latin = 0
        text.codePoints().forEach { codePoint ->
            if (Character.isLetter(codePoint)) {
                letters++
                if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN) latin++
            }
        }
        return letters > 0 && latin.toDouble() / letters >= 0.7
    }

    fun contentHash(paragraphs: List<ChapterParagraphRequest>): String {
        val output = ByteArrayOutputStream()
        paragraphs.forEach { paragraph ->
            output.write(integer(paragraph.paragraphIndex)); output.write(integer(paragraph.startChar))
            output.write(integer(paragraph.endChar)); output.write(field(paragraph.text))
        }
        return sha256(output.toByteArray())
    }

    private fun field(value: String): ByteArray {
        val body = value.toByteArray(StandardCharsets.UTF_8)
        return integer(body.size) + body
    }
    private fun integer(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it) }
}
