package de.shakie.playernext.ui

import android.app.Activity
import android.content.IntentSender
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import de.shakie.playernext.Prefs
import de.shakie.playernext.R
import java.util.Calendar

/**
 * Applies the selected skin (and the night skin during its hours) plus the orientation lock,
 * and offers the activity-result based helpers that item actions need.
 */
abstract class BaseActivity : AppCompatActivity() {
    private var appliedSkin: Int = 0

    private var imageCallback: ((Uri?) -> Unit)? = null
    private var senderCallback: ((Boolean) -> Unit)? = null
    private var createCallback: ((Uri?) -> Unit)? = null
    private var openCallback: ((Uri?) -> Unit)? = null
    private var openManyCallback: ((List<Uri>) -> Unit)? = null
    private var folderCallback: ((Uri?) -> Unit)? = null

    private val imageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        imageCallback?.invoke(uri); imageCallback = null
    }
    private val senderLauncher: ActivityResultLauncher<IntentSenderRequest> =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            senderCallback?.invoke(res.resultCode == Activity.RESULT_OK); senderCallback = null
        }
    private val createLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        createCallback?.invoke(uri); createCallback = null
    }
    private val openLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        openCallback?.invoke(uri); openCallback = null
    }
    private val openManyLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        openManyCallback?.invoke(uris); openManyCallback = null
    }
    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        folderCallback?.invoke(uri); folderCallback = null
    }

    fun pickImage(callback: (Uri?) -> Unit) {
        imageCallback = callback
        imageLauncher.launch("image/*")
    }

    /** Runs a MediaStore write/delete request and reports whether the user agreed. */
    fun runIntentSender(sender: IntentSender, callback: (Boolean) -> Unit) {
        senderCallback = callback
        senderLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    fun createDocument(name: String, callback: (Uri?) -> Unit) {
        createCallback = callback
        createLauncher.launch(name)
    }

    fun openDocument(types: Array<String>, callback: (Uri?) -> Unit) {
        openCallback = callback
        openLauncher.launch(types)
    }

    fun openDocuments(types: Array<String>, callback: (List<Uri>) -> Unit) {
        openManyCallback = callback
        openManyLauncher.launch(types)
    }

    fun openFolder(callback: (Uri?) -> Unit) {
        folderCallback = callback
        folderLauncher.launch(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedSkin = currentSkinOverlay()
        if (appliedSkin != 0) theme.applyStyle(appliedSkin, true)
        super.onCreate(savedInstanceState)
        applyOrientation()
        // Device tests only: `setprop log.tag.PlayerNextTestLock DEBUG` shows debug builds over the
        // lock screen so tests never need the unlock pattern. Always reset it to INFO afterwards.
        // Not once an update asked for a system dialog: those never show over the lock screen.
        if (de.shakie.playernext.BuildConfig.DEBUG && android.util.Log.isLoggable("PlayerNextTestLock", android.util.Log.DEBUG) &&
            !de.shakie.playernext.update.UpdateInstaller.systemDialogRequested
        ) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    override fun onResume() {
        super.onResume()
        if (currentSkinOverlay() != appliedSkin) {
            recreate()
            return
        }
        applyOrientation()
    }

    protected open val followsOrientationLock = true

    private fun applyOrientation() {
        if (!followsOrientationLock) return
        requestedOrientation = when (Prefs.lockOrientation) {
            "lock_portrait" -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            "lock_landscape" -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    companion object {
        fun currentSkinOverlay(): Int {
            if (Prefs.nightSkin == "night" && isNightTime()) return R.style.ThemeOverlay_PlayerNext_Skin_Night
            return when (Prefs.skin) {
                "red" -> R.style.ThemeOverlay_PlayerNext_Skin_Red
                "green" -> R.style.ThemeOverlay_PlayerNext_Skin_Green
                "blue" -> R.style.ThemeOverlay_PlayerNext_Skin_Blue
                "violet" -> R.style.ThemeOverlay_PlayerNext_Skin_Violet
                else -> 0
            }
        }

        private fun minutes(text: String): Int {
            val parts = text.split(':')
            return (parts.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (parts.getOrNull(1)?.toIntOrNull() ?: 0)
        }

        fun isNightTime(): Boolean {
            val c = Calendar.getInstance()
            val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
            val start = minutes(Prefs.nightStart)
            val end = minutes(Prefs.nightEnd)
            return if (start <= end) now in start until end else now >= start || now < end
        }
    }
}
