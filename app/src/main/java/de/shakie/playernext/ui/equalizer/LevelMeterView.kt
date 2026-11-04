package de.shakie.playernext.ui.equalizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import de.shakie.playernext.R
import de.shakie.playernext.playback.dsp.LevelMeter
import de.shakie.playernext.util.dp
import de.shakie.playernext.util.themeColor
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Peak meters in the manner of LoudMax: input L/R before the maximizer, its gain reduction and
 * output L/R after it. Shows what is audible now ([LevelMeter.audibleUs]), at most 30 frames
 * per second and only between [start] and [stop] (equalizer in the foreground). When playback
 * pauses the bars fall back and the loop stops until [wake].
 *
 * Scale: −60 … +3 dBFS, the upper 20 dB over two thirds of the width. The reduction bar grows
 * from the 0 dB mark to the left on the same scale, so input + gain − reduction ≈ output can be
 * read off directly. With the maximizer on, the threshold is marked on the input and the
 * ceiling on the output; input above the threshold is drawn in the strong accent.
 */
class LevelMeterView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val accent = context.themeColor(R.attr.pnAccent)
    private val accentStrong = context.themeColor(R.attr.pnAccentStrong)
    private val overColor = context.getColor(R.color.pn_error)
    private val onSurface = context.getColor(R.color.pn_on_surface)

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0B0D.toInt() }
    private val trackStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A2A2D.toInt(); style = Paint.Style.STROKE; strokeWidth = context.dp(1).toFloat()
    }
    private val fill = Paint()
    private val grid = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f }
    private val marker = Paint().apply { color = onSurface; strokeWidth = context.dp(2).toFloat() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.pn_on_surface_dim); textSize = sp(11f) }
    private val channel = Paint(label).apply { textAlign = Paint.Align.RIGHT; color = context.getColor(R.color.pn_on_surface_faint) }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = onSurface; textSize = sp(11f); textAlign = Paint.Align.RIGHT; fontFeatureSettings = "tnum"
    }
    private val scale = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pn_on_surface_faint); textSize = sp(9f); textAlign = Paint.Align.CENTER
    }
    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onSurface; textSize = sp(12f); textAlign = Paint.Align.CENTER }
    private val veil = Paint().apply { color = 0xCC131316.toInt() }

    private val inputText = context.getString(R.string.meter_input)
    private val reductionText = context.getString(R.string.meter_reduction)
    private val outputText = context.getString(R.string.meter_output)
    private val noSignal1 = context.getString(R.string.meter_no_signal_title)
    private val noSignal2 = context.getString(R.string.meter_no_signal_text)

    private val labelW = context.dp(84).toFloat()
    private val valueW = context.dp(48).toFloat()
    private val rowH = context.dp(12).toFloat()
    private val pairGap = context.dp(5).toFloat()
    private val groupGap = context.dp(12).toFloat()
    private val radius = context.dp(3).toFloat()
    private val channelGap = context.dp(6).toFloat()
    private val yInL = context.dp(4).toFloat()
    private val yInR = yInL + rowH + pairGap
    private val yGr = yInR + rowH + groupGap
    private val yOutL = yGr + rowH + groupGap
    private val yOutR = yOutL + rowH + pairGap
    private val yScale = yOutR + rowH + context.dp(13)
    private val totalH = (yScale + context.dp(5)).roundToInt()
    private val levelRows = floatArrayOf(yInL, yInR, yOutL, yOutR)
    private val channelNames = arrayOf("L", "R", "L", "R")
    private val rect = RectF()

    // Display state: levels in dBFS (input L/R, output L/R), reduction in positive dB.
    private val level = FloatArray(4) { FLOOR }
    private val peak = FloatArray(4) { FLOOR }
    private val peakUntil = LongArray(4)
    private var reduction = 0f
    private var reductionPeak = 0f
    private var reductionPeakUntil = 0L
    private val blocks = FloatArray(LevelMeter.VALUES)
    private val texts = arrayOfNulls<String>(5)
    private val textKeys = IntArray(5) { Int.MIN_VALUE }

    private var loudnessOn = false
    private var thresholdDb = -6f
    private var ceilingDb = -1f

    private var running = false
    private var scheduled = false
    private var startedNs = 0L
    private var awakeUntilNs = 0L
    private var lastFrameNs = 0L
    private var lastDataNs = 0L
    private var lastUs = LevelMeter.NONE
    private var showNoSignal = false
    private val visible = Rect()
    private val frame = Runnable { tick() }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    fun setLoudness(on: Boolean, threshold: Float, ceiling: Float) {
        loudnessOn = on
        thresholdDb = threshold
        ceilingDb = ceiling
        invalidate()
    }

    fun start() {
        if (running) return
        running = true
        val now = System.nanoTime()
        startedNs = now
        awakeUntilNs = now + AWAKE_NS
        lastFrameNs = 0L
        lastUs = LevelMeter.NONE
        schedule(0)
    }

    fun stop() {
        running = false
        scheduled = false
        removeCallbacks(frame)
    }

    /** Playback may have started: keep the loop alive a moment even before audio flows. */
    fun wake() {
        awakeUntilNs = System.nanoTime() + AWAKE_NS
        if (running && !scheduled) schedule(0)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun schedule(delayMs: Long) {
        scheduled = true
        postDelayed(frame, delayMs)
    }

    private fun toDb(linear: Float) = if (linear <= 1e-6f) FLOOR else max(FLOOR, 20f * log10(linear))

    private fun tick() {
        scheduled = false
        if (!running) return
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else ((now - lastFrameNs) / 1e9f).coerceIn(0f, 0.25f)
        lastFrameNs = now

        var found = 0
        val audible = LevelMeter.audibleUs(now)
        if (audible != LevelMeter.NONE) {
            val from = lastUs
            if (from == LevelMeter.NONE || audible < from || audible - from > JUMP_US) lastUs = audible - FIRST_WINDOW_US
            // Paused: the window is empty, so the bars fall back instead of repeating one block.
            if (audible > lastUs) found = LevelMeter.collect(lastUs, audible, blocks)
            lastUs = audible
        }
        if (found > 0) lastDataNs = now

        for (i in 0 until 4) {
            val db = if (found > 0) toDb(blocks[i]) else FLOOR
            level[i] = max(db, level[i] - FALL_DB_S * dt)
            if (db > FLOOR && db >= peak[i] - HOLD_TOLERANCE_DB) {
                // A steady level keeps the mark where it is.
                if (db > peak[i]) peak[i] = db
                peakUntil[i] = now + HOLD_NS
            } else if (now > peakUntil[i]) {
                peak[i] = max(level[i], peak[i] - FALL_DB_S * dt)
            }
        }
        val red = if (found > 0) -min(0f, toDb(blocks[4])) else 0f
        reduction = max(red, reduction - RETURN_DB_S * dt)
        if (red > 0f && red >= reductionPeak - HOLD_TOLERANCE_DB) {
            if (red > reductionPeak) reductionPeak = red
            reductionPeakUntil = now + HOLD_NS
        } else if (now > reductionPeakUntil) {
            reductionPeak = max(reduction, reductionPeak - RETURN_DB_S * dt)
        }

        val playing = LevelMeter.isPlaying
        showNoSignal = playing && now - max(lastDataNs, startedNs) > NO_SIGNAL_NS
        if (getLocalVisibleRect(visible)) invalidate()

        val settled = level.all { it <= FLOOR } && peak.all { it <= FLOOR } && reduction <= 0f && reductionPeak <= 0f
        if (playing || now < awakeUntilNs || !settled) schedule(FRAME_MS)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(totalH, heightMeasureSpec))
    }

    private fun barLeft() = labelW
    private fun barRight() = width - valueW

    /** dBFS → x: −60 … −20 dB over the first 35 %, −20 … +3 dB over the rest. */
    private fun x(db: Float): Float {
        val d = db.coerceIn(FLOOR, TOP)
        val f = if (d >= -20f) 0.35f + 0.65f * (d + 20f) / (TOP + 20f) else 0.35f * (d - FLOOR) / (-20f - FLOOR)
        return barLeft() + (barRight() - barLeft()) * f
    }

    override fun onDraw(canvas: Canvas) {
        val l = barLeft()
        val r = barRight()
        for (t in TICKS) {
            val xt = x(t)
            canvas.drawLine(xt, yInL, xt, yOutR + rowH, grid)
        }
        val split = if (loudnessOn) min(thresholdDb, 0f) else 0f
        drawLevel(canvas, yInL, 0, split)
        drawLevel(canvas, yInR, 1, split)
        drawReduction(canvas, yGr)
        drawLevel(canvas, yOutL, 2, 0f)
        drawLevel(canvas, yOutR, 3, 0f)
        if (loudnessOn) {
            val xt = x(thresholdDb)
            canvas.drawLine(xt, yInL - pairGap, xt, yInR + rowH + pairGap, marker)
            val xc = x(ceilingDb)
            canvas.drawLine(xc, yOutL - pairGap, xc, yOutR + rowH + pairGap, marker)
        }

        drawLabel(canvas, inputText, yInL, yInR + rowH)
        drawLabel(canvas, reductionText, yGr, yGr + rowH)
        drawLabel(canvas, outputText, yOutL, yOutR + rowH)
        for (i in 0 until 4) {
            val y = levelRows[i]
            canvas.drawText(channelNames[i], l - channelGap, centerBaseline(channel, y, y + rowH), channel)
            canvas.drawText(levelText(i, peak[i]), width.toFloat(), centerBaseline(number, y, y + rowH), number)
        }
        canvas.drawText(reductionText(reductionPeak), width.toFloat(), centerBaseline(number, yGr, yGr + rowH), number)

        for (t in TICK_LABELS) canvas.drawText(dbLabel(t), x(t), yScale, scale)
        canvas.drawText("dB", width.toFloat() - valueW / 2, yScale, scale)

        if (showNoSignal) {
            canvas.drawRect(l, yInL - pairGap, r, yOutR + rowH + pairGap, veil)
            val cx = (l + r) / 2
            val cy = (yInL + yOutR + rowH) / 2
            canvas.drawText(noSignal1, cx, cy - messagePaint.textSize * 0.3f, messagePaint)
            canvas.drawText(noSignal2, cx, cy + messagePaint.textSize * 1.1f, messagePaint)
        }
    }

    private fun drawTrack(canvas: Canvas, y: Float) {
        rect.set(barLeft(), y, barRight(), y + rowH)
        canvas.drawRoundRect(rect, radius, radius, track)
        canvas.drawRoundRect(rect, radius, radius, trackStroke)
    }

    private fun drawLevel(canvas: Canvas, y: Float, i: Int, splitDb: Float) {
        drawTrack(canvas, y)
        val top = y + 1
        val bottom = y + rowH - 1
        val lv = level[i]
        if (lv > FLOOR) {
            val x0 = barLeft() + 1
            val xl = x(lv)
            val xs = x(splitDb)
            val xz = x(0f)
            fill.color = accent
            canvas.drawRect(x0, top, min(xl, xs), bottom, fill)
            if (xl > xs && xz > xs) {
                fill.color = accentStrong
                canvas.drawRect(xs, top, min(xl, xz), bottom, fill)
            }
            if (xl > xz) {
                fill.color = overColor
                canvas.drawRect(xz, top, xl, bottom, fill)
            }
        }
        val p = peak[i]
        if (p > FLOOR) {
            val xp = x(p)
            fill.color = if (p > 0f) overColor else onSurface
            canvas.drawRect(xp - 2, y, xp + 2, y + rowH, fill)
        }
    }

    private fun drawReduction(canvas: Canvas, y: Float) {
        drawTrack(canvas, y)
        val xz = x(0f)
        if (reduction > 0.05f) {
            fill.color = accentStrong
            canvas.drawRect(x(-reduction), y + 1, xz, y + rowH - 1, fill)
        }
        if (reductionPeak > 0.05f) {
            val xp = x(-reductionPeak)
            fill.color = onSurface
            canvas.drawRect(xp - 2, y, xp + 2, y + rowH, fill)
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, top: Float, bottom: Float) {
        canvas.drawText(text, 0f, centerBaseline(label, top, bottom), label)
    }

    private fun centerBaseline(p: Paint, top: Float, bottom: Float) = (top + bottom) / 2 - (p.descent() + p.ascent()) / 2

    private fun levelText(i: Int, db: Float): String {
        val key = if (db <= FLOOR) Int.MIN_VALUE + 1 else (db * 10).roundToInt()
        if (key != textKeys[i]) {
            textKeys[i] = key
            texts[i] = if (db <= FLOOR) "−∞" else signed(key / 10f)
        }
        return texts[i]!!
    }

    private fun reductionText(db: Float): String {
        val key = (db * 10).roundToInt()
        if (key != textKeys[4]) {
            textKeys[4] = key
            texts[4] = if (key == 0) "0,0" else signed(-key / 10f)
        }
        return texts[4]!!
    }

    private fun signed(v: Float): String {
        val s = String.format(Locale.GERMANY, "%.1f", kotlin.math.abs(v))
        return when {
            v > 0f -> "+$s"
            v < 0f -> "−$s"
            else -> s
        }
    }

    private fun dbLabel(t: Float) = if (t < 0) "−${(-t).toInt()}" else "${t.toInt()}"

    companion object {
        const val FLOOR = -60f
        const val TOP = 3f
        private val TICKS = floatArrayOf(-40f, -20f, -12f, -6f, -3f, 0f)
        private val TICK_LABELS = floatArrayOf(-40f, -20f, -12f, -6f, 0f)
        private const val FRAME_MS = 33L
        private const val FALL_DB_S = 20f
        private const val RETURN_DB_S = 30f
        private const val HOLD_NS = 1_500_000_000L
        private const val HOLD_TOLERANCE_DB = 0.5f
        private const val AWAKE_NS = 1_500_000_000L
        private const val NO_SIGNAL_NS = 2_000_000_000L
        private const val JUMP_US = 500_000L
        private const val FIRST_WINDOW_US = 40_000L
    }
}
