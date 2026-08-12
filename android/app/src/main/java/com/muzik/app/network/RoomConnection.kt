package com.muzik.app.network

import com.muzik.app.model.ConnectionStatus
import com.muzik.app.model.ChatMessage
import com.muzik.app.model.ChatMessageEnvelope
import com.muzik.app.model.Membership
import com.muzik.app.model.PongEnvelope
import com.muzik.app.model.QueueImportResultEnvelope
import com.muzik.app.model.RoomSnapshot
import com.muzik.app.model.RoomSnapshotEnvelope
import com.muzik.app.model.VideoSummary
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class RoomConnection(
    private val client: MuzikClient,
    private val membership: Membership,
    private val scope: CoroutineScope,
    private val onSnapshot: (RoomSnapshot) -> Unit,
    private val onChatMessage: (ChatMessage) -> Unit,
    private val onQueueImportResult: (QueueImportResultEnvelope) -> Unit,
    private val onActionError: (String, String) -> Unit,
    private val onStatus: (ConnectionStatus) -> Unit,
    private val onClockOffset: (Long) -> Unit,
    private val onMembershipInvalid: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private var connectionJob: Job? = null
    private var session: DefaultClientWebSocketSession? = null
    private var smoothedClockOffsetMs: Double? = null

    fun connect() {
        if (connectionJob != null) return
        connectionJob = scope.launch {
            var retryDelayMs = 500L
            var lastConnectionError: String? = null
            while (isActive) {
                onStatus(ConnectionStatus.Connecting)
                try {
                    client.http.webSocket(request = {
                        url(webSocketUrl())
                        header("X-Room-Code", membership.roomCode)
                        header("X-Member-Id", membership.memberId)
                        header("X-Member-Token", membership.memberToken)
                        header("X-Muzik-Capabilities", "pause-vote-v1")
                    }) {
                        session = this
                        onStatus(ConnectionStatus.Connected)
                        lastConnectionError = null
                        retryDelayMs = 500L
                        sendJson(buildJsonObject { put("type", "request_snapshot") })
                        val pingJob = launch {
                            while (isActive) {
                                val now = System.currentTimeMillis()
                                sendJson(buildJsonObject {
                                    put("type", "ping")
                                    put("nonce", UUID.randomUUID().toString())
                                    put("clientTimeMs", now)
                                })
                                delay(5_000)
                            }
                        }
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) handle(frame.readText())
                            }
                        } finally {
                            pingJob.cancel()
                            session = null
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (isTerminalMembershipRejection(error)) {
                        onStatus(ConnectionStatus.Disconnected)
                        onMembershipInvalid()
                        return@launch
                    }
                    val message = error.message ?: "Room connection failed"
                    if (message != lastConnectionError) onError(message)
                    lastConnectionError = message
                }
                onStatus(ConnectionStatus.Disconnected)
                delay(retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(10_000)
            }
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        session = null
        onStatus(ConnectionStatus.Disconnected)
    }

    fun leave() {
        val activeSession = session
        val activeJob = connectionJob
        connectionJob = null
        scope.launch {
            try {
                activeSession?.sendJson(buildJsonObject { put("type", "leave_room") })
            } catch (_: Exception) {
                // A broken connection is already equivalent to being disconnected.
            } finally {
                activeJob?.cancel()
                session = null
                onStatus(ConnectionStatus.Disconnected)
            }
        }
    }

    fun addToQueue(video: VideoSummary, startPlayback: Boolean = false) {
        val addMessage = queueAddMessage(video)
        if (startPlayback) {
            sendInOrder(
                addMessage,
                buildJsonObject {
                    put("type", "playback_control")
                    put("action", "play")
                },
            )
        } else {
            send(addMessage)
        }
    }

    suspend fun addManyToQueue(
        requestId: String,
        videos: List<VideoSummary>,
        startPlayback: Boolean = false,
    ): Boolean {
        if (videos.isEmpty()) return false
        val activeSession = session
        if (activeSession == null) {
            onError("Room is reconnecting")
            return false
        }
        return try {
            activeSession.sendJson(queueAddManyMessage(requestId, videos, startPlayback))
            true
        } catch (error: Exception) {
            onError(error.message ?: "Unable to send playlist import")
            false
        }
    }

    fun vote(itemId: String, enabled: Boolean) = send(buildJsonObject {
        put("type", "queue_vote")
        put("itemId", itemId)
        put("enabled", enabled)
    })

    fun remove(itemId: String) = send(buildJsonObject {
        put("type", "queue_remove")
        put("itemId", itemId)
    })

    fun clearQueue() = send(buildJsonObject { put("type", "queue_clear") })

    fun reorder(itemId: String, beforeItemId: String?) =
        send(queueReorderMessage(itemId, beforeItemId))

    fun playNext(itemId: String) = send(queuePlayNextMessage(itemId))

    fun playItem(itemId: String) = send(buildJsonObject {
        put("type", "play_item")
        put("itemId", itemId)
    })

    fun playback(action: String, positionMs: Long? = null) = send(buildJsonObject {
        put("type", "playback_control")
        put("action", action)
        positionMs?.let { put("positionMs", it) }
    })

    fun requestPause() = send(pauseRequestMessage())

    fun votePause(vote: Boolean, pollId: String) = send(pauseVoteMessage(vote, pollId))

    fun voteToSkip() = send(buildJsonObject { put("type", "skip_vote") })

    fun sendChat(text: String): Boolean = enqueueSend(chatSendMessage(text))

    fun deleteChat(messageId: String) = send(chatDeleteMessage(messageId))

    fun setChatMuted(memberId: String, muted: Boolean) =
        send(chatMuteMessage(memberId, muted))

    private fun send(payload: JsonObject) {
        sendInOrder(payload)
    }

    private fun sendInOrder(vararg payloads: JsonObject) {
        enqueueSend(*payloads)
    }

    private fun enqueueSend(vararg payloads: JsonObject): Boolean {
        val activeSession = session
        if (activeSession == null) {
            onError("Room is reconnecting")
            return false
        }
        scope.launch {
            try {
                payloads.forEach { activeSession.sendJson(it) }
            } catch (error: Exception) {
                onError(error.message ?: "Unable to send room action")
            }
        }
        return true
    }

    private suspend fun DefaultClientWebSocketSession.sendJson(payload: JsonObject) {
        send(client.json.encodeToString(payload))
    }

    private fun handle(raw: String) {
        val element = client.json.parseToJsonElement(raw)
        val type = element.jsonObject["type"]?.jsonPrimitive?.content ?: return
        when (type) {
            "room_snapshot" -> {
                val envelope = client.json.decodeFromJsonElement(RoomSnapshotEnvelope.serializer(), element)
                onSnapshot(envelope.room)
            }
            "chat_message" -> {
                val envelope = client.json.decodeFromJsonElement(ChatMessageEnvelope.serializer(), element)
                onChatMessage(envelope.message)
            }
            "queue_import_result" -> {
                val envelope = client.json.decodeFromJsonElement(
                    QueueImportResultEnvelope.serializer(),
                    element,
                )
                onQueueImportResult(envelope)
            }
            "pong" -> {
                val pong = client.json.decodeFromJsonElement(PongEnvelope.serializer(), element)
                val receivedAt = System.currentTimeMillis()
                val midpoint = pong.clientTimeMs + (receivedAt - pong.clientTimeMs) / 2
                val sample = pong.serverTimeMs - midpoint
                smoothedClockOffsetMs = smoothedClockOffsetMs?.let { it * 0.75 + sample * 0.25 }
                    ?: sample.toDouble()
                onClockOffset(smoothedClockOffsetMs!!.toLong())
            }
            "error" -> {
                val message = element.jsonObject["message"]?.jsonPrimitive?.content
                val requestId = element.jsonObject["requestId"]?.jsonPrimitive?.content
                if (requestId != null) {
                    onActionError(requestId, message ?: "Room action failed")
                } else {
                    onError(message ?: "Room action failed")
                }
            }
        }
    }

    private fun webSocketUrl(): String {
        val schemeAdjusted = client.baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        return "$schemeAdjusted/ws"
    }
}

internal fun isTerminalMembershipRejection(error: Throwable): Boolean =
    generateSequence(error) { current ->
        current.cause?.takeUnless { cause -> cause === current }
    }.take(8).any { candidate ->
        val responseStatus = (candidate as? ResponseException)?.response?.status?.value
        responseStatus == 401 || isUnauthorizedWebSocketHandshake(candidate.message)
    }

private fun isUnauthorizedWebSocketHandshake(message: String?): Boolean {
    val normalized = message?.lowercase() ?: return false
    val identifiesWebSocketHandshake =
        "handshake" in normalized || "websocket" in normalized || "web socket" in normalized
    val identifiesUnauthorized =
        Regex("\\b401\\b").containsMatchIn(normalized) || "unauthorized" in normalized
    return identifiesWebSocketHandshake && identifiesUnauthorized
}

internal fun queueReorderMessage(itemId: String, beforeItemId: String?): JsonObject =
    buildJsonObject {
        put("type", "queue_reorder")
        put("itemId", itemId)
        beforeItemId?.let { put("beforeItemId", it) }
    }

internal fun queueAddMessage(video: VideoSummary): JsonObject = buildJsonObject {
    put("type", "queue_add")
    putJsonObject("video") {
        put("videoId", video.videoId)
        put("title", video.title)
        put("channelTitle", video.channelTitle)
        put("thumbnailUrl", video.thumbnailUrl)
        video.durationMs?.let { put("durationMs", it) }
    }
}

internal fun queueAddManyMessage(
    requestId: String,
    videos: List<VideoSummary>,
    startPlayback: Boolean,
): JsonObject = buildJsonObject {
    put("type", "queue_add_many")
    put("requestId", requestId)
    put("startPlayback", startPlayback)
    put("videos", kotlinx.serialization.json.JsonArray(videos.map(::videoMessage)))
}

private fun videoMessage(video: VideoSummary): JsonObject = buildJsonObject {
    put("videoId", video.videoId)
    put("title", video.title)
    put("channelTitle", video.channelTitle)
    put("thumbnailUrl", video.thumbnailUrl)
    video.durationMs?.let { put("durationMs", it) }
}

internal fun queuePlayNextMessage(itemId: String): JsonObject = buildJsonObject {
    put("type", "queue_play_next")
    put("itemId", itemId)
}

internal fun pauseRequestMessage(): JsonObject = buildJsonObject {
    put("type", "pause_request")
}

internal fun pauseVoteMessage(vote: Boolean, pollId: String): JsonObject = buildJsonObject {
    put("type", "pause_vote")
    put("vote", if (vote) "yes" else "no")
    put("pollId", pollId)
}

internal fun chatSendMessage(text: String): JsonObject = buildJsonObject {
    put("type", "chat_send")
    put("text", text)
}

internal fun chatDeleteMessage(messageId: String): JsonObject = buildJsonObject {
    put("type", "chat_delete")
    put("messageId", messageId)
}

internal fun chatMuteMessage(memberId: String, muted: Boolean): JsonObject = buildJsonObject {
    put("type", "chat_mute")
    put("memberId", memberId)
    put("muted", muted)
}
