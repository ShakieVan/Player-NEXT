package de.shakie.playernext.update

import org.json.JSONArray
import org.json.JSONObject

/** A release offered for installation: only complete ones with exactly one matching APK, size and SHA-256. */
data class UpdateRelease(
    val version: String,
    val notes: String,
    val size: Long,
    val sha256: String,
    val url: String,
    /** Test version (from the beta repository or marked as pre-release). */
    val beta: Boolean,
    val raw: String,
) {
    /** GitHub page of this release; also the fallback when the in-app update fails. */
    val pageUrl: String
        get() = url.substringBeforeLast('/').replace("/releases/download/", "/releases/tag/")

    companion object {
        const val REPOSITORY = "ShakieVan/Player-NEXT"
        const val LATEST_ENDPOINT = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases/latest"
        // Beta channel like Mau-Mau Flip: test versions live in their own repository. With the
        // channel on, both sources are asked and the highest version wins.
        const val BETA_REPOSITORY = "ShakieVan/Player-NEXT-Beta"
        const val BETA_ENDPOINT = "https://api.github.com/repos/$BETA_REPOSITORY/releases?per_page=20"
        const val BETA_RELEASES_PAGE = "https://github.com/$BETA_REPOSITORY/releases"
        private const val MAX_APK_BYTES = 256L * 1024 * 1024

        fun assetName(version: String) = "Player-NEXT-$version.apk"

        /**
         * Same rules as Mau-Mau Flip: tag "vX.Y.Z", no draft, exactly one asset named
         * Player-NEXT-X.Y.Z.apk with size and "sha256:" digest, download URL on the release of
         * this repository. Pre-releases and anything from the beta repository only with
         * [allowBeta]. A list (beta channel) yields its highest version. Anything else yields null.
         */
        fun parse(json: String, allowBeta: Boolean = false): UpdateRelease? = try {
            if (json.trimStart().startsWith("[")) {
                val list = JSONArray(json)
                (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let { o -> parse(o, allowBeta) } }
                    .reduceOrNull { a, b -> if (Versions.compare(b.version, a.version) > 0) b else a }
            } else {
                parse(JSONObject(json), allowBeta)
            }
        } catch (_: Exception) {
            null
        }

        private fun parse(data: JSONObject, allowBeta: Boolean): UpdateRelease? {
            if (data.optBoolean("draft", true) || (data.optBoolean("prerelease", true) && !allowBeta)) return null
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
            val fromBeta = url == "https://github.com/$BETA_REPOSITORY/releases/download/$tag/$name"
            if (url != "https://github.com/$REPOSITORY/releases/download/$tag/$name" && !(fromBeta && allowBeta)) return null
            val beta = fromBeta || data.optBoolean("prerelease", false)
            return UpdateRelease(version, data.optString("body").take(6000), size, hex.lowercase(), url, beta, data.toString())
        }
    }
}

object Versions {
    /** Regular releases end in .0; X.Y.Z with Z ≠ 0 is a beta (then the beta channel is on by default). */
    fun isBeta(version: String): Boolean = isValid(version) && version.substringAfterLast('.').toInt() != 0

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
