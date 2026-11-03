package de.shakie.playernext.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateReleaseTest {
    private val sha = "ab".repeat(32)

    private fun release(
        tag: String = "v1.2.0",
        name: String = "Player-NEXT-1.2.0.apk",
        digest: String? = "sha256:$sha",
        size: Long = 7_000_000,
        url: String = "https://github.com/ShakieVan/Player-NEXT/releases/download/$tag/$name",
        draft: Boolean = false,
        prerelease: Boolean = false,
        extraAsset: Boolean = false,
    ): String {
        val asset = JSONObject().put("name", name).put("size", size).put("browser_download_url", url)
        if (digest != null) asset.put("digest", digest)
        val assets = JSONArray().put(asset)
        if (extraAsset) assets.put(JSONObject(asset.toString()))
        return JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", prerelease)
            .put("body", "Neu: Widget").put("assets", assets).toString()
    }

    @Test
    fun acceptsACompleteRelease() {
        val r = UpdateRelease.parse(release())
        assertNotNull(r)
        assertEquals("1.2.0", r!!.version)
        assertEquals(sha, r.sha256)
        assertEquals(7_000_000L, r.size)
        assertEquals("https://github.com/ShakieVan/Player-NEXT/releases/tag/v1.2.0", r.pageUrl)
    }

    @Test
    fun rejectsIncompleteOrForeignReleases() {
        assertNull(UpdateRelease.parse(release(digest = null)))
        assertNull(UpdateRelease.parse(release(digest = "sha256:1234")))
        assertNull(UpdateRelease.parse(release(digest = "md5:$sha")))
        assertNull(UpdateRelease.parse(release(size = 0)))
        assertNull(UpdateRelease.parse(release(draft = true)))
        assertNull(UpdateRelease.parse(release(prerelease = true)))
        assertNull(UpdateRelease.parse(release(tag = "1.2.0")))
        assertNull(UpdateRelease.parse(release(tag = "v1.2")))
        assertNull(UpdateRelease.parse(release(name = "Player-NEXT-1.2.1.apk")))
        assertNull(UpdateRelease.parse(release(url = "https://example.com/Player-NEXT-1.2.0.apk")))
        assertNull(UpdateRelease.parse(release(extraAsset = true)))
        assertNull(UpdateRelease.parse("kein json"))
        assertNull(UpdateRelease.parse(""))
    }

    private fun betaRelease(version: String, prerelease: Boolean = true) =
        release(tag = "v$version", name = "Player-NEXT-$version.apk", prerelease = prerelease,
            url = "https://github.com/ShakieVan/Player-NEXT-Beta/releases/download/v$version/Player-NEXT-$version.apk")

    @Test
    fun betaChannelOnlyWhenAllowed() {
        assertNull(UpdateRelease.parse(betaRelease("1.1.1")))
        assertNull(UpdateRelease.parse(betaRelease("1.1.1", prerelease = false)))
        val b = UpdateRelease.parse(betaRelease("1.1.1"), allowBeta = true)
        assertNotNull(b)
        assertTrue(b!!.beta)
        assertEquals("https://github.com/ShakieVan/Player-NEXT-Beta/releases/tag/v1.1.1", b.pageUrl)
        // A pre-release in the main repository counts as beta too; a regular release does not.
        assertTrue(UpdateRelease.parse(release(prerelease = true), allowBeta = true)!!.beta)
        assertTrue(!UpdateRelease.parse(release(), allowBeta = true)!!.beta)
        // Other repositories stay out, also in the beta channel.
        assertNull(UpdateRelease.parse(release(url = "https://github.com/Someone/Player-NEXT-Beta/releases/download/v1.2.0/Player-NEXT-1.2.0.apk"), allowBeta = true))
    }

    @Test
    fun betaListYieldsTheHighestVersion() {
        val list = "[" + listOf(betaRelease("1.1.1"), betaRelease("1.1.3"), betaRelease("1.1.2"), release(draft = true, tag = "v1.1.9", name = "Player-NEXT-1.1.9.apk")).joinToString(",") + "]"
        assertEquals("1.1.3", UpdateRelease.parse(list, allowBeta = true)!!.version)
        assertNull(UpdateRelease.parse(list))
        assertNull(UpdateRelease.parse("[]", allowBeta = true))
    }

    @Test
    fun betaVersionsDoNotEndInZero() {
        assertTrue(Versions.isBeta("1.1.1"))
        assertTrue(Versions.isBeta("1.1.10"))
        assertTrue(!Versions.isBeta("1.1.0"))
        assertTrue(!Versions.isBeta("2.0.00"))
        assertTrue(!Versions.isBeta("1.1"))
    }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(Versions.compare("0.10.0", "0.9.9") > 0)
        assertTrue(Versions.compare("1.1.0", "1.1.0-debug") == 0)
        assertTrue(Versions.compare("1.0.99", "1.1.0") < 0)
        assertTrue(Versions.isValid("12.0.3"))
        assertTrue(!Versions.isValid("1.2"))
        assertTrue(!Versions.isValid("1.2.x"))
    }
}
