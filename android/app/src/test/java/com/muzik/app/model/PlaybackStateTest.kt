package com.muzik.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStateTest {
    @Test
    fun playingPositionAdvancesFromServerAnchor() {
        val state = PlaybackState(
            status = "playing",
            positionMs = 5_000,
            anchorServerTimeMs = 10_000,
        )
        assertEquals(7_500, state.expectedPositionMs(12_500))
    }

    @Test
    fun pausedPositionDoesNotAdvance() {
        val state = PlaybackState(
            status = "paused",
            positionMs = 5_000,
            anchorServerTimeMs = 10_000,
        )
        assertEquals(5_000, state.expectedPositionMs(30_000))
    }
}
