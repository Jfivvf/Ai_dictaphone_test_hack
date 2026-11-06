# Android test build: quick test protocol

## Get the APK

1. Open the repository's Actions tab.
2. Open the latest successful **Android Java build** run.
3. In Artifacts, download `audiovault-debug-apk`.
4. Extract `app-debug.apk` and install it on an Android device (allow installation from the source used to open the APK).

The APK is a debug build. It is not a Play Store release.

## First launch

- Grant microphone permission when recording is used.
- Import a short audio file first, or record a short sample.
- The first transcription downloads Whisper Small and speaker diarization models. Use Wi-Fi and keep several GB free. This is the only network-dependent part of the inference flow.
- Wait for model preparation to finish. Later runs reuse the downloaded models.

## Core acceptance test

Use a 1–3 minute Russian recording with at least two clearly distinct speakers, recorded without music if possible.

1. Import the file.
2. Select it in the recording list.
3. Tap **Расшифровать и определить говорящих**.
4. Confirm that a transcript appears with time ranges and speaker labels.
5. Check that fillers, repetitions and hesitations remain in the raw transcript; no text-cleaning pass should run by default.
6. Tap several transcript turns and confirm playback seeks to the displayed start time.
7. Edit a few words, save, close/reopen the recording and verify the text remains.
8. Export the transcript to PDF and open the PDF.

## Test cases to send back

For each sample, record:
- Android device model and Android version
- Audio duration and format
- Number of real speakers
- Whether the speaker count and turn boundaries are correct
- Approximate transcription errors (missed words, substitutions, punctuation)
- Whether the timecode jump lands on the correct phrase
- Any crash, ANR, or error text

Recommended samples:
- two speakers, clean room audio
- three speakers, natural conversation
- one speaker with pauses and filler words
- noisy recording or overlapping speech (stress test, not a baseline quality test)

## Known limits of this test build

- This is a first integration test, not a production quality claim. Accuracy must be measured on real recordings.
- Processing is currently in memory; very long audio can exceed RAM.
- The transcript is saved as rendered text, not yet as a separately editable segment database.
- Speaker labels are automatic cluster IDs (Speaker 1, Speaker 2, ...), not real-world identities.
- Llama summaries/tasks, account/password management, encryption-at-rest, and user-controlled backup/restore are not in this test milestone.
