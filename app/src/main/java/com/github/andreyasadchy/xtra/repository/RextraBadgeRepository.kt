package com.github.andreyasadchy.xtra.repository

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.chat.ChatMediaNetwork
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RextraBadgeRepository(private val context: Context, private val network: ChatMediaNetwork) {
    data class Badge(val title: String, val url: String)

    private val lock = Mutex()
    private val cache = AtomicFile(File(context.noBackupFilesDir, "rextra-badges-cache.json"))
    private val badges = MutableStateFlow<Map<String, List<Badge>>>(emptyMap())
    val assignments = badges.asStateFlow()
    private var lastSource: String? = null
    private var etag: String? = null
    private var nextRefresh = 0L

    suspend fun refresh() = withContext(Dispatchers.IO) {
        lock.withLock {
            val source = httpsUrl(context.prefs().getString(SOURCE, null)?.trim())?.toString() ?: DEFAULT_SOURCE
            if (lastSource != source) {
                lastSource = source
                nextRefresh = 0
                etag = null
                badges.value = readCache(source)
            }
            if (SystemClock.elapsedRealtime() < nextRefresh) return@withLock
            nextRefresh = SystemClock.elapsedRealtime() + RETRY_INTERVAL_MS
            try {
                val request = Request.Builder().url(source).header("Cache-Control", "no-cache").apply {
                    etag?.let { header("If-None-Match", it) }
                }.build()
                network.calls.newCall(request).executeAsync().use { response ->
                    if (response.code == 304) {
                        nextRefresh = SystemClock.elapsedRealtime() + REFRESH_INTERVAL_MS
                        return@use
                    }
                    if (!response.isSuccessful) return@use
                    val body = response.body.source()
                    if (body.request(524_289)) return@use
                    val registry = JSONObject(body.readUtf8())
                    badges.value = parse(registry, source)
                    etag = response.header("ETag")
                    nextRefresh = SystemClock.elapsedRealtime() + REFRESH_INTERVAL_MS
                    storeCache(source, registry)
                }
            } catch (e: CancellationException) {
                nextRefresh = 0
                throw e
            } catch (_: Exception) {
                // Keep the last valid assignments when the configured registry is unavailable.
            }
        }
    }

    private fun readCache(source: String): Map<String, List<Badge>> = runCatching {
        val saved = JSONObject(cache.readFully().decodeToString())
        if (saved.getString("source") != source) return@runCatching emptyMap()
        parse(saved.getJSONObject("registry"), source).also {
            etag = saved.optString("etag").takeIf { value -> value.isNotBlank() }
        }
    }.getOrDefault(emptyMap())

    private fun storeCache(source: String, registry: JSONObject) {
        val data = JSONObject().put("source", source).put("etag", etag).put("registry", registry)
        val stream = runCatching { cache.startWrite() }.getOrNull() ?: return
        try {
            stream.write(data.toString().encodeToByteArray())
            cache.finishWrite(stream)
        } catch (_: java.io.IOException) {
            cache.failWrite(stream)
        }
    }

    companion object {
        const val SOURCE = "chat_rextra_badge_registry"
        const val ENABLED = "chat_rextra_badges_enabled"
        const val DEFAULT_SOURCE = "https://raw.githubusercontent.com/LONTIXCODE/.github/main/CDN/badges/badges.json"
        private const val REFRESH_INTERVAL_MS = 2 * 60_000L
        private const val RETRY_INTERVAL_MS = 60_000L

        fun httpsUrl(value: String?) = value?.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.username.isEmpty() && it.password.isEmpty()
        }

        internal fun parse(json: JSONObject, source: String): Map<String, List<Badge>> {
            val registry = httpsUrl(source) ?: return emptyMap()
            return json.keys().asSequence().filter {
                it.length in 1..24 && it.all { digit -> digit in '0'..'9' }
            }.mapNotNull { userId ->
                val badges = when (val entry = json.opt(userId)) {
                    is JSONArray -> (0 until entry.length()).asSequence().mapNotNull { index ->
                        parseBadge(entry.opt(index), registry)
                    }.take(4).toList()
                    else -> listOfNotNull(parseBadge(entry, registry))
                }
                badges.takeIf { it.isNotEmpty() }?.let { userId to it }
            }.toMap()
        }

        private fun parseBadge(entry: Any?, registry: HttpUrl): Badge? {
            val address = when (entry) {
                is String -> entry
                is JSONObject -> entry.optString("url", "")
                else -> return null
            }
            val name = address.trim()
            val url = httpsUrl(name) ?: name.takeIf {
                it.length in 5..180 && it.endsWith(".png", ignoreCase = true) &&
                    it.none { char -> char in "/\\?#" || char.isISOControl() }
            }?.let {
                registry.newBuilder().removePathSegment(registry.pathSize - 1)
                    .addPathSegment(it).query(null).fragment(null).build()
            } ?: return null
            val title = (entry as? JSONObject)?.optString("title", "")?.trim()?.take(80)
                ?.takeIf { it.isNotBlank() } ?: "reXtra"
            return Badge(title, url.toString())
        }
    }
}
