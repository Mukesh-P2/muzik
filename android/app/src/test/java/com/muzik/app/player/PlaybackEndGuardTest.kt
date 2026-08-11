package com.muzik.app.player

import com.muzik.app.model.PlaybackState
import com.muzik.app.model.VideoSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackEndGuardTest {
    private val first = playback("aaaaaaaaaaa", revision = 1)
    private val second = playback("bbbbbbbbbbb", revision = 2)

    @Test
    fun ignoresOldEndedEventAfterANewRevisionIsScheduled() {
        val guard = PlaybackEndGuard()
        guard.revisionScheduled()
        guard.commandDispatched(first.revision)
        guard.playerStarted(first.video!!.videoId, first)

        guard.revisionScheduled()

        assertNull(guard.playerEnded(first.video.videoId, second))
    }

    @Test
    fun advancesOnlyOnceAfterTheCurrentRevisionActuallyStarts() {
        val guard = PlaybackEndGuard()
        guard.revisionScheduled()
        guard.commandDispatched(second.revision)

        assertNull(guard.playerEnded(second.video!!.videoId, second))
        guard.playerStarted(second.video.videoId, second)
        assertEquals(second.revision, guard.playerEnded(second.video.videoId, second))
        assertNull(guard.playerEnded(second.video.videoId, second))
    }

    @Test
    fun doesNotAdvanceAPausedRoom() {
        val guard = PlaybackEndGuard()
        guard.revisionScheduled()
        guard.commandDispatched(first.revision)
        guard.playerStarted(first.video!!.videoId, first)

        assertNull(guard.playerEnded(first.video.videoId, first.copy(status = "paused")))
    }

    private fun playback(videoId: String, revision: Long) = PlaybackState(
        video = VideoSummary(videoId = videoId, title = "Track $revision"),
        status = "playing",
        revision = revision,
    )
}
