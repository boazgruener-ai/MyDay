# Myday — Release Notes

Versioning follows `versionName`/`versionCode` in [app/build.gradle.kts](app/build.gradle.kts). This file is the source of truth for what changed between versions — update it (and bump the version) alongside any meaningful batch of work, not on every single commit.

---

## 0.4.0 — 2026-09-04

**Event colors — set or change a meeting's color by voice.**
- Previously a deliberate, documented non-feature: the system prompt explicitly told Claude not to bring up event color at all, so asking directly correctly got "I can't do that." Now genuinely built: `create_calendar_event` and `update_calendar_event` both accept a color, using Google Calendar's fixed 11-color palette (Lavender, Sage, Grape, Flamingo, Banana, Tangerine, Peacock, Graphite, Blueberry, Basil, Tomato) - there's no arbitrary/free-form color, so a casual request ("make it red," "turn it green") is mapped by Claude to the closest one of those 11. Verified live: "make it green" correctly resolved to Basil.
- Like every other real change, this is confirmed out loud before it happens - "for '[event]', do you want me to change its color to [name]?"

---

## 0.3.2 — 2026-09-03

**Long spoken answers cutting off mid-sentence, fixed.**
- Found via live testing: asking about the history of mankind stopped abruptly partway through. Root cause confirmed by testing the exact API call directly - every response was capped at 600 output tokens regardless of topic, and a genuinely broad question landed at 552 tokens in testing, just under that cap, meaning ordinary phrasing variance alone was enough to run over and get cut off mid-sentence.
- Raised the cap to 1536 tokens for real headroom.
- Also fixed the underlying UX, not just the ceiling: added guidance so a broad open-ended question (history of X, how Y works) gets a short spoken overview covering the main points, then an offer to go deeper - the way a person answers in conversation - rather than Claude attempting one uninterrupted essay-length monologue every time. Verified live: the same question now finishes cleanly in 360 tokens and ends with "Want me to go deeper into any of these phases?"

---

## 0.3.1 — 2026-09-03

**Two bugs found during live testing of 0.3.0/0.2.0, fixed.**
- **"Working on your request" showing up 10-15 seconds late.** The status was being set only after the Google access-token refresh completed, not before it - and that refresh is a live network round-trip to Google that can genuinely take that long. Moved the state update to fire immediately, before the token fetch, in both the voice-command and daily-briefing paths.
- **Web search answering "I can't find that" for things like league standings or streaming charts.** Verified live against the real API that `web_search` itself was working correctly - it was searching and finding real pages - but a search result's snippet is often just navigation text, not the actual table/list data on the page. Added Anthropic's `web_fetch` tool alongside `web_search`: Claude now follows up a promising search result by actually reading that page's full content, which reliably surfaces the real data (verified live: a Swiss Super League standings question now gets the actual current table, sourced by search-then-fetch in the same turn). `web_fetch` is still in beta and needed its own opt-in header on every API call.

---

## 0.3.0 — 2026-09-03

**Settings backup, after a real data-loss incident.**
- Root cause found and confirmed: on 2026-08-29, troubleshooting a stuck Force Stop, the app was uninstalled and reinstalled — which silently wipes all app data on Android, including every DataStore file. That erased the Promotion Allowlist and Junk Blacklist (set up earlier), with no warning, no way to recover them, and no easy way to even tell it had happened after the fact.
- **Automatic Google Drive backup**: the Allowlist, Blacklist, and Named Locations are now written to the signed-in Google account's private app-data storage (invisible in the user's normal Drive, readable only by this app) every time one is saved from the Settings menu — no button to remember to press. On startup, if any of these three come back empty locally, the app checks Drive for a backup before assuming there's genuinely nothing saved, and restores automatically if one exists.
- Needed a new Google permission scope (`drive.appdata`) alongside the existing Gmail/Calendar ones — existing sign-ins will be prompted to re-consent once, the same as when the Calendar scope changed previously.
- **Deliberately not backed up**: the Anthropic API key. It's live credential material, not app configuration — worth re-entering by hand (a 30-second task) rather than carrying a plaintext copy into a backup file, even a private one.
- Chose an explicit write-on-save/restore-on-empty design over relying on Android's built-in Auto Backup (already nominally enabled via `allowBackup="true"` in the manifest) — Auto Backup didn't actually restore this data after the Aug 29 uninstall/reinstall, and is known to be unreliable for a frequently-sideloaded debug build outside the Play Store, with no way to verify or trigger it on demand.

**Source code now under version control.**
- The project had no git history at all until now — meaning no undo for a bad edit beyond OneDrive's file sync, and no protection if this machine were lost. Initialized a local git repository with a proper `.gitignore` and pushed the current state as a baseline commit.
- Caught and excluded a real secret-leak risk in the process: `local.properties.bak` (a leftover backup copy containing the same OAuth client ID and Maps API key as `local.properties`) wasn't covered by the existing `.gitignore`, which only excluded the exact `local.properties` filename — a blind `git add -A` would have committed real credentials. Added `local.properties.bak` and `*.properties.bak` to `.gitignore` before the first commit.

---

## 0.2.0 — 2026-09-03

**Full GUI redesign — the app now looks and behaves like a real app, not a developer test screen.**
- Replaced the single ~1000-line scrolling column of buttons (permission grants, API key field, four list editors, five manual test buttons, all always visible) with a proper app shell: a hamburger drawer (top-left) for navigation, a bottom navigation bar for the few things actually used constantly, and a clean home screen.
- **Hamburger drawer**: Start/Stop Listening, **Authorization** (opens the new consolidated permissions screen), **Settings →** (Allowlist, Blacklist, Named Locations, API Key — each now a focused dialog instead of a permanently-visible inline editor), **Manual Runs →** (Test Claude API, Run Email Cleanup, Run Meeting Alert Check, Run Meeting Email Cleanup, Show Classification Log — each now shows its result in a dialog instead of cluttering the home screen).
- **Bottom navigation bar**: Start Myday/Stop Listening (icon toggles mic/mic-off), Stop Speaking, Speak Brief — the three things worth one tap, always visible, styled with icons + labels like a normal app's bottom nav (not a row of text buttons).
- **One consolidated Authorization screen**: every grant Myday needs (Mic+Location+Contacts, Notification Access, Do Not Disturb Access, Google Sign-in) listed together with live ✓/✗ status and a "Grant" action per row — the closest honest equivalent to "one button," since Android has no way to unify a runtime-permission dialog, a special-access Settings toggle, and an OAuth consent screen into a single native prompt.
- **New home screen**: app name, location, weather, a single "App State" line (now including a "Working on your request" state for the first time — previously the multi-second Claude+tool-use round trip showed no distinct status at all), a summary card (unread emails today, remaining meetings today, new WhatsApp messages today), and a live-updating **Recent Activity** feed of what Myday's AI has actually done (one line per voice turn and per background job run that did something notable — not shown for a routine "checked, nothing new" run, to keep it readable).
- **Deferred to a later pass, deliberately** (per explicit direction, not an oversight): the Profile screen (name/home address/preferred language) and any change to the existing inline Calendar/Gmail preview shown after Authorize — both still need their open behavioral questions decided first.

**Bugs found and fixed during live testing of the redesign above:**
- The hamburger drawer had no usable way to dismiss it — the panel took up nearly the full screen width, leaving only a sliver of tappable "outside" area, and the system back button/gesture wasn't wired to it either. Added a visible ✕ close button in a drawer header, and back now closes it too.
- Tapping a Manual Run closed the drawer and returned to the home screen with nothing visible for a beat before the result dialog appeared, reading as if the tap had done nothing. Fixed so the tap immediately produces the result dialog: for the common case (finishes in well under a second) the result just appears directly with no flash; only a run genuinely taking longer than ~1.5s reveals a "Working on it…" state first (with an Abort button that cancels the run, including the underlying background job for the three WorkManager-based runs, not just the wait).
- The Settings list editors (Allowlist/Blacklist/Named Locations) used one unconstrained-height text field with no scrollable container of its own — a swipe meant to scroll a long list instead landed on the field as a text-selection drag, which could wipe its contents outright with no way back. Rebuilt as a proper scrollable read-only list (tap it to start editing) plus a separately-scrollable text-edit view, both height-bounded so scrolling always scrolls; also added a real **Cancel** button (previously only Save existed) that discards any changes and leaves the saved list untouched.

---

## 0.1.3 — 2026-09-03

**General-knowledge fallback and live web search, for anything outside Calendar/Gmail/WhatsApp/Weather.**
- Myday was deliberately channel-scoped since day one — "if asked about anything else, say plainly that it's outside what you can help with." That's now relaxed in a controlled way: Claude can answer a genuinely general question directly from its own knowledge (translations, definitions, general facts — no cost, no tool) and, only when the question needs current or real-world information it doesn't have (a live score, today's business hours, "the nearest X," what's trending right now), it can use Anthropic's native web search tool.
- Deliberately **one unified tool-use call**, not a separate search-then-fallback pass: web search is just one more tool in the same list Claude already picks from for Calendar vs. Gmail vs. WhatsApp vs. Maps, with system-prompt guidance establishing priority order. Cheaper and simpler than a two-pass architecture, and consistent with how routing already works everywhere else in the app.
- Capped at 3 searches per request (`max_uses`) so one ambiguous question can't quietly rack up several billed searches, and biased toward Boaz's home area (Sonnenberg/Zurich, CH) for location-flavored questions — a static approximation, not the phone's live GPS position; a "nearest X" question while actually traveling would still search near home. Fixing that properly needs reverse-geocoding the device's live coordinates into a city name, which doesn't exist yet (`MapsRepository` only does forward geocoding today) — a real, separate addition if it turns out to matter in practice.
- No new API key, no new network client: Anthropic's web search tool runs entirely on Anthropic's own servers and resolves within the same conversational turn, so it needed no new dispatch case in `CommandExecutor` — just a data-model change (`MessagesRequest.tools` widened from a single-shape list to a heterogeneous one, the same trick `ChatMessage.content` already used) and the new prompt guidance.

---

## 0.1.2 — 2026-09-02

**Automatic payment/invoice/receipt filing.**
- Any inbox email whose subject contains "Payment," "Invoice," or "Receipt" (case-insensitive) is now automatically labeled `Myday/Payments` and archived out of the inbox — the same "file it out automatically" treatment Promotions and Junk already got.
- This is a deterministic subject-line keyword check, not an AI judgment call — checked ahead of the promotion allowlist and the LLM classification step, right after the junk blocklist. Financial mail shouldn't depend on a model's guess to end up somewhere findable.
- The manual "Run Email Cleanup Now" result and the periodic 6-hourly background run both now report a payments count alongside promotions/junk.
- This closes a gap between the original PRD's ideation ("subscriptions/invoices/receipts into a dedicated folder") and what had actually been built — only promotion/junk existed before this.
- **Bug found and fixed during live testing**: `GmailRepository.getOrCreateLabel` matched label names case-sensitively, so when Boaz already had a manually-created label differing only in capitalization from what this code expected, every cleanup run failed with an HTTP 409 from Gmail (which enforces label-name uniqueness case-insensitively) and silently retried forever via WorkManager's backoff, looking like a hang rather than an error. Fixed to match case-insensitively and reuse whatever's already there; this was a latent bug in the shared label lookup used by every category (Reviewed/Promotions/Junk/Payments), not something specific to this feature — it just took a user-created label in a different case to expose it.

---

## 0.1.1 — 2026-09-02

**Anthropic API key moved from build-time to per-device, so the app can be shared.**
- Previously the Claude API key was baked into the app at build time (`local.properties` → `BuildConfig`), meaning only Boaz's own key could ever be used, and it would have shipped inside the APK to anyone it was given to.
- Each installation now holds its own key, entered at runtime via a new **Anthropic API Key** section on the main screen, encrypted at rest using an Android-Keystore-backed key (via Tink) rather than stored as plain text.
- The **Start Wake-Word Listening** button is now gated on a key being present, with a clear "Add Anthropic API Key to Continue" state — mirrors the existing permission-gating pattern rather than failing confusingly mid-session.
- Verified live end-to-end on Boaz's own device: added a real key, ran a real Day Brief request, confirmed it worked.

**Sharing the app with other people is now a real, documented workflow, not just a theoretical option.**
- Google's OAuth consent screen (already in "Testing" mode) supports up to 100 named test users with no Google verification review — each person Boaz wants to try Myday is added by email address there before they can sign in.
- Build output renamed from Gradle's generic default (`app-debug.apk`) to `Myday-debug.apk`, with a clean `Myday.apk` copy placed in the project folder after each build for easy sharing.
- New plain-language install guide for a non-technical recipient — [How-to-Install-Myday.md](How-to-Install-Myday.md) (+ paired .docx) and a separately-designed published web version, covering sideloading, permissions, the Google "unverified app" warning, and creating a personal Anthropic key.
- First real friend onboarding in progress (APK shared via WeTransfer + the install guide by email) — not yet confirmed working on a device Claude has never touched.

**Docs caught up to match.**
- [PRD.md](PRD.md): Relevant Documents, Assumptions, and Roll-out strategy all updated to describe the friend-sharing mechanism; added an explicit "Play Store readiness — evaluated and set aside" writeup (the CASA security-assessment cost for Gmail's restricted scope, WhatsApp notification-listener review risk, still-single-tenant assumptions, and the lack of a backend to broker API keys publicly).
- The published "Myday Architecture" page gained an **API Key Vault** component and an **Android Keystore** glossary entry.
- [V1-Setup-Guide.md](V1-Setup-Guide.md) corrected to the current Google Cloud Console navigation path (**APIs & Services → OAuth consent screen → Audience**) and updated to reflect that the Anthropic key step is now per-device, not a one-time hand-off.

---

## 0.1.0 — baseline

Everything built before per-device key support — the app in the state it was in for Boaz's own daily personal use only.

- **Voice pipeline**: always-on on-device wake-word detection for "Myday"; cloud-based speech-to-text (switched from Android's built-in recognizer after live testing showed it unreliably cut off longer requests) with accent/name-recognition tuning; chunked text-to-speech with a working "Stop Speaking" interrupt.
- **Daily briefing**: first wake of the day *offers* a spoken summary (weather, today's calendar, important email from the last 24h) rather than auto-playing it; time-aware (already-passed meetings summarized as a count, not read individually); available on demand afterward too.
- **Calendar**: read today's events; create, update, delete events; RSVP to invites; invite attendees (restricted to known phone contacts, checked in code, not just prompted).
- **Gmail**: read and summarize recent mail; automatic classification into promotion/junk/keep with allow/block lists; send and reply to email, including translating a dictated English reply into the original message's language.
- **WhatsApp**: read personal DMs and any group message mentioning "Boaz" by name, captured live from WhatsApp's own notifications (no history from before the feature was enabled); reply into an existing conversation by voice via the same notification quick-reply mechanism WhatsApp itself uses — both confirmed working live.
- **Background hygiene**: auto-dismiss notifications for meetings that already ended; periodic inbox cleanup; "leave now" travel-time alerts ahead of physical meetings.
- **Contact resolution**: fuzzy name matching against phone contacts, corrected to surface every email address on a contact (not silently pick one) and ask when ambiguous rather than guess.
