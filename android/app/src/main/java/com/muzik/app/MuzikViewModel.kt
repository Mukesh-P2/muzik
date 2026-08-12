package com.muzik.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.muzik.app.model.ConnectionStatus
import com.muzik.app.model.ChatMessage
import com.muzik.app.model.Membership
import com.muzik.app.model.RoomSnapshot
import com.muzik.app.model.SearchResponse
import com.muzik.app.model.VideoSummary
import com.muzik.app.network.MuzikClient
import com.muzik.app.network.RoomConnection
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import java.util.UUID
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
    val pendingInviteCode: String? = null,
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
    val playlistInput: String = "",
    val importingPlaylist: Boolean = false,
    val playerConsent: Boolean = false,
    val clockOffsetMs: Long = 0,
    val playerPositionMs: Long = 0,
    val playerDurationMs: Long = 0,
)

class MuzikViewModel private constructor(
    private val clientFactory: () -> MuzikClient,
    private val searchRequest: (suspend (String, Membership) -> SearchResponse)?,
    private val playlistRequest: (suspend (String, Membership) -> SearchResponse)?,
    private val chatSendRequest: ((String) -> Boolean)?,
    private val membershipStore: MembershipStore,
    initialState: MuzikUiState,
    restoreConnection: Boolean,
) : ViewModel() {
    private constructor(bootstrap: MembershipBootstrap) : this(
        clientFactory = { MuzikClient() },
        searchRequest = null,
        playlistRequest = null,
        chatSendRequest = null,
        membershipStore = bootstrap.store,
        initialState = MuzikUiState(
            displayName = bootstrap.membership?.displayName.orEmpty(),
            membership = bootstrap.membership,
        ),
        restoreConnection = true,
    )

    internal constructor(
        initialState: MuzikUiState,
        searchRequest: suspend (String, Membership) -> SearchResponse,
        playlistRequest: (suspend (String, Membership) -> SearchResponse)? = null,
        chatSendRequest: ((String) -> Boolean)? = null,
        membershipStore: MembershipStore = NoOpMembershipStore,
    ) : this(
        clientFactory = { error("The test client should not be used") },
        searchRequest = searchRequest,
        playlistRequest = playlistRequest,
        chatSendRequest = chatSendRequest,
        membershipStore = membershipStore,
        initialState = initialState,
        restoreConnection = false,
    )

    private var clientInstance: MuzikClient? = null
    private val client: MuzikClient
        get() = clientInstance ?: clientFactory().also { clientInstance = it }
    private val _uiState = MutableStateFlow(initialState)
    val uiState: StateFlow<MuzikUiState> = _uiState.asStateFlow()
    private var connection: RoomConnection? = null
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private var playlistJob: Job? = null
    private var pendingPlaylistImport: PendingPlaylistImport? = null

    init {
        if (restoreConnection) initialState.membership?.let(::connect)
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val applicationContext = context.applicationContext

        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(MuzikViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            val store = AndroidMembershipStore(applicationContext)
            @Suppress("UNCHECKED_CAST")
            return MuzikViewModel(
                MembershipBootstrap(store = store, membership = store.load()),
            ) as T
        }
    }

    fun setDisplayName(value: String) = _uiState.update { it.copy(displayName = value.take(40)) }
    fun setRoomCode(value: String) = _uiState.update {
        it.copy(roomCodeInput = normalizeRoomCode(value))
    }
    fun handleInvite(value: String) {
        val roomCode = normalizeRoomCode(value)
        if (roomCode.isEmpty()) return
        _uiState.update { current ->
            when {
                current.loading -> current.copy(pendingInviteCode = roomCode)
                current.membership == null -> current.copy(roomCodeInput = roomCode)
                current.membership.roomCode.equals(roomCode, ignoreCase = true) -> current.copy(
                    pendingInviteCode = null,
                    notice = "You are already in room $roomCode",
                )
                else -> current.copy(pendingInviteCode = roomCode)
            }
        }
    }
    fun dismissInvite() = _uiState.update { it.copy(pendingInviteCode = null) }
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
    fun setPlaylistInput(value: String) = _uiState.update {
        it.copy(playlistInput = value.take(500))
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
                membershipStore.save(membership)
                _uiState.update { current ->
                    val pendingInvite = current.pendingInviteCode?.takeUnless { roomCode ->
                        membership.roomCode.equals(roomCode, ignoreCase = true)
                    }
                    current.copy(
                        loading = false,
                        roomRequest = null,
                        membership = membership,
                        pendingInviteCode = pendingInvite,
                    )
                }
                connect(membership)
            } catch (error: Exception) {
                val message = readableError(error)
                _uiState.update { current ->
                    current.copy(
                        loading = false,
                        roomRequest = null,
                        roomCodeInput = current.pendingInviteCode ?: current.roomCodeInput,
                        pendingInviteCode = null,
                        error = message,
                    )
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
            onChatMessage = chat@{ message ->
                if (connection !== newConnection) return@chat
                _uiState.update { current ->
                    val room = current.room ?: return@update current
                    current.copy(room = appendChatMessage(room, message))
                }
            },
            onQueueImportResult = result@{ result ->
                if (connection !== newConnection) return@result
                val pending = pendingPlaylistImport
                if (pending?.requestId != result.requestId) return@result
                pendingPlaylistImport = null
                _uiState.update {
                    it.copy(
                        importingPlaylist = false,
                        playlistInput = "",
                        playerConsent = it.playerConsent || result.startedPlayback,
                        notice = "Imported ${result.addedCount} playlist videos",
                    )
                }
            },
            onActionError = actionError@{ requestId, message ->
                if (connection !== newConnection) return@actionError
                if (pendingPlaylistImport?.requestId == requestId) {
                    pendingPlaylistImport = null
                    _uiState.update {
                        it.copy(importingPlaylist = false, error = message)
                    }
                } else {
                    showError(message)
                }
            },
            onStatus = status@{ status ->
                if (connection !== newConnection) return@status
                val importInterrupted = status == ConnectionStatus.Disconnected &&
                    pendingPlaylistImport != null
                if (importInterrupted) pendingPlaylistImport = null
                _uiState.update {
                    it.copy(
                        connectionStatus = status,
                        importingPlaylist = if (importInterrupted) false else it.importingPlaylist,
                        error = if (importInterrupted) {
                            "Connection changed during playlist import; check the queue before retrying"
                        } else {
                            it.error
                        },
                    )
                }
            },
            onClockOffset = clock@{ offset ->
                if (connection !== newConnection) return@clock
                _uiState.update { it.copy(clockOffsetMs = offset) }
            },
            onMembershipInvalid = invalid@{
                if (connection !== newConnection) return@invalid
                cancelSearchRequest()
                playlistJob?.cancel()
                playlistJob = null
                pendingPlaylistImport = null
                membershipStore.clear()
                connection = null
                _uiState.update { current ->
                    MuzikUiState(
                        displayName = current.displayName,
                        roomCodeInput = current.pendingInviteCode ?: current.roomCodeInput,
                        error = "This room is no longer available. Create or join a room again.",
                    )
                }
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

    fun importPlaylist() {
        val state = _uiState.value
        if (state.importingPlaylist) return
        val membership = state.membership ?: run {
            showError("Join a room before importing a playlist")
            return
        }
        if (state.connectionStatus != ConnectionStatus.Connected) {
            showError("Wait for the room to reconnect before importing a playlist")
            return
        }
        val value = state.playlistInput.trim()
        if (value.isEmpty()) {
            showError("Enter a YouTube playlist URL or ID")
            return
        }
        _uiState.update { it.copy(importingPlaylist = true, error = null) }
        playlistJob = viewModelScope.launch {
            try {
                val response = playlistRequest?.invoke(value, membership)
                    ?: client.importPlaylist(value, membership)
                val current = _uiState.value
                if (
                    current.membership?.memberId != membership.memberId ||
                    current.connectionStatus != ConnectionStatus.Connected
                ) {
                    _uiState.update {
                        it.copy(
                            importingPlaylist = false,
                            error = "The room disconnected; reconnect and try the playlist again",
                        )
                    }
                    return@launch
                }
                val selected = selectPlaylistImports(response.results, current.room)
                if (selected.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            importingPlaylist = false,
                            notice = "No new embeddable videos were available to import",
                        )
                    }
                    return@launch
                }
                val startsPlayback = current.room?.me?.isHost == true &&
                    current.room.playback.video == null
                val requestId = UUID.randomUUID().toString()
                pendingPlaylistImport = PendingPlaylistImport(requestId)
                val sent = connection?.addManyToQueue(
                    requestId = requestId,
                    videos = selected,
                    startPlayback = startsPlayback,
                ) == true
                if (!sent) {
                    if (pendingPlaylistImport?.requestId == requestId) {
                        pendingPlaylistImport = null
                    }
                    _uiState.update {
                        it.copy(
                            importingPlaylist = false,
                            error = "The room disconnected; reconnect and try the playlist again",
                        )
                    }
                    return@launch
                }
                if (pendingPlaylistImport?.requestId == requestId) {
                    _uiState.update {
                        it.copy(notice = "Importing ${selected.size} playlist videos")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                pendingPlaylistImport = null
                val message = readableError(error)
                _uiState.update { it.copy(importingPlaylist = false, error = message) }
            } finally {
                playlistJob = null
            }
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
    fun clearQueue() = connection?.clearQueue()
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
    fun sendChat(text: String): Boolean {
        val normalized = text.trim().take(500)
        if (normalized.isEmpty()) return false
        return chatSendRequest?.invoke(normalized) ?: (connection?.sendChat(normalized) == true)
    }
    fun deleteChat(messageId: String) = connection?.deleteChat(messageId)
    fun setChatMuted(memberId: String, muted: Boolean) =
        connection?.setChatMuted(memberId, muted)
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
        playlistJob?.cancel()
        playlistJob = null
        pendingPlaylistImport = null
        membershipStore.clear()
        connection?.leave()
        connection = null
        _uiState.update {
            MuzikUiState(displayName = it.displayName)
        }
    }

    fun switchToInvitedRoom() {
        val state = _uiState.value
        val roomCode = state.pendingInviteCode ?: return
        cancelSearchRequest()
        playlistJob?.cancel()
        playlistJob = null
        pendingPlaylistImport = null
        membershipStore.clear()
        connection?.leave()
        connection = null
        _uiState.value = MuzikUiState(
            displayName = state.displayName,
            roomCodeInput = roomCode,
            notice = "Ready to join room $roomCode",
        )
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
        playlistJob?.cancel()
        pendingPlaylistImport = null
        connection?.disconnect()
        clientInstance?.close()
        super.onCleared()
    }
}

internal fun selectPlaylistImports(
    results: List<VideoSummary>,
    room: RoomSnapshot?,
): List<VideoSummary> {
    val existingVideoIds = buildSet {
        room?.playback?.video?.videoId?.let(::add)
        room?.queue?.mapTo(this) { it.video.videoId }
    }
    val availableSlots = (100 - (room?.queue?.size ?: 0)).coerceAtLeast(0)
    return results
        .distinctBy(VideoSummary::videoId)
        .filterNot { it.videoId in existingVideoIds }
        .take(availableSlots)
}

internal fun appendChatMessage(
    room: RoomSnapshot,
    message: ChatMessage,
): RoomSnapshot {
    if (room.chat.any { existing -> existing.id == message.id }) return room
    return room.copy(chat = (room.chat + message).takeLast(MAX_CHAT_MESSAGES))
}

private const val MAX_CHAT_MESSAGES = 100

private data class PendingPlaylistImport(val requestId: String)

private fun normalizeRoomCode(value: String): String =
    value.filter(Char::isLetterOrDigit).uppercase().take(8)

private data class MembershipBootstrap(
    val store: MembershipStore,
    val membership: Membership?,
)
