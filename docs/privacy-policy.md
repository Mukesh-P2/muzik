# Muzik privacy policy (release draft)

> **Release blocker:** Replace `[SUPPORT EMAIL]`, publish this policy at a stable
> public URL, and obtain an appropriate legal review before distributing Muzik.

Effective date: August 11, 2026

Muzik is an Android application for creating shared rooms, searching for
YouTube videos, voting on a queue, and synchronizing playback. This policy
describes the information processed when you use Muzik.

## Information Muzik processes

- **Room profile:** the display name you choose, room code, randomly generated
  member identifier, and authentication token. The current room credential is
  saved in private, backup-excluded Android app storage so the app can reconnect
  after its process restarts.
- **Room activity:** presence, queued videos, votes, playback controls, song
  attribution, room history, chat messages, and host chat-moderation actions.
- **Search activity:** the YouTube search terms you submit and the resulting
  video metadata.
- **Network information:** the server temporarily uses the requesting IP
  address for one-minute rate limits. The hosting provider may independently
  process standard request and diagnostic information under its own terms.
- **YouTube playback information:** the official YouTube player communicates
  directly with YouTube. YouTube may process device, network, cookie, account,
  advertising, and playback information under Google's policies.

Muzik currently has no user accounts, advertising SDK, analytics SDK, crash
reporting SDK, contact access, location access, microphone access, or media-file
access.

## How information is used

Information is used only to operate shared rooms, authenticate room members,
synchronize playback, return YouTube search results, enforce room rules, limit
abuse, diagnose service errors, and protect service availability.

## Storage and retention

The Android app persists the current room credential until you explicitly leave,
clear app data, or uninstall. Android cloud backup and device-transfer rules
exclude this private preference. Room snapshots and search results remain in app
memory. The Muzik server keeps room state in memory; it has no production room
database. Rooms without connected members are eligible for deletion after six
hours of inactivity, and a server restart deletes all rooms. Search results are
cached in memory for up to ten minutes. One-minute rate-limit records are
periodically removed.

Room chat is limited to the latest 100 messages and exists only inside the
in-memory room. Hosts can delete messages and mute participants from sending new
chat messages. Chat attribution can remain until the message or room is deleted.

Leaving a room removes the member and their active votes. Some attribution
already attached to queued or played items can remain until the room itself is
deleted.

## Sharing and service providers

Muzik sends search requests to the official YouTube Data API through the Muzik
server. Playback uses the official visible YouTube embedded player and streams
directly from YouTube; Muzik does not download, proxy, or store video media.
The backend is hosted by Render. Information may be disclosed when required by
law or necessary to protect users, the service, and others.

Relevant third-party policies include:

- [Google Privacy Policy](https://policies.google.com/privacy)
- [YouTube Terms of Service](https://www.youtube.com/t/terms)
- [Render Privacy Policy](https://render.com/privacy)

## Security and choices

Production app traffic uses HTTPS and secure WebSockets. Room codes are not
passwords; share them only with intended participants. You can stop processing
by leaving a room and uninstalling the app. No internet service can guarantee
absolute security.

## Children

Muzik is not directed to children under 13, or a higher minimum age where local
law requires it. Do not use Muzik if you cannot lawfully agree to its terms and
the YouTube Terms of Service.

## Changes and contact

Material policy changes should be published at the same public URL with a new
effective date. For privacy questions, requests, or complaints, contact
`[SUPPORT EMAIL]`.
