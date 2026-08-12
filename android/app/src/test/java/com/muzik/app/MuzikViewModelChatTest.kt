package com.muzik.app

import com.muzik.app.model.ChatMessage
import com.muzik.app.model.Me
import com.muzik.app.model.PlaybackState
import com.muzik.app.model.RoomSnapshot
import com.muzik.app.model.SearchResponse
import com.muzik.app.model.SkipState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MuzikViewModelChatTest {
    @Test
    fun appendingChatEventsDeduplicatesAndKeepsTheNewestHundred() {
        var room = room()
        repeat(101) { index ->
            room = appendChatMessage(room, message(index))
        }

        assertEquals(100, room.chat.size)
        assertEquals("chat-1", room.chat.first().id)
        assertEquals("chat-100", room.chat.last().id)

        val duplicateResult = appendChatMessage(room, message(100))
        assertSame(room, duplicateResult)
        assertEquals(100, duplicateResult.chat.size)
    }

    @Test
    fun chatSendReturnsAcceptanceAndNormalizesText() {
        var sentText: String? = null
        var accept = true
        val viewModel = MuzikViewModel(
            initialState = MuzikUiState(),
            searchRequest = { _, _ -> SearchResponse() },
            chatSendRequest = { text ->
                sentText = text
                accept
            },
        )

        assertTrue(viewModel.sendChat("  Hello room  "))
        assertEquals("Hello room", sentText)

        accept = false
        assertFalse(viewModel.sendChat("Try again"))
        assertFalse(viewModel.sendChat("   "))
        assertEquals("Try again", sentText)
    }

    private fun room() = RoomSnapshot(
        code = "ABC123",
        serverTimeMs = 1,
        me = Me(id = "member-1", displayName = "Host", isHost = true),
        members = emptyList(),
        queue = emptyList(),
        playback = PlaybackState(),
        skip = SkipState(votes = 0, threshold = 1, votedByMe = false),
    )

    private fun message(index: Int) = ChatMessage(
        id = "chat-$index",
        memberId = "member-1",
        displayName = "Host",
        text = "Message $index",
        sentAt = index.toLong(),
    )
}
