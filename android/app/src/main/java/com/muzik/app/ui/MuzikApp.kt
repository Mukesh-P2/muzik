package com.muzik.app.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.muzik.app.MuzikUiState
import com.muzik.app.MuzikViewModel
import com.muzik.app.RoomRequest
import com.muzik.app.model.ConnectionStatus
import com.muzik.app.model.ChatMessage
import com.muzik.app.model.MemberSummary
import com.muzik.app.model.QueueItem
import com.muzik.app.model.RoomSnapshot
import com.muzik.app.model.VideoSummary
import com.muzik.app.player.YouTubePlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class QualityOption(val label: String, val iframeValue: String)

private val qualityOptions = listOf(
    QualityOption("Auto", "default"),
    QualityOption("144p", "tiny"),
    QualityOption("240p", "small"),
    QualityOption("360p", "medium"),
    QualityOption("480p", "large"),
    QualityOption("720p", "hd720"),
    QualityOption("1080p", "hd1080"),
    QualityOption("Highest available", "highres"),
)

@Composable
fun MuzikApp(
    state: MuzikUiState,
    viewModel: MuzikViewModel,
    isInPictureInPictureMode: Boolean = false,
) {
    val playerSessionId = state.membership?.memberId
    var selectedQualityValue by rememberSaveable(playerSessionId) {
        mutableStateOf(qualityOptions.first().iframeValue)
    }
    val selectedQuality = qualityOptions.firstOrNull {
        it.iframeValue == selectedQualityValue
    } ?: qualityOptions.first()
    val latestPlayerState = rememberUpdatedState(state)
    val latestQualityValue = rememberUpdatedState(selectedQualityValue)
    val playerViewport = if (state.playerConsent && playerSessionId != null) {
        remember(playerSessionId, viewModel) {
            movableContentOf {
                AndroidView(
                    factory = { context ->
                        YouTubePlayerView(
                            context = context,
                            onEnded = viewModel::onPlayerEnded,
                            onMessage = viewModel::reportPlayerMessage,
                            onProgress = viewModel::reportPlayerProgress,
                        )
                    },
                    update = { view ->
                        view.setPreferredQuality(latestQualityValue.value)
                        latestPlayerState.value.room?.playback?.let { playback ->
                            view.synchronize(playback, latestPlayerState.value.clockOffsetMs)
                        }
                    },
                    onRelease = YouTubePlayerView::destroy,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    } else {
        null
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.clearNotice()
        }
    }

    if (state.membership == null) {
        HomeScreen(state, viewModel, snackbar)
    } else {
        RoomScreen(
            state = state,
            viewModel = viewModel,
            snackbar = snackbar,
            playerViewport = playerViewport,
            selectedQuality = selectedQuality,
            onQualitySelected = { selectedQualityValue = it.iframeValue },
            isInPictureInPictureMode = isInPictureInPictureMode,
        )
    }
    state.pendingInviteCode?.takeIf { state.membership != null }?.let { roomCode ->
        InvitedRoomDialog(
            roomCode = roomCode,
            onDismiss = viewModel::dismissInvite,
            onConfirm = viewModel::switchToInvitedRoom,
        )
    }
}

@Composable
private fun InvitedRoomDialog(
    roomCode: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open room $roomCode?") },
        text = {
            Text("You are already in another room. Leave it and prepare to join room $roomCode?")
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Stay here") }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Leave and open") }
        },
    )
}

@Composable
private fun HomeScreen(
    state: MuzikUiState,
    viewModel: MuzikViewModel,
    snackbar: SnackbarHostState,
) {
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
                        ),
                    ),
                )
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                    ),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(14.dp).size(30.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Muzik",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Your room. Your queue. Perfectly in sync.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 8.dp,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                    ),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            "Start listening together",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Choose a name, then create a room or join your friends.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(20.dp))
                        OutlinedTextField(
                            value = state.displayName,
                            onValueChange = viewModel::setDisplayName,
                            label = { Text("Display name") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = viewModel::createRoom,
                            enabled = !state.loading,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            if (state.loading && state.roomRequest == RoomRequest.Create) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("Create a room", fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HorizontalDivider(Modifier.weight(1f))
                            Text(
                                "OR JOIN",
                                modifier = Modifier.padding(horizontal = 12.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            HorizontalDivider(Modifier.weight(1f))
                        }
                        OutlinedTextField(
                            value = state.roomCodeInput,
                            onValueChange = viewModel::setRoomCode,
                            label = { Text("Room code") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = viewModel::joinRoom,
                            enabled = !state.loading && state.roomCodeInput.isNotBlank(),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            if (state.loading && state.roomRequest == RoomRequest.Join) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("Join room", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Official YouTube playback stays visible and starts only when you choose.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomScreen(
    state: MuzikUiState,
    viewModel: MuzikViewModel,
    snackbar: SnackbarHostState,
    playerViewport: (@Composable () -> Unit)?,
    selectedQuality: QualityOption,
    onQualitySelected: (QualityOption) -> Unit,
    isInPictureInPictureMode: Boolean,
) {
    val room = state.room
    val context = LocalContext.current
    var showLeaveConfirmation by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = !isInPictureInPictureMode) { showLeaveConfirmation = true }
    Scaffold(
        snackbarHost = {
            if (!isInPictureInPictureMode) SnackbarHost(snackbar)
        },
        topBar = {
            if (!isInPictureInPictureMode) TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .background(connectionColor(state.connectionStatus), CircleShape),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "Room ${state.membership?.roomCode}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                connectionLabel(state.connectionStatus),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            state.membership?.roomCode?.let { shareRoomInvite(context, it) }
                        },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Invite people")
                    }
                    TextButton(
                        onClick = { showLeaveConfirmation = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Leave")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (room == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            RoomContent(
                modifier = Modifier.padding(padding),
                state = state,
                room = room,
                viewModel = viewModel,
                playerViewport = playerViewport,
                selectedQuality = selectedQuality,
                onQualitySelected = onQualitySelected,
                isInPictureInPictureMode = isInPictureInPictureMode,
            )
        }
    }
    if (showLeaveConfirmation) {
        val isHost = room?.me?.isHost ?: (state.membership?.isHost == true)
        val hasConnectedReplacement = room?.let { snapshot ->
            snapshot.members.any { member ->
                member.id != snapshot.me.id && member.connected
            }
        }
        LeaveRoomDialog(
            isHost = isHost,
            hasConnectedReplacement = hasConnectedReplacement,
            onDismiss = { showLeaveConfirmation = false },
            onConfirm = {
                showLeaveConfirmation = false
                viewModel.leaveRoom()
            },
        )
    }
}

@Composable
private fun LeaveRoomDialog(
    isHost: Boolean,
    hasConnectedReplacement: Boolean?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(10.dp),
                )
            }
        },
        title = { Text("Leave this room?", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                if (isHost && hasConnectedReplacement == true) {
                    "Host controls will pass to another connected listener. You can rejoin later with the room code."
                } else if (isHost && hasConnectedReplacement == false) {
                    "No other listener is connected to take over host controls. You can rejoin later with the room code."
                } else if (isHost) {
                    "If another listener is connected, host controls will pass to them. You can rejoin later with the room code."
                } else {
                    "You’ll leave the shared queue and playback session. You can rejoin later with the room code."
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Leave room", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Stay") }
        },
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun RoomContent(
    modifier: Modifier,
    state: MuzikUiState,
    room: RoomSnapshot,
    viewModel: MuzikViewModel,
    playerViewport: (@Composable () -> Unit)?,
    selectedQuality: QualityOption,
    onQualitySelected: (QualityOption) -> Unit,
    isInPictureInPictureMode: Boolean,
) {
    var showMembers by rememberSaveable(room.code) { mutableStateOf(false) }
    var showClearQueueConfirmation by rememberSaveable(room.code) { mutableStateOf(false) }
    var chatDraft by rememberSaveable(room.code) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val dragScrollScope = rememberCoroutineScope()
    val dragEdgeSizePx = with(LocalDensity.current) { 72.dp.toPx() }
    val dragElevationPx = with(LocalDensity.current) { 12.dp.toPx() }
    val hapticFeedback = LocalHapticFeedback.current
    var draggedQueueItemId by remember(room.code) { mutableStateOf<String?>(null) }
    var queueDragOffsetY by remember(room.code) { mutableFloatStateOf(0f) }
    var dragScrollJob by remember(room.code) { mutableStateOf<Job?>(null) }

    fun finishQueueDrag() {
        dragScrollJob?.cancel()
        dragScrollJob = null
        val draggedId = draggedQueueItemId ?: return
        val dragged = room.queue.firstOrNull { it.id == draggedId }
        val draggedInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggedId }
        if (dragged != null && draggedInfo != null) {
            val tierIds = room.queue
                .filter { it.voteCount == dragged.voteCount && it.id != draggedId }
                .map(QueueItem::id)
            val visibleTier = listState.layoutInfo.visibleItemsInfo
                .filter { it.key in tierIds }
                .sortedBy { it.offset }
            if (visibleTier.isNotEmpty()) {
                val draggedCenter = draggedInfo.offset + draggedInfo.size / 2f + queueDragOffsetY
                val nextVisible = visibleTier.firstOrNull { info ->
                    info.offset + info.size / 2f > draggedCenter
                }
                val beforeItemId = if (nextVisible != null) {
                    nextVisible.key as? String
                } else {
                    val lastVisibleId = visibleTier.lastOrNull()?.key as? String
                    val lastVisibleIndex = tierIds.indexOf(lastVisibleId)
                    if (lastVisibleIndex >= 0) tierIds.getOrNull(lastVisibleIndex + 1) else null
                }
                viewModel.reorderQueueItem(draggedId, beforeItemId)
            }
        }
        draggedQueueItemId = null
        queueDragOffsetY = 0f
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (state.playerConsent && playerViewport != null) {
            SyncedYouTubePlayer(
                state = state,
                selectedQuality = selectedQuality,
                onQualitySelected = onQualitySelected,
                playerViewport = playerViewport,
                isInPictureInPictureMode = isInPictureInPictureMode,
            )
        } else if (!isInPictureInPictureMode) {
            PlayerConsent(viewModel::consentToPlayback)
        }
        if (isInPictureInPictureMode) return@Column
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp),
            ) {
                item { NowPlaying(state, room, viewModel) }
                item { RoomPresence(room, onViewMembers = { showMembers = true }) }
                if (state.connectionStatus != ConnectionStatus.Connected) {
                    item { ConnectionNotice(state.connectionStatus) }
                }
                item { SearchBox(state, viewModel) }
                if (state.searchResults.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "Search results",
                            detail = "${state.searchResults.size} videos",
                        )
                    }
                    items(state.searchResults, key = VideoSummary::videoId) { video ->
                        SearchResult(video, onAdd = { viewModel.addToQueue(video) })
                    }
                }
                item {
                    SectionHeader(
                        title = "Up next",
                        detail = if (room.queue.isEmpty()) {
                            "Queue empty"
                        } else {
                            "${room.queue.size} queued  •  vote ranked"
                        },
                    )
                }
                if (room.me.isHost && room.queue.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { showClearQueueConfirmation = true }) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Clear queue")
                            }
                        }
                    }
                }
                if (room.queue.isNotEmpty()) {
                    item { QueueOrderingHint(room) }
                }
                if (room.queue.isEmpty()) {
                    item { EmptyQueue() }
                } else {
                    items(room.queue, key = QueueItem::id) { item ->
                        val addedByName = item.addedByName
                            ?: room.members.firstOrNull { member -> member.id == item.addedBy }?.displayName
                            ?: "A room member"
                        val canReorder = room.me.isHost &&
                            room.queue.count { it.voteCount == item.voteCount } > 1
                        val dragHandleModifier = if (canReorder) {
                            Modifier.pointerInput(item.id, room.queue) {
                                detectDragGestures(
                                    onDragStart = {
                                        hapticFeedback.performHapticFeedback(
                                            HapticFeedbackType.LongPress,
                                        )
                                        dragScrollJob?.cancel()
                                        dragScrollJob = null
                                        draggedQueueItemId = item.id
                                        queueDragOffsetY = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        queueDragOffsetY += dragAmount.y
                                        val layoutInfo = listState.layoutInfo
                                        val draggedInfo = layoutInfo.visibleItemsInfo
                                            .firstOrNull { info -> info.key == item.id }
                                        if (draggedInfo != null) {
                                            val draggedCenter = draggedInfo.offset +
                                                draggedInfo.size / 2f + queueDragOffsetY
                                            val scrollDelta = when {
                                                draggedCenter < layoutInfo.viewportStartOffset + dragEdgeSizePx ->
                                                    -24f
                                                draggedCenter > layoutInfo.viewportEndOffset - dragEdgeSizePx ->
                                                    24f
                                                else -> 0f
                                            }
                                            if (scrollDelta != 0f && dragScrollJob?.isActive != true) {
                                                dragScrollJob = dragScrollScope.launch {
                                                    val consumed = listState.scrollBy(scrollDelta)
                                                    if (draggedQueueItemId == item.id) {
                                                        queueDragOffsetY += consumed
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    onDragEnd = ::finishQueueDrag,
                                    onDragCancel = {
                                        dragScrollJob?.cancel()
                                        dragScrollJob = null
                                        draggedQueueItemId = null
                                        queueDragOffsetY = 0f
                                    },
                                )
                            }
                        } else {
                            Modifier
                        }
                        val rowModifier = if (draggedQueueItemId == item.id) {
                            Modifier
                                .zIndex(1f)
                                .graphicsLayer {
                                    translationY = queueDragOffsetY
                                    scaleX = 1.02f
                                    scaleY = 1.02f
                                    shadowElevation = dragElevationPx
                                }
                        } else {
                            Modifier
                        }
                        QueueRow(
                            item = item,
                            addedByName = addedByName,
                            isHost = room.me.isHost,
                            canReorder = canReorder,
                            modifier = rowModifier,
                            dragHandleModifier = dragHandleModifier,
                            viewModel = viewModel,
                        )
                    }
                }
                item {
                    SectionHeader(
                        title = "Room chat",
                        detail = if (room.chat.isEmpty()) {
                            "No messages"
                        } else {
                            "${room.chat.size} messages"
                        },
                    )
                }
                items(room.chat, key = ChatMessage::id) { message ->
                    ChatRow(
                        message = message,
                        serverTimeMs = room.serverTimeMs,
                        canDelete = room.me.isHost,
                        onDelete = { viewModel.deleteChat(message.id) },
                    )
                }
                item {
                    val muted = room.members.firstOrNull { it.id == room.me.id }?.chatMuted == true
                    ChatComposer(
                        value = chatDraft,
                        muted = muted,
                        onValueChange = { chatDraft = it.take(500) },
                        onSend = {
                            val message = chatDraft.trim()
                            if (message.isNotEmpty() && viewModel.sendChat(message)) {
                                chatDraft = ""
                            }
                        },
                    )
                }
                val historyCount = room.history.size + if (room.playback.video != null) 1 else 0
                item {
                    SectionHeader(
                        title = "Listening history",
                        detail = if (historyCount == 0) "Nothing played" else "$historyCount played",
                    )
                }
                room.playback.video?.let { currentVideo ->
                    item(key = "current-${currentVideo.videoId}") {
                        HistoryRow(
                            video = currentVideo,
                            addedByName = room.playback.addedByName
                                ?: room.members.firstOrNull { it.id == room.playback.addedBy }?.displayName
                                ?: "A room member",
                            isCurrent = true,
                        )
                    }
                }
                items(room.history, key = { "history-${it.id}-${it.playedAt}" }) { historyItem ->
                    HistoryRow(
                        video = historyItem.video,
                        addedByName = historyItem.addedByName
                            ?: room.members.firstOrNull { it.id == historyItem.addedBy }?.displayName
                            ?: "A room member",
                        isCurrent = false,
                        playedAt = historyItem.playedAt,
                        serverTimeMs = room.serverTimeMs,
                    )
                }
                if (historyCount == 0) item { EmptyHistory() }
            }
            room.pauseVote?.let { pauseVote ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(3f),
                ) {
                    PauseVoteCard(
                        requestedByName = pauseVote.requestedByName,
                        yesVotes = pauseVote.yesVotes,
                        noVotes = pauseVote.noVotes,
                        threshold = pauseVote.threshold,
                        myVote = pauseVote.myVote,
                        expiresAt = pauseVote.expiresAt,
                        startedAt = pauseVote.startedAt,
                        serverTimeMs = room.serverTimeMs,
                        eligibleVoters = pauseVote.eligibleVoters
                            ?: room.members.count { it.connected },
                        onVote = viewModel::votePause,
                    )
                }
            }
        }
    }
    if (showMembers) {
        MemberListDialog(
            members = room.members,
            currentAddedBy = room.playback.addedBy,
            currentMemberId = room.me.id,
            isHost = room.me.isHost,
            onSetChatMuted = viewModel::setChatMuted,
            onDismiss = { showMembers = false },
        )
    }
    if (showClearQueueConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearQueueConfirmation = false },
            title = { Text("Clear the queue?", fontWeight = FontWeight.Bold) },
            text = {
                Text("This removes all ${room.queue.size} queued videos. The current video keeps playing.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearQueueConfirmation = false
                        viewModel.clearQueue()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Clear queue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearQueueConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ChatRow(
    message: ChatMessage,
    serverTimeMs: Long,
    canDelete: Boolean,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Text(
                        message.displayName.firstOrNull()?.uppercase() ?: "?",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${message.displayName}  •  ${formatRelativeTime(serverTimeMs - message.sentAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (canDelete) {
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete message from ${message.displayName}",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

@Composable
private fun ChatComposer(
    value: String,
    muted: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    ) {
        if (muted) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("The host muted your room chat.")
            }
        } else {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("Message the room") },
                supportingText = { Text("${value.length}/500") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                trailingIcon = {
                    IconButton(onClick = onSend, enabled = value.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
                    }
                },
                minLines = 1,
                maxLines = 4,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RoomPresence(room: RoomSnapshot, onViewMembers: () -> Unit) {
    val connectedCount = room.members.count { it.connected }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
            ) {
                Icon(
                    Icons.Default.Group,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(9.dp).size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "$connectedCount listening now",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (room.me.isHost) "You’re hosting this room" else "Everyone follows the same playback",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(4.dp))
            TextButton(
                onClick = onViewMembers,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    "View all",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ConnectionNotice(status: ConnectionStatus) {
    val reconnecting = status == ConnectionStatus.Connecting
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (reconnecting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    if (reconnecting) "Reconnecting to the room…" else "Room connection lost",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Playback stays visible; room actions resume when the connection returns.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun MemberListDialog(
    members: List<MemberSummary>,
    currentAddedBy: String?,
    currentMemberId: String,
    isHost: Boolean,
    onSetChatMuted: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sortedMembers = remember(members, currentAddedBy) {
        members.sortedWith(
            compareByDescending<MemberSummary> { it.id == currentAddedBy }
                .thenByDescending { it.isHost }
                .thenByDescending { it.connected }
                .thenBy { it.displayName.lowercase() },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Room members", fontWeight = FontWeight.Bold)
                Text(
                    "${members.count { it.connected }} online  •  ${members.size} joined",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sortedMembers, key = MemberSummary::id) { member ->
                    MemberRow(
                        member = member,
                        addedCurrentSong = member.id == currentAddedBy,
                        canModerate = isHost && member.id != currentMemberId,
                        onToggleChatMuted = {
                            onSetChatMuted(member.id, !member.chatMuted)
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun MemberRow(
    member: MemberSummary,
    addedCurrentSong: Boolean,
    canModerate: Boolean,
    onToggleChatMuted: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = if (member.isHost) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            ) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                    Text(
                        member.displayName.firstOrNull()?.uppercase() ?: "?",
                        fontWeight = FontWeight.Bold,
                        color = if (member.isHost) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    member.displayName,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(if (member.isHost) "Host" else "Listener")
                        append("  •  ")
                        append(member.songsAddedCount)
                        append(if (member.songsAddedCount == 1) " song added" else " songs added")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                if (addedCurrentSong) {
                    Text(
                        "Added the song playing now",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (canModerate) {
                IconButton(onClick = onToggleChatMuted) {
                    Icon(
                        if (member.chatMuted) {
                            Icons.AutoMirrored.Filled.VolumeUp
                        } else {
                            Icons.AutoMirrored.Filled.VolumeOff
                        },
                        contentDescription = if (member.chatMuted) {
                            "Unmute ${member.displayName} in chat"
                        } else {
                            "Mute ${member.displayName} in chat"
                        },
                        tint = if (member.chatMuted) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        if (member.connected) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                if (member.connected) "Online" else "Offline",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                detail,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyQueue() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(11.dp).size(22.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("Build the room queue", fontWeight = FontWeight.SemiBold)
            Text(
                "Search YouTube above and add the first video.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun QueueOrderingHint(room: RoomSnapshot) {
    val hasReorderableTies = room.queue
        .groupingBy(QueueItem::voteCount)
        .eachCount()
        .any { it.value > 1 }
    val text = when {
        !room.me.isHost -> "Votes move popular picks up automatically."
        hasReorderableTies ->
            "Drag the handle to order tied songs. Use ⋮ → Play next to override votes."
        else -> "Votes set this order. Use ⋮ → Play next to choose a different next song."
    }
    Text(
        text = text,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EmptyHistory() {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Songs played in this room will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryRow(
    video: VideoSummary,
    addedByName: String,
    isCurrent: Boolean,
    playedAt: Long? = null,
    serverTimeMs: Long? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.44f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        },
        border = BorderStroke(
            1.dp,
            if (isCurrent) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VideoThumbnail(video)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    video.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append("Added by ")
                        append(addedByName)
                        if (!isCurrent && playedAt != null && serverTimeMs != null) {
                            append("  •  ")
                            append(formatRelativeTime(serverTimeMs - playedAt))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isCurrent) {
                    Text(
                        "PLAYING NOW",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerConsent(onConsent: () -> Unit) {
    val panelShape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .height(260.dp)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
                    ),
                ),
                shape = panelShape,
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                shape = panelShape,
            )
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(112.dp)
                .background(Color.White.copy(alpha = 0.035f), CircleShape),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(12.dp).size(28.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "READY FOR THE ROOM?",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Start the official YouTube player now, or simply add a video to begin playback.",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onConsent,
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Start player", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SyncedYouTubePlayer(
    state: MuzikUiState,
    selectedQuality: QualityOption,
    onQualitySelected: (QualityOption) -> Unit,
    playerViewport: @Composable () -> Unit,
    isInPictureInPictureMode: Boolean,
) {
    var qualityMenuExpanded by remember { mutableStateOf(false) }
    val panelShape = RoundedCornerShape(28.dp)
    val status = state.room?.playback?.status
    val statusColor = when (status) {
        "playing" -> MaterialTheme.colorScheme.secondary
        "paused" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = if (isInPictureInPictureMode) {
            Modifier.fillMaxSize().background(Color.Black)
        } else {
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
                shape = panelShape,
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = panelShape,
            )
                .padding(12.dp)
        },
    ) {
        if (!isInPictureInPictureMode) Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(statusColor, CircleShape))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    "SYNCED PLAYER",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    playerStatusLabel(status),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                )
            }
            Spacer(Modifier.weight(1f))
            Box {
                OutlinedButton(
                    onClick = { qualityMenuExpanded = true },
                    modifier = Modifier.height(38.dp),
                    shape = RoundedCornerShape(13.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Text(selectedQuality.label, style = MaterialTheme.typography.labelLarge)
                }
                DropdownMenu(
                    expanded = qualityMenuExpanded,
                    onDismissRequest = { qualityMenuExpanded = false },
                ) {
                    qualityOptions.forEach { quality ->
                        DropdownMenuItem(
                            text = { Text(quality.label) },
                            onClick = {
                                onQualitySelected(quality)
                                qualityMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }
        if (!isInPictureInPictureMode) Spacer(Modifier.height(10.dp))
        Box(
            modifier = if (isInPictureInPictureMode) {
                Modifier.fillMaxSize().background(Color.Black)
            } else {
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black, RoundedCornerShape(18.dp))
                    .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f),
                    shape = RoundedCornerShape(18.dp),
                )
                    .padding(2.dp)
                    .height(220.dp)
            },
        ) {
            playerViewport()
        }
        if (!isInPictureInPictureMode) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Official YouTube player  •  Quality may adapt to this device",
                modifier = Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun shareRoomInvite(context: Context, roomCode: String) {
    val inviteLink = "muzik://join?room=$roomCode"
    val message = "Join my Muzik room $roomCode. Open Muzik and enter the room code.\n$inviteLink"
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Join my Muzik room")
        putExtra(Intent.EXTRA_TEXT, message)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Invite to Muzik"))
}

@Composable
private fun NowPlaying(state: MuzikUiState, room: RoomSnapshot, viewModel: MuzikViewModel) {
    var seekPositionMs by remember(room.playback.video?.videoId) {
        mutableFloatStateOf(state.playerPositionMs.toFloat())
    }
    var seeking by remember(room.playback.video?.videoId) { mutableStateOf(false) }
    LaunchedEffect(state.playerPositionMs, seeking) {
        if (!seeking) seekPositionMs = state.playerPositionMs.toFloat()
    }
    val cardShape = RoundedCornerShape(24.dp)
    val isPlaying = room.playback.status == "playing"
    val canPlay = room.playback.video != null || room.queue.isNotEmpty()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "NOW PLAYING",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                PlaybackStatusPill(room.playback.status)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                room.playback.video?.title ?: "Nothing playing yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (room.playback.video != null) {
                    buildString {
                        append(room.playback.video?.channelTitle.orEmpty().ifBlank { "YouTube" })
                        room.playback.addedByName?.let {
                            append("  •  Added by ")
                            append(it)
                        }
                    }
                } else {
                    "Add something from the queue to get the room started"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (room.playback.video != null && state.playerDurationMs > 0) {
                val duration = state.playerDurationMs.toFloat().coerceAtLeast(1f)
                Spacer(Modifier.height(12.dp))
                Slider(
                    value = seekPositionMs.coerceIn(0f, duration),
                    onValueChange = {
                        seeking = true
                        seekPositionMs = it
                    },
                    onValueChangeFinished = {
                        if (room.me.isHost) viewModel.seek(seekPositionMs.toLong())
                        seeking = false
                    },
                    valueRange = 0f..duration,
                    enabled = room.me.isHost,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledThumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
                        disabledActiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.68f),
                        disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatTime(seekPositionMs.toLong()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    if (room.me.isHost) {
                        Text(
                            "DRAG TO SEEK  •  ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Text(
                        formatTime(state.playerDurationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 18.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (room.me.isHost) {
                    Button(
                        onClick = {
                            if (isPlaying) viewModel.pause() else viewModel.play()
                            Unit
                        },
                        enabled = isPlaying || canPlay,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(17.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isPlaying) "Pause" else "Play",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    FilledTonalButton(
                        onClick = viewModel::next,
                        enabled = canPlay,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(17.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Next")
                    }
                } else {
                    OutlinedButton(
                        onClick = viewModel::requestPause,
                        enabled = isPlaying && room.pauseVote == null,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(17.dp),
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (room.pauseVote == null) "Request pause" else "Vote open",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    FilledTonalButton(
                        onClick = viewModel::voteToSkip,
                        enabled = !room.skip.votedByMe && room.playback.video != null,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(17.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (room.skip.votedByMe) {
                                "Skip sent ${room.skip.votes}/${room.skip.threshold}"
                            } else {
                                "Skip ${room.skip.votes}/${room.skip.threshold}"
                            },
                            maxLines = 1,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PauseVoteCard(
    requestedByName: String,
    yesVotes: Int,
    noVotes: Int,
    threshold: Int,
    myVote: String?,
    expiresAt: Long,
    startedAt: Long?,
    serverTimeMs: Long,
    eligibleVoters: Int,
    onVote: (Boolean) -> Unit,
) {
    var estimatedServerNowMs by remember(expiresAt) { mutableLongStateOf(serverTimeMs) }
    LaunchedEffect(expiresAt, serverTimeMs) {
        val baselineServerTimeMs = serverTimeMs
        val baselineElapsedMs = android.os.SystemClock.elapsedRealtime()
        do {
            estimatedServerNowMs = baselineServerTimeMs +
                (android.os.SystemClock.elapsedRealtime() - baselineElapsedMs)
            delay(100)
        } while (estimatedServerNowMs < expiresAt)
    }
    val totalDurationMs = (expiresAt - (startedAt ?: (expiresAt - 10_000L)))
        .coerceAtLeast(1L)
    val remainingMs = (expiresAt - estimatedServerNowMs).coerceAtLeast(0L)
    val remainingSeconds = (remainingMs + 999L) / 1_000L
    val elapsedFraction = (1f - remainingMs.toFloat() / totalDurationMs.toFloat())
        .coerceIn(0f, 1f)
    val warningColor = Color(0xFFFFB547)
    val dangerColor = Color(0xFFFF3D5A)
    val timerColor = if (elapsedFraction < 0.5f) {
        lerp(Color(0xFF2DD4A3), warningColor, elapsedFraction / 0.5f)
    } else {
        lerp(
            warningColor,
            dangerColor,
            ((elapsedFraction - 0.5f) / 0.35f).coerceIn(0f, 1f),
        )
    }
    val responseCount = yesVotes + noVotes
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, timerColor.copy(alpha = 0.62f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$requestedByName requested a pause",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Surface(
                    shape = CircleShape,
                    color = timerColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, timerColor.copy(alpha = 0.55f)),
                ) {
                    Text(
                        "${remainingSeconds}s",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = timerColor,
                    )
                }
            }
            Text(
                "$responseCount/$eligibleVoters responded  •  $yesVotes yes  •  $threshold yes needed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "If nobody responds before zero, playback pauses automatically.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { remainingMs.toFloat() / totalDurationMs.toFloat() },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = timerColor,
                trackColor = timerColor.copy(alpha = 0.14f),
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onVote(true) },
                    enabled = myVote != "yes",
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(15.dp),
                ) {
                    Icon(Icons.Default.ThumbUp, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (myVote == "yes") "Voted yes" else "Yes")
                }
                OutlinedButton(
                    onClick = { onVote(false) },
                    enabled = myVote != "no",
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(15.dp),
                ) {
                    Icon(Icons.Default.ThumbDown, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (myVote == "no") "Voted no" else "No")
                }
            }
        }
    }
}

@Composable
private fun PlaybackStatusPill(status: String) {
    val label: String
    val color: Color
    when (status) {
        "playing" -> {
            label = "LIVE SYNC"
            color = MaterialTheme.colorScheme.secondary
        }
        "paused" -> {
            label = "PAUSED"
            color = MaterialTheme.colorScheme.primary
        }
        else -> {
            label = "READY"
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
    }
}

private fun playerStatusLabel(status: String?): String = when (status) {
    "playing" -> "Live with the room"
    "paused" -> "Room playback paused"
    else -> "Waiting for a selection"
}

@Composable
private fun SearchBox(state: MuzikUiState, viewModel: MuzikViewModel) {
    val focusManager = LocalFocusManager.current
    val canDismiss = state.searching || state.searchQuery.isNotBlank() || state.searchResults.isNotEmpty()
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Find your next video",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Search YouTube and add a pick for everyone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::setSearchQuery,
                placeholder = { Text("Songs, artists, live sets…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.searching) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(
                                onClick = viewModel::search,
                                enabled = state.searchQuery.trim().length >= 2,
                            ) {
                                Icon(Icons.Default.Search, contentDescription = "Search YouTube")
                            }
                        }
                        if (canDismiss) {
                            IconButton(
                                onClick = {
                                    viewModel.dismissSearch()
                                    focusManager.clearFocus()
                                },
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close search results")
                            }
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    viewModel.search()
                    focusManager.clearFocus()
                }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!state.searching && state.searchedQuery != null && state.searchResults.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "No results for “${state.searchedQuery}”. Try a song, artist, or video title.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
            )
            Text(
                "Import a YouTube playlist",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Adds up to 50 new embeddable videos while preserving playlist order.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.playlistInput,
                onValueChange = viewModel::setPlaylistInput,
                placeholder = { Text("Playlist URL or ID") },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null)
                },
                trailingIcon = {
                    if (state.importingPlaylist) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(
                            onClick = viewModel::importPlaylist,
                            enabled = state.playlistInput.isNotBlank(),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Import YouTube playlist",
                            )
                        }
                    }
                },
                enabled = !state.importingPlaylist,
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    viewModel.importPlaylist()
                    focusManager.clearFocus()
                }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SearchResult(video: VideoSummary, onAdd: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VideoThumbnail(video)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    video.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    video.channelTitle.ifBlank { "YouTube" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onAdd,
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add ${video.title} to queue",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: QueueItem,
    addedByName: String,
    isHost: Boolean,
    canReorder: Boolean,
    modifier: Modifier,
    dragHandleModifier: Modifier,
    viewModel: MuzikViewModel,
) {
    var showActions by remember { mutableStateOf(false) }
    val metadata = listOfNotNull(
        item.video.channelTitle.takeIf(String::isNotBlank),
        item.video.durationMs?.takeIf { it > 0 }?.let(::formatVideoDuration),
    ).joinToString("  •  ")
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (item.votedByMe) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                },
            ) {
                Column(
                    modifier = Modifier.width(48.dp).padding(vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconButton(
                        onClick = { viewModel.vote(item.id, !item.votedByMe) },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            contentDescription = if (item.votedByMe) {
                                "Remove vote from ${item.video.title}"
                            } else {
                                "Vote for ${item.video.title}"
                            },
                            tint = if (item.votedByMe) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Text(
                        "${item.voteCount}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.video.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metadata.isNotBlank()) {
                    Text(
                        metadata,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "Added by $addedByName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.isForcedNext) {
                    Text(
                        "HOST PICK  •  PLAYS NEXT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (isHost) {
                if (canReorder) {
                    Box(
                        modifier = Modifier.size(48.dp).then(dragHandleModifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = "Drag ${item.video.title} within songs with ${item.voteCount} votes",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box {
                    IconButton(
                        onClick = { showActions = true },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Queue actions for ${item.video.title}",
                        )
                    }
                    DropdownMenu(
                        expanded = showActions,
                        onDismissRequest = { showActions = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(if (item.isForcedNext) "Selected to play next" else "Play next")
                            },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null)
                            },
                            enabled = !item.isForcedNext,
                            onClick = {
                                showActions = false
                                viewModel.playNext(item.id)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Play now") },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                            onClick = {
                                showActions = false
                                viewModel.playItem(item.id)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove from queue") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                showActions = false
                                viewModel.remove(item.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoThumbnail(video: VideoSummary) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .width(112.dp)
            .height(64.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        if (video.thumbnailUrl.isNotBlank()) {
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        video.durationMs?.takeIf { it > 0 }?.let { durationMs ->
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.82f),
            ) {
                Text(
                    formatVideoDuration(durationMs),
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}

private fun connectionLabel(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.Connected -> "Connected"
    ConnectionStatus.Connecting -> "Connecting…"
    ConnectionStatus.Disconnected -> "Offline"
}

@Composable
private fun connectionColor(status: ConnectionStatus): Color = when (status) {
    ConnectionStatus.Connected -> MaterialTheme.colorScheme.secondary
    ConnectionStatus.Connecting -> MaterialTheme.colorScheme.tertiary
    ConnectionStatus.Disconnected -> MaterialTheme.colorScheme.error
}

private fun formatVideoDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

private fun formatRelativeTime(elapsedMs: Long): String {
    val minutes = elapsedMs.coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} hr ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
