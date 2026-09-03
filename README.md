# Myday

A personal, always-listening Android assistant — woken by saying "Myday." Built solo in Kotlin/Jetpack Compose with Claude (Anthropic) as an AI build partner.

On first wake of the day, Myday gives a full spoken morning briefing — weather (highs/lows, rain chance and timing, an umbrella call), today's Google Calendar schedule, and anything important from the last 24 hours of email — then handles whatever's asked afterward in plain English, not a fixed menu of recognized commands. In the background it keeps the inbox tidy (filing promotions, junk, and payment receipts into their own labels) and clears notifications for meetings that have already ended.

## Features

- **Voice pipeline** — on-device wake-word detection ("Myday"), cloud speech-to-text, chunked text-to-speech with a working interrupt ("Stop Speaking").
- **Daily briefing** — weather, calendar, and important email, offered once per day and available on demand afterward.
- **Calendar** — read, create, update, delete events; RSVP to invites; invite attendees (checked against known contacts).
- **Gmail** — read and summarize recent mail; automatic classification into Promotions/Junk/Payments/Keep via a mix of deterministic allow/block lists and an LLM judgment call; send and reply, including translating a dictated reply into the original message's language.
- **WhatsApp** — reads personal DMs and any group message mentioning the user by name, captured live from WhatsApp's own notifications; replies into an existing conversation by voice via the same quick-reply mechanism WhatsApp itself uses.
- **Background hygiene** — periodic inbox cleanup, "leave now" travel-time alerts ahead of physical meetings, auto-dismissal of stale meeting notifications.
- **Settings backup** — the Promotion Allowlist, Junk Blacklist, and Named Locations back up automatically to the signed-in Google account's private Drive storage on every save, and restore automatically if ever found empty — a real incident (an uninstall during debugging silently wiped this data) is what prompted it.
- **Everything outside those channels** — a general-knowledge question or a translation is answered directly from Claude's own knowledge; anything needing current real-world information falls back to a capped, cost-aware live web search — rather than being refused outright.

## Tech stack

- **UI**: Jetpack Compose, Material 3 (hamburger drawer + bottom nav app shell)
- **Language model**: Anthropic Claude, via direct Messages API calls (tool use for every channel: Calendar, Gmail, WhatsApp, Maps, web search)
- **Local storage**: AndroidX DataStore (Preferences) for settings/state, Room for the WhatsApp message store
- **Networking**: Retrofit + OkHttp against Gmail, Calendar, Open-Meteo, Google Drive, and Distance Matrix/Geocoding REST APIs
- **Background work**: WorkManager for periodic inbox cleanup and meeting-travel checks
- **Auth**: Google Identity Services (`AuthorizationClient`) for Gmail/Calendar/Drive scopes
- **Secrets at rest**: Android Keystore-backed encryption (via Tink) for the per-device Anthropic API key
- **Wake word**: an on-device ONNX model (openWakeWord), always listening for "Myday"

## Project docs

- [PRD.md](PRD.md) — full product requirements: problem, scope, architecture, phased build plan.
- [RELEASE_NOTES.md](RELEASE_NOTES.md) — versioned changelog; current version lives in `app/build.gradle.kts`.
- [V1-Setup-Guide.md](V1-Setup-Guide.md) — developer-facing environment and API-key setup.
- [How-to-Install-Myday.md](How-to-Install-Myday.md) — plain-language sideloading guide for a non-technical friend/family install.

## Building it yourself

This repo intentionally ships no secrets. Create `local.properties` in the project root (already git-ignored) with:

```properties
sdk.dir=<path to your Android SDK>
GOOGLE_OAUTH_CLIENT_ID=<your Android OAuth client ID>
GOOGLE_MAPS_API_KEY=<your Maps Platform key, optional — only travel-time alerts need it>
HOME_CITY=<your city>
HOME_LATITUDE=<your latitude>
HOME_LONGITUDE=<your longitude>
HOME_REGION=<your region/canton/state>
HOME_COUNTRY=<your two-letter country code>
HOME_TIMEZONE=<your IANA timezone, e.g. Europe/Zurich>
HOME_DISPLAY=<what the home screen should show, e.g. "Zurich">
```

The Anthropic API key isn't build-time configuration — it's entered once at runtime, per device, from the app's Settings menu, and encrypted at rest. See [V1-Setup-Guide.md](V1-Setup-Guide.md) for the full walkthrough (Google Cloud OAuth consent screen, enabling the right APIs, generating a Maps key).

```bash
./gradlew assembleDebug
```

## Status

Personal daily-use project, actively developed. See [RELEASE_NOTES.md](RELEASE_NOTES.md) for what's shipped and what's next.

---

**Author:** Boaz Gruener · **AI build partner:** Claude (Anthropic)
