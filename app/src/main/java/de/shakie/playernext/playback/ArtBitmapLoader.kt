package de.shakie.playernext.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import de.shakie.playernext.art.ArtKey
import de.shakie.playernext.art.ArtLoader
import java.util.concurrent.Executors

/** Serves "playernext://" artwork URIs (notification, lock screen, Android Auto) from ArtLoader. */
@UnstableApi
class ArtBitmapLoader : BitmapLoader {
    private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun supportsMimeType(mimeType: String) = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = executor.submit<Bitmap> {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw IllegalArgumentException("undecodable artwork")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (uri.scheme != QueueItems.ART_SCHEME) return Futures.immediateFailedFuture(IllegalArgumentException("unsupported $uri"))
        return executor.submit<Bitmap> {
            val seg = uri.pathSegments
            val key = when (uri.host) {
                "song" -> ArtKey.Song(seg[0].toLong(), seg[1].toLong())
                else -> ArtKey.Album(seg[0].toLong())
            }
            ArtLoader.loadBitmap(key, 512) ?: throw IllegalStateException("no artwork")
        }
    }
}
