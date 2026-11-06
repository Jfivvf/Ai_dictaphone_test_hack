# Java Android foundation

This branch adds a native Java entry point and the first real device-side workflows to the existing Android project.

## Implemented

- Audio capture through Android `MediaRecorder`.
- Foreground microphone service so recording can continue while the Activity is not visible.
- Import through the Storage Access Framework; selected audio is copied into the app-private recordings directory.
- Local SQLite catalogue for recording metadata and transcript text.
- Manual transcript editing and persistence.
- PDF export through Android `PdfDocument`.
- Empty library is shown as empty; no sample recordings or fabricated transcripts are inserted.
- No network calls are made by the recording, import, library, or PDF workflows. Android backup is disabled.

## Existing ML contract

The sibling `Proj_audio` repository defines Python interfaces `WhisperBase` and `LlamaBase`. The Whisper contract expects a Java class named `ru.hck.voice.bridge.WhisperBridge` with setters for language, threads, beam size, best-of, temperature, temperature increment, translation, timestamps and context, plus initialize/transcribe/release methods.

The current Android repository does not contain that bridge implementation or a packaged native inference runtime. Therefore this branch does not pretend to transcribe audio, diarize speakers, generate summaries, or extract tasks. A real transcription button must only be added after the bridge and its supported model runtime are integrated and tested on-device.

## Not yet implemented

- Whisper runtime and model download/verification.
- Speaker diarization runtime and speaker-label editing.
- Timestamped transcript segments and tap-to-seek playback.
- Llama summary and task extraction.
- Markdown rendering with speaker colors.
- Audio playback and configurable decoding settings.
- Encrypted database/files, account management, password changes and backup/restore.
- Model download manager and progress reporting.

## Storage

- SQLite database: app-private `audio_vault.db` stores recording metadata and transcript text.
- File storage: app-private `files/recordings/` stores source audio.
- The current SQLite database is not encrypted. App-private storage and disabled Android backup reduce exposure but are not a substitute for at-rest encryption.

## Build notes

- Java source/target compatibility: Java 17.
- Existing project namespace and application ID are retained.
- Android minimum SDK remains 24.
- Recording requires microphone permission. On Android 13+, notification permission may be requested by the platform for foreground-service notification visibility.
