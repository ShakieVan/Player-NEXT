package de.shakie.playernext.ui.equalizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import de.shakie.playernext.R
import de.shakie.playernext.util.dp
import de.shakie.playernext.util.themeColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Vertical EQ fader with dB scale; double tap resets to 0 dB. */
class VerticalSlider @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var min = -12f
    var max = 12f
    var value = 0f
        set(v) {
            field = v.coerceIn(min, max)
            invalidate()
        }
    var onChange: ((Float, Boolean) -> Unit)? = null

    private val accent = context.themeColor(R.attr.pnAccent)
    private val accentStrong = context.themeColor(R.attr.pnAccentStrong)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0B0D.toInt() }
    private val trackStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2A2D.toInt(); style = Paint.Style.STROKE; strokeWidth = context.dp(1).toFloat() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zero = Paint().apply { color = 0x55FFFFFF; strokeWidth = context.dp(1).toFloat() }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.pn_on_surface_faint); textSize = context.dp(9).toFloat() }
    private val thumb = ContextCompat.getDrawable(context, R.drawable.seek_thumb)!!
    private val trackWidth = context.dp(8).toFloat()
    private val thumbSize = context.dp(26)
    private val pad = context.dp(14).toFloat()

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            value = 0f
            onChange?.invoke(value, true)
            return true
        }
    })

    private fun yFor(v: Float): Float {
        val usable = height - 2 * pad
        return pad + usable * (1f - (v - min) / (max - min))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val rect = RectF(cx - trackWidth / 2, pad, cx + trackWidth / 2, height - pad)
        canvas.drawRoundRect(rect, trackWidth / 2, trackWidth / 2, track)
        canvas.drawRoundRect(rect, trackWidth / 2, trackWidth / 2, trackStroke)
        val y0 = yFor(0f)
        val yv = yFor(value)
        if (value != 0f) {
            fill.shader = LinearGradient(0f, y0, 0f, yv, accentStrong, accent, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(RectF(cx - trackWidth / 2 + 1, minOf(y0, yv), cx + trackWidth / 2 - 1, maxOf(y0, yv)), 3f, 3f, fill)
        }
        canvas.drawLine(cx - trackWidth * 1.6f, y0, cx - trackWidth, y0, zero)
        canvas.drawText("${max.toInt()}", 0f, pad + tick.textSize / 3, tick)
        canvas.drawText("0", 0f, y0 + tick.textSize / 3, tick)
        canvas.drawText("${min.toInt()}", 0f, height - pad + tick.textSize / 3, tick)
        thumb.setBounds((cx - thumbSize / 2).toInt(), (yv - thumbSize / 2).toInt(), (cx + thumbSize / 2).toInt(), (yv + thumbSize / 2).toInt())
        thumb.draw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (detector.onTouchEvent(event)) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                parent.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP)
                val usable = height - 2 * pad
                val v = max - (event.y - pad) / usable * (max - min)
                value = (Math.round(v * 2) / 2f).coerceIn(min, max)
                onChange?.invoke(value, event.actionMasked == MotionEvent.ACTION_UP)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}

/** Rotary knob (0..1000) with an accent arc, like PlayerPro's bass/virtualizer knobs. */
class KnobView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var value = 0
        set(v) {
            field = v.coerceIn(0, 1000)
            invalidate()
        }
    var onChange: ((Int, Boolean) -> Unit)? = null

    private val accent = context.themeColor(R.attr.pnAccent)
    private val accentStrong = context.themeColor(R.attr.pnAccentStrong)
    private val arcBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = context.dp(5).toFloat(); color = 0xFF0B0B0D.toInt(); strokeCap = Paint.Cap.ROUND
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(5).toFloat(); strokeCap = Paint.Cap.ROUND }
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(2).toFloat(); color = 0x33FFFFFF }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = context.dp(5).toFloat(); strokeCap = Paint.Cap.ROUND }
    private var lastAngle = 0f

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - context.dp(8)
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(oval, 135f, 270f, false, arcBg)
        arc.shader = SweepGradient(cx, cy, intArrayOf(accentStrong, accent, accentStrong), null)
        if (value > 0) canvas.drawArc(oval, 135f, 270f * value / 1000f, false, arc)
        val fr = r - context.dp(10)
        face.shader = RadialGradient(cx - fr / 3, cy - fr / 3, fr * 1.6f, intArrayOf(0xFFEDEDED.toInt(), 0xFF9C9C9C.toInt(), 0xFF5A5A5A.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, fr, face)
        canvas.drawCircle(cx, cy, fr, rim)
        val angle = Math.toRadians((135.0 + 270.0 * value / 1000.0))
        marker.color = accent
        val inner = fr * 0.45f
        val outer = fr * 0.8f
        canvas.drawLine(
            cx + (cos(angle) * inner).toFloat(), cy + (sin(angle) * inner).toFloat(),
            cx + (cos(angle) * outer).toFloat(), cy + (sin(angle) * outer).toFloat(), marker
        )
    }

    private fun angleOf(e: MotionEvent): Float = Math.toDegrees(atan2((e.y - height / 2f).toDouble(), (e.x - width / 2f).toDouble())).toFloat()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                lastAngle = angleOf(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val a = angleOf(event)
                var d = a - lastAngle
                if (d > 180) d -= 360
                if (d < -180) d += 360
                lastAngle = a
                value += (d / 270f * 1000).toInt()
                onChange?.invoke(value, false)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                onChange?.invoke(value, true)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
