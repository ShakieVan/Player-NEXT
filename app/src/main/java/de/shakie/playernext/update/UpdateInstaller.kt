package de.shakie.playernext.update

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import de.shakie.playernext.R
import java.io.File

/**
 * Android side of the update (as in Mau-Mau Flip's Updater.java): install permission, checking
 * the downloaded APK (package, higher version, announced version, same signature) and starting
 * the system installer.
 *
 * System dialogs never appear over the lock screen. If the app lies over it (debug test switch
 * PlayerNextTestLock), that is withdrawn first and the dialog only starts after unlocking.
 */
object UpdateInstaller {
    /** Set once a system dialog was requested; BaseActivity then no longer shows over the lock screen. */
    @Volatile var systemDialogRequested = false
        private set

    sealed interface Launch {
        data object Started : Launch
        /** Waits for unlocking; [done] reports later whether the dialog started. */
        data object WaitingForUnlock : Launch
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(activity: Activity, done: (Boolean) -> Unit): Launch =
        startSystemDialog(activity, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName)), done)

    /** Null = fine, otherwise a German reason. */
    fun verify(context: Context, apk: File, version: String): String? = try {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installed = pm.getPackageInfo(context.packageName, flags)
        val candidate = pm.getPackageArchiveInfo(apk.path, flags)
        when {
            candidate == null -> context.getString(R.string.update_reject_unreadable)
            candidate.packageName != context.packageName -> context.getString(R.string.update_reject_package)
            candidate.longVersionCode <= installed.longVersionCode -> context.getString(R.string.update_reject_older)
            candidate.versionName != version -> context.getString(R.string.update_reject_version)
            signers(candidate).isEmpty() || signers(candidate) != signers(installed) -> context.getString(R.string.update_reject_signature)
            else -> null
        }
    } catch (e: Exception) {
        context.getString(R.string.update_reject_failed, e.toString())
    }

    private fun signers(info: PackageInfo): Set<Signature> = info.signingInfo?.apkContentsSigners?.toSet().orEmpty()

    fun install(activity: Activity, apk: File, done: (Boolean) -> Unit): Launch {
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("Player-NEXT-Update", uri)
        return startSystemDialog(activity, intent, done)
    }

    private fun startSystemDialog(activity: Activity, intent: Intent, done: (Boolean) -> Unit): Launch {
        systemDialogRequested = true
        activity.setShowWhenLocked(false)
        activity.setTurnScreenOn(false)
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            done(startQuietly(activity, intent))
            return Launch.Started
        }
        keyguard.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = done(startQuietly(activity, intent))
            override fun onDismissCancelled() = done(false)
            override fun onDismissError() = done(false)
        })
        return Launch.WaitingForUnlock
    }

    private fun startQuietly(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
