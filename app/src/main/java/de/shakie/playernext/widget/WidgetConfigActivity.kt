package de.shakie.playernext.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import de.shakie.playernext.R
import de.shakie.playernext.art.ArtKey
import de.shakie.playernext.art.ArtLoader
import de.shakie.playernext.data.Library
import de.shakie.playernext.ui.BaseActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Background transparency (and cover for the Plus widget) with a live preview on the wallpaper.
 * Started by the launcher when a widget is placed or reconfigured; without a widget id (settings)
 * the choice applies to all placed widgets.
 */
class WidgetConfigActivity : BaseActivity() {
    private var widgetId: Int? = null
    private lateinit var provider: BaseWidget
    private lateinit var options: WidgetOptions
    private lateinit var host: FrameLayout
    private var art: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            .takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }
        // Placing the widget also succeeds when the screen is left with Back; changes are saved right away.
        widgetId?.let { setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, it)) }
        provider = widgetId?.let { Widgets.providerFor(this, it) } ?: WidgetPlus()
        options = widgetId?.let { WidgetOptions.of(this, it) } ?: WidgetOptions.defaults(this)
        setContentView(R.layout.activity_widget_config)
        host = findViewById(R.id.preview_host)

        if (widgetId == null) {
            findViewById<TextView>(R.id.config_title).setText(R.string.widget_config_all_title)
            findViewById<TextView>(R.id.config_hint).text =
                getString(R.string.widget_config_all_hint) + " " + getString(R.string.widget_click_hint)
        }
        val label = findViewById<TextView>(R.id.opacity_label)
        val slider = findViewById<Slider>(R.id.opacity)
        val cover = findViewById<MaterialSwitch>(R.id.cover)
        slider.value = (options.opacity / 5 * 5).coerceIn(0, 100).toFloat()
        label.text = getString(R.string.widget_opacity, options.opacity)
        slider.addOnChangeListener { _, value, _ ->
            label.text = getString(R.string.widget_opacity, value.toInt())
            change(options.copy(opacity = value.toInt()))
        }
        cover.visibility = if (provider.coverOptional) View.VISIBLE else View.GONE
        cover.isChecked = options.cover
        cover.setOnCheckedChangeListener { _, checked -> change(options.copy(cover = checked)) }
        findViewById<View>(R.id.done).setOnClickListener { finish() }
        // From the settings: offer to place the Plus widget directly (launcher shows its own confirmation).
        val manager = AppWidgetManager.getInstance(this)
        if (widgetId == null && manager.isRequestPinAppWidgetSupported) {
            findViewById<View>(R.id.pin).apply {
                visibility = View.VISIBLE
                setOnClickListener { manager.requestPinAppWidget(ComponentName(context, WidgetPlus::class.java), null, null) }
            }
        }

        sizePreview()
        renderPreview()
        if (Library.loaded || !Library.hasPermission()) loadArt() else Library.refresh { renderPreview(); loadArt() }
    }

    private fun loadArt() {
        val song = Widgets.currentState(this).song ?: return
        lifecycleScope.launch {
            art = withContext(Dispatchers.IO) { ArtLoader.loadBitmap(ArtKey.Song(song.id, song.albumId), provider.requestedArtSize) }
            renderPreview()
        }
    }

    private fun change(next: WidgetOptions) {
        options = next
        val id = widgetId
        if (id != null) WidgetOptions.save(this, id, next) else WidgetOptions.saveAll(this, next)
        Widgets.update(this)
        renderPreview()
    }

    private fun renderPreview() {
        host.removeAllViews()
        // Inflated like a launcher does: the activity's AppCompat inflater would swap in views RemoteViews can't drive.
        val view = Widgets.preview(this, provider, options, art).apply(applicationContext, host)
        host.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Uses the widget's real size when the launcher reports it, otherwise its default cell size. */
    private fun sizePreview() {
        val density = resources.displayMetrics.density
        val reported = widgetId?.let { AppWidgetManager.getInstance(this).getAppWidgetOptions(it) }
        var w = reported?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        var h = reported?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
        if (w <= 0 || h <= 0) {
            val (dw, dh) = when (provider) {
                is WidgetSmall -> 340 to 72
                is WidgetSquare -> 180 to 180
                is WidgetLarge -> 340 to 340
                else -> 340 to 160
            }
            w = dw; h = dh
        }
        val maxW = resources.displayMetrics.widthPixels / density - 32
        host.layoutParams = host.layoutParams.apply {
            width = (minOf(w.toFloat(), maxW) * density).toInt()
            height = (h * density).toInt()
        }
    }
}
