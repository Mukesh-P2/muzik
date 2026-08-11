package com.muzik.app

import com.muzik.app.model.Membership
import com.muzik.app.model.SearchResponse
import com.muzik.app.model.VideoSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MuzikViewModelSearchTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun dismissSearchCancelsAndRejectsAStaleResponse() = runTest {
        val cancellationObserved = CompletableDeferred<Unit>()
        val staleVideo = VideoSummary(videoId = "abcdefghijk", title = "Stale result")
        val membership = Membership(
            roomCode = "ABC123",
            memberId = "member-1",
            memberToken = "token",
            displayName = "Listener",
            isHost = false,
        )
        val viewModel = MuzikViewModel(
            initialState = MuzikUiState(
                membership = membership,
                searchQuery = "ambient",
                searchResults = listOf(
                    VideoSummary(videoId = "12345678901", title = "Previous result"),
                ),
            ),
            searchRequest = { _, _ ->
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    cancellationObserved.complete(Unit)
                }
                SearchResponse(results = listOf(staleVideo))
            },
        )

        viewModel.search()
        runCurrent()
        assertTrue(viewModel.uiState.value.searching)

        viewModel.dismissSearch()
        assertEquals("", viewModel.uiState.value.searchQuery)
        assertTrue(viewModel.uiState.value.searchResults.isEmpty())
        assertFalse(viewModel.uiState.value.searching)

        runCurrent()
        assertTrue(cancellationObserved.isCompleted)
        assertTrue(viewModel.uiState.value.searchResults.isEmpty())
        assertFalse(viewModel.uiState.value.searching)
    }

    @Test
    fun changingQueryCancelsAndRejectsThePreviousResponse() = runTest {
        val cancellationObserved = CompletableDeferred<Unit>()
        val staleVideo = VideoSummary(videoId = "abcdefghijk", title = "Stale result")
        val membership = Membership(
            roomCode = "ABC123",
            memberId = "member-1",
            memberToken = "token",
            displayName = "Listener",
            isHost = false,
        )
        val viewModel = MuzikViewModel(
            initialState = MuzikUiState(
                membership = membership,
                searchQuery = "ambient",
            ),
            searchRequest = { _, _ ->
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    cancellationObserved.complete(Unit)
                }
                SearchResponse(results = listOf(staleVideo))
            },
        )

        viewModel.search()
        runCurrent()
        assertTrue(viewModel.uiState.value.searching)

        viewModel.setSearchQuery("jazz")
        assertEquals("jazz", viewModel.uiState.value.searchQuery)
        assertTrue(viewModel.uiState.value.searchResults.isEmpty())
        assertFalse(viewModel.uiState.value.searching)

        runCurrent()
        assertTrue(cancellationObserved.isCompleted)
        assertEquals("jazz", viewModel.uiState.value.searchQuery)
        assertTrue(viewModel.uiState.value.searchResults.isEmpty())
        assertFalse(viewModel.uiState.value.searching)
    }

    @Test
    fun recordsACompletedEmptySearchUntilTheQueryChanges() = runTest {
        val membership = Membership(
            roomCode = "ABC123",
            memberId = "member-1",
            memberToken = "token",
            displayName = "Listener",
            isHost = false,
        )
        val viewModel = MuzikViewModel(
            initialState = MuzikUiState(
                membership = membership,
                searchQuery = "ambient",
            ),
            searchRequest = { _, _ -> SearchResponse() },
        )

        viewModel.search()
        runCurrent()

        assertEquals("ambient", viewModel.uiState.value.searchedQuery)
        assertTrue(viewModel.uiState.value.searchResults.isEmpty())
        viewModel.setSearchQuery("jazz")
        assertNull(viewModel.uiState.value.searchedQuery)
    }
}
