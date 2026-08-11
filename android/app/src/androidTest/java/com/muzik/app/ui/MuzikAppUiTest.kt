package com.muzik.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.muzik.app.MuzikUiState
import com.muzik.app.MuzikViewModel
import com.muzik.app.model.ChatMessage
import com.muzik.app.model.Me
import com.muzik.app.model.MemberSummary
import com.muzik.app.model.Membership
import com.muzik.app.model.PlaybackState
import com.muzik.app.model.RoomSnapshot
import com.muzik.app.model.SearchResponse
import com.muzik.app.model.SkipState
import org.junit.Rule
import org.junit.Test

class MuzikAppUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun homeScreenShowsCreateAndJoinActions() {
        val state = MuzikUiState()
        val viewModel = testViewModel(state)

        compose.setContent {
            MuzikTheme { MuzikApp(state = state, viewModel = viewModel) }
        }

        compose.onNodeWithText("Create a room").assertIsDisplayed()
        compose.onNodeWithText("Join room").assertIsDisplayed()
    }

    @Test
    fun activeRoomShowsChatAndPlaylistImport() {
        val membership = membership()
        val state = MuzikUiState(
            membership = membership,
            room = RoomSnapshot(
                code = membership.roomCode,
                serverTimeMs = 2_000,
                me = Me(membership.memberId, membership.displayName, isHost = true),
                members = listOf(
                    MemberSummary(
                        id = membership.memberId,
                        displayName = membership.displayName,
                        isHost = true,
                        connected = true,
                    ),
                ),
                queue = emptyList(),
                playback = PlaybackState(),
                skip = SkipState(votes = 0, threshold = 1, votedByMe = false),
                chat = listOf(
                    ChatMessage(
                        id = "chat-1",
                        memberId = membership.memberId,
                        displayName = membership.displayName,
                        text = "Hello room",
                        sentAt = 1_000,
                    ),
                ),
            ),
        )
        val viewModel = testViewModel(state)

        compose.setContent {
            MuzikTheme { MuzikApp(state = state, viewModel = viewModel) }
        }

        compose.onNodeWithText("Import a YouTube playlist").assertIsDisplayed()
        compose.onNodeWithText("Room chat").assertIsDisplayed()
        compose.onNodeWithText("Hello room").assertIsDisplayed()
    }

    private fun testViewModel(state: MuzikUiState) = MuzikViewModel(
        initialState = state,
        searchRequest = { _, _ -> SearchResponse() },
    )

    private fun membership() = Membership(
        roomCode = "ABC123",
        memberId = "member-1",
        memberToken = "secret-token",
        displayName = "Host",
        isHost = true,
    )
}
