package com.github.andreyasadchy.xtra.repository

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.chat.ChatMediaNetwork
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class SharedBansRepository(
    private val context: Context,
    private val baseClient: Lazy<OkHttpClient>,
    private val mediaNetwork: ChatMediaNetwork,
) {
    data class ProfileImages(val avatar: String?, val banner: String?)
    data class Ban(val channel: String, val since: Long, val lifted: Boolean)
    enum class Problem { ACCOUNT, SIGN_IN, DENIED, EXPIRED, LIMIT, CONNECTION }
    class Failure(val problem: Problem) : Exception()
    private data class Cached(val bans: List<Ban>, val time: Long)
    private val cache = mutableMapOf<String, Cached>()

    private val account get() = context.tokenPrefs().getString(C.USER_ID, null)?.takeIf(ID::matches)
    private val preferences get() = context.tokenPrefs()

    fun signedIn() = currentTicket() != null

    fun disconnect() {
        preferences.edit { remove(SESSION) }
        synchronized(cache) { cache.clear() }
    }

    fun cached(userId: String): List<Ban>? {
        val owner = account ?: return null
        if (!signedIn()) return null
        return synchronized(cache) {
            cache["$owner/$userId"]?.takeIf { SystemClock.elapsedRealtime() - it.time < 30_000 }?.bans
        }
    }

    suspend fun signIn(openBrowser: (String) -> Unit) {
        val owner = account ?: throw Failure(Problem.ACCOUNT)
        val client = client()
        val (code, start) = request(client, "begin", "uid", owner)
        if (code == 429) throw Failure(Problem.LIMIT)
        val state = start?.optString("state").orEmpty()
        val uri = start?.optString("url")?.toHttpUrlOrNull()
        if (code != 200 || state.isBlank() || uri == null || !uri.isHttps ||
            uri.username.isNotEmpty() || uri.password.isNotEmpty() ||
            uri.host != "twitch.tv" && !uri.host.endsWith(".twitch.tv")
        ) throw Failure(Problem.CONNECTION)
        openBrowser(uri.toString())
        val deadline = SystemClock.elapsedRealtime() + start.optLong("wait", 900).coerceIn(30, 900) * 1_000
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(2_000)
            if (account != owner) throw Failure(Problem.ACCOUNT)
            val (status, result) = request(client, "poll", "state", state)
            if (status == 404 || status == 410) throw Failure(Problem.EXPIRED)
            if (status == 429) delay(3_000)
            if (status != 200 || result == null) continue
            if (result.optBoolean("denied")) throw Failure(Problem.DENIED)
            val ticket = result.optString("ticket")
            if (ticket.isBlank()) continue
            val payload = decode(ticket)
            val returned = result.optString("uid").ifBlank { payload?.optString("u").orEmpty() }
            val expiry = result.optLong("exp")
            if (returned != owner || payload?.optString("u")?.takeIf { it.isNotBlank() }?.let { it != owner } == true ||
                expiry <= System.currentTimeMillis() / 1_000 || account != owner
            ) throw Failure(Problem.DENIED)
            preferences.edit {
                putString(SESSION, JSONObject().put("uid", owner).put("ticket", ticket).put("exp", expiry).toString())
            }
            return
        }
        throw Failure(Problem.EXPIRED)
    }

    suspend fun lookup(userId: String, channelId: String?, channelLogin: String?): List<Ban> {
        require(ID.matches(userId))
        val owner = account ?: throw Failure(Problem.ACCOUNT)
        val ticket = currentTicket() ?: throw Failure(Problem.SIGN_IN)
        cached(userId)?.let { return it }
        val data = exchange(ticket,
            onHello = { socket, frame ->
                if (!frame.optBoolean("verified")) throw Failure(Problem.SIGN_IN)
                if (channelId != null && ID.matches(channelId) &&
                    channelLogin != null && LOGIN.matches(channelLogin)
                ) {
                    socket.send(JSONObject().put("op", "sbwatch").put("room", channelId)
                        .put("login", channelLogin).toString())
                }
                socket.send(JSONObject().put("op", "sbget").put("u", userId).toString())
            },
            read = { frame ->
                when (frame.optString("op")) {
                    "sbdoc" -> if (frame.optString("u") == userId) parseBans(frame) else null
                    "sberr" -> {
                        if (frame.optString("u") == userId && frame.optString("code") == "doc_limit") {
                            throw Failure(Problem.LIMIT)
                        }
                        null
                    }
                    "authstale" -> throw Failure(Problem.SIGN_IN)
                    else -> null
                }
            },
        )
        if (account != owner || currentTicket() != ticket) throw Failure(Problem.ACCOUNT)
        synchronized(cache) {
            if (cache.size >= 64) cache.remove(cache.keys.first())
            cache["$owner/$userId"] = Cached(data, SystemClock.elapsedRealtime())
        }
        return data
    }

    suspend fun profileImages(login: String, userId: String?): ProfileImages {
        if (!LOGIN.matches(login)) return ProfileImages(null, null)
        val requested = login.lowercase()
        return exchange("",
            onHello = { socket, _ ->
                socket.send(JSONObject().put("op", "c7who").put("logins", org.json.JSONArray().put(requested)).toString())
            },
            read = { frame ->
                val asked = frame.optJSONArray("asked")
                if (frame.optString("op") != "c7who" || asked == null ||
                    (0 until asked.length()).none { asked.optString(it).equals(requested, true) }
                ) {
                    null
                } else {
                    val profiles = frame.optJSONArray("plus")
                    val profile = (0 until (profiles?.length() ?: 0)).asSequence().mapNotNull { profiles?.optJSONObject(it) }
                        .firstOrNull {
                            it.optString("login").equals(requested, true) &&
                                (userId == null || it.optString("uid").let { id -> id.isBlank() || id == userId })
                        }
                    ProfileImages(
                        profile?.optString("avatar")?.let { value -> ChatterinoPlusRepository.imageUrl(value) },
                        profile?.optString("banner")?.let { value -> ChatterinoPlusRepository.imageUrl(value) },
                    )
                }
            },
        )
    }

    private suspend fun <T : Any> exchange(
        ticket: String,
        onHello: (WebSocket, JSONObject) -> Unit,
        read: (JSONObject) -> T?,
    ): T = withTimeout(15_000) {
        suspendCancellableCoroutine { continuation ->
            val socket = client().newWebSocket(Request.Builder().url("wss://ws.chat.eblo.id/").build(),
                object : WebSocketListener() {
                    var ready = false
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val id = UUID.randomUUID().toString().replace("-", "").take(16)
                        webSocket.send(JSONObject().put("op", "hello").put("v", 2).put("ticket", ticket)
                            .put("lid", id).put("cn", 1).put("why", "start").toString())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (!continuation.isActive || text.length > 512_000) return
                        val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                        try {
                            when (frame.optString("op")) {
                                "hello" -> if (!ready) {
                                    onHello(webSocket, frame)
                                    ready = true
                                }
                                "busy" -> throw Failure(Problem.CONNECTION)
                                else -> if (ready) read(frame)?.let { continuation.resume(it) }
                            }
                        } catch (e: Failure) {
                            if (e.problem == Problem.SIGN_IN && currentTicket() == ticket) disconnect()
                            continuation.resumeWithException(e)
                        }
                        if (!continuation.isActive) webSocket.close(1000, null)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (continuation.isActive) continuation.resumeWithException(Failure(Problem.CONNECTION))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (continuation.isActive) continuation.resumeWithException(Failure(Problem.CONNECTION))
                    }
                })
            continuation.invokeOnCancellation { socket.cancel() }
        }
    }

    private fun currentTicket(): String? {
        val uid = account ?: return null
        val stored = preferences.getString(SESSION, null) ?: return null
        return runCatching {
            val session = JSONObject(stored)
            if (session.optString("uid") == uid && session.optLong("exp") > System.currentTimeMillis() / 1_000 + 30)
                session.optString("ticket").takeIf { it.isNotBlank() }
            else null
        }.getOrNull()
    }

    private fun client(): OkHttpClient = (mediaNetwork.proxyClient() ?: baseClient.value).newBuilder()
        .apply { interceptors().clear() }.cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).callTimeout(10, TimeUnit.SECONDS).build()

    private suspend fun request(client: OkHttpClient, action: String, key: String, value: String) =
        withContext(Dispatchers.IO) {
            val endpoint = "https://ws.chat.eblo.id/auth/$action".toHttpUrl().newBuilder().addQueryParameter(key, value).build()
            client.newCall(Request.Builder().url(endpoint).header("Cache-Control", "no-store").build()).executeAsync()
                .use { response ->
                    val source = response.body.source()
                    val json = if (source.request(65_537)) null
                        else runCatching { JSONObject(source.readUtf8()) }.getOrNull()
                    response.code to json
                }
        }

    companion object {
        private val ID = Regex("[0-9]{1,24}")
        private val LOGIN = Regex("[A-Za-z0-9_]{1,25}")
        private const val SESSION = "chatterino_plus_shared_bans_session"

        private fun decode(ticket: String) = runCatching {
            JSONObject(Base64.decode(ticket.substringBefore("."), Base64.URL_SAFE or Base64.NO_WRAP).decodeToString())
        }.getOrNull()

        internal fun parseBans(frame: JSONObject): List<Ban> {
            val items = frame.optJSONArray("items") ?: return emptyList()
            return (0 until minOf(items.length(), 300)).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                val name = item.optString("login").takeIf(LOGIN::matches)
                    ?: item.optString("room").takeIf(ID::matches) ?: return@mapNotNull null
                Ban(name, item.optLong("since").coerceIn(0, 40_000_000_000), item.optLong("selfclaim_at") > 0)
            }.sortedByDescending { it.since }
        }
    }
}
