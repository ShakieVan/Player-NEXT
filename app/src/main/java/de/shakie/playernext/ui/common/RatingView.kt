package de.shakie.playernext.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import de.shakie.playernext.Prefs
import de.shakie.playernext.R
import de.shakie.playernext.util.dp
import de.shakie.playernext.util.themeColor

/** Five tappable stars; value in half-star units (0..10). Tapping the current value clears it. */
class RatingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var rating: Int = 0
        set(value) {
            field = value.coerceIn(0, 10)
            invalidate()
        }
    var onRatingChanged: ((Int) -> Unit)? = null

    private val full: Drawable = tinted(R.drawable.ic_star, context.themeColor(R.attr.pnAccent))
    private val half: Drawable = tinted(R.drawable.ic_star_half, context.themeColor(R.attr.pnAccent))
    private val empty: Drawable = tinted(R.drawable.ic_star_border, context.getColor(R.color.pn_star_empty))
    private val starSize = context.dp(30)
    private val gap = context.dp(8)

    private fun tinted(res: Int, color: Int): Drawable =
        ContextCompat.getDrawable(context, res)!!.mutate().apply { setTint(color) }

    override fun onDraw(canvas: Canvas) {
        val total = starSize * 5 + gap * 4
        var x = (width - total) / 2
        val y = (height - starSize) / 2
        for (i in 0 until 5) {
            val d = when {
                rating >= (i + 1) * 2 -> full
                rating == i * 2 + 1 -> half
                else -> empty
            }
            d.setBounds(x, y, x + starSize, y + starSize)
            d.draw(canvas)
            x += starSize + gap
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action != MotionEvent.ACTION_UP) return super.onTouchEvent(event)
        val total = starSize * 5 + gap * 4
        val left = (width - total) / 2f
        val rel = ((event.x - left) / (starSize + gap)).coerceIn(0f, 4.999f)
        val star = rel.toInt()
        val inLeftHalf = (rel - star) * (starSize + gap) < starSize / 2f
        var value = if (Prefs.halfStars && inLeftHalf) star * 2 + 1 else (star + 1) * 2
        if (value == rating) value = 0
        rating = value
        onRatingChanged?.invoke(value)
        performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
