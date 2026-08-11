package com.muzik.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muzik.app.model.ConnectionStatus
import com.muzik.app.model.Membership
import com.muzik.app.model.RoomSnapshot
import com.muzik.app.model.SearchResponse
import com.muzik.app.model.VideoSummary
import com.muzik.app.network.MuzikClient
import com.muzik.app.network.RoomConnection
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class RoomRequest { Create, Join }

data class MuzikUiState(
    val displayName: String = "",
    val roomCodeInput: String = "",
    val membership: Membership? = null,
    val room: RoomSnapshot? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val loading: Boolean = false,
    val roomRequest: RoomRequest? = null,
    val error: String? = null,
    val notice: String? = null,
    val searchQuery: String = "",
    val searchResults: List<VideoSummary> = emptyList(),
    val searchedQuery: String? = null,
    val searching: Boolean = false,
    val playerConsent: Boolean = false,
    val clockOffsetMs: Long = 0,
    val playerPositionMs: Long = 0,
    val playerDurationMs: Long = 0,
)

class MuzikViewModel private constructor(
    private val clientFactory: () -> MuzikClient,
    private val searchRequest: (suspend (String, Membership) -> SearchResponse)?,
    initialState: MuzikUiState,
) : ViewModel() {
    constructor() : this(
        clientFactory = { MuzikClient() },
        searchRequest = null,
        initialState = MuzikUiState(),
    )

    internal constructor(
        initialState: MuzikUiState,
        searchRequest: suspend (String, Membership) -> SearchResponse,
    ) : this(
        clientFactory = { error("The test client should not be used") },
        searchRequest = searchRequest,
        initialState = initialState,
    )

    private var clientInstance: MuzikClient? = null
    private val client: MuzikClient
        get() = clientInstance ?: clientFactory().also { clientInstance = it }
    private val _uiState = MutableStateFlow(initialState)
    val uiState: StateFlow<MuzikUiState> = _uiState.asStateFlow()
    private var connection: RoomConnection? = null
    private var searchJob: Job? = null
    private var searchGeneration = 0L

    fun setDisplayName(value: String) = _uiState.update { it.copy(displayName = value.take(40)) }
    fun setRoomCode(value: String) = _uiState.update {
        it.copy(roomCodeInput = value.filter(Char::isLetterOrDigit).uppercase().take(8))
    }
    fun setSearchQuery(value: String) {
        val nextQuery = value.take(100)
        if (_uiState.value.searchQuery == nextQuery) return
        cancelSearchRequest()
        _uiState.update {
            it.copy(
                searchQuery = nextQuery,
                searchResults = emptyList(),
                searchedQuery = null,
                searching = false,
            )
        }
    }
    fun clearError() = _uiState.update { it.copy(error = null) }
    fun clearNotice() = _uiState.update { it.copy(notice = null) }
    fun consentToPlayback() = _uiState.update { it.copy(playerConsent = true) }

    fun createRoom() {
        val displayName = _uiState.value.displayName.trim()
        establishRoom(RoomRequest.Create) { client.createRoom(displayName) }
    }

    fun joinRoom() {
        val state = _uiState.value
        val roomCode = state.roomCodeInput
        val displayName = state.displayName.trim()
        establishRoom(RoomRequest.Join) {
            client.joinRoom(roomCode, displayName)
        }
    }

    private fun establishRoom(action: RoomRequest, request: suspend () -> Membership) {
        val state = _uiState.value
        if (state.loading) return
        if (state.displayName.isBlank()) {
            showError("Enter a display name")
            return
        }
        _uiState.update { it.copy(loading = true, roomRequest = action, error = null) }
        viewModelScope.launch {
            try {
                val membership = request()
                _uiState.update {
                    it.copy(loading = false, roomRequest = null, membership = membership)
                }
                connect(membership)
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(loading = false, roomRequest = null, error = readableError(error))
                }
            }
        }
    }

    private fun connect(membership: Membership) {
        connection?.disconnect()
        lateinit var newConnection: RoomConnection
        newConnection = RoomConnection(
            client = client,
            membership = membership,
            scope = viewModelScope,
            onSnapshot = snapshot@{ room ->
                if (connection !== newConnection) return@snapshot
                _uiState.update { current ->
                    val videoChanged = current.room?.playback?.video?.videoId !=
                        room.playback.video?.videoId
                    current.copy(
                        room = room,
                        error = null,
                        playerPositionMs = if (videoChanged) room.playback.positionMs
                            else current.playerPositionMs,
                        playerDurationMs = if (videoChanged) 0 else current.playerDurationMs,
                    )
                }
            },
            onStatus = status@{ status ->
                if (connection !== newConnection) return@status
                _uiState.update { it.copy(connectionStatus = status) }
            },
            onClockOffset = clock@{ offset ->
                if (connection !== newConnection) return@clock
                _uiState.update { it.copy(clockOffsetMs = offset) }
            },
            onError = error@{ message ->
                if (connection !== newConnection) return@error
                showError(message)
            },
        )
        connection = newConnection
        newConnection.connect()
    }

    fun search() {
        val state = _uiState.value
        if (state.searching) return
        val query = state.searchQuery.trim()
        if (query.length < 2) {
            showError("Enter at least two characters")
            return
        }
        val membership = state.membership ?: run {
            showError("Join a room before searching")
            return
        }
        val generation = ++searchGeneration
        _uiState.update { it.copy(searching = true, searchedQuery = null, error = null) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val response = searchRequest?.invoke(query, membership)
                    ?: client.search(query, membership)
                _uiState.update {
                    if (
                        searchGeneration == generation &&
                        it.membership?.memberId == membership.memberId
                    ) {
                        it.copy(
                            searching = false,
                            searchResults = response.results,
                            searchedQuery = query,
                        )
                    } else {
                        it
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = readableError(error)
                _uiState.update {
                    if (
                        searchGeneration == generation &&
                        it.membership?.memberId == membership.memberId
                    ) {
                        it.copy(searching = false, error = message)
                    } else {
                        it
                    }
                }
            } finally {
                if (searchGeneration == generation) searchJob = null
            }
        }
        searchJob = job
        job.start()
    }

    fun dismissSearch() {
        cancelSearchRequest()
        _uiState.update {
            it.copy(
                searchQuery = "",
                searchResults = emptyList(),
                searchedQuery = null,
                searching = false,
            )
        }
    }

    fun addToQueue(video: VideoSummary) {
        val room = _uiState.value.room
        // Tapping Add is an explicit playback gesture, so it can also initialize the
        // visible embedded player instead of leaving the user at "Start player".
        _uiState.update { it.copy(playerConsent = true) }
        val startsPlayback = room?.me?.isHost == true && room.playback.video == null
        connection?.addToQueue(
            video = video,
            startPlayback = startsPlayback,
        )
        cancelSearchRequest()
        _uiState.update {
            it.copy(
                searchQuery = "",
                searchResults = emptyList(),
                searchedQuery = null,
                searching = false,
                notice = if (startsPlayback) {
                    "Added ${video.title.take(60)} and starting playback"
                } else {
                    "Added ${video.title.take(60)} to the queue"
                },
            )
        }
    }

    fun vote(itemId: String, enabled: Boolean) = connection?.vote(itemId, enabled)
    fun remove(itemId: String) = connection?.remove(itemId)
    fun reorderQueueItem(itemId: String, beforeItemId: String?) =
        connection?.reorder(itemId, beforeItemId)
    fun playNext(itemId: String) = connection?.playNext(itemId)
    fun playItem(itemId: String) = connection?.playItem(itemId)
    fun play() = connection?.playback("play")
    fun pause() = connection?.playback("pause")
    fun requestPause() = connection?.requestPause()
    fun votePause(vote: Boolean) {
        val pollId = _uiState.value.room?.pauseVote?.id ?: return
        connection?.votePause(vote, pollId)
    }
    fun next() = connection?.playback("next")
    fun seek(positionMs: Long) = connection?.playback("seek", positionMs)
    fun voteToSkip() = connection?.voteToSkip()
    fun reportPlayerMessage(message: String) = showError(message)

    fun reportPlayerProgress(positionMs: Long, durationMs: Long) = _uiState.update {
        it.copy(
            playerPositionMs = positionMs.coerceAtLeast(0),
            playerDurationMs = durationMs.coerceAtLeast(0),
        )
    }

    fun onPlayerEnded(revision: Long) {
        val room = _uiState.value.room ?: return
        if (
            room.me.isHost &&
            room.playback.revision == revision &&
            room.playback.video != null &&
            room.playback.status == "playing"
        ) {
            next()
        }
    }

    fun leaveRoom() {
        cancelSearchRequest()
        connection?.leave()
        connection = null
        _uiState.update {
            MuzikUiState(displayName = it.displayName)
        }
    }

    private fun showError(message: String) = _uiState.update { it.copy(error = message) }

    private fun cancelSearchRequest() {
        searchGeneration += 1
        searchJob?.cancel()
        searchJob = null
    }

    private suspend fun readableError(error: Exception): String {
        if (error is ResponseException) {
            return try {
                client.json.parseToJsonElement(error.response.bodyAsText())
                    .jsonObject["error"]?.jsonPrimitive?.content
                    ?: "Request failed (${error.response.status.value})"
            } catch (_: Exception) {
                "Request failed (${error.response.status.value})"
            }
        }
        return error.message ?: "Something went wrong"
    }

    override fun onCleared() {
        cancelSearchRequest()
        connection?.disconnect()
        clientInstance?.close()
        super.onCleared()
    }
}
