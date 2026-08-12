package com.muzik.app.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoMetadataCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesLegacyVideoWithoutDuration() {
        val video = json.decodeFromString<VideoSummary>(
            """{"videoId":"abcdefghijk","title":"Legacy","channelTitle":"Channel","thumbnailUrl":"https://example.test/thumb.jpg"}""",
        )

        assertEquals("Legacy", video.title)
        assertNull(video.durationMs)
    }

    @Test
    fun decodesLegacyQueueItemWithoutContributorName() {
        val item = json.decodeFromString<QueueItem>(
            """{"id":"item-1","video":{"videoId":"abcdefghijk","title":"Song"},"addedBy":"member-1","addedAt":1,"voteCount":1,"votedByMe":true}""",
        )

        assertEquals("member-1", item.addedBy)
        assertNull(item.addedByName)
        assertFalse(item.isForcedNext)
    }

    @Test
    fun decodesEnrichedVideoAndContributorMetadata() {
        val item = json.decodeFromString<QueueItem>(
            """{"id":"item-2","video":{"videoId":"abcdefghijk","title":"Song","channelTitle":"Channel","thumbnailUrl":"https://example.test/thumb.jpg","durationMs":125000},"addedBy":"member-2","addedByName":"Listener","addedAt":2,"voteCount":1,"votedByMe":false}""",
        )

        assertEquals(125_000L, item.video.durationMs)
        assertEquals("Listener", item.addedByName)
    }

    @Test
    fun decodesStandaloneChatMessageEvent() {
        val envelope = json.decodeFromString<ChatMessageEnvelope>(
            """{"type":"chat_message","message":{"id":"chat-1","memberId":"member-1","displayName":"Listener","text":"Hello","sentAt":123}}""",
        )

        assertEquals("chat_message", envelope.type)
        assertEquals("chat-1", envelope.message.id)
        assertEquals("Hello", envelope.message.text)
    }

    @Test
    fun decodesPlaylistImportResult() {
        val envelope = json.decodeFromString<QueueImportResultEnvelope>(
            """{"type":"queue_import_result","requestId":"request-1","addedCount":12,"startedPlayback":true}""",
        )

        assertEquals("request-1", envelope.requestId)
        assertEquals(12, envelope.addedCount)
        assertTrue(envelope.startedPlayback)
    }

    @Test
    fun decodesLegacySnapshotWithoutHistoryAttributionCountsOrPauseVote() {
        val snapshot = json.decodeFromString<RoomSnapshot>(
            """
                {
                  "code":"ABC123",
                  "serverTimeMs":100,
                  "me":{"id":"member-1","displayName":"Host","isHost":true},
                  "members":[
                    {"id":"member-1","displayName":"Host","isHost":true,"connected":true}
                  ],
                  "queue":[],
                  "playback":{
                    "video":null,
                    "status":"idle",
                    "positionMs":0,
                    "anchorServerTimeMs":100,
                    "revision":0
                  },
                  "skip":{"votes":0,"threshold":1,"votedByMe":false}
                }
            """.trimIndent(),
        )

        assertTrue(snapshot.history.isEmpty())
        assertTrue(snapshot.chat.isEmpty())
        assertEquals(0, snapshot.members.single().songsAddedCount)
        assertFalse(snapshot.members.single().chatMuted)
        assertNull(snapshot.playback.addedBy)
        assertNull(snapshot.playback.addedByName)
        assertNull(snapshot.pauseVote)
    }

    @Test
    fun decodesHistoryCurrentAttributionQueuePriorityAndPauseVote() {
        val snapshot = json.decodeFromString<RoomSnapshot>(
            """
                {
                  "code":"ABC123",
                  "serverTimeMs":200,
                  "me":{"id":"member-1","displayName":"Host","isHost":true},
                  "members":[
                    {
                      "id":"member-1",
                      "displayName":"Host",
                      "isHost":true,
                      "connected":true,
                      "songsAddedCount":3,
                      "chatMuted":true
                    }
                  ],
                  "queue":[
                    {
                      "id":"queue-1",
                      "video":{"videoId":"abcdefghijk","title":"Next"},
                      "addedBy":"member-1",
                      "addedByName":"Host",
                      "addedAt":150,
                      "voteCount":1,
                      "votedByMe":true,
                      "isForcedNext":true
                    }
                  ],
                  "playback":{
                    "video":{"videoId":"lmnopqrstuv","title":"Current"},
                    "status":"playing",
                    "positionMs":0,
                    "anchorServerTimeMs":200,
                    "revision":2,
                    "addedBy":"member-1",
                    "addedByName":"Host"
                  },
                  "skip":{"votes":0,"threshold":1,"votedByMe":false},
                  "history":[
                    {
                      "id":"history-1",
                      "video":{"videoId":"12345678901","title":"Previous"},
                      "addedBy":"member-1",
                      "addedByName":"Host",
                      "addedAt":50,
                      "playedAt":100
                    }
                  ],
                  "chat":[
                    {
                      "id":"chat-1",
                      "memberId":"member-1",
                      "displayName":"Host",
                      "text":"Hello room",
                      "sentAt":175
                    }
                  ],
                  "pauseVote":{
                    "requestedBy":"member-1",
                    "requestedByName":"Host",
                    "yesVotes":1,
                    "noVotes":0,
                    "threshold":2,
                    "myVote":"yes",
                    "expiresAt":1000,
                    "startedAt":500
                  }
                }
            """.trimIndent(),
        )

        assertEquals(3, snapshot.members.single().songsAddedCount)
        assertTrue(snapshot.members.single().chatMuted)
        assertTrue(snapshot.queue.single().isForcedNext)
        assertEquals("Host", snapshot.playback.addedByName)
        assertEquals("Previous", snapshot.history.single().video.title)
        assertEquals("Hello room", snapshot.chat.single().text)
        assertNotNull(snapshot.pauseVote)
        assertEquals("yes", snapshot.pauseVote?.myVote)
        assertEquals(500L, snapshot.pauseVote?.startedAt)
        assertNull(snapshot.pauseVote?.id)
        assertNull(snapshot.pauseVote?.eligibleVoters)
    }
}
