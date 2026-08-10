package com.muzik.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.muzik.app.MuzikUiState
import com.muzik.app.MuzikViewModel
import com.muzik.app.model.ConnectionStatus
import com.muzik.app.model.QueueItem
import com.muzik.app.model.RoomSnapshot
import com.muzik.app.model.VideoSummary
import com.muzik.app.player.YouTubePlayerView

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
fun MuzikApp(state: MuzikUiState, viewModel: MuzikViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    if (state.membership == null) {
        HomeScreen(state, viewModel, snackbar)
    } else {
        RoomScreen(state, viewModel, snackbar)
    }
}

@Composable
private fun HomeScreen(
    state: MuzikUiState,
    viewModel: MuzikViewModel,
    snackbar: SnackbarHostState,
) {
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Muzik", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            Text(
                "Watch together. Choose together.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(36.dp))
            OutlinedTextField(
                value = state.displayName,
                onValueChange = viewModel::setDisplayName,
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = viewModel::createRoom,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Create a room")
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = state.roomCodeInput,
                onValueChange = viewModel::setRoomCode,
                label = { Text("Room code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = viewModel::joinRoom,
                enabled = !state.loading && state.roomCodeInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Join room") }
            Spacer(Modifier.height(24.dp))
            Text(
                "Playback uses a visible official YouTube player. Each participant starts playback explicitly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomScreen(
    state: MuzikUiState,
    viewModel: MuzikViewModel,
    snackbar: SnackbarHostState,
) {
    val room = state.room
    val context = LocalContext.current
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Room ${state.membership?.roomCode}", fontWeight = FontWeight.Bold)
                        Text(
                            connectionLabel(state.connectionStatus),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
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
                    TextButton(onClick = viewModel::leaveRoom) { Text("Leave") }
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
            )
        }
    }
}

@Composable
private fun RoomContent(
    modifier: Modifier,
    state: MuzikUiState,
    room: RoomSnapshot,
    viewModel: MuzikViewModel,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp),
    ) {
        item {
            if (state.playerConsent) {
                SyncedYouTubePlayer(state, viewModel)
            } else {
                PlayerConsent(viewModel::consentToPlayback)
            }
        }
        item { NowPlaying(state, room, viewModel) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Group, contentDescription = null)
                Text(
                    "  ${room.members.count { it.connected }} listening",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.weight(1f))
                if (room.me.isHost) Text("You are host", color = MaterialTheme.colorScheme.secondary)
            }
        }
        item { SearchBox(state, viewModel) }
        if (state.searchResults.isNotEmpty()) {
            item {
                Text(
                    "YouTube results",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(state.searchResults, key = VideoSummary::videoId) { video ->
                SearchResult(video, onAdd = { viewModel.addToQueue(video) })
            }
        }
        item {
            Text(
                "Up next",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        if (room.queue.isEmpty()) {
            item {
                Text(
                    "The queue is empty. Search YouTube to suggest something.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(room.queue, key = QueueItem::id) { item ->
                QueueRow(item, room.me.isHost, viewModel)
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
                "Start the official YouTube player and keep its video and controls visible while you sync.",
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
private fun SyncedYouTubePlayer(state: MuzikUiState, viewModel: MuzikViewModel) {
    var player by remember { mutableStateOf<YouTubePlayerView?>(null) }
    var selectedQuality by remember { mutableStateOf(qualityOptions.first()) }
    var qualityMenuExpanded by remember { mutableStateOf(false) }
    val panelShape = RoundedCornerShape(28.dp)
    val status = state.room?.playback?.status
    val statusColor = when (status) {
        "playing" -> MaterialTheme.colorScheme.secondary
        "paused" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
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
            .padding(12.dp),
    ) {
        Row(
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
                                selectedQuality = quality
                                qualityMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black, RoundedCornerShape(18.dp))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f),
                    shape = RoundedCornerShape(18.dp),
                )
                .padding(2.dp),
        ) {
            AndroidView(
                factory = { context ->
                    YouTubePlayerView(
                        context = context,
                        onEnded = viewModel::onPlayerEnded,
                        onMessage = viewModel::reportPlayerMessage,
                        onProgress = viewModel::reportPlayerProgress,
                    ).also { player = it }
                },
                update = { view ->
                    view.setPreferredQuality(selectedQuality.iframeValue)
                    state.room?.playback?.let { view.synchronize(it, state.clockOffsetMs) }
                },
                modifier = Modifier.fillMaxWidth().height(220.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Official YouTube player  •  Quality may adapt to this device",
            modifier = Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            player?.destroy()
            player = null
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
                room.playback.video?.channelTitle?.takeIf(String::isNotBlank)
                    ?: "Add something from the queue to get the room started",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
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
                    FilledTonalButton(
                        onClick = viewModel::voteToSkip,
                        enabled = !room.skip.votedByMe && room.playback.video != null,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(17.dp),
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (room.skip.votedByMe) {
                                "Skip vote sent  •  ${room.skip.votes}/${room.skip.threshold}"
                            } else {
                                "Vote to skip  •  ${room.skip.votes}/${room.skip.threshold}"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
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
    OutlinedTextField(
        value = state.searchQuery,
        onValueChange = viewModel::setSearchQuery,
        label = { Text("Search YouTube") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (state.searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else IconButton(onClick = viewModel::search) {
                Icon(Icons.Default.Search, contentDescription = "Search")
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

@Composable
private fun SearchResult(video: VideoSummary, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                video.channelTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add to queue") }
    }
}

@Composable
private fun QueueRow(item: QueueItem, isHost: Boolean, viewModel: MuzikViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { viewModel.vote(item.id, !item.votedByMe) }) {
            Icon(
                Icons.Default.ArrowUpward,
                contentDescription = if (item.votedByMe) "Remove vote" else "Vote",
                tint = if (item.votedByMe) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
            )
        }
        Text("${item.voteCount}", modifier = Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.video.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                item.video.channelTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isHost) {
            IconButton(onClick = { viewModel.playItem(item.id) }) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play now")
            }
            IconButton(onClick = { viewModel.remove(item.id) }) {
                Icon(Icons.Default.Delete, contentDescription = "Remove")
            }
        }
    }
}

private fun connectionLabel(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.Connected -> "Connected"
    ConnectionStatus.Connecting -> "Connecting…"
    ConnectionStatus.Disconnected -> "Offline"
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
