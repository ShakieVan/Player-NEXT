package de.shakie.playernext.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {
    val audio: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val video: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasAudio(context: Context) = granted(context, audio)
    fun hasVideo(context: Context) = granted(context, video)
    fun hasNotifications(context: Context) =
        Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    fun required(context: Context): Array<String> {
        val list = mutableListOf(audio)
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.READ_MEDIA_VIDEO
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list.filterNot { granted(context, it) }.distinct().toTypedArray()
    }

    private fun granted(context: Context, p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
}
