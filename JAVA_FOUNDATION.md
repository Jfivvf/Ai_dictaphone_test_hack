# Java Android application

This branch is migrating the launcher and core audio workflow to Java while retaining existing Kotlin UI modules only where they do not conflict with the Java launcher.

## Implemented in this branch

- Native Android audio recording using `MediaRecorder` in a microphone foreground service.
- Audio import through the Storage Access Framework into app-private storage.
- Local SQLite recording metadata and transcript persistence.
- Manual transcript editor and PDF export.
- Local audio decoding with `MediaExtractor` + `MediaCodec`, downmixing to mono and resampling to 16 kHz.
- Real offline ASR through the Sherpa-ONNX Android AAR and multilingual Whisper Small.
- Real offline speaker diarization through Sherpa-ONNX OfflineSpeakerDiarization (Pyannote segmentation + 3D-Speaker embeddings).
- Word/token timestamp alignment to diarization intervals; speaker turns are displayed with start/end timecodes.
- Tap a transcript turn to start playback at its timecode.
- Model files are downloaded on first transcription, cached under app-private `files/models`, and reused offline.

## Models and runtime

- Sherpa-ONNX Android AAR: release v2.0.1, downloaded during Gradle `preBuild` and checked against the published SHA-256 digest.
- ASR: multilingual Whisper Small, quantized encoder and full decoder; Russian is selected as the initial recognition language.
- Diarization segmentation: Pyannote segmentation 3.0.
- Speaker embeddings: 3D-Speaker ERes2Net base.
- Runtime provider: CPU. Audio decoding and model inference happen locally. Model download is the only network operation in this pipeline.

Model downloads are substantial. The first transcription needs an internet connection and free storage. The downloaded models are retained in private app storage.

## Important behavior

- The app does not use Android's cloud SpeechRecognizer for transcription.
- No speaker labels are guessed by alternating speaker numbers. Speaker identities come from acoustic segmentation, speaker embeddings, and clustering.
- If ASR timestamps or diarization segments are missing, processing fails with an error instead of fabricating a transcript.
- The original audio remains local. The transcript defaults to the uncleaned Whisper output; no automatic removal of fillers or repetitions is applied.
- A transcript row's time range comes from the diarization interval; recognized tokens are assigned using Whisper token timestamps.

## Current limitations / validation still required

- A successful Gradle build is required before the APK can be considered buildable; GitHub Actions is running the first build check.
- The pipeline still requires validation against real Russian multi-speaker recordings on physical Android devices, especially overlapping speech, short turns, background noise, and long recordings.
- The current transcript editor stores the rendered speaker/timecode text. A structured segment table with independently editable speaker IDs and exact sub-second timestamps is a follow-up persistence migration.
- Current playback navigation jumps to the start of a speaker turn; it does not yet stop automatically at that turn's end.
- The first implementation processes the decoded audio in memory. Very long recordings may exceed device memory; chunked ASR/diarization is a later optimization.
- Llama summary and task extraction, model settings UI, encrypted database/files, user accounts and backup/restore are not part of this transcription milestone.

## Build

- Java 17.
- Android minSdk 24, compileSdk 36.
- The Sherpa AAR is fetched by the Gradle task and verified before the app is compiled.
