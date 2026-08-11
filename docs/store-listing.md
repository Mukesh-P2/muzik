# Muzik store listing draft

> **Release blocker:** Confirm the final package ID and operator contact, replace
> every bracketed placeholder, capture final device screenshots, and review all
> declarations against the exact production build before submitting.

## Listing copy

**App name:** Muzik

**Short description:**

Watch YouTube together with shared queues, voting, and synced playback.

**Full description:**

Muzik turns YouTube viewing into a shared room experience.

Create a room or join friends with a room code. Everyone can search for
embeddable YouTube videos, add suggestions, and vote on the shared queue. The
host controls playback while Muzik keeps each participant aligned to the same
server-authoritative timeline.

Features include:

- Shared rooms across different networks
- Official, visible YouTube playback
- YouTube video search and a ranked group queue
- Queue, skip, and pause voting
- Host playback and queue controls
- Listening history and contributor attribution
- Room chat with host moderation
- YouTube playlist import and host queue management
- Automatic reconnection and host handoff
- Fullscreen and picture-in-picture viewing

Playback synchronization is best effort and can vary because of buffering,
advertisements, device performance, network conditions, and regional video
availability. Muzik does not download, proxy, cache, or provide background
audio from YouTube.

By using Muzik, users agree to the Muzik Terms of Service and the YouTube Terms
of Service.

## Release notes for 0.2.0

- Create and join shared YouTube watch rooms
- Search, contribute, vote, and manage a synchronized queue
- Host play, pause, seek, next, play-now, reorder, and removal controls
- Group pause and skip decisions
- Listening history, attribution, reconnection, and host handoff
- Fullscreen and picture-in-picture playback improvements

## Required listing fields

- App category: Entertainment
- Support email: `[SUPPORT EMAIL]`
- Privacy-policy URL: `[PUBLISHED PRIVACY POLICY URL]`
- Terms URL: `[PUBLISHED TERMS URL]`
- Website: `[OPTIONAL WEBSITE URL]`
- Final package/application ID: `[FINAL APPLICATION ID]`
- Countries/regions: `[DISTRIBUTION DECISION]`
- Pricing: `[FREE OR PAID DECISION]`

## Screenshot plan

Capture the production-themed build without real names, tokens, private room
codes, or copyrighted thumbnail choices that create unnecessary listing risk:

1. Create/join screen.
2. Active room with visible official YouTube player.
3. Search results with YouTube attribution.
4. Ranked shared queue and voting controls.
5. Members/history or host controls.

## Preliminary Play data-safety inventory

This is an engineering inventory, not a completed Play Console declaration.
Recheck it after adding any analytics, crash reporting, accounts, persistence,
or advertising.

| Data or capability | Current behavior |
|---|---|
| Display name | Sent to Muzik server and shown to room members |
| Search queries | Sent to Muzik server and YouTube Data API |
| Room interactions | Sent to Muzik server and held in memory |
| Member token | Stored in private backup-excluded app preferences and sent to Muzik server for room authentication; not user-visible |
| IP address | Temporarily used server-side for rate limiting; hosting provider may process logs |
| YouTube/player data | Processed directly by Google/YouTube through the official embedded player |
| Precise location | Not requested |
| Contacts, photos, files, microphone | Not requested |
| Advertising SDK | None in the Muzik Android code |
| Analytics/crash-reporting SDK | None currently |
| Account creation | None currently |
| Data encrypted in transit | HTTPS/WSS in the production build |
| User deletion request | No durable account/database; leave removes active membership, room expires in memory |
