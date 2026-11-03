package de.shakie.playernext.ui.common

import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.shakie.playernext.R
import de.shakie.playernext.data.Song
import de.shakie.playernext.tags.TagIO
import de.shakie.playernext.ui.BaseActivity
import de.shakie.playernext.util.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Edits the lyrics embedded in a file (USLT / LYRICS field). */
object LyricsEditor {
    fun edit(activity: BaseActivity, song: Song, current: String, onSaved: (String) -> Unit = {}) {
        val input = EditText(activity).apply {
            setText(current)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 8
            maxLines = 16
            gravity = android.view.Gravity.TOP
        }
        val frame = FrameLayout(activity).apply {
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.lyrics_edit)
            .setView(frame)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text.toString()
                activity.lifecycleScope.launch {
                    if (!TagIO.requestWriteAccess(activity, listOf(song))) {
                        ItemActions.toast(activity, activity.getString(R.string.tag_permission_denied))
                        return@launch
                    }
                    val error = withContext(Dispatchers.IO) {
                        try {
                            TagIO.write(activity, listOf(song), mapOf(TagIO.Field.LYRICS to text), TagIO.ArtworkChange.Keep)
                            null
                        } catch (e: Exception) {
                            e.message ?: e.javaClass.simpleName
                        }
                    }
                    if (error == null) {
                        ItemActions.toast(activity, activity.getString(R.string.tag_saved))
                        onSaved(text)
                    } else {
                        ItemActions.toast(activity, activity.getString(R.string.tag_failed, error))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
