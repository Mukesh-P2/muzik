package com.muzik.app.network

import com.muzik.app.BuildConfig
import com.muzik.app.model.Membership
import com.muzik.app.model.SearchResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MuzikClient(
    val baseUrl: String = BuildConfig.SERVER_URL.trimEnd('/'),
) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    val http = HttpClient(OkHttp) {
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            // Render Free can take about a minute to wake. Do not automatically retry
            // create/join POST requests because a lost response could create duplicates.
            requestTimeoutMillis = 90_000
            connectTimeoutMillis = 30_000
        }
        install(WebSockets) {
            pingIntervalMillis = 20_000
        }
    }

    suspend fun createRoom(displayName: String): Membership =
        http.post("$baseUrl/api/rooms") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("displayName", displayName.trim()) })
        }.body()

    suspend fun joinRoom(roomCode: String, displayName: String): Membership =
        http.post("$baseUrl/api/rooms/${roomCode.trim().uppercase()}/join") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("displayName", displayName.trim()) })
        }.body()

    suspend fun search(query: String, membership: Membership): SearchResponse =
        http.get("$baseUrl/api/youtube/search") {
            url { parameters.append("q", query.trim()) }
            header("X-Room-Code", membership.roomCode)
            header("X-Member-Id", membership.memberId)
            header("X-Member-Token", membership.memberToken)
        }.body()

    suspend fun importPlaylist(value: String, membership: Membership): SearchResponse =
        http.get("$baseUrl/api/youtube/playlist") {
            url { parameters.append("value", value.trim()) }
            header("X-Room-Code", membership.roomCode)
            header("X-Member-Id", membership.memberId)
            header("X-Member-Token", membership.memberToken)
        }.body()

    fun close() = http.close()
}
