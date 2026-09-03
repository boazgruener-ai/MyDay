# Myday V1 — Environment, Tools & API Keys Setup Guide

This is the setup checklist for V1 only (wake word + read-only Google integration + inbox cleanup), per [PRD.md](PRD.md). Nothing here writes app code yet — this just gets every account, key, and tool in place so scaffolding can start without stopping to go fetch a credential mid-build.

## What I need from you, in total
By the end of this guide, I'll need these things from you to start building:
1. An **Anthropic API key** (from console.anthropic.com)
2. A **Google Cloud OAuth Client ID** (from console.cloud.google.com) — mostly set up now, finished with one extra value once the Android project exists
3. Your OK on a proposed **app package name**: `ch.boazgruener.myday` (this is just an internal identifier, not user-facing — happy to change it if you'd rather use something else)
4. *(Added later, for the "time to leave for your meeting" feature)* A **Google Maps Platform API key** — see Step 8 below. Everything else in the app works without this; it only gates the travel-time alerts.

*(Originally there was a fourth item — a Picovoice AccessKey + trained wake-word file. Picovoice discontinued its free personal tier on 2026-06-30 in favor of a 7-day enterprise trial gated behind a company-email signup — not usable for this project. We've switched the wake-word engine to **openWakeWord**, which is free, open-source, and needs no account at all — see the updated Step 4 below.)*

Everything below is organized by *where* the step happens, in the order that avoids backtracking.

---

## Step 1 — Your PC: install Android Studio

1. Download Android Studio from `https://developer.android.com/studio` (official Google site).
2. Run the installer, accept the default "Standard" install type — this pulls in the Android SDK, platform tools (`adb`), and an emulator image automatically.
3. First launch will download additional SDK components; let it finish.

Nothing further needed here until we scaffold the project — Android Studio will open the project folder we create later.

## Step 2 — Web: Google Cloud Console (Gmail + Calendar access)

This is free — Gmail API and Calendar API have generous free quotas with no billing account required at this scale.

1. Go to `https://console.cloud.google.com/` and sign in with the same Google account you want Myday to read (your personal Gmail/Calendar).
2. Create a new project — name it e.g. **Myday**.
3. In **APIs & Services → Library**, enable:
   - **Gmail API**
   - **Google Calendar API**
4. Go to **APIs & Services → OAuth consent screen** (Google restructured this page into a set of tabs: Overview, Branding, Audience, Clients, Data Access):
   - Under **Audience**: User type **External** (Internal isn't available on a personal, non-Workspace Gmail account); Publishing status **Testing** — skips Google's verification review entirely, which is exactly what you want for a personal, sideloaded app; add your own Gmail address as a **Test user**.
   - Under **Branding**: app name (**Myday**), your email as support/developer contact.
   - Under **Data Access**, click **"Add or Remove Scopes"** and add: `gmail.modify` (covers reading, labeling, and archiving — needed for V1's inbox cleanup) and `calendar.events` (read/write access to events — upgraded from the original `calendar.readonly` once write-mode (accepting/declining meetings by voice) was added; if `calendar.readonly` is already listed from before, it's fine to leave it, just make sure `calendar.events` is added too).
5. In **APIs & Services → Credentials → Create Credentials → OAuth client ID**:
   - Application type: **Android**.
   - Package name: `ch.boazgruener.myday` (from the proposal above — let me know if you want a different one).
   - SHA-1 certificate fingerprint: **leave this step open for now** — Android Studio generates a debug signing key the first time we build the project, and I'll hand you the exact SHA-1 to paste in here once that exists. I'll flag this explicitly when we get there.

**Known trade-off worth knowing now:** because the consent screen stays in "Testing" mode (by design — avoids Google's review process), Google issues refresh tokens that expire after 7 days for this account type. In practice that means roughly once a week, Myday's first "Hey Myday" of the day will ask you to re-sign-in via a browser consent screen instead of giving the briefing directly. Minor friction, but worth expecting rather than being surprised by.

**One-time action needed after adding `calendar.events`:** since this is a broader scope than what you originally granted, the app needs fresh consent for it — the background wake-word service can't get this on its own (only an interactive screen can). After adding the scope above, open the Myday app and tap **"Authorize Gmail + Calendar"** once; it'll show the consent screen again, now listing calendar write access. Until you do this, write-mode requests (like accepting a meeting) will fail and Myday will ask you to open the app and sign in again.

## Step 3 — Web: Anthropic Console (Claude API key)

1. Go to `https://console.anthropic.com/` and sign in (or create an account).
2. Go to **API Keys** and create a new key. Name it something like `myday-android`.
3. Optional but recommended: in **Settings → Limits**, set a monthly spend cap, since this key will be called every day for the briefing plus classification — a sensible personal-use safety net, not because it's expected to be expensive.
4. Paste the key directly into the app once it's installed — **Settings → Anthropic API Key → Add API Key** (see the app's main screen). It's entered per-device, not baked into the app at build time, and is encrypted at rest via an Android-Keystore-backed key before being saved. No need to send it anywhere or hand it over — this is what makes it possible for anyone sideloading this app to use their own Anthropic account and billing instead of Boaz's.

**This step now applies to anyone sideloading the app, not just Boaz** — each person who installs Myday creates their own free Anthropic account and enters their own key on their own device.

## Step 4 — Wake word: openWakeWord (no account needed)

Nothing for you to do here. openWakeWord (the engine that listens on-device, continuously, for "Hey Myday" without draining your battery the way constant cloud speech recognition would) is free, open-source, and doesn't require signing up anywhere — unlike Picovoice Porcupine, which was the original plan until its free tier was discontinued.

When we scaffold the project, I'll train a custom "Hey Myday" model locally against synthetic speech samples (no need for you to record your own voice repeatedly) and bundle the resulting model file directly into the Android app via the `openwakeword-android-kt` Kotlin/ONNX Runtime library.

*(Confirmed: two wake phrases are trained and listened for - "Hey Myday" and the shorter "Myday" alone.)*

## Step 5 — Your Android phone: developer access

1. **Settings → About phone** → tap **Build number** 7 times → this unlocks **Developer options**.
2. **Settings → System → Developer options** → enable **USB debugging**.
3. Connect the phone to your PC via USB. When prompted on the phone, **Allow USB debugging** for this computer (check "always allow" so you don't get asked every time).

This lets Android Studio install and run debug builds directly on your phone — no APK sideloading, no "unknown sources" toggle needed for this workflow.

## Step 6 — Your Android phone: permissions (once we have a build to run)

These can't be done yet — they're granted *inside* the running app or its Settings entry, which won't exist until after scaffolding. Listing them here so nothing is a surprise mid-build:

- **Microphone**: standard runtime permission prompt on first launch — just tap Allow.
- **Notification access** (needed in V1 to detect and dismiss ended-meeting notifications; this same permission also powers the WhatsApp read + reply feature added later — see PRD.md's V3 section — since there's no other way to see WhatsApp messages or send a reply for a personal account): Android requires this to be granted manually — **Settings → Apps → Special app access → Notification access → Myday → Allow**. There's no way to trigger this via a normal in-app popup; I'll remind you with the exact path when the app is ready to test. No separate account, key, or app-store step is needed for WhatsApp — it works entirely through this one permission plus WhatsApp already being installed on the phone.
- **Battery optimization exemption**: since the wake-word listener needs to keep running all day, go to **Settings → Apps → Myday → Battery → Unrestricted**. Without this, some phones (especially non-Pixel Android skins) may kill the background listener after a while and "Hey Myday" will stop responding until you reopen the app.
- **Google sign-in**: the first time you launch the app, it'll open a browser/consent screen for the Google account — sign in and approve the Gmail/Calendar scopes from Step 2.

## Step 7 — Local project hygiene (I'll handle this, noted for transparency)

When we scaffold, I'll:
- Initialize a git repository for the project (currently this folder isn't one).
- Put the Google OAuth client info into `local.properties` — never committed or hardcoded into a source file. *(The Anthropic key no longer goes here — it's entered per-device in the app itself, see Step 3.)*
- Add a `.gitignore` covering `local.properties`, build output, and keystores.

## Step 8 — Web: Google Cloud Console (Maps Platform, for "time to leave" alerts)

*(Added after initial scaffolding, once the proactive-scheduler feature — email cleanup, meeting-reminder voice echo, and travel-time alerts — was underway.)* This is the one piece that's genuinely blocked on you; everything else in this feature works already.

1. In the same Google Cloud project used for Step 2 (**Myday**), go to **APIs & Services → Library** and enable **Distance Matrix API** and **Geocoding API** (the second one was added later — see the note below).
2. Maps Platform requires a billing account on the project (unlike Gmail/Calendar API). Go to **Billing** and link or create one — Google gives a recurring monthly credit that comfortably covers personal-scale usage (a background check every ~15 minutes, only for meetings with a physical location), so this shouldn't cost anything in practice, but the card-on-file requirement itself is worth knowing about upfront.
3. Go to **APIs & Services → Credentials → Create Credentials → API key**. This creates a new, separate key (not the OAuth client from Step 2 — Maps Platform uses plain API keys, not OAuth).
4. Click into the new key and restrict it, so it can only be used the way this app uses it:
   - **Application restrictions**: Android apps → add package name `ch.boazgruener.myday` with SHA-1 certificate fingerprint `0B:C9:F0:77:14:8D:8B:A5:68:BC:B0:12:45:E7:86:38:F1:25:E4:46` (this is the debug keystore's fingerprint, already generated on this PC — same kind of value as Step 2's OAuth client, just a different credential).
   - **API restrictions**: restrict the key to **Distance Matrix API** and **Geocoding API**.
5. Copy the key and send it to me the same way you did the Anthropic key (paste it in chat, or tell me once it's in your password manager and paste it into `local.properties` yourself under `GOOGLE_MAPS_API_KEY=`) — I'll wire it in.

**Update needed on your existing key:** the key you already created only has Distance Matrix API in its API restrictions allow-list. A later feature (disambiguating same-named places, like the "Sonnenberg" in your canton vs. others elsewhere) needs the Geocoding API too, and calls to it are currently being rejected with `REQUEST_DENIED`. To fix: enable **Geocoding API** in the Library (step 1 above) if not already, then go to **APIs & Services → Credentials**, click your existing Maps key, and add **Geocoding API** to its **API restrictions** list alongside Distance Matrix API. No new key or code change needed — the app already calls it, it's just being blocked at the key level.

**Tried and reverted — Cloud Speech-to-Text API for "Stop":** voice-based "Stop" while Myday is talking is unreliable using Android's built-in speech recognizer alone, since it has no way to cancel out the phone's own simultaneous speech output ("double-talk"). A fix using raw microphone capture with Android's `AcousticEchoCanceler`, transcribed via Google Cloud Speech-to-Text, was built and tested - but making it work required switching Myday's TTS output to "voice communication" audio, which broke playback volume (defaults toward the earpiece) and interfered with unrelated logic elsewhere badly enough to reintroduce a previously-fixed bug. Reverted in favor of the **"Stop Speaking" button on the persistent notification** as the reliable interruption method - voice "Stop" still works sometimes, but isn't guaranteed. If Cloud Speech-to-Text API is still enabled/allow-listed on your key from this experiment, it's harmless to leave as is (unused) or remove - no code depends on it anymore.

---

## Suggested order to actually do this in
Steps 1–3 (PC install + two web consoles) have no dependency on each other — do them in any order, or all in one sitting. Step 4 needs nothing from you. Step 5 (phone developer mode) is also independent and quick. Step 6 only becomes possible once we've scaffolded and built the app for the first time — I'll walk you through it at that point rather than you trying to find settings that don't exist yet.

Ping me once you've got the Anthropic key and the Google Cloud project/OAuth client started — that's the point where we can move into scaffolding the actual Android project.
