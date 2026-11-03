package de.shakie.playernext.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * Player handed to the media session. Shuffle physically reorders the queue (like
 * PlayerPro) instead of using a hidden shuffle order, so the queue list, the "n/m"
 * counter and drag & drop always match what plays next. ExoPlayer's own shuffle flag is
 * kept in sync but backed by an identity shuffle order.
 */
class QueuePlayer(private val exo: ExoPlayer, private val hooks: Hooks) : ForwardingPlayer(exo) {

    interface Hooks {
        /** Called before a user initiated track change while playing. */
        fun beforeManualSkip(forward: Boolean)

        /** Called before the queue grows to [count] items (the system queue is cut off for large ones). */
        fun beforeQueueGrows(count: Int)
    }

    /** Queue order before shuffling, as mediaIds. */
    private var originalOrder: MutableList<String>? = null

    init {
        exo.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(0))
    }

    val currentOriginalOrder: List<String>? get() = originalOrder

    fun restoreShuffleState(enabled: Boolean, original: List<String>?) {
        originalOrder = if (enabled) original?.toMutableList() else null
        exo.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(exo.mediaItemCount))
        exo.shuffleModeEnabled = enabled
    }

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        if (shuffleModeEnabled == exo.shuffleModeEnabled) return
        if (shuffleModeEnabled) shuffleInPlace() else restoreOrder()
        exo.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(exo.mediaItemCount))
        super.setShuffleModeEnabled(shuffleModeEnabled)
    }

    private fun items(): List<MediaItem> = (0 until exo.mediaItemCount).map { exo.getMediaItemAt(it) }

    private fun shuffleInPlace() {
        val count = exo.mediaItemCount
        val all = items()
        originalOrder = all.map { it.mediaId }.toMutableList()
        if (count < 2) return
        val cur = exo.currentMediaItemIndex.coerceIn(0, count - 1)
        val rest = all.filterIndexed { i, _ -> i != cur }.shuffled()
        if (cur + 1 < count) exo.removeMediaItems(cur + 1, count)
        if (cur > 0) exo.removeMediaItems(0, cur)
        exo.addMediaItems(rest)
    }

    private fun restoreOrder() {
        val original = originalOrder ?: return
        originalOrder = null
        val count = exo.mediaItemCount
        if (count < 2) return
        val present = items().associateBy { it.mediaId }
        val known = original.toHashSet()
        val target = original.filter { it in present } + present.keys.filter { it !in known }
        val curItem = exo.currentMediaItem ?: return
        val pos = target.indexOf(curItem.mediaId).takeIf { it >= 0 } ?: return
        val cur = exo.currentMediaItemIndex
        if (cur + 1 < count) exo.removeMediaItems(cur + 1, count)
        if (cur > 0) exo.removeMediaItems(0, cur)
        exo.addMediaItems(0, target.subList(0, pos).map { present.getValue(it) })
        exo.addMediaItems(target.subList(pos + 1, target.size).map { present.getValue(it) })
    }

    /**
     * Start of a new round with shuffle and "repeat all": while the last song plays, everything
     * before it gets a fresh order, so the next round is new and still has every song once.
     * The songs heard last don't come right at the start of the new round.
     */
    fun reshuffleBeforeLast() {
        val count = exo.mediaItemCount
        val cur = exo.currentMediaItemIndex
        if (count < 3 || cur != count - 1) return
        val before = (0 until cur).map { exo.getMediaItemAt(it) }
        val keepAway = minOf(20, before.size / 2)
        val recent = before.takeLast(keepAway)
        val fresh = before.dropLast(keepAway).shuffled().toMutableList()
        recent.forEach { fresh.add(Random.nextInt(minOf(keepAway, fresh.size), fresh.size + 1), it) }
        // Add first, then remove the old copies: the current index never points past the list.
        exo.addMediaItems(0, fresh)
        exo.removeMediaItems(fresh.size, fresh.size + cur)
    }

    /** Positions of the current queue items in unshuffled order (for saving), or null. */
    fun originalPositions(): IntArray? {
        val original = originalOrder ?: return null
        val index = HashMap<String, Int>(exo.mediaItemCount * 2)
        for (i in 0 until exo.mediaItemCount) index[exo.getMediaItemAt(i).mediaId] = i
        return original.mapNotNull { index[it] }.toIntArray()
    }

    // --- new queues keep the chosen start item first when shuffle is on ---------------------

    override fun setMediaItems(mediaItems: List<MediaItem>) = setMediaItems(mediaItems, true)

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        hooks.beforeQueueGrows(mediaItems.size)
        if (exo.shuffleModeEnabled && mediaItems.size > 1) {
            setShuffled(mediaItems, 0, 0)
        } else {
            originalOrder = null
            super.setMediaItems(mediaItems, resetPosition)
        }
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        // Playback resumption hands back the queue that is already loaded: keep it (and its order).
        if (isCurrentQueue(mediaItems)) {
            if (startIndex != exo.currentMediaItemIndex) super.seekTo(startIndex, startPositionMs)
            return
        }
        hooks.beforeQueueGrows(mediaItems.size)
        if (exo.shuffleModeEnabled && mediaItems.size > 1) {
            setShuffled(mediaItems, startIndex, startPositionMs)
        } else {
            originalOrder = null
            super.setMediaItems(mediaItems, startIndex, startPositionMs)
        }
    }

    private fun isCurrentQueue(items: List<MediaItem>): Boolean =
        items.size == exo.mediaItemCount && items.isNotEmpty() && items.indices.all { items[it].mediaId == exo.getMediaItemAt(it).mediaId }

    private fun setShuffled(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        val start = startIndex.coerceIn(0, items.size - 1)
        originalOrder = items.map { it.mediaId }.toMutableList()
        val ordered = listOf(items[start]) + items.filterIndexed { i, _ -> i != start }.shuffled()
        super.setMediaItems(ordered, 0, if (startPositionMs == androidx.media3.common.C.TIME_UNSET) 0 else startPositionMs)
    }

    override fun addMediaItems(mediaItems: List<MediaItem>) {
        hooks.beforeQueueGrows(exo.mediaItemCount + mediaItems.size)
        originalOrder?.addAll(mediaItems.map { it.mediaId })
        super.addMediaItems(mediaItems)
    }

    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
        hooks.beforeQueueGrows(exo.mediaItemCount + mediaItems.size)
        originalOrder?.let { orig ->
            // "Play next" lands right after the current song in the unshuffled order too.
            val cur = exo.currentMediaItem?.mediaId
            val at = if (index <= exo.currentMediaItemIndex + 1 && cur != null) orig.indexOf(cur) + 1 else orig.size
            orig.addAll(at.coerceIn(0, orig.size), mediaItems.map { it.mediaId })
        }
        super.addMediaItems(index, mediaItems)
    }

    override fun clearMediaItems() {
        originalOrder = if (exo.shuffleModeEnabled) mutableListOf() else null
        super.clearMediaItems()
    }

    // --- manual track changes ------------------------------------------------------------

    override fun seekToNext() {
        hooks.beforeManualSkip(true)
        super.seekToNext()
    }

    override fun seekToNextMediaItem() {
        hooks.beforeManualSkip(true)
        super.seekToNextMediaItem()
    }

    override fun seekToPrevious() {
        if (exo.currentPosition <= exo.maxSeekToPreviousPosition) hooks.beforeManualSkip(false)
        super.seekToPrevious()
    }

    override fun seekToPreviousMediaItem() {
        hooks.beforeManualSkip(false)
        super.seekToPreviousMediaItem()
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex != exo.currentMediaItemIndex) hooks.beforeManualSkip(true)
        super.seekTo(mediaItemIndex, positionMs)
    }
}
