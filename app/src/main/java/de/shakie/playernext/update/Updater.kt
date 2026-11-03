package de.shakie.playernext.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import de.shakie.playernext.BuildConfig
import de.shakie.playernext.Prefs
import de.shakie.playernext.R
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * Update function like Mau-Mau Flip: looks for the latest GitHub release, downloads the APK only
 * on request, checks size and SHA-256 from the GitHub API, then package, version and signature
 * ([UpdateInstaller]) and starts the system installer. The automatic check runs at most once a
 * day at app start. This is the only part of Player NEXT that uses the internet.
 *
 * Beta channel as in Mau-Mau Flip: test versions come from a repository of their own
 * ([UpdateRelease.BETA_REPOSITORY]); with the channel on both are asked and the highest version
 * wins. Until switched, the channel follows the installed version (a beta keeps getting betas).
 *
 * Remembered in its own preferences: last successful check (ms) and the raw JSON of the offered release.
 */
object Updater {
    private const val PREFS = "updates"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val CHECK_TIMEOUT_MS = 12_000
    // A download only stops when no byte arrives for this long, however long it takes overall.
    private const val STALL_TIMEOUT_MS = 30_000

    var status = ""
        private set
    var release: UpdateRelease? = null
        private set
    var busy = false
        private set
    /** Download progress 0..100, -1 when not downloading. */
    var percent = -1
        private set
    var apkReady = false
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context

    val currentVersion: String get() = BuildConfig.VERSION_NAME.substringBefore('-')

    val beta: Boolean get() = Prefs.updateBeta(Versions.isBeta(currentVersion))

    /** Channel switched: forget the offered release and look again right away. */
    fun setBeta(on: Boolean) {
        if (on == beta) return
        Prefs.setUpdateBeta(on)
        prefs().edit().remove("release").apply()
        release = null
        apkReady = false
        // A check or download still running for the other channel starts the new check when it ends.
        if (!busy) check()
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun publish(text: String) {
        status = text
        listeners.forEach { it() }
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        status = app.getString(R.string.update_not_checked)
        val cached = UpdateRelease.parse(prefs().getString("release", "").orEmpty(), beta)
        val lastCheck = prefs().getLong("last_check", 0)
        if (cached != null && Versions.compare(cached.version, currentVersion) > 0) {
            release = cached
            apkReady = apkFile(cached).exists()
            status = availableText(cached)
        } else if (lastCheck > 0) {
            status = lastCheckedText(lastCheck)
        }
        // Leftovers of aborted downloads and already installed APKs; only the open update's APK stays.
        executor.execute { prune() }
    }

    val available: Boolean get() = release != null

    /** At app start: at most once a day, and only when allowed in the settings. */
    fun autoCheck(context: Context) {
        init(context)
        if (!Prefs.updateAutoCheck) return
        if (System.currentTimeMillis() - prefs().getLong("last_check", 0) < DAY_MS) return
        check()
    }

    fun check() {
        if (busy) return
        busy = true
        percent = -1
        publish(app.getString(R.string.update_checking))
        val allowBeta = beta
        val sources = if (allowBeta) listOf(UpdateRelease.BETA_ENDPOINT, UpdateRelease.LATEST_ENDPOINT) else listOf(UpdateRelease.LATEST_ENDPOINT)
        executor.execute {
            // One after the other; a newer version is offered even if the other source failed.
            val outcomes = sources.map { fetch(it, allowBeta) }
            val best = outcomes.mapNotNull { it.found }.reduceOrNull { a, b -> if (Versions.compare(b.version, a.version) > 0) b else a }
            // The GitHub limit explains a failure best: it hits every source from the same network.
            val failures = outcomes.filter { it.failure != null }
            val failure = (failures.firstOrNull { it.limited } ?: failures.firstOrNull())?.failure
            main.post {
                if (allowBeta == beta) {
                    finishCheck(CheckOutcome(best, failure))
                } else {
                    busy = false
                    check()
                }
            }
        }
    }

    private class CheckOutcome(val found: UpdateRelease?, val failure: String?, val limited: Boolean = false)

    private fun fetch(endpoint: String, allowBeta: Boolean): CheckOutcome {
        val connection = try {
            URL(endpoint).openConnection() as HttpURLConnection
        } catch (_: IOException) {
            return CheckOutcome(null, app.getString(R.string.update_no_connection))
        }
        return try {
            connection.connectTimeout = CHECK_TIMEOUT_MS
            connection.readTimeout = CHECK_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "Player-NEXT/$currentVersion")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            when (val code = connection.responseCode) {
                200 -> {
                    CheckOutcome(UpdateRelease.parse(readLimited(connection, 1024 * 1024), allowBeta), null)
                }
                // No release published yet: a valid answer, not a failure.
                404 -> CheckOutcome(null, null)
                else -> CheckOutcome(null, failureText(code, connection), code == 403 || code == 429)
            }
        } catch (_: IOException) {
            CheckOutcome(null, app.getString(R.string.update_no_connection))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * GitHub answers too many anonymous requests (60 per hour and IP address, shared by everyone
     * in the same Wi-Fi) with 403 or 429. That should not look like "no connection".
     */
    private fun failureText(code: Int, connection: HttpURLConnection): String {
        if (code != 403 && code != 429) return app.getString(R.string.update_github_error, code)
        val retry = connection.getHeaderField("retry-after")?.toLongOrNull()
        val reset = connection.getHeaderField("x-ratelimit-reset")?.toLongOrNull()
        val remaining = connection.getHeaderField("x-ratelimit-remaining")
        val wait = retry ?: if (remaining == "0" && reset != null) (reset - System.currentTimeMillis() / 1000).coerceAtLeast(0) else null
        if (wait == null) return app.getString(R.string.update_rate_limited)
        val minutes = ceil(wait / 60.0).toInt().coerceAtLeast(1)
        return app.getString(R.string.update_rate_limited_wait, app.resources.getQuantityString(R.plurals.update_minutes, minutes, minutes))
    }

    /** The API answer as text; more than [limit] bytes is not a release we would offer. */
    private fun readLimited(connection: HttpURLConnection, limit: Int): String {
        val out = java.io.ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(16 * 1024)
            while (out.size() <= limit) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        }
        return if (out.size() > limit) "" else out.toString(Charsets.UTF_8.name())
    }

    private fun finishCheck(outcome: CheckOutcome) {
        busy = false
        val found = outcome.found
        val failed = outcome.failure != null
        val newer = found != null && Versions.compare(found.version, currentVersion) > 0
        // The check time is only stored after a full answer: a failure (no network, GitHub limit) would
        // otherwise block the automatic check for a day.
        prefs().edit().apply {
            if (!failed) putLong("last_check", System.currentTimeMillis())
            if (found != null && (newer || !failed)) putString("release", found.raw)
        }.apply()
        when {
            newer -> {
                release = found
                apkReady = apkFile(found!!).exists()
                publish(availableText(found))
            }
            failed -> publish(outcome.failure!!)
            found == null -> {
                release = null
                publish(app.getString(R.string.update_no_release))
            }
            else -> {
                release = null
                publish(app.getString(R.string.update_up_to_date))
            }
        }
    }

    private fun availableText(rel: UpdateRelease): String =
        app.getString(if (rel.beta) R.string.update_available_beta else R.string.update_available, rel.version)

    private fun lastCheckedText(ms: Long): String =
        app.getString(R.string.update_last_checked, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms)))

    private fun dir() = File(app.filesDir, "updates")
    private fun apkFile(rel: UpdateRelease) = File(dir(), "${rel.sha256}.apk")

    private fun prune() {
        val keep = release?.let(::apkFile)
        dir().listFiles()?.forEach { if (it != keep) it.delete() }
    }

    fun download() {
        val rel = release ?: return
        if (busy) return
        busy = true
        percent = 0
        publish(app.getString(R.string.update_downloading))
        executor.execute {
            val ok = try {
                fetchApk(rel)
            } catch (_: IOException) {
                null
            }
            main.post {
                busy = false
                percent = -1
                if (release !== rel) {
                    check()
                    return@post
                }
                when (ok) {
                    true -> {
                        apkReady = true
                        publish(app.getString(R.string.update_ready))
                    }
                    false -> publish(app.getString(R.string.update_corrupt))
                    null -> publish(app.getString(R.string.update_download_failed))
                }
            }
        }
    }

    /** True = stored and checked, false = checksum or size wrong, IOException = network problem. */
    private fun fetchApk(rel: UpdateRelease): Boolean {
        dir().mkdirs()
        prune()
        val target = apkFile(rel)
        val part = File(target.path + ".part")
        val connection = URL(rel.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CHECK_TIMEOUT_MS
            connection.readTimeout = STALL_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "Player-NEXT/$currentVersion")
            connection.setRequestProperty("Accept", "application/octet-stream")
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            var shown = 0
            connection.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        bytes += n
                        if (bytes > rel.size) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        val p = (bytes * 100 / rel.size).toInt()
                        if (p != shown) {
                            shown = p
                            main.post { percent = p; listeners.forEach { it() } }
                        }
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (bytes != rel.size || hex != rel.sha256) {
                part.delete()
                return false
            }
            return part.renameTo(target)
        } catch (e: IOException) {
            part.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /** Install permission, then the checks of [UpdateInstaller.verify], then the system installer. */
    fun install(activity: Activity) {
        val rel = release ?: return
        val apk = apkFile(rel)
        if (!apk.exists()) {
            apkReady = false
            publish(app.getString(R.string.update_download_failed))
            return
        }
        if (!UpdateInstaller.canInstall(activity)) {
            val ask = app.getString(R.string.update_allow_install)
            val launch = UpdateInstaller.openInstallPermission(activity) { started -> main.post { publish(if (started) ask else app.getString(R.string.update_unlock_again)) } }
            publish(if (launch == UpdateInstaller.Launch.WaitingForUnlock) app.getString(R.string.update_unlock_wait) else ask)
            return
        }
        val problem = UpdateInstaller.verify(activity, apk, rel.version)
        if (problem != null) {
            apk.delete()
            apkReady = false
            publish(app.getString(R.string.update_rejected, problem))
            return
        }
        val launch = UpdateInstaller.install(activity, apk) { started ->
            main.post { publish(app.getString(if (started) R.string.update_installer_started else R.string.update_unlock_again)) }
        }
        if (launch == UpdateInstaller.Launch.WaitingForUnlock) publish(app.getString(R.string.update_unlock_wait))
    }

    /** Way out without the updater: the release page, where the APK can be loaded in the browser. */
    fun openReleasePage(context: Context) {
        val url = release?.pageUrl ?: if (beta) UpdateRelease.BETA_RELEASES_PAGE else UpdateRelease.RELEASES_PAGE
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
        }
    }
}
