package de.shakie.playernext.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.res.ResourcesCompat
import de.shakie.playernext.R

/** "Player NEXT" in the Tube NEXT wordmark style: Montserrat Bold, NEXT with the red→orange gradient. */
class WordmarkView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatTextView(context, attrs) {

    init {
        typeface = ResourcesCompat.getFont(context, R.font.montserrat_bold)
        setTextColor(context.getColor(R.color.pn_on_surface))
        includeFontPadding = false
        maxLines = 1
        val text = SpannableString("Player NEXT")
        text.setSpan(GradientSpan(context.getColor(R.color.pn_logo_red), context.getColor(R.color.pn_logo_orange)), 7, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setText(text)
    }

    private class GradientSpan(private val start: Int, private val end: Int) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, s: Int, e: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let { paint.getFontMetricsInt(it) }
            return paint.measureText(text, s, e).toInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence, s: Int, e: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val width = paint.measureText(text, s, e)
            val old = paint.shader
            paint.shader = LinearGradient(x, 0f, x + width, 0f, start, end, Shader.TileMode.CLAMP)
            canvas.drawText(text, s, e, x, y.toFloat(), paint)
            paint.shader = old
        }
    }
}
