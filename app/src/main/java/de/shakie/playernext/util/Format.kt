package de.shakie.playernext.util

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun duration(ms: Long): String {
        val total = (ms.coerceAtLeast(0) + 500) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    fun date(millis: Long): String =
        if (millis <= 0) "" else DateFormat.getDateInstance(DateFormat.LONG, Locale.GERMANY).format(Date(millis))

    fun relative(millis: Long): String {
        if (millis <= 0) return ""
        val diff = System.currentTimeMillis() - millis
        val minutes = diff / 60_000
        return when {
            minutes < 60 -> "vor $minutes Minuten"
            minutes < 60 * 24 -> "vor ${minutes / 60} Stunden"
            else -> "vor ${minutes / (60 * 24)} Tagen"
        }
    }

    fun size(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> String.format(Locale.GERMANY, "%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format(Locale.GERMANY, "%.2f MB", bytes / 1_048_576.0)
        else -> String.format(Locale.GERMANY, "%d kB", bytes / 1024)
    }

    fun stars(halfStars: Int): String {
        if (halfStars <= 0) return "–"
        val full = halfStars / 2
        return "★".repeat(full) + (if (halfStars % 2 == 1) "½" else "")
    }
}

fun Context.dp(value: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()
fun Context.dp(value: Int): Int = dp(value.toFloat())

@ColorInt
fun Context.themeColor(@AttrRes attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}
