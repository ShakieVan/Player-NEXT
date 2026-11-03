package de.shakie.playernext.ui.nowplaying

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.max

/** PlayerPro's album art transition effects for swiping between songs. */
object PageTransformers {
    fun forKey(key: String): ViewPager2.PageTransformer? = when (key) {
        "player_swipe_accordion" -> ViewPager2.PageTransformer { page, pos ->
            page.pivotX = if (pos < 0) 0f else page.width.toFloat()
            page.scaleX = if (pos < 0) 1f + pos else 1f - pos
        }
        "player_swipe_stack" -> ViewPager2.PageTransformer { page, pos ->
            page.translationX = if (pos < 0) 0f else -page.width * pos
            page.translationZ = -pos
        }
        "player_swipe_drawer" -> ViewPager2.PageTransformer { page, pos ->
            page.translationX = if (pos <= 0) 0f else -page.width * pos * 0.5f
            page.translationZ = -abs(pos)
            page.alpha = if (pos <= 0) 1f else 1f - pos * 0.3f
        }
        "player_swipe_tablet" -> ViewPager2.PageTransformer { page, pos ->
            page.cameraDistance = page.width * 20f
            page.pivotX = page.width / 2f
            page.pivotY = page.height / 2f
            page.rotationY = 30f * pos
        }
        "player_swipe_cube" -> ViewPager2.PageTransformer { page, pos ->
            page.cameraDistance = page.width * 20f
            page.pivotX = if (pos < 0) page.width.toFloat() else 0f
            page.pivotY = page.height / 2f
            page.rotationY = 90f * pos
        }
        "player_swipe_flip" -> ViewPager2.PageTransformer { page, pos ->
            page.cameraDistance = page.width * 20f
            page.translationX = -page.width * pos
            page.rotationY = 180f * pos
            page.visibility = if (abs(pos) < 0.5f) View.VISIBLE else View.INVISIBLE
        }
        "player_swipe_zoom" -> ViewPager2.PageTransformer { page, pos ->
            val s = max(0.6f, 1f - abs(pos))
            page.scaleX = s
            page.scaleY = s
            page.alpha = 1f - abs(pos) * 0.6f
        }
        "player_swipe_zoom_slide" -> ViewPager2.PageTransformer { page, pos ->
            val s = max(0.85f, 1f - abs(pos))
            page.scaleX = s
            page.scaleY = s
            page.alpha = 0.5f + (s - 0.85f) / 0.15f * 0.5f
        }
        "player_swipe_rotate_up" -> ViewPager2.PageTransformer { page, pos ->
            page.pivotX = page.width / 2f
            page.pivotY = 0f
            page.rotation = -15f * pos
        }
        "player_swipe_rotate_down" -> ViewPager2.PageTransformer { page, pos ->
            page.pivotX = page.width / 2f
            page.pivotY = page.height.toFloat()
            page.rotation = 15f * pos
        }
        "player_swipe_depth" -> ViewPager2.PageTransformer { page, pos ->
            if (pos <= 0) {
                page.alpha = 1f; page.translationX = 0f; page.translationZ = 0f; page.scaleX = 1f; page.scaleY = 1f
            } else {
                page.alpha = 1f - pos
                page.translationX = page.width * -pos
                page.translationZ = -1f
                val s = 0.75f + 0.25f * (1 - abs(pos))
                page.scaleX = s
                page.scaleY = s
            }
        }
        else -> null
    }
}
