package de.shakie.playernext.ui.video

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import de.shakie.playernext.Prefs
import de.shakie.playernext.R
import de.shakie.playernext.data.Library
import de.shakie.playernext.data.Video
import de.shakie.playernext.playback.PlayerHub
import de.shakie.playernext.ui.BaseActivity
import de.shakie.playernext.util.dp

/** Video playback with its own short-lived player; music pauses while a video plays. */
@OptIn(UnstableApi::class)
class VideoPlayerActivity : BaseActivity() {
    companion object {
        fun start(context: Context, video: Video) =
            context.startActivity(Intent(context, VideoPlayerActivity::class.java).putExtra("id", video.id))
    }

    override val followsOrientationLock = false
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var title: TextView
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private val positions by lazy { getSharedPreferences("video_positions", MODE_PRIVATE) }
    private var videos: List<Video> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        playerView = PlayerView(this).apply {
            setShowNextButton(true)
            setShowPreviousButton(true)
            controllerShowTimeoutMs = 3500
            keepScreenOn = true
        }
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x99000000.toInt())
        }
        fun button(icon: Int, desc: Int, click: () -> Unit) = ImageButton(this).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            background = getDrawable(R.drawable.round_ripple)
            contentDescription = getString(desc)
            setOnClickListener { click() }
        }
        bar.addView(button(R.drawable.ic_back, R.string.back) { finish() }, LinearLayout.LayoutParams(dp(52), dp(52)))
        title = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 17f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(button(R.drawable.ic_aspect, R.string.video_scale) { cycleResize() }, LinearLayout.LayoutParams(dp(52), dp(52)))
        bar.addView(button(R.drawable.ic_rotate, R.string.video_rotate) { rotate() }, LinearLayout.LayoutParams(dp(52), dp(52)))
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> bar.visibility = v })
        setContentView(root)
        hideSystemBars()

        val id = intent.getLongExtra("id", -1)
        videos = Library.snapshot.videos
        val start = videos.indexOfFirst { it.id == id }
        if (start < 0) {
            finish()
            return
        }
        PlayerHub.controller?.pause()
        val p = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        val list = if (Prefs.playNextVideo) videos else listOf(videos[start])
        val index = if (Prefs.playNextVideo) start else 0
        p.setMediaItems(list.map { v ->
            MediaItem.Builder().setUri(v.uri).setMediaId(v.path)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(v.title).build()).build()
        }, index, positions.getLong(list[index].path, 0))
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                title.text = mediaItem?.mediaMetadata?.title
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    p.currentMediaItem?.mediaId?.let { positions.edit().remove(it).apply() }
                }
            }
        })
        title.text = list[index].title
        p.prepare()
        p.play()
        playerView.player = p
        player = p
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun cycleResize() {
        resizeMode = when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView.resizeMode = resizeMode
        val label = when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> R.string.video_scale_zoom
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> R.string.video_scale_stretch
            else -> R.string.video_scale_fit
        }
        android.widget.Toast.makeText(this, label, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun rotate() {
        requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        player?.let { p ->
            p.currentMediaItem?.mediaId?.let { id ->
                if (p.playbackState != Player.STATE_ENDED) positions.edit().putLong(id, p.currentPosition).apply()
            }
            p.pause()
        }
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    @Suppress("unused")
    private fun visibleFlag(v: Boolean) = if (v) View.VISIBLE else View.GONE
}
