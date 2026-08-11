package com.muzik.app.network

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
