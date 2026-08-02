package com.lagradost.quicknovel.tts.cloud

import android.content.Context
import java.io.File

class CloudTtsDiskCache(context: Context, private val maxBytes: Long = 5L * 1024 * 1024 * 1024) {
    private val directory = File(context.cacheDir, "cloud_tts_audio").apply { mkdirs() }

    @Synchronized fun get(cacheKey: String): File? = file(cacheKey).takeIf { it.isFile }?.also {
        it.setLastModified(System.currentTimeMillis())
    }

    @Synchronized fun put(cacheKey: String, bytes: ByteArray): File {
        val target = file(cacheKey)
        val temporary = File(directory, "$cacheKey.tmp")
        temporary.outputStream().use { it.write(bytes) }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        trim()
        return target
    }

    private fun file(cacheKey: String): File {
        require(cacheKey.matches(Regex("[a-fA-F0-9]{32,128}"))) { "Invalid Cloud TTS cache key" }
        return File(directory, "$cacheKey.mp3")
    }

    private fun trim() {
        val files = directory.listFiles()?.filter { it.extension == "mp3" }
            ?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (cached in files) {
            if (total <= maxBytes) break
            total -= cached.length()
            cached.delete()
        }
    }
}
