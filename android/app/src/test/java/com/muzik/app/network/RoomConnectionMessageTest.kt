package com.muzik.app.network

import com.muzik.app.model.VideoSummary
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomConnectionMessageTest {
    @Test
    fun reorderMessageNamesTheItemAndItsSuccessor() {
        val message = queueReorderMessage(itemId = "item-2", beforeItemId = "item-1")

        assertEquals("queue_reorder", message["type"]?.jsonPrimitive?.content)
        assertEquals("item-2", message["itemId"]?.jsonPrimitive?.content)
        assertEquals("item-1", message["beforeItemId"]?.jsonPrimitive?.content)
    }

    @Test
    fun reorderMessageOmitsSuccessorWhenMovingToTierEnd() {
        val message = queueReorderMessage(itemId = "item-2", beforeItemId = null)

        assertEquals("queue_reorder", message["type"]?.jsonPrimitive?.content)
        assertFalse(message.containsKey("beforeItemId"))
    }

    @Test
    fun playNextMessageUsesQueuePlayNextType() {
        val message = queuePlayNextMessage(itemId = "item-3")

        assertEquals("queue_play_next", message["type"]?.jsonPrimitive?.content)
        assertEquals("item-3", message["itemId"]?.jsonPrimitive?.content)
    }

    @Test
    fun pauseMessagesUseRequestAndExplicitYesNoVotes() {
        assertEquals(
            "pause_request",
            pauseRequestMessage()["type"]?.jsonPrimitive?.content,
        )
        val yes = pauseVoteMessage(true, "poll-1")
        val no = pauseVoteMessage(false, "poll-1")
        assertEquals("yes", yes["vote"]?.jsonPrimitive?.content)
        assertEquals("no", no["vote"]?.jsonPrimitive?.content)
        assertEquals("poll-1", yes["pollId"]?.jsonPrimitive?.content)
    }

    @Test
    fun chatMessagesCarryTextAndModerationTargets() {
        val send = chatSendMessage("Hello")
        val delete = chatDeleteMessage("message-1")
        val mute = chatMuteMessage("member-2", true)

        assertEquals("chat_send", send["type"]?.jsonPrimitive?.content)
        assertEquals("Hello", send["text"]?.jsonPrimitive?.content)
        assertEquals("message-1", delete["messageId"]?.jsonPrimitive?.content)
        assertEquals("member-2", mute["memberId"]?.jsonPrimitive?.content)
        assertTrue(mute["muted"]?.jsonPrimitive?.content?.toBoolean() == true)
    }

    @Test
    fun playlistBatchMessageCarriesOneAtomicRequest() {
        val message = queueAddManyMessage(
            requestId = "request-1",
            videos = listOf(
                VideoSummary(videoId = "aaaaaaaaaaa", title = "First"),
                VideoSummary(videoId = "bbbbbbbbbbb", title = "Second"),
            ),
            startPlayback = true,
        )

        assertEquals("queue_add_many", message["type"]?.jsonPrimitive?.content)
        assertEquals("request-1", message["requestId"]?.jsonPrimitive?.content)
        assertEquals(2, message["videos"]?.jsonArray?.size)
        assertTrue(message["startPlayback"]?.jsonPrimitive?.content?.toBoolean() == true)
    }

    @Test
    fun unauthorizedWebSocketHandshakeIsATerminalMembershipRejection() {
        val direct = IllegalStateException(
            "Handshake exception, expected status code 101 but was 401",
        )
        val wrapped = IllegalStateException(
            "Connection failed",
            IllegalStateException("WebSocket handshake failed: 401 Unauthorized"),
        )

        assertTrue(isTerminalMembershipRejection(direct))
        assertTrue(isTerminalMembershipRejection(wrapped))
    }

    @Test
    fun transientOrUnrelatedConnectionErrorsRemainRetryable() {
        assertFalse(
            isTerminalMembershipRejection(
                IllegalStateException("Handshake exception, expected status code 101 but was 503"),
            ),
        )
        assertFalse(isTerminalMembershipRejection(IllegalStateException("Request failed after 401ms")))
        assertFalse(isTerminalMembershipRejection(IllegalStateException("Network is unreachable")))
    }
}
