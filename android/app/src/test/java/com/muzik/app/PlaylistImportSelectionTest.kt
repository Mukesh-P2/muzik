package com.muzik.app

import com.muzik.app.model.Me
import com.muzik.app.model.PlaybackState
import com.muzik.app.model.QueueItem
import com.muzik.app.model.RoomSnapshot
import com.muzik.app.model.SkipState
import com.muzik.app.model.VideoSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistImportSelectionTest {
    @Test
    fun removesDuplicatesCurrentVideoAndExistingQueueItems() {
        val current = video("aaaaaaaaaaa")
        val queued = video("bbbbbbbbbbb")
        val fresh = video("ccccccccccc")
        val room = room(
            playback = PlaybackState(video = current, status = "playing"),
            queue = listOf(queueItem(queued)),
        )

        val selected = selectPlaylistImports(
            listOf(current, queued, fresh, fresh),
            room,
        )

        assertEquals(listOf(fresh), selected)
    }

    @Test
    fun respectsTheRemainingRoomQueueCapacity() {
        val queue = (0 until 99).map { index ->
            queueItem(video(videoId(index)))
        }
        val room = room(playback = PlaybackState(), queue = queue)
        val candidates = listOf(video("zzzzzzzzzzz"), video("yyyyyyyyyyy"))

        assertEquals(listOf(candidates.first()), selectPlaylistImports(candidates, room))
    }

    private fun room(playback: PlaybackState, queue: List<QueueItem>) = RoomSnapshot(
        code = "ABC123",
        serverTimeMs = 1,
        me = Me(id = "member-1", displayName = "Host", isHost = true),
        members = emptyList(),
        queue = queue,
        playback = playback,
        skip = SkipState(votes = 0, threshold = 1, votedByMe = false),
    )

    private fun queueItem(video: VideoSummary) = QueueItem(
        id = "item-${video.videoId}",
        video = video,
        addedBy = "member-1",
        addedAt = 1,
        voteCount = 1,
        votedByMe = true,
    )

    private fun video(id: String) = VideoSummary(videoId = id, title = id)

    private fun videoId(index: Int): String = index.toString().padStart(11, '0')
}
