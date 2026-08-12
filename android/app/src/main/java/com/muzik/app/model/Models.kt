package com.muzik.app.model

import kotlinx.serialization.Serializable

@Serializable
data class Membership(
    val roomCode: String,
    val memberId: String,
    val memberToken: String,
    val displayName: String,
    val isHost: Boolean,
)

@Serializable
data class VideoSummary(
    val videoId: String,
    val title: String,
    val channelTitle: String = "",
    val thumbnailUrl: String = "",
    val durationMs: Long? = null,
)

@Serializable
data class SearchResponse(val results: List<VideoSummary> = emptyList())

@Serializable
data class MemberSummary(
    val id: String,
    val displayName: String,
    val isHost: Boolean,
    val connected: Boolean,
    val songsAddedCount: Int = 0,
    val chatMuted: Boolean = false,
)

@Serializable
data class ChatMessage(
    val id: String,
    val memberId: String,
    val displayName: String,
    val text: String,
    val sentAt: Long,
)

@Serializable
data class ChatMessageEnvelope(
    val type: String,
    val message: ChatMessage,
)

@Serializable
data class QueueImportResultEnvelope(
    val type: String,
    val requestId: String,
    val addedCount: Int,
    val startedPlayback: Boolean,
)

@Serializable
data class Me(
    val id: String,
    val displayName: String,
    val isHost: Boolean,
)

@Serializable
data class QueueItem(
    val id: String,
    val video: VideoSummary,
    val addedBy: String,
    val addedByName: String? = null,
    val addedAt: Long,
    val voteCount: Int,
    val votedByMe: Boolean,
    val isForcedNext: Boolean = false,
)

@Serializable
data class HistoryItem(
    val id: String,
    val video: VideoSummary,
    val addedBy: String,
    val addedByName: String? = null,
    val addedAt: Long,
    val playedAt: Long,
)

@Serializable
data class PlaybackState(
    val video: VideoSummary? = null,
    val status: String = "idle",
    val positionMs: Long = 0,
    val anchorServerTimeMs: Long = 0,
    val revision: Long = 0,
    val addedBy: String? = null,
    val addedByName: String? = null,
) {
    fun expectedPositionMs(estimatedServerNowMs: Long): Long =
        if (status == "playing") {
            positionMs + (estimatedServerNowMs - anchorServerTimeMs).coerceAtLeast(0)
        } else {
            positionMs
        }
}

@Serializable
data class SkipState(
    val votes: Int,
    val threshold: Int,
    val votedByMe: Boolean,
)

@Serializable
data class PauseVoteState(
    val id: String? = null,
    val requestedBy: String,
    val requestedByName: String,
    val yesVotes: Int,
    val noVotes: Int,
    val threshold: Int,
    val eligibleVoters: Int? = null,
    val myVote: String? = null,
    val expiresAt: Long,
    val startedAt: Long? = null,
)

@Serializable
data class RoomSnapshot(
    val code: String,
    val serverTimeMs: Long,
    val me: Me,
    val members: List<MemberSummary>,
    val queue: List<QueueItem>,
    val playback: PlaybackState,
    val skip: SkipState,
    val history: List<HistoryItem> = emptyList(),
    val chat: List<ChatMessage> = emptyList(),
    val pauseVote: PauseVoteState? = null,
)

@Serializable
data class RoomSnapshotEnvelope(
    val type: String,
    val room: RoomSnapshot,
)

@Serializable
data class PongEnvelope(
    val type: String,
    val nonce: String,
    val clientTimeMs: Long,
    val serverTimeMs: Long,
)

enum class ConnectionStatus { Disconnected, Connecting, Connected }
