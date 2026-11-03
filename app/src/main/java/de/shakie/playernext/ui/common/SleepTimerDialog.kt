package de.shakie.playernext.ui.common

import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.shakie.playernext.Prefs
import de.shakie.playernext.R
import de.shakie.playernext.playback.SleepTimer
import de.shakie.playernext.ui.BaseActivity
import de.shakie.playernext.util.Format

object SleepTimerDialog {
    fun show(activity: BaseActivity) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_sleep_timer, null)
        val value = view.findViewById<TextView>(R.id.sleep_value)
        val seek = view.findViewById<SeekBar>(R.id.sleep_seek)
        val fade = view.findViewById<CheckBox>(R.id.sleep_fade)
        val finish = view.findViewById<CheckBox>(R.id.sleep_finish)
        val status = view.findViewById<TextView>(R.id.sleep_status)
        val presets = view.findViewById<LinearLayout>(R.id.sleep_presets)

        val last = Prefs.sp.getInt("sleep_last_minutes", 30)
        seek.progress = (last - 1).coerceIn(0, 179)
        fade.isChecked = Prefs.sp.getBoolean("sleep_fade", true)
        finish.isChecked = Prefs.sp.getBoolean("sleep_finish", false)
        fun minutes() = seek.progress + 1
        fun show() { value.text = activity.resources.getQuantityString(R.plurals.sleep_minutes, minutes(), minutes()) }
        show()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = show()
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })
        for (m in listOf(15, 30, 45, 60, 90)) {
            val b = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = m.toString()
                minWidth = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { seek.progress = m - 1 }
            }
            presets.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6 })
        }
        if (SleepTimer.active) {
            status.visibility = android.view.View.VISIBLE
            status.text = activity.getString(R.string.sleep_active, Format.duration(SleepTimer.remainingMs()))
        }

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.sleep_timer)
            .setView(view)
            .setPositiveButton(R.string.sleep_start) { _, _ ->
                Prefs.sp.edit().putInt("sleep_last_minutes", minutes()).putBoolean("sleep_fade", fade.isChecked)
                    .putBoolean("sleep_finish", finish.isChecked).apply()
                SleepTimer.start(minutes(), fade.isChecked, finish.isChecked)
                ItemActions.toast(activity, activity.resources.getQuantityString(R.plurals.sleep_started, minutes(), minutes()))
            }
            .setNegativeButton(R.string.cancel, null)
        if (SleepTimer.active) {
            builder.setNeutralButton(R.string.sleep_cancel) { _, _ ->
                SleepTimer.cancel()
                ItemActions.toast(activity, activity.getString(R.string.sleep_stopped))
            }
        }
        builder.show()
    }
}
