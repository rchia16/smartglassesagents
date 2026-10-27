# GB10 Local Host

This folder contains the local host milestone for the Smart Glasses Agents experiment.

The server provides the API shape that the Android app expects. It can run one selected model profile per image request and falls back to deterministic mock responses when the selected runtime is unavailable.

It supports Ollama profiles plus OpenAI-compatible vLLM profiles for Nemotron and Qwen. Android does not need separate provider code paths; it loads `/models`, selects one `agent_profile`, and sends that profile in `/analyze_image`.

## Run

```powershell
python .\gb10-host\mock_server.py
```

The server listens on `0.0.0.0:8765` by default.

On the GB10 (Linux), run it from this directory with:

```bash
apptainer exec ~/containers/vllm.sif python mock_server.py
```

For the Android emulator, use:

```text
http://10.0.2.2:8765
```

For a physical phone on the same network, use the GB10 machine's LAN IP:

```text
http://<gb10-lan-ip>:8765
```

Do not use `10.0.2.2` from a physical phone. That address is an Android emulator alias for the host machine. On the Linux GB10, check its LAN address with `hostname -I`. On a Windows host, use:

```powershell
ipconfig
```

Then enter that address in the app, for example:

```text
http://192.168.0.194:8765
```

If Tailscale Serve is configured as described below, a phone connected to the
same tailnet can instead enter this HTTPS base URL in the app:

```text
https://promaxgb10-4e2b.tail06a565.ts.net:9443
```

The app appends `/health`, `/models`, and other API paths, so enter the base URL
without `/health` or `/v1`. Confirm the URL serves this API with
`curl https://promaxgb10-4e2b.tail06a565.ts.net:9443/health`.

## Keep Qwen and the API running on the GB10 (systemd)

These **user services** reproduce the working Apptainer commands. Run the
following on the GB10 as the Linux user who owns `~/containers/vllm.sif` and
the model cache. Run it from the repository's `gb10-host` directory. The
container must already provide `fix-nvidia-libpath` and
`start-vllm-qwen25-vl`, as in the image used for the working manual command.
The checked-in `vllm.def` currently defines a differently named Qwen helper,
so building that file as-is does not provide these two commands.

```bash
cd /path/to/SmartGlassesAgents/gb10-host
GB10_REPO_DIR="$(pwd -P)"
GB10_APPTAINER="$(command -v apptainer)"
test -f "$HOME/containers/vllm.sif"
test -f "$GB10_REPO_DIR/mock_server.py"
mkdir -p "$HOME/.config/systemd/user"

cat > "$HOME/.config/systemd/user/gb10-qwen.service" <<EOF
[Unit]
Description=GB10 Qwen3 VL vLLM server

[Service]
Type=simple
ExecStart=$GB10_APPTAINER exec --nv --bind $HOME:$HOME --env VLLM_PORT=8001 --env VLLM_HOST=127.0.0.1 --env MODEL_ID=Qwen/Qwen3-VL-8B-Instruct --env VLLM_MAX_MODEL_LEN=8192 --env VLLM_GPU_MEMORY_UTILIZATION=0.50 $HOME/containers/vllm.sif fix-nvidia-libpath start-vllm-qwen25-vl
Restart=on-failure
RestartSec=10

[Install]
WantedBy=default.target
EOF

cat > "$HOME/.config/systemd/user/gb10-api.service" <<EOF
[Unit]
Description=GB10 Smart Glasses Agents API
Wants=gb10-qwen.service
After=gb10-qwen.service

[Service]
Type=simple
WorkingDirectory=$GB10_REPO_DIR
ExecStart=$GB10_APPTAINER exec --bind $GB10_REPO_DIR:$GB10_REPO_DIR --env GB10_HOST=127.0.0.1 --env GB10_ENABLE_OLLAMA=0 --env GB10_ENABLE_VLLM=0 --env GB10_ENABLE_QWEN=1 --env QWEN_BASE_URL=http://127.0.0.1:8001/v1 --env QWEN_MODEL=Qwen/Qwen3-VL-8B-Instruct $HOME/containers/vllm.sif python mock_server.py
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
systemctl --user enable --now gb10-qwen.service gb10-api.service
sudo loginctl enable-linger "$(id -un)"
```

`enable --now` starts both services and enables them at login. Linger keeps
the user's systemd manager running after logout and starts it at boot. The API
can start before Qwen has finished loading; `/models` will show Qwen as
unreachable until the model is ready. These commands assume the repository
path has no spaces. If you move the repository or replace the `.sif`, update
the paths in the unit files and reload systemd.

This configuration binds both ports to `127.0.0.1`; the phone reaches the API
through Tailscale Serve. For direct LAN access, change `GB10_HOST` in the API
unit to `0.0.0.0` and restrict access to a trusted network.

Check status, logs, and both endpoints:

```bash
systemctl --user status gb10-qwen.service gb10-api.service
journalctl --user -u gb10-qwen.service -u gb10-api.service -f
curl -f http://127.0.0.1:8001/health
curl -f http://127.0.0.1:8765/health
curl -s http://127.0.0.1:8765/models
```

To stop now but keep the boot configuration, use:

```bash
systemctl --user stop gb10-api.service gb10-qwen.service
```

To disable both at boot and stop them now, use:

```bash
systemctl --user disable --now gb10-api.service gb10-qwen.service
```

To restart after changing settings or replacing the container, and to clear
a failed/start-limit state if needed:

```bash
systemctl --user daemon-reload
systemctl --user reset-failed gb10-qwen.service gb10-api.service
systemctl --user restart gb10-qwen.service gb10-api.service
```

`reset-failed` clears systemd's failure counter; it does not erase model
caches or experiment runs. To remove these services entirely, disable and stop
them, delete the two files under `~/.config/systemd/user/`, then run
`systemctl --user daemon-reload`. Only disable linger if this user has no
other services that need to run after logout.

### Tailscale HTTPS name

If port `9443` is not already mapped to the GB10 API, configure Tailscale
Serve on the GB10. This mapping persists in Tailscale's configuration; it does
not need a separate systemd unit. The phone must be connected to the same
tailnet and allowed by its access rules.

```bash
tailscale serve status
sudo tailscale serve --bg --https=9443 http://127.0.0.1:8765
tailscale serve status
curl -f https://promaxgb10-4e2b.tail06a565.ts.net:9443/health
```

If `tailscale serve status` already shows port `9443` forwarding to
`http://127.0.0.1:8765`, keep the existing mapping. To remove only this
mapping, run `sudo tailscale serve --https=9443 off`. Do not use
`tailscale serve reset` unless you intend to remove every Serve mapping on
this GB10. Use Serve, not Funnel, so this unauthenticated API remains inside
the tailnet. [Tailscale Serve CLI](https://tailscale.com/docs/reference/tailscale-cli/serve)

## Endpoints

- `GET /health`
- `GET /models`
- `POST /analyze_image`
- `GET /experiment_runs`
- `GET /export`

The implementation does not store raw audio. It stores experiment run records on disk as JSONL, including the optional `voice_transcript` sent by the Android app. Raw images are not stored unless explicitly enabled.

`POST /analyze_image` accepts:

- `session_id`
- `task_type`
- `prompt`
- `selected_agent_profile`
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

When using the Apptainer image from [vllm.def](D:/Ray/StudioProjects/SmartGlassesAgents/gb10-host/vllm.def:1), the intended Ollama model names are the local aliases created by the helper scripts:

```text
OLLAMA_BASE_URL=http://127.0.0.1:11434
GB10_ENABLE_OLLAMA=1
GB10_FAST_MODEL=local_gemma_fast
GB10_DETAIL_MODEL=local_gemma_detail
OLLAMA_TIMEOUT_SECONDS=90
```

Those aliases come from:

```text
local_gemma_fast   -> GEMMA_FAST_MODEL, default gemma3:12b
local_gemma_detail -> GEMMA_DETAIL_MODEL, default gemma3:27b
local_llama_fast   -> llama3.2-vision:11b
```

If `local_gemma_detail` fails to load on GB10, the usual cause is that the alias was created from `gemma3:27b` and exceeds the practical Ollama resource budget. In that case, recreate the detail alias from a smaller model, for example `gemma3:12b`.

To build the Ollama aliases in the container:

```powershell
pull-local-gemma-fast
pull-local-gemma-detail
```

To rebuild the detail alias against a smaller base model:

```powershell
$env:GEMMA_DETAIL_MODEL='gemma3:12b'
pull-local-gemma-detail
```

To use direct Ollama model names instead of the local aliases:

```powershell
$env:GB10_FAST_MODEL='gemma3:12b'
$env:GB10_DETAIL_MODEL='gemma3:12b'
python .\gb10-host\mock_server.py
```

`GET /models` reports whether each configured model is available, not pulled, or whether Ollama is unreachable.

## Experiment Logging

Runs are persisted on the GB10 as JSONL:

```text
GB10_DATA_DIR=gb10-host/data
GB10_RUNS_JSONL=gb10-host/data/experiment_runs.jsonl
GB10_STORE_RAW_IMAGES=0
GB10_RAW_IMAGE_DIR=gb10-host/data/raw_images
```

Each run record includes:

- run ID and session ID
- task type, prompt, optional voice transcript
- selected agent profile, model ID, runtime, prompt version
- capture metadata and base64 payload size
- latency, confidence, answer, speech text, observations, uncertainties

`GET /experiment_runs` returns the persisted JSON records. `GET /export` returns a CSV summary derived from the same persisted file.

To store raw images for a controlled experiment, explicitly enable it on the GB10:

```powershell
$env:GB10_STORE_RAW_IMAGES='1'
python .\gb10-host\mock_server.py
```

Keep raw image storage disabled unless the experiment protocol requires it.

## Local vLLM Runtimes

The host includes vLLM profiles for local detail models running on the GB10. It assumes each server exposes an OpenAI-compatible API:

```text
GET  /v1/models
POST /v1/chat/completions
```

Default Nemotron configuration:

```text
GB10_ENABLE_VLLM=1
VLLM_BASE_URL=http://127.0.0.1:8000/v1
VLLM_MODEL=nvidia/NVIDIA-Nemotron-Nano-12B-v2-VL-BF16
VLLM_TIMEOUT_SECONDS=120
VLLM_MAX_TOKENS=512
```

Default Qwen configuration:

```text
GB10_ENABLE_QWEN=1
QWEN_BASE_URL=http://127.0.0.1:8001/v1
QWEN_MODEL=Qwen/Qwen2.5-VL-7B-Instruct
QWEN_TIMEOUT_SECONDS=120
QWEN_MAX_TOKENS=512
```

`local_qwen_detail` is shown in `/models` by default. If the Qwen server is not running, its status is `server_unreachable`, but Android can still display it as a selectable profile.

To run only Qwen from the Android app, start your Qwen vLLM server and launch the GB10 host with the other profiles disabled:

```powershell
$env:GB10_ENABLE_OLLAMA='0'
$env:GB10_ENABLE_VLLM='0'
$env:GB10_ENABLE_QWEN='1'
$env:QWEN_BASE_URL='http://127.0.0.1:8001/v1'
$env:QWEN_MODEL='Qwen/Qwen3-VL-8B-Instruct'
python .\gb10-host\mock_server.py
```

If the local Qwen server uses the primary vLLM port instead, point Qwen at that endpoint:

```powershell
$env:QWEN_BASE_URL='http://127.0.0.1:8000/v1'
python .\gb10-host\mock_server.py
```

If a local server requires a bearer token, keep it on the GB10:

```powershell
$env:VLLM_API_KEY='<local-token>'
$env:QWEN_API_KEY='<local-token>'
python .\gb10-host\mock_server.py
```

`GET /models` reports each profile status separately. `POST /analyze_image` runs only the requested `selected_agent_profile`.
