# wisprdroid

Voice dictation for Android, modeled on [Wispr Flow](https://wisprflow.ai) and [OpenWhispr](https://github.com/OpenWhispr/openwhispr). Transcription and text processing run on [Groq](https://groq.com). Native Kotlin, no dependencies, ~33 KB APK.

## Features

- Floating pill shown above the keyboard when an editable field is focused
  - Tap: start/stop recording
  - Hold: push-to-talk
  - Drag: reposition (saved relative to keyboard)
- Transcription via Groq `whisper-large-v3-turbo`
- Optional LLM cleanup: removes fillers, applies self-corrections, fixes punctuation
- Assistant mode: prefix with the agent name ("Jarvis, ...") to run a command. Field text and selection are sent as context; with a selection, the output replaces it
- Custom dictionary, passed to Whisper as a prompt and to the LLM as preferred spellings
- Works alongside any keyboard

## How it works

1. An `AccessibilityService` listens for focus and window events. The pill is shown when an input method window is present and the focused node is editable and not a password field.
2. The pill is a `TYPE_ACCESSIBILITY_OVERLAY` window, so no `SYSTEM_ALERT_WINDOW` permission is needed.
3. Audio is recorded with `MediaRecorder` (AAC, 16 kHz, mono, 32 kbps) and uploaded to `/audio/transcriptions`.
4. The transcript goes to `/chat/completions` for cleanup or an assistant response.
5. Text is inserted at the cursor with `ACTION_SET_TEXT` + `ACTION_SET_SELECTION`. Fallback: `ACTION_PASTE`. If no field is focused, the text is copied to the clipboard.

## Setup

1. Install the APK from [Releases](../../releases) or build it.
2. Open the app, enter a [Groq API key](https://console.groq.com/keys), grant microphone access.
3. Enable the service under Settings > Accessibility.
   On Android 13+ with a sideloaded APK, first go to App info > menu > Allow restricted settings.

## Build

Requires JDK 17 and Android SDK platform 35.

```sh
./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

Release builds are minified with R8 and signed with the debug key.

## Configuration

| Setting | Default |
| --- | --- |
| Transcription model | `whisper-large-v3-turbo` |
| Cleanup / assistant model | `llama-3.3-70b-versatile` |
| Assistant name | `Jarvis` |
| Language | auto |
| AI cleanup | on |

## Source

| File | Purpose |
| --- | --- |
| `FlowService.kt` | Accessibility service: visibility, touch, recording, insertion |
| `BubbleView.kt` | Canvas-drawn pill (idle, recording waveform, busy) |
| `Groq.kt` | HTTP client and prompts |
| `MainActivity.kt` | Settings screen |
| `Cfg.kt` | SharedPreferences wrapper |

## Limitations

- `ACTION_SET_TEXT` replaces the full field content; rich editors may lose formatting or undo history.
- The paste fallback overwrites the clipboard (background clipboard reads are blocked on Android 10+).
- The API key is stored in plain text in app-private storage.
- Some OEM battery optimizations may kill the accessibility service.
