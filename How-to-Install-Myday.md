# Installing Myday on Your Phone

Myday is a voice assistant app Boaz built — you say "Myday" out loud and it can read you your calendar, summarize your email, and check your WhatsApp messages, all hands-free.

It's not on the Google Play Store (it's a personal project, not a public app), so installing it looks a little different than apps you're used to. It's a handful of steps, but none of them are hard — just follow along in order.

**Before you start**, make sure Boaz has your Gmail address — he needs to add it on his end before you can sign in with Google in step 6. If you skip ahead and it fails there, that's why — just check with him.

---

## Step 1 — Get the app file onto your phone

Boaz will send you a link to a file called **Myday.apk** (it's about 105 MB, so it may take a minute over Wi-Fi). Open that link on your phone and download it — it'll typically land in your **Downloads** folder or show up as a notification when it's done.

*(An "APK" is just what an Android app's install file is called — same idea as a `.exe` on Windows, just for phones.)*

## Step 2 — Let your phone install it

Android blocks installing apps from outside the Play Store by default, so you'll need to allow it once for whichever app you used to download the file (usually your Chrome browser or the Files app):

1. Tap the downloaded file to open it.
2. Android will likely show a warning like *"For your security, your phone is not allowed to install unknown apps from this source."* Tap **Settings** on that message.
3. Turn on **Allow from this source**.
4. Go back and tap the file again.

## Step 3 — Install it

You'll now see the normal Android install screen for Myday. You may also see a **Google Play Protect** warning saying it "doesn't recognize" the app — that's expected for any app not from the Play Store. Tap **Install anyway** (you may need to tap through an extra confirmation).

Tap **Install**, then **Open** once it's done.

## Step 4 — Grant permissions

Myday will ask for a few permissions when you first open it and tap around. Say yes to all of them — each one is something the app genuinely needs to work:
- **Microphone** — to hear you say "Myday" and your questions.
- **Location** — for weather and "how long until I need to leave" alerts.
- **Contacts** — so it can match names you say to people in your phone.
- **Notifications** — so it can tell you things out loud.

## Step 5 — Turn on two extra permissions

These two aren't part of the normal popup — you grant them from inside the app itself:

- Tap **Grant Notification Access** → find **Myday** in the list → turn it on. *(This is what lets Myday read your WhatsApp messages and clear out old calendar reminders — Android requires this to be a manual, explicit step, it can't be a simple popup.)*
- Tap **Grant Do Not Disturb Access** → allow it. *(This keeps your phone from beeping over Myday while it's listening.)*

## Step 6 — Sign in with your Google account

Tap **Authorize Gmail + Calendar** and sign in with the Google account whose calendar and email you want Myday to use. Google will show a warning that the app "isn't verified" — that's normal for a personal project like this (verifying an app with Google costs real money and isn't worth it for a handful of friends trying it out). Tap through **Advanced → Go to Myday (unsafe)** to continue — "unsafe" here just means Google hasn't formally reviewed it, not that anything is actually wrong.

**One quirk to expect**: because of that unverified status, Google will ask you to sign in again roughly once a week. Not a bug — just re-tap the same button when it happens.

## Step 7 — Add your own Anthropic API key

Myday is powered by an AI model called Claude, made by a company called Anthropic — and it needs its own account/key to work, separate from Google. This is the one step where you're setting up something of your own rather than just granting a permission:

1. On your phone, open a browser and go to **console.anthropic.com**.
2. Sign in or create a free account.
3. Go to **API Keys** → **Create Key**. Give it any name (e.g. "myday").
4. It'll show you a long code starting with `sk-ant-...` — copy it right away (it's only shown once).
5. Switch back to Myday, tap **Add API Key**, tap and hold the empty box, and **Paste**.
6. Tap **Save API Key**.

*(Anthropic's free tier is small, but plenty for trying this out — you're not on the hook for any real cost just by testing it.)*

## Step 8 — Try it

Tap **Start Wake-Word Listening**, then just say **"Myday"** out loud. Wait for it to respond, then ask it something like *"What's on my calendar today?"* or *"Any important email today?"*

---

### If something doesn't work
- **Google sign-in fails / "access blocked"** → Boaz likely hasn't added your Gmail address yet — ping him.
- **It doesn't respond to "Myday"** → make sure you tapped **Start Wake-Word Listening** first, and that the microphone permission was granted.
- **Anything else** → tell Boaz what happened; he built this and can help debug it.
