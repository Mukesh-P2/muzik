# YouTube integration constraints

Muzik uses the official YouTube IFrame Player API and YouTube Data API. The
following are non-negotiable product constraints:

- The embedded player is visible while content plays and is at least 200×200.
- The app does not cover, restyle, replace, or obscure the player or its ads.
- Playback is not provided from a hidden or background player.
- The app does not isolate audio, download media, cache media, or use an
  undocumented YouTube Music/InnerTube endpoint.
- Users explicitly consent to starting playback when joining a session.
- Videos that disable embedding or are unavailable to a participant fail
  visibly; the app does not bypass the restriction.
- Search results retain YouTube attribution and are not presented as Muzik's
  own catalog.
- The Android privacy policy and terms must disclose use of YouTube API
  Services and link to the YouTube Terms of Service before public release.

Official references:

- https://developers.google.com/youtube/terms/developer-policies
- https://developers.google.com/youtube/terms/required-minimum-functionality
- https://developers.google.com/youtube/iframe_api_reference

This file is an engineering guardrail, not legal advice. A public launch should
include a YouTube API compliance review and legal review.
