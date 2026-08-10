# Muzik architecture and feature guide

## 1. What Muzik is

Muzik is an Android YouTube watch-party application. People join the same
room, suggest YouTube videos, vote on the queue, and follow one
server-authoritative playback timeline.

Muzik does **not** download, extract, proxy, cache, or separately play YouTube
audio. The backend fetches search metadata only. Every phone streams the
selected video directly from YouTube through the visible official YouTube
IFrame Player.

## 2. System overview

```text
Android phone A ── HTTPS + WSS ──┐
                                 │
Android phone B ── HTTPS + WSS ──┼── Public Muzik server (Render)
                                 │       │
Android phone C ── HTTPS + WSS ──┘       └── HTTPS search requests
                                                    │
Each phone ───────── direct video streaming ────────┴── YouTube
```

The public server is the meeting point, so phones do not need to be on the
same Wi-Fi network. They only need internet access and an APK built with the
same public server URL.

### Components

| Component | Responsibility |
|---|---|
| Compose UI | Join/create screens, player consent, queue, voting, search, host controls, and status messages |
| `MuzikViewModel` | Owns UI state and connects user actions to network/player operations |
| `MuzikClient` | Performs authenticated HTTPS calls and configures JSON and timeouts |
| `RoomConnection` | Maintains the WSS connection, reconnects, exchanges room messages, and estimates server time |
| `YouTubePlayerView` | Hosts a WebView containing the official IFrame Player and reconciles it with room playback |
| Node room server | Authenticates room members and owns presence, host permission, queue, votes, and playback state |
| YouTube Data API v3 | Returns embeddable video search metadata |
| YouTube IFrame Player API | Streams and controls the selected video independently on each phone |

## 3. Repository layout

```text
android/                         Native Android application
  app/src/main/assets/          Official IFrame Player wrapper page
  app/src/main/java/.../model/  Shared Android data models
  app/src/main/java/.../network HTTPS and WebSocket clients
  app/src/main/java/.../player/ Player synchronization bridge
  app/src/main/java/.../ui/     Compose screens
server/
  src/index.ts                  HTTP, WebSocket, YouTube search, and process lifecycle
  src/room.ts                   Room rules and authoritative state
  src/types.ts                  Server-side data types
  test/                         Room behavior tests
docs/                           Architecture and YouTube compliance rules
render.yaml                     Render deployment blueprint
```

## 4. Connection and authentication flow

### Create a room

1. The user enters a display name and taps **Create a room**.
2. Android sends `POST /api/rooms` with the display name.
3. The server creates a six-character room code, member ID, and random member
   token.
4. Android stores this membership in memory and opens `wss://SERVER/ws`.
5. Room code, member ID, and token are sent as WebSocket upgrade headers. The
   token is not placed in the URL.
6. The server authenticates the connection, marks the member connected, and
   broadcasts a personalized room snapshot.

### Join a room

Joining uses `POST /api/rooms/{code}/join`, then follows the same authenticated
WebSocket flow. Room codes are normalized to uppercase.

### Reconnection

If a network changes or the WebSocket closes, Android retries with exponential
delays from 500 ms up to 10 seconds. A connected client also sends application
clock pings every five seconds. The server sends WebSocket heartbeat frames and
terminates dead connections so presence and host handoff recover after abrupt
disconnects.

Membership is currently held in Android memory. Leaving the room or the Android
process being destroyed requires joining again.

## 5. HTTP API

| Method and path | Purpose | Authentication |
|---|---|---|
| `GET /` | Service identity/status | None |
| `GET /health` | Render health check | None |
| `POST /api/rooms` | Create a room | None |
| `POST /api/rooms/{code}/join` | Join a room | None |
| `GET /api/youtube/search?q=...` | Search embeddable videos | Room headers |

Authenticated requests use `X-Room-Code`, `X-Member-Id`, and
`X-Member-Token`. HTTP bodies are limited to 32 KB. General and search-specific
rate limits reduce accidental abuse of the public service and YouTube quota.

## 6. WebSocket protocol

The server broadcasts `room_snapshot` after a connection or successful room
mutation. Snapshots are personalized because `me.isHost` and `votedByMe` depend
on the receiving member.

Android-to-server messages:

| Type | Action |
|---|---|
| `ping` | Clock-offset sample request |
| `request_snapshot` | Request current room state |
| `queue_add` | Add a validated YouTube video summary |
| `queue_vote` | Add or remove the member's queue vote |
| `queue_remove` | Host removes an item |
| `play_item` | Host immediately selects an item |
| `playback_control` | Host sends `play`, `pause`, `seek`, or `next` |
| `skip_vote` | Member votes to advance to the next item |
| `leave_room` | Explicitly remove the member and their votes from the room |

Server-to-Android messages:

| Type | Contents |
|---|---|
| `room_snapshot` | Members, queue, playback, skip count, and server time |
| `pong` | Echoed client time and current server time |
| `error` | A rejected action's safe user-facing message |

WebSocket messages are limited to 32 KB and each connection has a message-rate
limit.

## 7. How YouTube search and playback work

### Search

1. A member submits at least two characters from the Android search box.
2. Android calls the authenticated Muzik search endpoint.
3. The server calls the official YouTube Data API v3 `search` endpoint with:
   `type=video` and `videoEmbeddable=true`.
4. The server returns video ID, title, channel name, and thumbnail URL. Results
   are cached in memory for ten minutes to reduce quota consumption.
5. A member adds a result to the shared queue. Only the YouTube ID and display
   metadata are stored; no media passes through the Muzik server.

`YOUTUBE_API_KEY` exists only on the server. It is never compiled into the APK.
YouTube search consumes Data API quota, so production usage needs quota
monitoring.

### Playback

1. Each participant explicitly taps **Start YouTube player**.
2. Android creates a visible WebView at least 200 dp high and loads the bundled
   IFrame Player wrapper.
3. When the host starts an item, the server broadcasts its YouTube video ID and
   playback timeline.
4. Each phone independently asks the official player to cue/load that ID.
5. The phone streams video directly from YouTube. YouTube controls, branding,
   availability restrictions, and ads remain controlled by YouTube.

Videos with embedding disabled, deleted videos, region restrictions, and
player errors are shown to the user; Muzik does not bypass them.

## 8. Synchronization model

The server, not the host phone, owns playback truth:

```text
video                 selected YouTube metadata or null
status                idle | playing | paused
positionMs            position at the anchor
anchorServerTimeMs    server time when the state takes effect
revision              increases for each timeline change
```

For a playing video, the expected position is:

```text
positionMs + max(0, estimatedServerNowMs - anchorServerTimeMs)
```

For paused or idle playback, the expected position is simply `positionMs`.

### Clock estimation

Android sends its send time in a `ping`. When `pong` returns, it assumes roughly
half the round-trip happened before the server timestamp and half after it:

```text
midpoint = sentAt + (receivedAt - sentAt) / 2
clockOffset = serverTime - midpoint
```

Samples are smoothed (75% previous estimate, 25% new sample). Android estimates
server time as device time plus this offset, so slightly incorrect phone clocks
do not directly control playback.

### Scheduled controls

Starting a new video gets a 1.2-second lead time so clients can cue it. Pause,
seek, and similar controls get a 500 ms lead. Every phone schedules the action
against the same future server timestamp.

### Drift correction

While playing, Android asks the IFrame Player for its actual position every
four seconds. If absolute drift reaches 900 ms, it seeks to the expected room
position. A paused player that should be playing is also restarted.

Synchronization is best effort. Network delay, buffering, advertisements,
device performance, and regional video availability can cause temporary
differences. Ads cannot be synchronized or skipped by the application.

## 9. Queue, voting, and permissions

Queue order is deterministic:

1. Higher vote count first.
2. Earlier addition first when vote counts are equal.

The member adding a video automatically gives it one vote. Duplicate video IDs
and invalid IDs are rejected. A room accepts up to 100 queue items and 50
members.

| Feature | Host | Member |
|---|---:|---:|
| Search and suggest a video | Yes | Yes |
| Vote on queue items | Yes | Yes |
| Start a selected queue item | Yes | No |
| Play, pause, seek, or advance | Yes | No |
| Remove a queue item | Yes | No |
| Vote to skip | Server accepts it; UI uses direct host controls | Yes |

A skip succeeds at half of currently connected members, rounded up. Disconnecting
removes that member's skip vote.

If the host disconnects, the earliest still-connected member becomes host. If
the room creator never establishes a WebSocket, the first connected member is
elected, preventing an unusable room.

## 10. User-visible features

- Create or join a room with a guest display name.
- Public cross-network operation through an HTTPS/WSS backend.
- Connection status and automatic reconnection.
- Explicit consent before creating the YouTube player.
- Official visible YouTube playback.
- Server-side embeddable-video search.
- Shared ranked queue with per-user votes.
- Host play-now, remove, play, pause, seek, and next controls.
- Seek/progress display populated by the official player.
- Majority skip voting for non-host members.
- Connected-member count and automatic host handoff.
- User-facing API, room-action, and YouTube player errors.
- Dark Material 3 Compose interface.

## 11. Server memory and lifecycle

Rooms are stored in the Node process. Disconnected rooms are pruned after six
hours of inactivity to prevent unbounded memory growth. Render Free can stop or
restart a service, which immediately removes all rooms because there is no
database.

An explicit leave removes the membership and its queue/skip votes. An accidental
disconnect keeps the membership in memory so the same token can reconnect and
resume the session.

This is suitable for an MVP where rooms are temporary. Durable production
architecture should use:

- Redis for room snapshots, presence expiry, and multi-instance fan-out.
- PostgreSQL for accounts, moderation, and durable history.
- Signed short-lived access tokens for registered accounts.

## 12. Public deployment on Render

`render.yaml` defines the web service:

- root directory: `server`
- build: `npm ci && npm run build`
- start: `npm start`
- health check: `/health`
- required secret: `YOUTUBE_API_KEY`

After Render provides an HTTPS URL, put it in `android/local.properties` before
building the APK:

```properties
MUZIK_SERVER_URL=https://your-muzik-service.onrender.com
```

The URL becomes `BuildConfig.SERVER_URL`. HTTPS is converted to WSS for the
room connection. Changing servers therefore requires rebuilding the APK.

Render Free sleeps after an idle period and can take about a minute to wake.
Active WebSocket traffic keeps an in-use room awake, but Free instances are not
recommended for a production launch.

## 13. Security and privacy boundaries

- Public production traffic uses HTTPS and WSS.
- Member tokens are random, opaque, and sent in headers.
- The YouTube API key remains server-side.
- Request sizes, message sizes, room sizes, and request rates are bounded.
- Unexpected server exceptions are logged server-side but returned as a generic
  error rather than leaking internals.
- The server stores display names and temporary room state only in memory.
- The app does not request storage, microphone, contacts, or location access.

Before a public store release, add a privacy policy, terms, abuse moderation,
monitoring, durable storage where required, and a YouTube API compliance/legal
review. See `docs/youtube-compliance.md`.
