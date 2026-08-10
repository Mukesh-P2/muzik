import { existsSync, readFileSync } from "node:fs";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { URL } from "node:url";
import { WebSocket, WebSocketServer } from "ws";
import { RoomError, RoomManager } from "./room.js";
import type { VideoSummary } from "./types.js";

loadLocalEnv();

const port = parsePort(process.env.PORT);
const allowedOrigin = process.env.ALLOWED_ORIGIN ?? "*";
const rooms = new RoomManager();
const sockets = new Map<string, Map<string, Set<WebSocket>>>();
const responsiveSockets = new WeakSet<WebSocket>();
const searchCache = new Map<string, { expiresAt: number; results: VideoSummary[] }>();
const rateLimits = new Map<string, { resetAt: number; count: number }>();
const ROOM_IDLE_TTL_MS = 6 * 60 * 60 * 1_000;

const server = createServer(async (request, response) => {
  setCors(response);
  if (request.method === "OPTIONS") {
    response.writeHead(204).end();
    return;
  }

  try {
    const url = new URL(request.url ?? "/", `http://${request.headers.host}`);

    if (request.method === "GET" && url.pathname === "/health") {
      sendJson(response, 200, { ok: true, serverTimeMs: Date.now() });
      return;
    }

    if (request.method === "GET" && url.pathname === "/") {
      sendJson(response, 200, { name: "Muzik room server", ok: true });
      return;
    }

    enforceRateLimit(`http:${clientAddress(request)}`, 120);

    if (request.method === "POST" && url.pathname === "/api/rooms") {
      const body = await readJson(request);
      const { room, member } = rooms.createRoom(String(body.displayName ?? ""));
      sendJson(response, 201, membershipResponse(room.code, member));
      return;
    }

    const joinMatch = url.pathname.match(/^\/api\/rooms\/([A-Za-z0-9]+)\/join$/);
    if (request.method === "POST" && joinMatch) {
      const body = await readJson(request);
      const room = rooms.getRoom(joinMatch[1]!);
      const member = room.addMember(String(body.displayName ?? ""));
      sendJson(response, 201, membershipResponse(room.code, member));
      return;
    }

    if (request.method === "GET" && url.pathname === "/api/youtube/search") {
      authenticateRequest(request);
      enforceRateLimit(`search:${String(request.headers["x-member-id"] ?? "")}`, 10);
      const query = (url.searchParams.get("q") ?? "").trim();
      if (query.length < 2) throw new RoomError("Search query is too short");
      if (query.length > 100) throw new RoomError("Search query is too long");
      const results = await searchYouTube(query);
      sendJson(response, 200, { results });
      return;
    }

    sendJson(response, 404, { error: "Not found" });
  } catch (error) {
    handleError(response, error);
  }
});

const webSockets = new WebSocketServer({ noServer: true, maxPayload: 32_000 });

server.on("upgrade", (request, socket, head) => {
  try {
    const url = new URL(request.url ?? "/", `http://${request.headers.host}`);
    if (url.pathname !== "/ws") {
      socket.destroy();
      return;
    }
    const roomCode = headerValue(request, "x-room-code") ?? url.searchParams.get("roomCode") ?? "";
    const memberId = headerValue(request, "x-member-id") ?? url.searchParams.get("memberId") ?? "";
    const token = headerValue(request, "x-member-token") ?? url.searchParams.get("token") ?? "";
    const room = rooms.getRoom(roomCode);
    room.authenticate(memberId, token);

    webSockets.handleUpgrade(request, socket, head, (webSocket) => {
      webSockets.emit("connection", webSocket, request, { roomCode: room.code, memberId });
    });
  } catch {
    socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
    socket.destroy();
  }
});

webSockets.on(
  "connection",
  (webSocket: WebSocket, _request: IncomingMessage, context: unknown) => {
    const { roomCode, memberId } = context as { roomCode: string; memberId: string };
    const room = rooms.getRoom(roomCode);
    addSocket(roomCode, memberId, webSocket);
    responsiveSockets.add(webSocket);
    webSocket.on("pong", () => responsiveSockets.add(webSocket));
    room.setConnected(memberId, true);
    broadcastRoom(roomCode);
    let messageWindowStartedAt = Date.now();
    let messageCount = 0;

    webSocket.on("message", (data) => {
      try {
        const now = Date.now();
        if (now - messageWindowStartedAt >= 60_000) {
          messageWindowStartedAt = now;
          messageCount = 0;
        }
        messageCount += 1;
        if (messageCount > 180) {
          webSocket.close(1008, "Message rate limit exceeded");
          return;
        }
        const message = parseObjectJson(data.toString(), "Invalid room message");
        if (message.type === "ping") {
          send(webSocket, {
            type: "pong",
            nonce: message.nonce,
            clientTimeMs: message.clientTimeMs,
            serverTimeMs: Date.now(),
          });
          return;
        }

        if (message.type === "queue_add") {
          room.addToQueue(memberId, message.video as VideoSummary);
        } else if (message.type === "queue_vote") {
          room.setVote(memberId, String(message.itemId ?? ""), Boolean(message.enabled));
        } else if (message.type === "queue_remove") {
          room.removeQueueItem(memberId, String(message.itemId ?? ""));
        } else if (message.type === "play_item") {
          room.playQueueItem(memberId, String(message.itemId ?? ""));
        } else if (message.type === "playback_control") {
          const action = String(message.action ?? "") as "play" | "pause" | "seek" | "next";
          if (!["play", "pause", "seek", "next"].includes(action)) {
            throw new RoomError("Unknown playback action");
          }
          room.control(memberId, action, numberOrUndefined(message.positionMs));
        } else if (message.type === "skip_vote") {
          room.voteToSkip(memberId);
        } else if (message.type === "leave_room") {
          room.removeMember(memberId);
          closeMemberSockets(roomCode, memberId);
          broadcastRoom(roomCode);
          return;
        } else if (message.type === "request_snapshot") {
          send(webSocket, { type: "room_snapshot", room: room.publicStateFor(memberId) });
          return;
        } else {
          throw new RoomError("Unknown message type");
        }
        broadcastRoom(roomCode);
      } catch (error) {
        if (!(error instanceof RoomError) && !(error instanceof SyntaxError)) {
          console.warn("Unhandled WebSocket message error:", error);
        }
        send(webSocket, {
          type: "error",
          message: error instanceof RoomError ? error.message : "Invalid room message",
        });
      }
    });

    webSocket.on("close", () => {
      removeSocket(roomCode, memberId, webSocket);
      if (room.hasMember(memberId) && !hasSocket(roomCode, memberId)) {
        room.setConnected(memberId, false);
      }
      broadcastRoom(roomCode);
    });

    webSocket.on("error", (error) => {
      console.warn(`WebSocket error in room ${roomCode}:`, error.message);
    });
  },
);

const heartbeatTimer = setInterval(() => {
  for (const socket of webSockets.clients) {
    if (!responsiveSockets.has(socket)) {
      socket.terminate();
      continue;
    }
    responsiveSockets.delete(socket);
    socket.ping();
  }
}, 30_000);
heartbeatTimer.unref();

const cleanupTimer = setInterval(() => {
  for (const roomCode of rooms.pruneInactiveRooms(ROOM_IDLE_TTL_MS)) {
    sockets.delete(roomCode);
  }
  const now = Date.now();
  for (const [key, value] of rateLimits) {
    if (value.resetAt <= now) rateLimits.delete(key);
  }
  for (const [key, value] of searchCache) {
    if (value.expiresAt <= now) searchCache.delete(key);
  }
}, 10 * 60_000);
cleanupTimer.unref();

server.listen(port, "0.0.0.0", () => {
  console.log(`Muzik server listening on http://0.0.0.0:${port}`);
});

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.once(signal, () => {
    clearInterval(heartbeatTimer);
    clearInterval(cleanupTimer);
    webSockets.close();
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(1), 10_000).unref();
  });
}

function membershipResponse(code: string, member: { id: string; token: string; displayName: string; isHost: boolean }) {
  return {
    roomCode: code,
    memberId: member.id,
    memberToken: member.token,
    displayName: member.displayName,
    isHost: member.isHost,
  };
}

async function searchYouTube(query: string): Promise<VideoSummary[]> {
  const cacheKey = query.toLocaleLowerCase();
  const cached = searchCache.get(cacheKey);
  if (cached && cached.expiresAt > Date.now()) return cached.results;

  const apiKey = process.env.YOUTUBE_API_KEY;
  if (!apiKey) throw new RoomError("YouTube search is not configured", 503);
  const url = new URL("https://www.googleapis.com/youtube/v3/search");
  url.search = new URLSearchParams({
    key: apiKey,
    part: "snippet",
    type: "video",
    videoEmbeddable: "true",
    maxResults: "15",
    q: query,
  }).toString();
  let response: Response;
  try {
    response = await fetch(url, { signal: AbortSignal.timeout(10_000) });
  } catch (error) {
    console.warn("YouTube search request failed:", error);
    throw new RoomError("YouTube search is temporarily unavailable", 502);
  }
  if (!response.ok) throw new RoomError("YouTube search failed", 502);
  const body = (await response.json()) as {
    items?: Array<{
      id?: { videoId?: string };
      snippet?: {
        title?: string;
        channelTitle?: string;
        thumbnails?: { medium?: { url?: string }; default?: { url?: string } };
      };
    }>;
  };
  const results = (body.items ?? []).flatMap((item) => {
    const videoId = item.id?.videoId;
    const snippet = item.snippet;
    if (!videoId || !snippet?.title) return [];
    return [{
      videoId,
      title: decodeEntities(snippet.title),
      channelTitle: decodeEntities(snippet.channelTitle ?? ""),
      thumbnailUrl: snippet.thumbnails?.medium?.url ?? snippet.thumbnails?.default?.url ?? "",
    }];
  });
  if (searchCache.size >= 200) searchCache.delete(searchCache.keys().next().value ?? "");
  searchCache.set(cacheKey, { expiresAt: Date.now() + 10 * 60_000, results });
  return results;
}

function broadcastRoom(roomCode: string): void {
  const room = rooms.getRoom(roomCode);
  for (const [memberId, memberSockets] of sockets.get(roomCode) ?? []) {
    const payload = { type: "room_snapshot", room: room.publicStateFor(memberId) };
    for (const socket of memberSockets) send(socket, payload);
  }
}

function addSocket(roomCode: string, memberId: string, socket: WebSocket): void {
  let roomSockets = sockets.get(roomCode);
  if (!roomSockets) sockets.set(roomCode, (roomSockets = new Map()));
  let memberSockets = roomSockets.get(memberId);
  if (!memberSockets) roomSockets.set(memberId, (memberSockets = new Set()));
  memberSockets.add(socket);
}

function removeSocket(roomCode: string, memberId: string, socket: WebSocket): void {
  const memberSockets = sockets.get(roomCode)?.get(memberId);
  memberSockets?.delete(socket);
  if (memberSockets?.size === 0) sockets.get(roomCode)?.delete(memberId);
}

function hasSocket(roomCode: string, memberId: string): boolean {
  return (sockets.get(roomCode)?.get(memberId)?.size ?? 0) > 0;
}

function closeMemberSockets(roomCode: string, memberId: string): void {
  const roomSockets = sockets.get(roomCode);
  const memberSockets = roomSockets?.get(memberId);
  roomSockets?.delete(memberId);
  if (roomSockets?.size === 0) sockets.delete(roomCode);
  for (const socket of memberSockets ?? []) socket.close(1000, "Member left room");
}

function send(socket: WebSocket, value: unknown): void {
  if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(value));
}

function setCors(response: ServerResponse): void {
  response.setHeader("Access-Control-Allow-Origin", allowedOrigin);
  response.setHeader("Cache-Control", "no-store");
  response.setHeader("X-Content-Type-Options", "nosniff");
  response.setHeader(
    "Access-Control-Allow-Headers",
    "content-type, authorization, x-room-code, x-member-id, x-member-token",
  );
  response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
}

function sendJson(response: ServerResponse, status: number, value: unknown): void {
  response.writeHead(status, { "content-type": "application/json; charset=utf-8" });
  response.end(JSON.stringify(value));
}

async function readJson(request: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  let length = 0;
  for await (const chunk of request) {
    const buffer = Buffer.from(chunk);
    length += buffer.length;
    if (length > 32_000) throw new RoomError("Request body is too large", 413);
    chunks.push(buffer);
  }
  if (chunks.length === 0) return {};
  try {
    return parseObjectJson(Buffer.concat(chunks).toString("utf8"), "JSON body must be an object");
  } catch {
    throw new RoomError("Invalid JSON body");
  }
}

function parseObjectJson(value: string, invalidMessage: string): Record<string, unknown> {
  const parsed: unknown = JSON.parse(value);
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new RoomError(invalidMessage);
  }
  return parsed as Record<string, unknown>;
}

function handleError(response: ServerResponse, error: unknown): void {
  const status = error instanceof RoomError ? error.statusCode : 500;
  if (!(error instanceof RoomError)) console.error("Unhandled request error:", error);
  const message = error instanceof RoomError ? error.message : "Internal server error";
  sendJson(response, status, { error: message });
}

function enforceRateLimit(key: string, limit: number, now = Date.now()): void {
  const current = rateLimits.get(key);
  if (!current || current.resetAt <= now) {
    rateLimits.set(key, { resetAt: now + 60_000, count: 1 });
    return;
  }
  current.count += 1;
  if (current.count > limit) throw new RoomError("Too many requests; try again shortly", 429);
}

function clientAddress(request: IncomingMessage): string {
  const forwarded = request.headers["x-forwarded-for"];
  if (typeof forwarded === "string") return forwarded.split(",", 1)[0]!.trim();
  return request.socket.remoteAddress ?? "unknown";
}

function numberOrUndefined(value: unknown): number | undefined {
  return typeof value === "number" ? value : undefined;
}

function parsePort(value: string | undefined): number {
  const parsed = Number(value ?? 8080);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 65_535) {
    throw new Error("PORT must be an integer between 1 and 65535");
  }
  return parsed;
}

function authenticateRequest(request: IncomingMessage): void {
  const roomCode = request.headers["x-room-code"];
  const memberId = request.headers["x-member-id"];
  const token = request.headers["x-member-token"];
  if (typeof roomCode !== "string" || typeof memberId !== "string" || typeof token !== "string") {
    throw new RoomError("Room membership required", 401);
  }
  rooms.getRoom(roomCode).authenticate(memberId, token);
}

function headerValue(request: IncomingMessage, name: string): string | undefined {
  const value = request.headers[name];
  return typeof value === "string" ? value : undefined;
}

function loadLocalEnv(): void {
  if (!existsSync(".env")) return;
  for (const rawLine of readFileSync(".env", "utf8").split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const separator = line.indexOf("=");
    if (separator <= 0) continue;
    const key = line.slice(0, separator).trim();
    let value = line.slice(separator + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    if (process.env[key] === undefined) process.env[key] = value;
  }
}

function decodeEntities(value: string): string {
  return value
    .replaceAll("&amp;", "&")
    .replaceAll("&quot;", '"')
    .replaceAll("&#39;", "'")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">");
}
