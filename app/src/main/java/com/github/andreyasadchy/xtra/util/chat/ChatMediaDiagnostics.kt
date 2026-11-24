package com.github.andreyasadchy.xtra.util.chat

import android.content.Context
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.repository.ChatterinoPlusRepository
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class ChatMediaDiagnostics(private val context: Context, private val network: ChatMediaNetwork) {
    suspend fun run(): String = withContext(Dispatchers.IO) {
        val proxy = ChatProxyPreferences.isEnabled(context)
        if (proxy && ChatProxyPreferences.configuration(context) == null) {
            return@withContext context.getString(R.string.chat_proxy_invalid)
        }
        val route = context.getString(if (proxy) R.string.chat_check_route_proxy else R.string.chat_check_route_direct)
        val rows = coroutineScope {
            listOf(
                async {
                    probe("Twitch IRC") {
                        checkIrc()
                        200
                    }
                },
                async { provider("7TV", "https://7tv.io/v3/emote-sets/global") { json ->
                    val host = JSONObject(json).getJSONArray("emotes").getJSONObject(0)
                        .getJSONObject("data").getJSONObject("host").getString("url")
                    "${if (host.startsWith("//")) "https:" else ""}$host/1x.webp"
                } },
                async { provider("BTTV", "https://api.betterttv.net/3/cached/emotes/global") { json ->
                    val id = JSONArray(json).getJSONObject(0).getString("id")
                    "https://cdn.betterttv.net/emote/$id/1x"
                } },
                async { provider("FFZ", "https://api.frankerfacez.com/v1/set/global") { json ->
                    val sets = JSONObject(json).getJSONObject("sets")
                    val urls = sets.getJSONObject(sets.keys().next()).getJSONArray("emoticons")
                        .getJSONObject(0).getJSONObject("urls")
                    val url = urls.getString("1")
                    if (url.startsWith("//")) "https:$url" else url
                } },
                async { provider("Chatterino+", ChatterinoPlusRepository.ROSTER_URL) { json ->
                    ChatterinoPlusRepository.parseRoster(JSONObject(json)).sampleBadgeUrl()
                } },
            ).awaitAll()
        }
        "$route\n\n${rows.joinToString("\n")}"
    }

    private suspend fun provider(
        name: String,
        endpoint: String,
        image: (String) -> String?,
    ): String {
        var url: String? = null
        val catalog = probe(context.getString(R.string.chat_check_catalog, name)) {
            request(endpoint) { response ->
                if (response.isSuccessful) {
                    val body = response.body.source()
                    if (!body.request(2_097_153)) url = image(body.readUtf8())
                }
                response.code
            }
        }
        val target = url?.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
        val imageLabel = context.getString(R.string.chat_check_image, name)
        val asset = if (target == null || !isAssetHost(target)) {
            "$imageLabel  ·  ${context.getString(R.string.chat_check_no_sample)}"
        } else {
            probe(imageLabel) {
                request(target.toString()) { response ->
                    if (response.isSuccessful && (response.body.contentType()?.type != "image" || !response.body.source().request(1))) {
                        return@request null
                    }
                    response.code
                }
            }
        }
        return "$catalog\n$asset"
    }

    private fun checkIrc() {
        val host = ChatUtils.IRC_HOST
        val proxy = ChatProxyPreferences.configuration(context).takeIf { ChatProxyPreferences.isEnabled(context) }
        if (proxy != null) {
            proxy.openSocket(host, 6697, tls = true).close()
        } else {
            (SSLSocketFactory.getDefault().createSocket() as SSLSocket).use { socket ->
                socket.connect(InetSocketAddress(host, 6697), 8_000)
                socket.soTimeout = 8_000
                socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                socket.startHandshake()
            }
        }
    }

    private suspend fun request(url: String, read: (okhttp3.Response) -> Int?): Int? {
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        val call = network.calls.newCall(request)
        call.timeout().timeout(12, TimeUnit.SECONDS)
        return call.executeAsync().use(read)
    }

    private suspend fun probe(label: String, run: suspend () -> Int?): String {
        val status = try {
            when (val code = run()) {
                in 200..299 -> context.getString(R.string.chat_check_ok)
                null -> context.getString(R.string.chat_check_invalid_response)
                else -> context.getString(R.string.chat_check_http, code)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val causes = generateSequence<Throwable>(e) { it.cause }.take(8).toList()
            context.getString(when {
                causes.any { it.message?.contains("proxy auth", ignoreCase = true) == true } -> R.string.chat_check_auth
                causes.any { it is SSLException } -> R.string.chat_check_tls
                causes.any { it is java.net.SocketTimeoutException || it is java.io.InterruptedIOException } -> R.string.chat_check_timeout
                e is org.json.JSONException -> R.string.chat_check_invalid_response
                else -> R.string.chat_check_unreachable
            })
        }
        return "$label  ·  $status"
    }

    private fun isAssetHost(url: HttpUrl) = url.host in setOf(
        "cdn.7tv.app", "cdn.7tv.io", "cdn.betterttv.net", "cdn.frankerfacez.com",
        "chat.eblo.id", "ws.chat.eblo.id",
    )
}
