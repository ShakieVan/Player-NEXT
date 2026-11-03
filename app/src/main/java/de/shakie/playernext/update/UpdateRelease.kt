package de.shakie.playernext.update

import org.json.JSONObject

/** A release offered for installation: only complete ones with exactly one matching APK, size and SHA-256. */
data class UpdateRelease(
    val version: String,
    val notes: String,
    val size: Long,
    val sha256: String,
    val url: String,
    val raw: String,
) {
    /** GitHub page of this release; also the fallback when the in-app update fails. */
    val pageUrl: String
        get() = url.substringBeforeLast('/').replace("/releases/download/", "/releases/tag/")

    companion object {
        const val REPOSITORY = "ShakieVan/Player-NEXT"
        const val LATEST_ENDPOINT = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases/latest"
        private const val MAX_APK_BYTES = 256L * 1024 * 1024

        fun assetName(version: String) = "Player-NEXT-$version.apk"

        /**
         * Same rules as Mau-Mau Flip: tag "vX.Y.Z", no draft or pre-release, exactly one asset
         * named Player-NEXT-X.Y.Z.apk with size and "sha256:" digest, download URL on this
         * repository's release. Anything else yields null.
         */
        fun parse(json: String): UpdateRelease? = try {
            val data = JSONObject(json)
            parse(data)
        } catch (_: Exception) {
            null
        }

        private fun parse(data: JSONObject): UpdateRelease? {
            if (data.optBoolean("draft", true) || data.optBoolean("prerelease", true)) return null
            val tag = data.optString("tag_name")
            if (!tag.startsWith("v") || !Versions.isValid(tag.substring(1))) return null
            val version = tag.substring(1)
            val name = assetName(version)
            val assets = data.optJSONArray("assets") ?: return null
            val matches = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.filter { it.optString("name") == name }
            val asset = matches.singleOrNull() ?: return null
            val size = asset.optLong("size", 0)
            val digest = asset.optString("digest")
            val url = asset.optString("browser_download_url")
            val hex = digest.removePrefix("sha256:")
            if (size <= 0 || size > MAX_APK_BYTES || !digest.startsWith("sha256:") || hex.length != 64 ||
                !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            ) return null
            if (url != "https://github.com/$REPOSITORY/releases/download/$tag/$name") return null
            return UpdateRelease(version, data.optString("body").take(6000), size, hex.lowercase(), url, data.toString())
        }
    }
}

object Versions {
    fun isValid(text: String): Boolean {
        val parts = text.split('.')
        return parts.size == 3 && parts.all { p -> p.isNotEmpty() && p.length <= 6 && p.all(Char::isDigit) }
    }

    /** Numeric per part (0.10.0 > 0.9.9): positive when [a] is newer. */
    fun compare(a: String, b: String): Int {
        val x = a.substringBefore('-').split('.')
        val y = b.substringBefore('-').split('.')
        for (i in 0 until 3) {
            val xi = x.getOrNull(i)?.toIntOrNull() ?: 0
            val yi = y.getOrNull(i)?.toIntOrNull() ?: 0
            if (xi != yi) return xi.compareTo(yi)
        }
        return 0
    }
}
