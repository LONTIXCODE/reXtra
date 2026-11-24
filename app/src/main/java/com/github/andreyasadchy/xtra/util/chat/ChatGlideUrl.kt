package com.github.andreyasadchy.xtra.util.chat

import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.Headers
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.github.andreyasadchy.xtra.BuildConfig
import androidx.fragment.app.Fragment
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import okhttp3.Call
import java.io.InputStream

data class ChatGlideUrl(val url: GlideUrl) {
    class Factory(private val calls: Call.Factory) : ModelLoaderFactory<ChatGlideUrl, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<ChatGlideUrl, InputStream> {
            val loader = OkHttpUrlLoader(calls)
            return object : ModelLoader<ChatGlideUrl, InputStream> {
                override fun handles(model: ChatGlideUrl) = true

                override fun buildLoadData(model: ChatGlideUrl, width: Int, height: Int, options: Options) =
                    loader.buildLoadData(model.url, width, height, options)
            }
        }

        override fun teardown() = Unit
    }
}

fun chatGlideUrl(url: String?, thirdParty: Boolean, channelId: String? = null): ChatGlideUrl? =
    url?.takeIf { it.isNotBlank() }?.let {
        val headers = if (thirdParty || channelId != null && StvImageCache.applies(it)) {
            Headers {
                buildMap {
                    if (thirdParty) put("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                    if (channelId != null && StvImageCache.applies(it)) put(StvImageCache.CHANNEL_HEADER, channelId)
                }
            }
        } else Headers.DEFAULT
        ChatGlideUrl(GlideUrl(it, headers))
    }

internal fun Fragment.chatImageChannel(): String? {
    val chat = (this as? ChatFragment) ?: parentFragment as? ChatFragment
    return StvImageCache.channelId(chat?.chatChannelId)
}

internal fun ImageRequest.Builder.chatImageData(image: Any?, thirdParty: Boolean, channelId: String?) {
    data(image)
    val channel = channelId?.takeIf { StvImageCache.applies(image as? String) }
    if (channel != null) diskCachePolicy(CachePolicy.DISABLED)
    if (channel != null || thirdParty) {
        httpHeaders(NetworkHeaders.Builder().apply {
            if (thirdParty) add("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
            channel?.let { add(StvImageCache.CHANNEL_HEADER, it) }
        }.build())
    }
}
