package com.quranroots.app.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads and caches Husary recitation audio per ayah.
 *
 * Source: https://everyayah.com/data/Husary_128kbps/
 *   File pattern: SSSAAA.mp3  (zero-padded, e.g. 001001.mp3 = Surah 1 Ayah 1)
 *
 * Files are stored in the app's cache directory; missing files are fetched
 * once on first play and re-used afterwards.
 */
class AudioCache(context: Context) {

    private val dir: File = File(context.cacheDir, "husary").apply { mkdirs() }
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun fileFor(surah: Int, ayah: Int): File {
        val name = "%03d%03d.mp3".format(surah, ayah)
        return File(dir, name)
    }

    fun localFile(surah: Int, ayah: Int): File? {
        val f = fileFor(surah, ayah)
        return if (f.exists() && f.length() > 0) f else null
    }

    suspend fun ensureDownloaded(surah: Int, ayah: Int): File = withContext(Dispatchers.IO) {
        val f = fileFor(surah, ayah)
        if (f.exists() && f.length() > 0) return@withContext f
        val url = BASE + "%03d%03d.mp3".format(surah, ayah)
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("empty body")
            val tmp = File(dir, f.name + ".part")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            if (!tmp.renameTo(f)) tmp.copyTo(f, overwrite = true).also { tmp.delete() }
        }
        f
    }

    companion object {
        private const val BASE = "https://everyayah.com/data/Husary_128kbps/"
    }
}
