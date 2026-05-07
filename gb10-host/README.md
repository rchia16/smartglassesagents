# GB10 Local Host

This folder contains the local host milestone for the Smart Glasses Agents experiment.

The server provides the API shape that the Android app expects. It tries local Ollama VLM inference first and falls back to deterministic mock responses when Ollama is unavailable or a model is not installed.

## Run

```powershell
python .\gb10-host\mock_server.py
```

The server listens on `0.0.0.0:8765`.

For the Android emulator, use:

```text
http://10.0.2.2:8765
```

For a physical phone on the same network, use the GB10 machine's LAN IP:

```text
http://<gb10-lan-ip>:8765
```

Do not use `10.0.2.2` from a physical phone. That address is an Android emulator alias for the host machine. On a real phone, check the GB10/desktop Wi-Fi IPv4 address with:

```powershell
ipconfig
```

Then enter that address in the app, for example:

```text
http://192.168.0.194:8765
```

## Endpoints

- `GET /health`
- `GET /models`
- `POST /analyze_image`
- `GET /experiment_runs`
- `GET /export`

The implementation does not store images or raw audio. It stores only lightweight run records in memory for the current process, including the optional `voice_transcript` sent by the Android app.

`POST /analyze_image` accepts:

- `session_id`
- `task_type`
- `prompt`
- `voice_transcript` optional
- `image_base64`
- `image_mime_type`
- `capture_metadata`

`capture_metadata` includes:

- `source`
- `captured_at_ms`
- `mode`: `single_capture` or `live_sample`
- `sample_index` for live samples

## Local VLM Runtime

The first local runtime target is Ollama.

Install/pull the initial model pair on the GB10:

```powershell
ollama pull gemma3:4b
ollama pull gemma3:12b
```

Then start Ollama before running the GB10 host. The host defaults are:

```text
OLLAMA_BASE_URL=http://127.0.0.1:11434
GB10_FAST_MODEL=gemma3:4b
GB10_DETAIL_MODEL=gemma3:12b
OLLAMA_TIMEOUT_SECONDS=90
```

To use different local models:

```powershell
$env:GB10_FAST_MODEL='llama3.2-vision:11b'
$env:GB10_DETAIL_MODEL='gemma3:12b'
python .\gb10-host\mock_server.py
```

`GET /models` reports whether each configured model is available, not pulled, or whether Ollama is unreachable.
