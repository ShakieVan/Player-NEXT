package de.shakie.playernext.update

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import de.shakie.playernext.Prefs
import de.shakie.playernext.R
import de.shakie.playernext.ui.BaseActivity

/** Status, release notes and the steps check → download → install (like Mau-Mau Flip's settings section). */
class UpdateActivity : BaseActivity() {
    private val listener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Updater.init(this)
        setContentView(R.layout.activity_update)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<TextView>(R.id.installed).text =
            getString(if (Versions.isBeta(Updater.currentVersion)) R.string.update_installed_beta else R.string.update_installed, Updater.currentVersion)
        findViewById<View>(R.id.btn_check).setOnClickListener { Updater.check() }
        findViewById<View>(R.id.btn_download).setOnClickListener { Updater.download() }
        findViewById<View>(R.id.btn_install).setOnClickListener { Updater.install(this) }
        findViewById<View>(R.id.btn_browser).setOnClickListener { Updater.openReleasePage(this) }
        findViewById<MaterialSwitch>(R.id.auto_check).apply {
            isChecked = Prefs.updateAutoCheck
            setOnCheckedChangeListener { _, checked -> Prefs.updateAutoCheck = checked }
        }
        findViewById<MaterialSwitch>(R.id.beta).apply {
            isChecked = Updater.beta
            setOnCheckedChangeListener { _, checked -> Updater.setBeta(checked) }
        }
    }

    override fun onStart() {
        super.onStart()
        Updater.addListener(listener)
        refresh()
    }

    override fun onStop() {
        Updater.removeListener(listener)
        super.onStop()
    }

    private fun refresh() {
        val rel = Updater.release
        findViewById<TextView>(R.id.status).text = Updater.status
        findViewById<LinearProgressIndicator>(R.id.progress).apply {
            visibility = if (Updater.busy && Updater.percent >= 0) View.VISIBLE else View.GONE
            setProgressCompat(Updater.percent.coerceAtLeast(0), true)
        }
        findViewById<View>(R.id.btn_check).isEnabled = !Updater.busy
        findViewById<MaterialButton>(R.id.btn_download).apply {
            visibility = if (rel != null && !Updater.apkReady && !Updater.busy) View.VISIBLE else View.GONE
            if (rel != null) text = getString(R.string.update_download, Formatter.formatShortFileSize(context, rel.size))
        }
        findViewById<View>(R.id.btn_install).visibility = if (rel != null && Updater.apkReady && !Updater.busy) View.VISIBLE else View.GONE
        val notes = rel?.notes?.trim().orEmpty()
        findViewById<TextView>(R.id.notes_title).apply {
            visibility = if (notes.isNotEmpty()) View.VISIBLE else View.GONE
            text = rel?.let { getString(if (it.beta) R.string.update_notes_beta else R.string.update_notes, it.version) }
        }
        findViewById<TextView>(R.id.notes).apply {
            visibility = if (notes.isNotEmpty()) View.VISIBLE else View.GONE
            val density = resources.displayMetrics.density
            text = ReleaseNotes.format(notes, gap = (10 * density).toInt(), radius = (3 * density).toInt(), color = currentTextColor)
        }
    }
}
