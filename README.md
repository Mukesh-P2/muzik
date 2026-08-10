# Muzik

Muzik is an Android watch-party application for synchronized, social YouTube
playback. Participants create or join a room, start the visible YouTube player,
suggest videos, vote on the shared queue, and follow a server-authoritative
playback timeline.

The project uses the official YouTube embedded player. It does not extract,
download, cache, proxy, or play YouTube audio in the background.

## Repository layout

- `android/` — native Kotlin and Jetpack Compose Android application
- `server/` — TypeScript HTTP and WebSocket room server
- `docs/` — architecture, protocol, and YouTube compliance notes
- `render.yaml` — optional Render deployment blueprint
- `TODO.md` — build, release, and future-work checklist

## Features

- Create or join a room with a display name and room code.
- Search for embeddable YouTube videos and add them to a shared queue.
- Rank queue items with member votes.
- Synchronize visible YouTube playback across connected Android devices.
- Give the host play, pause, seek, next, play-now, and remove controls.
- Allow members to vote to skip the current video.
- Share room-code invitations through Android's share sheet and open invite links in the app.
- Let each participant request a preferred YouTube playback quality for their own device.
- Reconnect automatically and transfer host permission after disconnection.

## Prerequisites

Install the following before starting:

- [Node.js](https://nodejs.org/) 18.18 or newer and npm
- [Android Studio](https://developer.android.com/studio)
- Android SDK Platform 35 and its SDK Build-Tools
- Java 17; Android Studio's bundled JDK is recommended
- An Android emulator or a physical device running Android 8.0 (API 26) or newer
- A Google Cloud project and YouTube Data API key if search is required

Android Studio may ask you to install SDK 35 and accept Google's SDK licenses.
The project owner must review and accept those terms.

## 1. Configure the backend environment

Open a terminal in the repository root and enter the server directory:

```bash
cd server
npm install
```

Create the local environment file from the included example.

macOS, Linux, Git Bash, or WSL:

```bash
cp .env.example .env
```

Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

Open `server/.env` in a text editor and configure it:

```dotenv
PORT=8080
YOUTUBE_API_KEY=your_server_side_youtube_api_key
ALLOWED_ORIGIN=*
```

| Variable | Required | Purpose |
|---|---:|---|
| `PORT` | No | Backend port; defaults to `8080` |
| `YOUTUBE_API_KEY` | For search | Server-side YouTube Data API v3 credential |
| `ALLOWED_ORIGIN` | No | Allowed HTTP origin; `*` is convenient for development |

`server/.env` is ignored by Git. Keep the YouTube key in this file or in the
deployment provider's secret settings. Never place it in Android source code or
`android/local.properties`, because values compiled into an APK can be read by
users.

The server reads `.env` from its current working directory, so run server npm
commands from the `server/` directory.

### Create a YouTube API key

Search is optional, but it requires a key:

1. Open [Google Cloud Console](https://console.cloud.google.com/).
2. Create or select a project.
3. Open **APIs & Services → Library**.
4. Enable **YouTube Data API v3**.
5. Open **APIs & Services → Credentials** and create an API key.
6. Restrict the key to **YouTube Data API v3** and, where practical, restrict
   where the deployed backend can use it.
7. Paste the key after `YOUTUBE_API_KEY=` in `server/.env`.

YouTube search consumes API quota. Playback itself uses the IFrame Player and
does not use this Data API key.

## 2. Start and verify the backend

From `server/`, start the development server:

```bash
npm run dev
```

Keep this terminal running. A successful startup prints:

```text
Muzik server listening on http://0.0.0.0:8080
```

In another terminal, verify the health endpoint:

```bash
curl http://localhost:8080/health
```

It should return JSON containing `"ok":true`. You can also run the backend
checks before building Android:

```bash
cd server
npm run typecheck
npm test
```

## 3. Open and configure the Android project

1. Start Android Studio.
2. Select **Open** and choose the repository's `android/` directory, not the
   repository root.
3. Trust the project if prompted.
4. Allow the Gradle sync to finish.
5. Install Android SDK Platform 35 if Android Studio requests it. It can also be
   installed through **Tools → SDK Manager**.
6. If Gradle reports a Java error, open **Settings → Build, Execution,
   Deployment → Build Tools → Gradle** and select JDK 17 or 21 as the Gradle
   JDK. The Android source and bytecode still target Java 17.

Android Studio normally creates `android/local.properties` with the Android SDK
location. The file is machine-specific and ignored by Git. A Windows example is:

```properties
sdk.dir=C\:\\Users\\your-name\\AppData\\Local\\Android\\Sdk
```

Do not copy another developer's `sdk.dir`; let Android Studio generate the
correct path for the current computer.

## 4. Configure the Android backend URL

The correct URL depends on where the app runs.

### Android Studio emulator

No URL change is needed for the standard Android emulator. The default is:

```text
http://10.0.2.2:8080
```

Inside the emulator, `10.0.2.2` points to the development computer. Do not use
`localhost`; inside Android it refers to the Android device itself.

### Physical Android device on the same network

Find the development computer's LAN IPv4 address, such as `192.168.1.20`. Add
the following line to the existing `android/local.properties` file without
removing its `sdk.dir` line:

```properties
MUZIK_SERVER_URL=http://192.168.1.20:8080
```

The phone and computer must be on the same network. Allow inbound TCP port 8080
through the computer's firewall if necessary. Guest Wi-Fi networks sometimes
block communication between devices.

### Public server

For phones on different networks, use the deployed HTTPS address:

```properties
MUZIK_SERVER_URL=https://your-muzik-service.onrender.com
```

The URL is compiled into the app. After changing it, rebuild and reinstall the
APK. Release builds require HTTPS because cleartext HTTP is disabled for the
release build type.

After editing `local.properties`, click **Sync Project with Gradle Files** in
Android Studio.

## 5. Create an emulator or connect a phone

For an emulator:

1. Open **Tools → Device Manager**.
2. Select **Create Virtual Device**.
3. Choose a phone profile and an Android system image with API 26 or newer.
4. Finish the wizard and start the virtual device.

For a physical phone:

1. Enable **Developer options** and **USB debugging** on the phone.
2. Connect it by USB and approve the debugging prompt.
3. Select the phone from Android Studio's device menu.

## 6. Run and test the app

1. Confirm that `npm run dev` is still running in `server/`.
2. Select the `app` run configuration and the desired Android device.
3. Click **Run** or press `Shift+F10`.
4. Enter a display name and create a room.
5. On a second emulator or phone, enter another display name and join using the
   displayed room code.
6. Tap **Start YouTube player** on each device.
7. Search for a video, add it to the queue, and test voting and host controls.

Search displays “YouTube search is not configured” if `YOUTUBE_API_KEY` is
empty, but room creation and synchronized playback can still be tested.

## 7. Compile a debug APK in Android Studio

1. Wait for Gradle sync to finish without errors.
2. Select the `debug` build variant if Android Studio shows the Build Variants
   panel.
3. Choose **Build → Build Bundle(s) / APK(s) → Build APK(s)**. In some Android
   Studio versions this is under **Build → Generate App Bundles or APKs**.
4. Wait for the **APK(s) generated successfully** notification.
5. Click **Locate**, or open:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed automatically with the Android debug key and is
suitable for development and testing, not store distribution.

### Command-line debug build

Windows PowerShell:

```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug
```

macOS or Linux with Java 17 and the Android SDK configured:

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

## 8. Deploy the backend publicly with Render

This repository includes `render.yaml`:

1. Push the repository to a Git provider supported by Render.
2. In Render, create a new **Blueprint** and select the repository.
3. Allow Render to create the `muzik-server` web service.
4. Add `YOUTUBE_API_KEY` as a secret environment variable.
5. Deploy and wait for the service to become available.
6. Open `https://your-service.onrender.com/health` and confirm that it returns
   `"ok":true`.
7. Put that HTTPS URL in `android/local.properties` as
   `MUZIK_SERVER_URL`, then rebuild the Android app.

Render's free service can sleep while idle and may take about a minute to wake.
Rooms are stored only in server memory, so a restart or redeployment removes all
active rooms.

### Feature development and deployment workflow

Classify each new feature before implementation:

- Android-only UI or local behavior requires a new APK, but no server deploy.
- Server-only behavior can be deployed without a new APK if the existing HTTP
  and WebSocket contracts remain compatible.
- Features that change an API, WebSocket message, or shared data model require
  coordinated Android and server changes. Keep the server backward-compatible
  with installed app versions, deploy the server first, verify it, and then
  distribute the updated APK.

Render automatically redeploys the server after changes are pushed to the
connected deployment branch. Test changes locally before pushing that branch.
Every server deployment or restart currently removes active rooms because room
state is held in memory. Changing `MUZIK_SERVER_URL` also requires rebuilding
the APK because the URL is compiled into the Android app.

## 9. Create a signed release build

Complete the public-server setup before building a release. The repository does
not contain signing credentials.

1. Set an HTTPS `MUZIK_SERVER_URL` in `android/local.properties`.
2. Update `versionCode` and `versionName` in
   `android/app/build.gradle.kts` when preparing a new release.
3. In Android Studio, choose **Build → Generate Signed Bundle / APK**.
4. Select **Android App Bundle** for Google Play or **APK** for direct
   distribution.
5. Select an existing keystore or choose **Create new**.
6. Store the keystore and passwords securely outside the repository. Losing the
   signing key can prevent future app updates.
7. Select the `release` build variant and finish the wizard.
8. Test the signed artifact on physical devices before distribution.

Typical output locations are:

```text
android/app/build/outputs/bundle/release/app-release.aab
android/app/build/outputs/apk/release/app-release.apk
```

Before a public store release, also add final icons and store assets, a privacy
policy, terms, abuse reporting, monitoring, and a YouTube API compliance review.

## Troubleshooting

### Use a Java 17-compatible Gradle JDK

This project targets Java 17 and is verified with Gradle JDK 17 and 21. Android
Studio's JBR 25 is too new for the current Gradle/Kotlin toolchain and can fail
with `IllegalArgumentException: 25.0.2`. Select a JDK 17/21 installation in the
Gradle settings or point command-line `JAVA_HOME` to one.

### Android SDK location not found

Open `android/` in Android Studio and install SDK 35 through SDK Manager. Let
Android Studio regenerate `android/local.properties` with the correct `sdk.dir`.

### The app cannot connect from the emulator

Make sure the backend is running on port 8080 and use
`http://10.0.2.2:8080`, not `localhost`.

### The app cannot connect from a physical phone

Use the computer's LAN address, keep both devices on the same network, and check
the computer firewall. Confirm from another device that the backend computer is
reachable on port 8080.

### YouTube search is unavailable

Confirm that `YOUTUBE_API_KEY` is present in `server/.env`, YouTube Data API v3
is enabled, the key restrictions permit the backend request, and the API quota
has not been exhausted. Restart the backend after editing `.env`.

### The public app cannot connect

Confirm that the Render health endpoint works, `MUZIK_SERVER_URL` uses `https`,
and the APK was rebuilt after changing the URL.

## Additional documentation

- [Architecture and feature guide](docs/architecture.md)
- [YouTube compliance constraints](docs/youtube-compliance.md)
- [Project TODO list](TODO.md)
