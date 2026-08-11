# Muzik

Muzik is an Android watch-party application for synchronized, social YouTube
playback. Participants create or join a room, search for videos, contribute to
a ranked queue, and follow a server-authoritative playback timeline.

Playback uses the official visible YouTube embedded player. Muzik does not
extract, download, cache, proxy, or provide background YouTube audio.

## How it works

The Node server owns room membership, presence, host permissions, queue order,
votes, history, pause/skip decisions, and playback state. Android clients use an
authenticated WebSocket connection to receive personalized room snapshots and
synchronize their visible YouTube player against the server clock.

The host can control playback, reorder songs with equal vote counts, choose a
specific song to play next, and remove queue items. Members can add and vote on
songs, request a pause, and vote to skip. The app also supports listening
history, contributor attribution, automatic playback of the first host-added
song, reconnect handling, host handoff, and Android picture-in-picture.

## Repository structure

- `android/` — Kotlin, Jetpack Compose, WebView player, and room client
- `server/` — TypeScript HTTP/WebSocket server and YouTube search adapter
- `docs/architecture.md` — protocol, synchronization, room rules, and data flow
- `docs/youtube-compliance.md` — YouTube integration and policy constraints
- `render.yaml` — Render server definition

## Requirements

- Node.js 18.18 or newer and npm
- Android Studio with Android SDK Platform 35
- Java 17 or 21 for Gradle
- Android 8.0/API 26 or newer
- A YouTube Data API v3 key when server-side search is required

## Server configuration

Create `server/.env` from `server/.env.example` and configure:

```dotenv
PORT=8080
YOUTUBE_API_KEY=your_server_side_youtube_api_key
ALLOWED_ORIGIN=*
```

| Variable | Required | Purpose |
|---|---:|---|
| `PORT` | No | HTTP/WebSocket port; defaults to `8080` |
| `YOUTUBE_API_KEY` | For search | Server-only YouTube Data API credential |
| `ALLOWED_ORIGIN` | No | Allowed HTTP origin |

Keep `.env`, API keys, and credentials out of source control. The YouTube key
must remain on the server and must never be compiled into the APK.

Common server commands, run from `server/`:

```bash
npm install
npm run dev
npm test
npm run typecheck
npm run build
```

The health endpoint is available at `/health`. Room creation and synchronized
playback work without a YouTube key, but search will report that it is not
configured.

## Android configuration

Open `android/` as the Android Studio project. Android Studio normally creates
`android/local.properties` with the local SDK path. The optional
`MUZIK_SERVER_URL` property selects the backend:

```properties
MUZIK_SERVER_URL=https://your-muzik-service.onrender.com
```

Useful development URLs:

- Android emulator to local server: `http://10.0.2.2:8080`
- Physical device on the same network: the computer's LAN address
- Release build: an HTTPS public server URL

`MUZIK_SERVER_URL` is compiled into the APK. Changing it requires rebuilding
and reinstalling the app. Release builds reject cleartext HTTP.

Build and test from `android/`:

```bash
./gradlew testDebugUnitTest assembleDebug
./gradlew lintRelease assembleRelease
```

On Windows, use `gradlew.bat`. Debug APKs are written under
`android/app/build/outputs/apk/debug/`. Public releases require a signing key
stored outside the repository.

## Protocol compatibility

Shared changes must remain backward-compatible with installed APKs. Snapshot
extensions are optional and Android ignores unknown fields. New client
capabilities, such as pause voting, are announced during WebSocket connection
so legacy clients are not counted in unsupported decisions.

For any shared Android/server change:

1. Test both sides.
2. Deploy the backward-compatible server first.
3. Verify server health and WebSocket behavior.
4. Distribute the new APK afterward.

The server must recognize messages such as `queue_reorder` before an APK that
sends them is distributed; otherwise that APK receives `Unknown message type`.

## Production constraints

- Render automatically deploys pushes to the configured production branch.
- Room state is currently held in memory, so any server restart or deployment
  clears all active rooms.
- Render must receive `YOUTUBE_API_KEY` through its secret environment settings.
- A server URL change requires a newly built APK.
- Signing credentials, local properties, and environment files must not be
  committed.
- Deploy the server before distributing an APK for shared protocol changes.

## YouTube compliance

Search uses the official YouTube Data API v3. Playback uses the official IFrame
Player inside a visible WebView with YouTube controls available. Muzik does not
download media, remove YouTube attribution, suppress required controls, bypass
advertisements, or implement background audio playback.

See [architecture.md](docs/architecture.md) for implementation details and
[youtube-compliance.md](docs/youtube-compliance.md) for the full compliance
constraints.
