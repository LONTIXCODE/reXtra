package com.github.andreyasadchy.xtra.util.chat

import android.content.Context
import com.github.andreyasadchy.xtra.util.prefs
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal class StvImageCache(private val context: Context) : Interceptor {
    private val directory = File(context.filesDir, "7tv-channel-images")
    private val lock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val channel = request.header(CHANNEL_HEADER) ?: return chain.proceed(request)
        val clean = request.newBuilder().removeHeader(CHANNEL_HEADER).build()
        val format = clean.url.encodedPath.substringAfterLast('.')
        if (channelId(channel) == null || clean.url.host !in hosts ||
            !clean.url.encodedPath.startsWith("/emote/") || format !in formats
        ) return chain.proceed(clean)
        val key = MessageDigest.getInstance("SHA-256")
            .digest(clean.url.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(File(directory, channel), key)
        val stored = disk {
            trim(file.parentFile!!, channelLimit())
            if (!file.isFile) null else file.readBytes().also { file.setLastModified(System.currentTimeMillis()) }
        }
        if (stored != null) {
            return Response.Builder()
                .request(clean).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "image/$format")
                .body(stored.toResponseBody("image/$format".toMediaType())).build()
        }
        val response = chain.proceed(clean)
        if (response.isSuccessful && response.body.contentType()?.type == "image") {
            val sizeLimit = minOf(channelLimit(), 2 * MIB)
            if (response.body.contentLength() in -1..sizeLimit) {
                val bytes = try {
                    response.peekBody(sizeLimit + 1).bytes()
                } catch (e: IOException) {
                    response.close()
                    throw e
                }
                if (bytes.isNotEmpty() && bytes.size <= sizeLimit) {
                    disk {
                        val folder = file.parentFile!!
                        folder.mkdirs()
                        val temp = File.createTempFile("asset", ".tmp", folder)
                        try {
                            temp.writeBytes(bytes)
                            if (!file.exists()) temp.renameTo(file)
                        } finally {
                            temp.delete()
                        }
                        trim(folder, channelLimit())
                        trimTotal(excluded())
                    }
                }
            }
        }
        return response
    }

    fun clearUnprotected(): Int = disk {
        val protected = excluded()
        var count = 0
        directory.listFiles().orEmpty().filter { it.isDirectory && it.name !in protected }.forEach { folder ->
            count += folder.listFiles().orEmpty().count { it.isFile && it.delete() }
            folder.delete()
        }
        count
    } ?: 0

    fun applyLimit(mebibytes: Int) {
        disk { directory.listFiles().orEmpty().filter(File::isDirectory).forEach { trim(it, mebibytes * MIB) } }
    }

    private fun channelLimit(): Long = (context.prefs().getString(LIMIT, "16")?.toLongOrNull() ?: 16L)
        .coerceIn(1, 128) * MIB

    fun excluded() = context.prefs().getString(EXCLUDED, "").orEmpty().split(',', '\n', ' ')
        .mapNotNull(::channelId).toSet()

    private fun trim(folder: File, limit: Long) {
        val files = folder.listFiles().orEmpty().filter(File::isFile)
        removeOldest(files, files.sumOf(File::length) - limit)
    }

    private fun trimTotal(excluded: Set<String>) {
        val files = directory.listFiles().orEmpty().filter(File::isDirectory).flatMap { folder ->
            folder.listFiles().orEmpty().filter(File::isFile)
        }
        val unprotected = files.filter { it.parentFile?.name !in excluded }
        removeOldest(unprotected, unprotected.sumOf(File::length) - 256 * MIB)
    }

    private fun removeOldest(files: List<File>, excess: Long) {
        if (excess > 0) removeFiles(files.sortedBy(File::lastModified), excess)
    }

    private fun removeFiles(files: List<File>, excess: Long) {
        var remaining = excess
        for (file in files) {
            if (remaining <= 0) break
            val size = file.length()
            if (file.delete()) remaining -= size
        }
    }

    private fun <T> disk(block: () -> T): T? = synchronized(lock) {
        try {
            block()
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        const val CHANNEL_HEADER = "X-reXtra-7TV-Cache"
        const val LIMIT = "chat_stv_cache_limit"
        const val EXCLUDED = "chat_stv_cache_excluded"
        const val CLEAR = "chat_stv_cache_clear"
        private const val MIB = 1024L * 1024L
        private val hosts = setOf("cdn.7tv.app", "cdn.7tv.io")
        private val formats = setOf("png", "gif", "webp", "avif")

        fun channelId(value: String?): String? = value?.takeIf {
            it.length in 1..24 && it.all { digit -> digit in '0'..'9' }
        }

        fun applies(url: String?) = url?.let {
            hosts.any { host -> it.startsWith("https://$host/emote/") }
        } ?: false
    }
}
