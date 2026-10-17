# Voice input (optional)

Dictate tasks instead of typing. **Off by default**; turn it on in Settings > Voice input. Nothing is recorded,
and no model is downloaded, until you do.

- **Chat:** a round microphone button sits **above Run**, in the Run button's own column, so the text box keeps its width.
- **Floating prompt:** the microphone sits **to the left of Close**.
- Tap to start (button turns red), tap again or stop talking to finish (amber while converting). The words appear in
  the task box for you to check; **it never runs a task by itself**. Long-press the microphone in the chat to open Voice settings.
- The microphone permission is requested the first time (the overlay opens a small permission screen for it).

## Two engines

| | Google speech | Local Whisper |
|---|---|---|
| Where it runs | Android's own recognizer (Google speech service) | On this phone, CPU only (whisper.cpp) |
| Audio leaves the phone | Yes, to Google, unless "Prefer on-device recognition" is on and the language pack is installed | Never |
| Download | None (the optional on-device language pack is Google's) | One model, after you choose it: Tiny English 32 MB, **Base English 60 MB (default)**, Base multilingual 60 MB, Small multilingual 190 MB |
| Needs network | Usually | No (only for the one-time model download) |
| Live partial text | Yes | No (the text appears when you finish) |

## Local Whisper details

- **The model is not in the APK.** Choosing Local Whisper offers a download (with a metered-connection warning). It comes
  from the whisper.cpp model repository, pinned to one revision, and is accepted only if its size and **SHA-256** match the
  catalog (`WhisperModels`); a wrong file is deleted. Downloads resume after an interruption, run in the app process so leaving
  Settings does not stop them, and land in the app's private storage (removed with the app or with "Delete this model").
- **The native library is in the APK** (whisper.cpp, MIT, vendored in `third_party/whisper.cpp`, CPU backend only; see
  `VENDORED.md`): about **1.9 MB for arm64** (2.1 MB more for the x86_64 emulator build). Universal debug APK 10.6 MB
  (6.6 MB before voice); `-PabiOnly=arm64-v8a` builds the phone-only APK at 8.5 MB.
- **Why not an on-demand dynamic feature?** Play Feature Delivery needs Play Store installs of an app bundle; this app is sideloaded.
  If it is ever published on Play, the `voice/` code and `whisper_jni` can move into an on-demand module. Until then the
  model (the large part) is already downloaded on demand.
- **CPU:** arm64 phones need half-precision and dot-product instructions (Snapdragon 8+ Gen 1 in the Fold 4 has them,
  12 GB RAM); the app checks `/proc/cpuinfo` before loading the library and says so if they are missing.
- **Memory and speed:** the model loads on first use (about 0.1 s on the emulator) and is freed after 90 s idle. On the
  emulator's x86 CPU, `base.en` transcribed 11 s of speech in 3.7 s; ARM with fp16/dot-product should be faster.
  Recording stops after 1.2 s of silence, 8 s without speech, or 30 s.
- Language: English-only models always use English; multilingual ones take a language (auto-detect, English, Spanish, French, German, Portuguese).
  Short commands are more reliable with a fixed language than with auto-detect.

## Privacy and safety

- The audit log records that a dictation happened (engine, audio length, processing time, number of characters), never the words.
- Audio is held in memory only, is never written to disk, and is discarded after transcription.
- Text lands in the task box like typed text: the agent, the policy layer and the confirm gate treat it identically.
- Microphone use is shown by Android's green indicator. The overlay can record while another app is in front (verified:
  the audio service reports the recording as not silenced with Settings in front).

## Testing without a microphone (adb)

```bash
adb shell am broadcast -a dev.egarcia.harness.ADB_VOICE -n dev.egarcia.harness/.AdbVoiceReceiver --es mode whisper --es model base.en --ez download true
adb push sample.wav /sdcard/Android/data/dev.egarcia.harness/files/sample.wav     # 16-bit PCM WAV, any rate
adb shell am broadcast -a dev.egarcia.harness.ADB_VOICE -n dev.egarcia.harness/.AdbVoiceReceiver --es wav sample.wav
adb logcat -s HarnessVoice HarnessWhisper
```
Verified with the whisper.cpp JFK sample: exact transcript. The emulator's microphone is silent, so live speech was verified
only as far as recording starting, states changing and "no speech" handling; real speech needs the Fold 4.
