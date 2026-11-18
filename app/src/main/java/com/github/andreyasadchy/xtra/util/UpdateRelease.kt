package com.github.andreyasadchy.xtra.util

import android.content.Context
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

private const val REXTRA_FEED = "https://api.github.com/repos/LONTIXCODE/reXtra/releases/latest"
private const val OLD_XTRA_FEED = "https://api.github.com/repos/crackededed/xtra/releases/tags/latest"
private const val APK_TYPE = "application/vnd.android.package-archive"
private val releaseVersion = Regex("""v?(\d+)\.(\d+)\.(\d+)""")

fun Context.updateCheckUrl(): String {
    val preferences = prefs()
    val saved = preferences.getString(C.UPDATE_URL, null)?.trim()
    val default = getString(R.string.rextra_update_feed)
    if (saved.equals(OLD_XTRA_FEED, ignoreCase = true)) {
        preferences.edit { putString(C.UPDATE_URL, default) }
        return default
    }
    return saved?.takeIf { it.isNotEmpty() } ?: default
}

data class UpdateAsset(val url: String, val size: Long?)

fun findUpdateAsset(release: JsonObject, feed: String, lastChecked: Long): UpdateAsset? {
    val isRextra = feed.equals(REXTRA_FEED, ignoreCase = true)
    if (isRextra) {
        val installed = parseVersion(BuildConfig.VERSION_NAME.removeSuffix("-DEBUG")) ?: return null
        val published = parseVersion(release.text("tag_name")) ?: return null
        if (published <= installed) return null
    }
    val assets = release["assets"] as? JsonArray ?: return null
    return assets.filterIsInstance<JsonObject>().firstNotNullOfOrNull { asset ->
        if (asset.text("content_type") != APK_TYPE) return@firstNotNullOfOrNull null
        if (!isRextra) {
            val updatedAt = asset.text("updated_at")?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
                ?: return@firstNotNullOfOrNull null
            if (updatedAt <= 0 || updatedAt <= lastChecked) return@firstNotNullOfOrNull null
        }
        val url = asset.text("browser_download_url")?.takeIf { it.isNotBlank() } ?: return@firstNotNullOfOrNull null
        UpdateAsset(url, (asset["size"] as? JsonPrimitive)?.longOrNull)
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun parseVersion(tag: String?): Version? {
    val groups = releaseVersion.matchEntire(tag.orEmpty())?.groupValues ?: return null
    return Version(
        groups[1].toIntOrNull() ?: return null,
        groups[2].toIntOrNull() ?: return null,
        groups[3].toIntOrNull() ?: return null,
    )
}

private data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version) = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
}
