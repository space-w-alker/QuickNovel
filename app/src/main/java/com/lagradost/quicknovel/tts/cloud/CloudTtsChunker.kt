package com.lagradost.quicknovel.tts.cloud

object CloudTtsChunker {
    fun split(text: String, maximum: Int): List<String> {
        require(maximum > 0)
        if (text.length <= maximum) return listOf(text)
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val hardEnd = (start + maximum).coerceAtMost(text.length)
            if (hardEnd == text.length) {
                result += text.substring(start)
                break
            }
            val window = text.substring(start, hardEnd)
            val boundary = listOf(
                window.indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' },
                window.indexOfLast { it == ',' || it == ';' || it == ':' },
                window.indexOfLast { it.isWhitespace() },
            ).firstOrNull { it >= maximum / 3 }?.plus(1) ?: maximum
            result += text.substring(start, start + boundary).trim()
            start += boundary
            while (start < text.length && text[start].isWhitespace()) start++
        }
        return result.filter { it.isNotBlank() }
    }
}
