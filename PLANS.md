# PLANS.md

## Ray-Ban Meta Local/Free VLM Experiment Plan

### Summary

Build this repository into an Android experiment app for Ray-Ban Meta AI Glasses. The phone acts as the capture, microphone, and audio-output hub. The Dell GB10 acts as the local model host and experiment orchestrator. The system compares free/local VLM agent profiles, optionally adds free online reference models, and speaks concise model responses back through the glasses audio.

The first working milestone is intentionally narrow: capture one still image, send it to the GB10, run one local VLM, return a structured result plus `speech_text`, and speak that result through Android Text-to-Speech.

### Target System

```text
Ray-Ban Meta AI Glasses
  -> visual capture through Meta Wearables DAT where available
  -> microphone path where available
  -> Bluetooth audio output

Android phone app
  -> DAT device/session integration
  -> still capture and sampled frame capture
  -> microphone capture or fallback
  -> local network client to GB10
  -> native Text-to-Speech readout

Dell GB10 host
  -> local VLM runtime
  -> agent profiles and prompt orchestration
  -> optional online-free model adapters
  -> local experiment storage and export
```

### Phase 1: Repository Foundation

Goals:

- Keep the starter Android app working.
- Add project structure for capture, backend client, experiment state, and speech output.
- Define shared DTOs for the GB10 API before integrating real models.

Android work:

- Replace the starter "Hello Android" screen with a simple experiment shell.
- Add screens or composables for:
  - connection status
  - capture preview placeholder
  - task selector
  - result display
  - speech controls
- Add package structure:
  - `capture`
  - `dat`
  - `network`
  - `speech`
  - `experiment`
  - `ui`
- Add DTOs for analyze requests and normalized results.

GB10 work:

- Create a local backend project in this repo or a clearly named sibling module.
- Add mock endpoints:
  - `GET /health`
  - `GET /models`
  - `POST /analyze_image`
- Return mocked `structured_result` and `speech_text`.

Acceptance criteria:

- Android app runs.
- Android can call GB10 `/health`.
- Android can display and speak mocked `speech_text`.

### Phase 2: Ray-Ban Meta DAT Connection

Goals:

- Connect Android to Ray-Ban Meta AI Glasses through Meta Wearables DAT.
- Validate real or mocked device lifecycle before model integration.
- Current repo status: a compileable DAT-facing abstraction and mock controller are implemented; real SDK wiring still needs GitHub Packages credentials and a Meta Wearables application ID.

Work:

- Add Meta Wearables DAT dependencies:
  - core
  - camera
  - mock device support
- Configure GitHub Packages credentials without committing tokens.
- Add required app metadata and permissions.
- Implement DAT session state handling:
  - not registered
  - registered
  - discovering
  - connected
  - running
  - paused
  - stopped
  - error
- Add MockDeviceKit support for development without glasses.

Acceptance criteria:

- App can show DAT device/session state.
- App can enter a running session with glasses or mock device.
- Failure states are visible and recoverable.

### Phase 3: Still Image Capture To GB10

Goals:

- Send one real image to the GB10 and receive a model-ready response.
- Prefer glasses capture, but keep phone camera fallback.

Work:

- Capture still image from Ray-Ban Meta DAT camera path where available.
- Add phone camera fallback.
- Compress/resize image before upload.
- Attach task metadata:
  - `task_type`
  - `prompt`
  - `session_id`
  - capture timestamp
  - source: glasses or phone
- Send request to `POST /analyze_image`.

Acceptance criteria:

- One still image reaches GB10.
- Android displays the returned result.
- Android speaks returned `speech_text`.
- No model/provider key exists in Android.

### Phase 4: Local VLM Runtime On GB10

Goals:

- Replace mock model response with local inference.
- Start with the easiest reliable local VLM runtime.
- Current repo status: the GB10 host now attempts Ollama inference with `gemma3:4b` and `gemma3:12b`, while retaining mock fallback when Ollama or models are unavailable.

Initial model targets:

- `gemma3:4b` as the fast Gemma baseline.
- `llama3.2-vision:11b` as an alternate fast baseline.
- `gemma3:12b` as the first detail model candidate.

Runtime preference:

- Use Ollama first for local vision serving if it supports the required model and image path cleanly.
- Keep the GB10 orchestration API stable so the runtime can change later.

Work:

- Add local model client in the GB10 backend.
- Send image plus task prompt to the selected local VLM.
- Normalize model response into the shared schema.
- Generate or extract concise `speech_text`.

Acceptance criteria:

- GB10 runs one local VLM on a captured still image.
- Response includes model ID, runtime, prompt version, latency, structured result, and speech text.
- Android does not need to know which local runtime is used.

### Phase 5: Two-Agent Comparison

Goals:

- Run two agent profiles on the same image/task.
- Display both results and speak the selected result.

Agent profiles:

- `local_gemma_fast`
  - model: `gemma3:4b`
  - purpose: speed and live sampling
- `local_gemma_detail`
  - model: `gemma3:12b`, later `gemma3:27b` if practical
  - purpose: OCR and fine detail
- `local_llama_fast`
  - model: `llama3.2-vision:11b`
  - purpose: baseline alternative
- `local_detail_reference`
  - model: Nemotron Nano 12B V2 VL or Qwen2.5 VL 72B if feasible
  - purpose: benchmark only

Work:

- Add agent profile config on GB10.
- Run fast and detail profiles against the same input.
- Normalize both outputs.
- Select one `speech_text` for readout based on task configuration.
- Log both results.

Acceptance criteria:

- Android shows side-by-side model outputs.
- Android speaks only one concise result by default.
- Experiment log records both agents.

### Phase 6: Microphone Input

Goals:

- Let the user ask a spoken question or issue a task instruction.
- Attach the transcript to the visual request.
- Current repo status: Android now has a push-to-talk phone microphone fallback using the system speech recognizer; the transcript is sent as `voice_transcript` and included in GB10 VLM prompts/logs. Raw audio is not stored.

Preferred input order:

1. Glasses microphone path if available through DAT or Android Bluetooth audio routing.
2. Phone microphone fallback.

Work:

- Add microphone permission flow.
- Add push-to-talk or short capture mode.
- Add local transcription path on phone or GB10.
- Attach transcript to `POST /analyze_image`.
- Keep raw audio storage disabled by default.

Acceptance criteria:

- User can speak a short query.
- Query transcript is included in the model prompt.
- Raw audio is not stored unless explicitly enabled.

### Phase 7: Speech Output To Glasses

Goals:

- Reliably speak model answers through Ray-Ban Meta audio.
- Avoid noisy or overlapping narration.
- Current repo status: Android TTS uses speech/media audio attributes and exposes Bluetooth route detection, permission request, refresh, and an opt-in forced Bluetooth communication route. Forced routing is off by default because it can reduce clarity by switching glasses into a call/headset profile.

Work:

- Use Android native Text-to-Speech.
- Route audio to the paired Ray-Ban Meta Bluetooth audio device where Android exposes the route.
- Add UI controls:
  - mute
  - stop
  - replay latest
  - speech enabled/disabled
- Stop current speech before starting a new readout.
- Keep speech short and non-identifying for face tasks.

Acceptance criteria:

- Model response is audible through glasses audio.
- User can stop and replay speech.
- Live mode does not produce continuous overlapping narration.

### Phase 8: Sampled Live Mode

Goals:

- Use the glasses video stream or repeated capture to sample frames for live testing.
- Avoid continuous raw video inference.
- Current repo status: Android now has sampled live controls for interval, max duration, and live speech. The implementation uses DAT mock frames when the mock session is running, or the latest captured still as fallback, with exactly one request in flight. Duplicate live speech is suppressed.

Work:

- Add live sampling controls:
  - interval
  - max session duration
  - enabled agents
  - speech behavior
- Keep one in-flight request per enabled agent profile.
- Drop or replace stale frames instead of queueing indefinitely.
- Use fast local model by default.
- Run detail model only on selected frames or explicit user request.

Acceptance criteria:

- Live sampling runs at a controlled interval.
- No unbounded request queue is possible.
- Speech happens only for meaningful changes or explicit user requests.

### Phase 9: Optional Free Online Reference Models

Goals:

- Add free online models as comparison routes without making them required.

Candidate routes:

- Gemini free-tier API models if available.
- NVIDIA hosted trial/free VLM endpoints if available.
- Hugging Face free/community inference for non-critical checks.

Rules:

- Disabled by default.
- Tokens stay only on GB10.
- Online failures must not block local inference.
- Every online result must be marked as reference-only.

Acceptance criteria:

- A run can optionally include an online reference result.
- Local model results still complete if online reference fails.
- Logs include provider, model ID, latency, quota/failure state, and whether the route was online.

### Phase 10: Experiment Logging And Export

Goals:

- Make runs reproducible and analyzable.

Store:

- run ID
- session ID
- task type
- image metadata
- optional transcript
- capture source
- agent profile
- model ID
- runtime/provider
- prompt version
- latency
- structured result
- speech text
- optional human ground truth

Export:

- JSON for full-fidelity records.
- CSV for comparison analysis.

Acceptance criteria:

- Experiment runs persist on GB10.
- Export includes all fields needed to compare agents.
- Face tasks remain non-identifying.

### Testing Strategy

Automated tests:

- Android DTO serialization.
- Android experiment state transitions.
- GB10 request validation.
- Agent profile selection.
- Result normalization.
- Speech text generation constraints.
- Live sampling backpressure behavior.

Manual hardware tests:

- Ray-Ban Meta pairing and session state.
- DAT camera/photo/video path.
- Phone camera fallback.
- Glasses microphone path or phone microphone fallback.
- TTS routed to glasses audio.
- End-to-end still capture to GB10 local VLM.
- Sampled live mode throttling.

### Non-Negotiable Constraints

- No OpenRouter runtime dependency.
- No ChatGPT/OpenAI dependency for the main path.
- Android must not contain provider secrets.
- Local/free models are the primary implementation target.
- Online-free models are optional references only.
- No open-ended face identification.
- Do not store raw audio/images unless explicitly enabled for the experiment.
- First milestone must stay small: still image, local model, structured result, speech readout.
