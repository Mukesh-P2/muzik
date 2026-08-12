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
Rooms also include bounded chat with host message deletion and member muting,
YouTube playlist import, and a host action to clear the queued items.

## Repository structure

- `android/` — Kotlin, Jetpack Compose, WebView player, and room client
- `server/` — TypeScript HTTP/WebSocket server and YouTube search adapter
- `docs/architecture.md` — protocol, synchronization, room rules, and data flow
- `docs/youtube-compliance.md` — YouTube integration and policy constraints
- `render.yaml` — Render server definition

## Requirements

- Node.js 18.19 or newer and npm
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
REDIS_URL=rediss://default:password@host:6379
```

| Variable | Required | Purpose |
|---|---:|---|
| `PORT` | No | HTTP/WebSocket port; defaults to `8080` |
| `YOUTUBE_API_KEY` | For search | Server-only YouTube Data API credential |
| `ALLOWED_ORIGIN` | No | Allowed HTTP origin |
| `REDIS_URL` | No | Experimental single-process cold-start recovery; memory-only when omitted |

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

The health endpoint is available at `/health`, and Prometheus-compatible runtime
gauges are available at `/metrics`. Room creation and synchronized playback work
without a YouTube key, but search and playlist import will report that they are
not configured.

## Android configuration

Open `android/` as the Android Studio project. Android Studio normally creates
`android/local.properties` with the local SDK path. The `MUZIK_SERVER_URL`
property selects the backend:

```properties
MUZIK_SERVER_URL=https://your-muzik-service.onrender.com
```

Useful development URLs:

- Android emulator to local server: `http://10.0.2.2:8080`
- Physical device on the same network: the computer's LAN address
- Release build: an explicit absolute HTTPS public server URL

Debug builds fall back to `http://10.0.2.2:8080` when the property is omitted.
Release builds have no fallback: `MUZIK_SERVER_URL` must be supplied explicitly
and must be an absolute `https://` URL. It may be set in `local.properties`,
passed as `-PMUZIK_SERVER_URL=https://...`, or exposed to Gradle as
`ORG_GRADLE_PROJECT_MUZIK_SERVER_URL`. The release build runs
`validateReleaseServerUrl` before compiling or packaging.

`MUZIK_SERVER_URL` is compiled into the APK. Changing it requires rebuilding
and reinstalling the app.

Build and test from `android/`:

```bash
./gradlew testDebugUnitTest assembleDebug
./gradlew lintRelease assembleRelease
```

On Windows, use `gradlew.bat`. Debug APKs are written under
`android/app/build/outputs/apk/debug/`. Public releases require a signing key
stored outside the repository.

### Release signing

Release signing is optional for local and CI verification builds. Without
credentials, `assembleRelease` produces an unsigned APK. A distributable build
requires all four of these values, supplied as environment variables or in the
user-level Gradle properties file (`~/.gradle/gradle.properties`), never in this
repository:

```properties
MUZIK_RELEASE_STORE_FILE=/absolute/path/outside/the/repository/muzik-release.jks
MUZIK_RELEASE_STORE_PASSWORD=replace-with-secret
MUZIK_RELEASE_KEY_ALIAS=muzik
MUZIK_RELEASE_KEY_PASSWORD=replace-with-secret
```

Gradle fails early if only some signing values are present. Protect and back up
the keystore and passwords separately; losing the signing key can prevent
updates to an installed production application.

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

The server must recognize messages such as `queue_reorder` and `queue_add_many`
before an APK that sends them is distributed; otherwise that APK receives
`Unknown message type`.

## Production constraints

- Render automatically deploys pushes to the configured production branch.
- Production room state is currently memory-only, so a restart or deployment
  clears all active rooms. The optional Redis adapter can recover a single
  stopped process on a cold start, but it is not safe for rolling deployments
  or multiple instances until coordinated ownership/revisions and pub/sub are
  implemented; do not enable it in that topology.
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
constraints. Release drafts for the [privacy policy](docs/privacy-policy.md),
[terms of service](docs/terms-of-service.md), and
[community and moderation rules](docs/community-and-moderation.md) must have
their marked placeholders resolved and be reviewed and published before launch.
