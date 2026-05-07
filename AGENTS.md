# AGENTS.md

## Project Purpose

This repository is for an Android experiment app that uses Ray-Ban Meta AI Glasses as capture and audio hardware, an Android phone as the session hub, and a Dell GB10 PC as the local model host.

The experiment compares multiple vision-language agent profiles on fine-detail tasks:

- Reading small or distant text on boards.
- Identifying small items and their approximate positions on a table.
- Counting and locating visible faces without identifying people.
- Combining visual context with a short spoken user query.
- Reading model responses back to the user through the glasses audio path.

The primary runtime must be local/free where possible. Hosted/free online models may be added only as optional reference routes and must not be required for the main experiment path.

## Current Repo Shape

At the time this file was created, the repo is a starter Kotlin/Jetpack Compose Android app:

- Android app module: `app`
- Main activity: `app/src/main/java/com/example/smartglassesagents/MainActivity.kt`
- Build system: Gradle Kotlin DSL
- UI toolkit: Jetpack Compose / Material 3

No backend, Ray-Ban Meta DAT integration, camera pipeline, microphone pipeline, local VLM client, experiment logging, or speech readout has been implemented yet.

## Architecture Direction

Use this architecture unless the user explicitly changes it:

```text
Ray-Ban Meta AI Glasses
  -> camera/video/photo capture where exposed by Meta Wearables DAT
  -> microphone/audio route where available
  -> Bluetooth audio output for spoken responses

Android phone app
  -> Meta Wearables DAT device/session management
  -> capture permissions and capture pipeline
  -> fallback phone camera/microphone
  -> local network client to Dell GB10
  -> native Android Text-to-Speech playback

Dell GB10 local host
  -> local VLM runtime
  -> agent orchestration
  -> optional online-free reference adapters
  -> experiment run storage and export
```

The Android app must not store provider API keys. Any online-free reference provider tokens belong only on the GB10 host, preferably in environment variables or local secret files that are not committed.

## Agent Profiles

Agents are backend profiles, not separate Android classes that directly call remote providers. The GB10 orchestrator owns model selection, prompts, timeouts, normalization, and logging.

Initial profile set:

- `local_gemma_fast`
  - Model target: `gemma3:4b`
  - Runtime target: Ollama first, unless a better GB10 runtime is chosen.
  - Purpose: fast still-image checks and sampled live frames.

- `local_gemma_detail`
  - Model target: `gemma3:12b`, with `gemma3:27b` as a benchmark if latency is acceptable.
  - Purpose: fine-detail OCR, object localization, spatial layout, and uncertainty reporting.

- `local_llama_fast`
  - Model target: `llama3.2-vision:11b`
  - Purpose: alternative fast local VLM and baseline comparison.

- `local_detail_reference`
  - Model targets: `nvidia/nemotron-nano-12b-v2-vl` and/or quantized `qwen2.5-vl-72b-instruct` if practical on GB10.
  - Purpose: detail-focused benchmark, not required for the first MVP.

- `online_free_reference`
  - Model targets: free Gemini API tier, NVIDIA hosted trial/free endpoints, or Hugging Face community/free inference if available.
  - Purpose: optional comparison only.
  - Must be disabled by default and never block local inference.

## Model Response Contract

Normalize all model responses before Android renders or speaks them.

Minimum response shape:

```json
{
  "run_id": "string",
  "task_type": "board_text | tabletop_items | faces | general_query",
  "selected_speech_agent": "string",
  "results": [
    {
      "agent_profile": "string",
      "model_id": "string",
      "runtime": "ollama | local_runtime | online_reference",
      "prompt_version": "string",
      "answer": "string",
      "observations": ["string"],
      "locations": [
        {
          "label": "string",
          "position": "string",
          "confidence": 0.0
        }
      ],
      "confidence": 0.0,
      "uncertainties": ["string"],
      "latency_ms": 0
    }
  ],
  "speech_text": "short spoken-friendly answer"
}
```

`speech_text` must be short, plain, and suitable for immediate Text-to-Speech. It should not include raw JSON, markdown tables, long reasoning traces, or verbose uncertainty lists.

## Speech Readout Rules

Android should use native Text-to-Speech first because it is free and can work offline.

Speech behavior:

- Speak only final results by default, not partial streaming tokens.
- Stop or fade any current speech before speaking a new result.
- Provide mute, stop, and replay controls.
- Avoid constant narration in sampled live mode.
- In live mode, speak only meaningful changes or explicit user-requested readouts.
- Keep face-task speech non-identifying.

Examples:

- "I can read three words on the board: safety checklist today."
- "There are four small items on the table. The red item is near the front left."
- "I see two faces, one near the center and one on the right."

## Capture Rules

Preferred visual capture path:

1. Ray-Ban Meta glasses camera/photo/video through Meta Wearables DAT.
2. Sampled frames or still captures sent to the GB10.
3. Phone camera fallback if glasses capture is unavailable.

Preferred microphone path:

1. Glasses microphone if exposed reliably through DAT or Android Bluetooth audio routing.
2. Phone microphone fallback through Android audio APIs.

Do not send continuous raw video by default. The default experiment mode is still-image capture. Sampled live mode must enforce a fixed interval, a maximum session duration, and backpressure so frames do not queue indefinitely.

## Ray-Ban Meta DAT Integration Notes

Use the Meta Wearables Device Access Toolkit for Android:

- Repository: `https://github.com/facebook/meta-wearables-dat-android`
- Expected SDK areas:
  - core device/session lifecycle
  - camera/photo/video capture
  - mock device support for development

Implementation should include:

- App registration with Meta AI / Wearables Developer Center.
- Manifest metadata for the Meta Wearables application ID.
- Camera and audio permissions as needed.
- Explicit session state handling for running, paused, stopped, and error states.
- MockDeviceKit support for development without physical glasses.

Do not assume every glasses capability is exposed in DAT. If glasses microphone or audio routing is not directly available, use Android system Bluetooth audio routing and phone microphone fallback.

## Local Host API Direction

The GB10 service should expose a small HTTP API that Android can call on the local network.

Minimum endpoints:

- `GET /health`
- `GET /models`
- `POST /analyze_image`
- `GET /experiment_runs`
- `GET /export`

Later endpoints may include:

- `POST /pair`
- `POST /transcribe_audio`
- `POST /analyze_frame`
- `POST /cancel_run`

Use simple token or pairing-code auth for the local MVP. Do not expose the GB10 service publicly without proper authentication, TLS, rate limits, and logging review.

## Privacy And Safety Requirements

- Do not implement open-ended identity recognition.
- Face tasks are limited to count, approximate location, and non-identifying visible descriptions.
- Use session IDs instead of real participant names.
- Store raw images, audio, or transcripts only when explicitly enabled for the experiment.
- Keep local data on the GB10 by default.
- Online-free reference adapters must be opt-in per run or per experiment configuration.
- Logs must not include secrets or unnecessary biometric/audio content.

## Testing Expectations

For each implementation stage, add focused tests where practical:

- Android DTO serialization and state transitions.
- GB10 API request validation and response normalization.
- Agent profile selection.
- Speech text generation constraints.
- Live sampling throttling and cancellation behavior.

Manual acceptance checks are required for hardware behaviors:

- Ray-Ban Meta pairing/session state.
- Glasses capture or phone fallback.
- Microphone capture or fallback.
- Audio output routed to the glasses.
- End-to-end still image inference through GB10.

## Engineering Constraints

- Keep Android UI in Jetpack Compose.
- Keep model/provider credentials out of Android.
- Prefer local/free inference for the main path.
- Treat online models as unreliable reference adapters.
- Keep the first milestone small: capture one still image, send it to GB10, return text, and speak it.
- Record model ID, runtime, prompt version, latency, task type, and image metadata for every experiment run.
