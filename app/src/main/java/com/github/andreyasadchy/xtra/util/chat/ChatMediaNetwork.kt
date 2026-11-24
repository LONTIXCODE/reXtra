package com.github.andreyasadchy.xtra.util.chat

import android.content.Context
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.imageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.coil.CacheControlCacheStrategy
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.IOException

class ChatMediaNetwork(private val context: Context, baseClient: Lazy<OkHttpClient>) {
    internal val stvCache = StvImageCache(context)
    private val direct by lazy {
        baseClient.value.newBuilder().apply { interceptors().clear() }
            .addInterceptor(stvCache).build()
    }
    private val invalidProxy by lazy {
        direct.newBuilder().addInterceptor { throw invalidConfiguration() }.build()
    }
    private var lastSettings: ChatProxyPreferences.Settings? = null
    private var lastClient: OkHttpClient? = null

    @Synchronized
    fun proxyClient(): OkHttpClient? {
        if (!ChatProxyPreferences.isEnabled(context)) {
            lastSettings = null
            lastClient = null
            return null
        }
        val settings = ChatProxyPreferences.settings(context)
        if (lastSettings == settings) return lastClient
        val proxy = settings.create() ?: throw invalidConfiguration()
        return proxy.client(direct).also {
            lastSettings = settings
            lastClient = it
        }
    }

    private fun invalidConfiguration() = IOException(context.getString(R.string.chat_proxy_invalid))

    val calls = Call.Factory { request ->
        val client = try {
            proxyClient() ?: direct
        } catch (_: IOException) {
            invalidProxy
        }
        client.newCall(request)
    }

    @OptIn(ExperimentalCoilApi::class)
    val imageLoader by lazy {
        ImageLoader.Builder(context)
            .diskCache { context.imageLoader.diskCache }
            .memoryCache { context.imageLoader.memoryCache }
            .components {
                add(OkHttpNetworkFetcherFactory(
                    callFactory = { calls },
                    cacheStrategy = { CacheControlCacheStrategy() },
                ))
            }
            .build()
    }
}

fun Context.chatImageLoader(channelId: String? = null): ImageLoader =
    if (ChatProxyPreferences.isEnabled(this) || channelId != null) {
        (applicationContext as XtraApp).xtraModule.chatMediaNetwork.imageLoader
    } else imageLoader
