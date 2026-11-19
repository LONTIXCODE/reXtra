package com.github.andreyasadchy.xtra.util.chat

import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.Headers
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.github.andreyasadchy.xtra.BuildConfig
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

fun chatGlideUrl(url: String?, thirdParty: Boolean): ChatGlideUrl? =
    url?.takeIf { it.isNotBlank() }?.let {
        val headers = if (thirdParty) {
            Headers { mapOf("User-Agent" to "Xtra/" + BuildConfig.VERSION_NAME) }
        } else Headers.DEFAULT
        ChatGlideUrl(GlideUrl(it, headers))
    }
