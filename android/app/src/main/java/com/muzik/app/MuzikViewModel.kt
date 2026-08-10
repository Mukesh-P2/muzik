package com.muzik.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muzik.app.model.ConnectionStatus
import com.muzik.app.model.Membership
import com.muzik.app.model.RoomSnapshot
import com.muzik.app.model.VideoSummary
import com.muzik.app.network.MuzikClient
import com.muzik.app.network.RoomConnection
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class MuzikUiState(
    val displayName: String = "",
    val roomCodeInput: String = "",
    val membership: Membership? = null,
    val room: RoomSnapshot? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val loading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val searchResults: List<VideoSummary> = emptyList(),
    val searching: Boolean = false,
    val playerConsent: Boolean = false,
    val clockOffsetMs: Long = 0,
    val playerPositionMs: Long = 0,
    val playerDurationMs: Long = 0,
)

class MuzikViewModel : ViewModel() {
    private val client = MuzikClient()
    private val _uiState = MutableStateFlow(MuzikUiState())
    val uiState: StateFlow<MuzikUiState> = _uiState.asStateFlow()
    private var connection: RoomConnection? = null

    fun setDisplayName(value: String) = _uiState.update { it.copy(displayName = value.take(40)) }
    fun setRoomCode(value: String) = _uiState.update {
        it.copy(roomCodeInput = value.filter(Char::isLetterOrDigit).uppercase().take(8))
    }
    fun setSearchQuery(value: String) = _uiState.update { it.copy(searchQuery = value.take(100)) }
    fun clearError() = _uiState.update { it.copy(error = null) }
    fun consentToPlayback() = _uiState.update { it.copy(playerConsent = true) }

    fun createRoom() {
        val displayName = _uiState.value.displayName.trim()
        establishRoom { client.createRoom(displayName) }
    }

    fun joinRoom() {
        val state = _uiState.value
        val roomCode = state.roomCodeInput
        val displayName = state.displayName.trim()
        establishRoom {
            client.joinRoom(roomCode, displayName)
        }
    }

    private fun establishRoom(request: suspend () -> Membership) {
        val state = _uiState.value
        if (state.loading) return
        if (state.displayName.isBlank()) {
            showError("Enter a display name")
            return
        }
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val membership = request()
                _uiState.update { it.copy(loading = false, membership = membership) }
                connect(membership)
            } catch (error: Exception) {
                _uiState.update { it.copy(loading = false, error = readableError(error)) }
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
        _uiState.update { it.copy(searching = true, error = null) }
        viewModelScope.launch {
            try {
                val response = client.search(query, membership)
                _uiState.update {
                    if (it.membership?.memberId == membership.memberId) {
                        it.copy(searching = false, searchResults = response.results)
                    } else {
                        it
                    }
                }
            } catch (error: Exception) {
                val message = readableError(error)
                _uiState.update {
                    if (it.membership?.memberId == membership.memberId) {
                        it.copy(searching = false, error = message)
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun addToQueue(video: VideoSummary) {
        connection?.addToQueue(video)
        _uiState.update { it.copy(searchQuery = "", searchResults = emptyList()) }
    }

    fun vote(itemId: String, enabled: Boolean) = connection?.vote(itemId, enabled)
    fun remove(itemId: String) = connection?.remove(itemId)
    fun playItem(itemId: String) = connection?.playItem(itemId)
    fun play() = connection?.playback("play")
    fun pause() = connection?.playback("pause")
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
        connection?.leave()
        connection = null
        _uiState.update {
            MuzikUiState(displayName = it.displayName)
        }
    }

    private fun showError(message: String) = _uiState.update { it.copy(error = message) }

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
        connection?.disconnect()
        client.close()
        super.onCleared()
    }
}
