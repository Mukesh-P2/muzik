package com.muzik.app.network

import com.muzik.app.model.ConnectionStatus
import com.muzik.app.model.Membership
import com.muzik.app.model.PongEnvelope
import com.muzik.app.model.RoomSnapshot
import com.muzik.app.model.RoomSnapshotEnvelope
import com.muzik.app.model.VideoSummary
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
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
    private val onStatus: (ConnectionStatus) -> Unit,
    private val onClockOffset: (Long) -> Unit,
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

    fun addToQueue(video: VideoSummary) = send(buildJsonObject {
        put("type", "queue_add")
        putJsonObject("video") {
            put("videoId", video.videoId)
            put("title", video.title)
            put("channelTitle", video.channelTitle)
            put("thumbnailUrl", video.thumbnailUrl)
        }
    })

    fun vote(itemId: String, enabled: Boolean) = send(buildJsonObject {
        put("type", "queue_vote")
        put("itemId", itemId)
        put("enabled", enabled)
    })

    fun remove(itemId: String) = send(buildJsonObject {
        put("type", "queue_remove")
        put("itemId", itemId)
    })

    fun playItem(itemId: String) = send(buildJsonObject {
        put("type", "play_item")
        put("itemId", itemId)
    })

    fun playback(action: String, positionMs: Long? = null) = send(buildJsonObject {
        put("type", "playback_control")
        put("action", action)
        positionMs?.let { put("positionMs", it) }
    })

    fun voteToSkip() = send(buildJsonObject { put("type", "skip_vote") })

    private fun send(payload: JsonObject) {
        scope.launch {
            try {
                session?.sendJson(payload) ?: onError("Room is reconnecting")
            } catch (error: Exception) {
                onError(error.message ?: "Unable to send room action")
            }
        }
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
                onError(message ?: "Room action failed")
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
