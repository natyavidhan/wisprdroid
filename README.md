# wisprdroid

I wanted Wispr Flow on my Android phone without the subscription, and I liked what [OpenWhispr](https://github.com/OpenWhispr/openwhispr) does on desktop. So this is a very small Android take on the same idea: talk, and clean text shows up in whatever box you're typing in. Groq does all the heavy lifting.

The APK is about 33 KB. It uses no libraries and no AndroidX, just the Android framework and Kotlin.

## What it does

When you tap into a text field and the keyboard comes up, a little dark pill sits just above the keyboard.

- **Tap** it to start talking. Tap again when you're done.
- **Hold** it if you prefer push-to-talk. Let go to send.
- **Drag** it up or down if it's covering something, like a send button. It remembers where you put it.

After you stop, the audio goes to Groq's Whisper (`whisper-large-v3-turbo`). A quick LLM pass then tidies it up: it drops the "um"s, fixes punctuation, and handles things like "let's meet at 3, no actually 4" becoming "let's meet at 4". The result is typed in at your cursor. You can turn cleanup off if you want the raw transcript.

### Talking to the assistant

Start with the assistant's name and it treats what you say as a command instead of dictation:

> "Jarvis, write a polite reply saying I can't make it Friday"

It can see what's already in the text field. If you select some text first, its answer replaces that selection. That's handy for things like "Jarvis, make this sound less angry". The name is configurable. The default is Jarvis because Whisper reliably spells it right. I tried "Whispr" and it kept coming out as "Whisper".

### Dictionary

Add names, product words or jargon in settings. They go to Whisper as a hint and to the cleanup model as preferred spellings.

## Install

Grab an APK from Releases, or build one yourself (see below).

1. Install it and open **Whispr**.
2. Paste your [Groq API key](https://console.groq.com/keys) and allow the microphone.
3. Tap **Turn on Whispr in Accessibility** and enable it.
4. Tap into any text field, and the pill should appear.

**If the accessibility toggle is greyed out:** Android 13+ blocks this for sideloaded apps by default. Go to *App info → ⋮ (top right) → Allow restricted settings*, then try again.

## Why an accessibility service?

It's the only way for an ordinary app to know a text field is focused and type into it without replacing your keyboard. You keep Gboard, SwiftKey or whatever you use now, and this floats on top. The overlay is an accessibility overlay too, so there's no "display over other apps" permission.

I also skipped React Native and Flutter on purpose. An accessibility service has to be native code either way, and a framework would turn a 33 KB app into a 20 MB one.

## Building

You'll need JDK 17 and the Android SDK (platform 35).

```sh
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key, so it installs straight away. If you plan to distribute it, set up a real signing config.

## Code tour

There are five files in `app/src/main/java/dev/apkwhispr/`:

| File | What's in it |
| --- | --- |
| `FlowService.kt` | The accessibility service: when to show the pill, touch handling, recording, and inserting text |
| `BubbleView.kt` | The pill itself, drawn on a Canvas (mic, live waveform, loading dots) |
| `Groq.kt` | A tiny HTTP client for transcription and chat, plus the prompts |
| `MainActivity.kt` | The settings screen, built in code |
| `Cfg.kt` | SharedPreferences wrapper |

## Known rough edges

- Text is inserted with `ACTION_SET_TEXT`, which rewrites the field's contents. That's fine for normal inputs. In some rich editors (certain web apps, Google Docs) you might lose formatting or undo history.
- When `SET_TEXT` is refused, it falls back to pasting. That overwrites your clipboard, and Android won't let a background app read the old clipboard to restore it.
- Whisper sometimes hears "Thank you." in silence. The most common phantom phrases are filtered out, but not all of them.
- Your Groq key is stored in plain text in the app's private storage. That's fine for a personal phone, but don't ship it like that.
- I've mostly tried it on stock-ish Android. Some OEM skins get aggressive about killing accessibility services, so if the pill vanishes, check battery optimization.

## Credits

The idea and the assistant-by-name flow come from [OpenWhispr](https://github.com/OpenWhispr/openwhispr). The UX is lifted shamelessly from [Wispr Flow](https://wisprflow.ai). Transcription and LLM calls go through [Groq](https://groq.com).
