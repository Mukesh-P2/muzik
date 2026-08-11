package com.muzik.app.player

import com.muzik.app.model.PlaybackState

internal class PlaybackEndGuard {
    private var dispatchedRevision: Long? = null
    private var eligibleRevision: Long? = null

    fun revisionScheduled() {
        dispatchedRevision = null
        eligibleRevision = null
    }

    fun commandDispatched(revision: Long) {
        dispatchedRevision = revision
    }

    fun playerStarted(videoId: String, playback: PlaybackState?) {
        if (
            playback?.status == "playing" &&
            playback.video?.videoId == videoId &&
            dispatchedRevision == playback.revision
        ) {
            eligibleRevision = playback.revision
        }
    }

    fun playerEnded(videoId: String, playback: PlaybackState?): Long? {
        val revision = eligibleRevision ?: return null
        if (
            playback?.status != "playing" ||
            playback.revision != revision ||
            playback.video?.videoId != videoId
        ) {
            return null
        }
        eligibleRevision = null
        return revision
    }
}
