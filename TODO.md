# Muzik TODO

## Before the next debug build

- [x] Open `android/` in Android Studio and install Android SDK 35.
- [x] Confirm Android source and bytecode target Java 17.
- [x] Let Android Studio create `android/local.properties` with the local SDK path.
- [x] Create `server/.env` from `server/.env.example`.
- [x] Add `YOUTUBE_API_KEY` to `server/.env` if YouTube search is required.
- [x] Start the backend with `npm run dev` from `server/`.
- [x] Build and run the Android debug app on an emulator or device.
- [x] Test two-client create, join, leave, reconnect, queue voting, playback, and
  host handoff through the automated HTTP/WebSocket integration test.
- [ ] Test search, queue voting, host controls, skip voting, and host handoff.
- [ ] Repeat the full room flow on two physical devices after public deployment.

## Before a public release

- [ ] Deploy the backend and verify its `/health` endpoint.
- [ ] Set `MUZIK_SERVER_URL` to the public HTTPS backend before building the APK.
- [ ] Restrict and monitor the YouTube Data API key and quota.
- [ ] Configure Android release signing and protect the signing credentials.
- [ ] Choose the production application ID, version name, and version code.
- [ ] Add final launcher icons, screenshots, and store listing assets.
- [ ] Test on physical devices, different Android versions, and different networks.
- [ ] Add a privacy policy, terms, abuse reporting, and moderation rules.
- [ ] Complete a YouTube API Services compliance review.
- [ ] Add crash reporting, server monitoring, and alerting.

## Future improvements

- [ ] Persist rooms and presence with Redis.
- [ ] Add accounts and durable data with PostgreSQL.
- [ ] Restore room membership after the Android process restarts.
- [ ] Add room chat and moderation tools.
- [ ] Add playlist import and queue-management improvements.
- [ ] Add broader Android UI, integration, and server API test coverage.
- [x] Add CI checks for backend tests and Android builds.
