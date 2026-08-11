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
- [x] Test search, queue voting, host controls, skip voting, and host handoff.
- [ ] Repeat the full room flow on two physical devices after public deployment.

## Before a public release

- [x] Deploy the backend and verify its `/health` endpoint.
- [x] Set `MUZIK_SERVER_URL` to the public HTTPS backend before building the APK.
- [ ] Restrict and monitor the YouTube Data API key and quota.
- [ ] Configure Android release signing and protect the signing credentials.
  Signing integration is ready; the external keystore and four secret values
  still need to be created and protected.
- [ ] Confirm the production version name and version code.
- [ ] Test on physical devices, different Android versions, and different networks.
- [ ] Add a privacy policy, terms, abuse reporting, and moderation rules.
  Release drafts exist under `docs/`; operator identity, contact details,
  publishing, and legal review remain required.
- [ ] Complete a YouTube API Services compliance review.
- [ ] Add crash reporting, server monitoring, and alerting.
  `/health` exposes uptime and live room/connection gauges, and `/metrics` is
  Prometheus-compatible. External crash/uptime providers and alert contacts
  still need to be configured.

## Future improvements

- [x] Add optional Redis-backed room persistence and safe presence restoration.
  Production remains memory-only until a Redis service is provisioned and its
  `REDIS_URL` is configured in Render.
- [ ] Add accounts and durable data with PostgreSQL.
  Resume only after choosing Google sign-in, email magic links, or intentionally
  remaining guest-only; this decision changes APIs, stored personal data, and
  the privacy policy.
- [x] Restore room membership after the Android process restarts.
- [x] Add room chat and moderation tools.
- [x] Add playlist import and queue-management improvements.
- [x] Add broader Android UI, integration, and server API test coverage.
  Compose instrumentation tests compile in CI; executing them remains part of
  physical/emulator release testing.
- [x] Add CI checks for backend tests and Android builds.

## Later product and branding decisions

- [ ] Choose the final production application ID.
- [ ] Choose the final visual identity and launcher icon.
- [ ] Capture final screenshots and finish branded store-listing assets.
  Store copy and a screenshot plan already exist under `docs/`.
