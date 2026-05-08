#!/usr/bin/env python3
"""GB10 local host API for the Android experiment MVP.

The host keeps the same simple HTTP contract used by the Android app, but it now
tries local Ollama VLM inference and one or more vLLM VLM backends before falling
back to deterministic mock responses.

Supported profiles:
- local_gemma_fast      via Ollama
- local_gemma_detail    via Ollama
- local_nemotron_detail via vLLM
- local_qwen_detail     via vLLM

It intentionally uses only the Python standard library.
"""

from __future__ import annotations

import csv
import io
import json
import os
import re
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import urlparse


def first_env(names: tuple[str, ...], default: str = "") -> str:
    for name in names:
        value = os.environ.get(name)
        if value is not None:
            return value
    return default


def env_bool(names: tuple[str, ...], default: str) -> bool:
    return first_env(names, default).lower() in {"1", "true", "yes", "on"}


def default_vllm_health_url(base_url: str) -> str:
    if base_url.endswith("/v1"):
        return f"{base_url[:-3]}/health"
    return f"{base_url}/health"


def default_vllm_agent_profile(model_id: str) -> str:
    if "qwen" in model_id.lower():
        return "local_qwen_detail"
    if "nemotron" in model_id.lower():
        return "local_nemotron_detail"
    return "local_vllm_detail"


HOST = os.environ.get("GB10_HOST", "0.0.0.0")
PORT = int(os.environ.get("GB10_PORT", "8765"))

ENABLE_OLLAMA = env_bool(("GB10_ENABLE_OLLAMA",), "1")
OLLAMA_BASE_URL = os.environ.get("OLLAMA_BASE_URL", "http://127.0.0.1:11434").rstrip("/")
OLLAMA_TIMEOUT_SECONDS = float(os.environ.get("OLLAMA_TIMEOUT_SECONDS", "90"))
FAST_MODEL = os.environ.get("GB10_FAST_MODEL", "local_gemma_fast")
DETAIL_MODEL = os.environ.get("GB10_DETAIL_MODEL", "local_gemma_detail")

# Primary vLLM profile. Defaults to Nemotron.
ENABLE_VLLM = env_bool(("GB10_ENABLE_VLLM", "GB10_ENABLE_NEMOTRON"), "1")
VLLM_BASE_URL = first_env(
    ("VLLM_BASE_URL", "BASE_URL", "NEMOTRON_BASE_URL"),
    "http://127.0.0.1:8000/v1",
).rstrip("/")
VLLM_HEALTH_URL = first_env(
    ("VLLM_HEALTH_URL", "NEMOTRON_HEALTH_URL"),
    default_vllm_health_url(VLLM_BASE_URL),
).rstrip("/")
VLLM_MODEL = first_env(
    ("VLLM_MODEL", "VLLM_MODEL_ID", "MODEL_ID", "NEMOTRON_MODEL"),
    "nvidia/NVIDIA-Nemotron-Nano-12B-v2-VL-BF16",
)
VLLM_AGENT_PROFILE = first_env(
    ("VLLM_AGENT_PROFILE", "NEMOTRON_AGENT_PROFILE"),
    default_vllm_agent_profile(VLLM_MODEL),
)
VLLM_API_KEY = first_env(("VLLM_API_KEY", "NEMOTRON_API_KEY"), "")
VLLM_TIMEOUT_SECONDS = float(first_env(("VLLM_TIMEOUT_SECONDS", "NEMOTRON_TIMEOUT_SECONDS"), "120"))
VLLM_MAX_TOKENS = int(first_env(("VLLM_MAX_TOKENS", "NEMOTRON_MAX_TOKENS"), "512"))
VLLM_TEMPERATURE = float(first_env(("VLLM_TEMPERATURE", "NEMOTRON_TEMPERATURE"), "0"))

# Qwen is exposed as a selectable profile by default. If no Qwen server is
# running, /models reports it as server_unreachable instead of hiding it.
ENABLE_QWEN = env_bool(("GB10_ENABLE_QWEN", "ENABLE_QWEN"), "1")
QWEN_BASE_URL = first_env(("QWEN_BASE_URL",), "http://127.0.0.1:8001/v1").rstrip("/")
QWEN_HEALTH_URL = first_env(("QWEN_HEALTH_URL",), default_vllm_health_url(QWEN_BASE_URL)).rstrip("/")
QWEN_MODEL = first_env(("QWEN_MODEL", "QWEN_MODEL_ID"), "Qwen/Qwen2.5-VL-72B-Instruct")
QWEN_AGENT_PROFILE = first_env(("QWEN_AGENT_PROFILE",), "local_qwen_detail")
QWEN_API_KEY = first_env(("QWEN_API_KEY",), VLLM_API_KEY)
QWEN_TIMEOUT_SECONDS = float(first_env(("QWEN_TIMEOUT_SECONDS",), str(VLLM_TIMEOUT_SECONDS)))
QWEN_MAX_TOKENS = int(first_env(("QWEN_MAX_TOKENS",), str(VLLM_MAX_TOKENS)))
QWEN_TEMPERATURE = float(first_env(("QWEN_TEMPERATURE",), str(VLLM_TEMPERATURE)))

DATA_DIR = Path(os.environ.get("GB10_DATA_DIR", Path(__file__).resolve().parent / "data"))
RUNS_JSONL_PATH = Path(os.environ.get("GB10_RUNS_JSONL", DATA_DIR / "experiment_runs.jsonl"))
STORE_RAW_IMAGES = env_bool(("GB10_STORE_RAW_IMAGES",), "0")
RAW_IMAGE_DIR = Path(os.environ.get("GB10_RAW_IMAGE_DIR", DATA_DIR / "raw_images"))

PROMPT_VERSION = "local-vlm-v1"
RUNS: list[dict[str, Any]] = []

AGENT_PROFILES: list[dict[str, str]] = []

if ENABLE_OLLAMA:
    AGENT_PROFILES.extend(
        [
            {
                "agent_profile": "local_gemma_fast",
                "model_id": FAST_MODEL,
                "runtime": "ollama",
                "purpose": "Fast local still-image and sampled-frame analysis",
            },
            {
                "agent_profile": "local_gemma_detail",
                "model_id": DETAIL_MODEL,
                "runtime": "ollama",
                "purpose": "Fine-detail OCR, small-object localization, and uncertainty reporting",
            },
        ]
    )

if ENABLE_VLLM:
    AGENT_PROFILES.append(
        {
            "agent_profile": VLLM_AGENT_PROFILE,
            "model_id": VLLM_MODEL,
            "runtime": "vllm",
            "base_url": VLLM_BASE_URL,
            "health_url": VLLM_HEALTH_URL,
            "api_key": VLLM_API_KEY,
            "timeout_seconds": str(VLLM_TIMEOUT_SECONDS),
            "max_tokens": str(VLLM_MAX_TOKENS),
            "temperature": str(VLLM_TEMPERATURE),
            "purpose": "Nemotron/vLLM detail-focused OCR, small-object localization, and spatial benchmark",
        }
    )

if ENABLE_QWEN:
    AGENT_PROFILES.append(
        {
            "agent_profile": QWEN_AGENT_PROFILE,
            "model_id": QWEN_MODEL,
            "runtime": "vllm",
            "base_url": QWEN_BASE_URL,
            "health_url": QWEN_HEALTH_URL,
            "api_key": QWEN_API_KEY,
            "timeout_seconds": str(QWEN_TIMEOUT_SECONDS),
            "max_tokens": str(QWEN_MAX_TOKENS),
            "temperature": str(QWEN_TEMPERATURE),
            "purpose": "Qwen2.5-VL detail benchmark alternative",
        }
    )


class Gb10Handler(BaseHTTPRequestHandler):
    server_version = "GB10Host/0.5"

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/health":
            self._send_json(
                {
                    "status": "ok",
                    "service": "gb10-local-host",
                    "ollama_enabled": ENABLE_OLLAMA,
                    "ollama_base_url": OLLAMA_BASE_URL,
                    "ollama_reachable": ollama_is_reachable() if ENABLE_OLLAMA else False,
                    "vllm_enabled": ENABLE_VLLM,
                    "vllm_base_url": VLLM_BASE_URL if ENABLE_VLLM else None,
                    "vllm_health_url": VLLM_HEALTH_URL if ENABLE_VLLM else None,
                    "vllm_model": VLLM_MODEL if ENABLE_VLLM else None,
                    "vllm_agent_profile": VLLM_AGENT_PROFILE if ENABLE_VLLM else None,
                    "vllm_reachable": endpoint_is_reachable(VLLM_HEALTH_URL, VLLM_API_KEY)
                    if ENABLE_VLLM
                    else False,
                    "qwen_enabled": ENABLE_QWEN,
                    "qwen_base_url": QWEN_BASE_URL if ENABLE_QWEN else None,
                    "qwen_health_url": QWEN_HEALTH_URL if ENABLE_QWEN else None,
                    "qwen_model": QWEN_MODEL if ENABLE_QWEN else None,
                    "qwen_agent_profile": QWEN_AGENT_PROFILE if ENABLE_QWEN else None,
                    "qwen_reachable": endpoint_is_reachable(QWEN_HEALTH_URL, QWEN_API_KEY)
                    if ENABLE_QWEN
                    else False,
                }
            )
        elif path == "/models":
            self._send_json({"models": describe_models()})
        elif path == "/experiment_runs":
            self._send_json({"runs": load_persisted_runs()})
        elif path == "/export":
            self._send_export()
        else:
            self._send_json({"error": f"Unknown endpoint: {path}"}, status=404)

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        if path != "/analyze_image":
            self._send_json({"error": f"Unknown endpoint: {path}"}, status=404)
            return

        body = self._read_json()
        task_type = body.get("task_type", "general_query")
        prompt = body.get("prompt", "")
        voice_transcript = str(body.get("voice_transcript", "")).strip()
        session_id = body.get("session_id", "")
        image_base64 = body.get("image_base64", "")
        selected_agent_profile = str(body.get("selected_agent_profile", "")).strip()
        capture_metadata = body.get("capture_metadata", {})

        if not image_base64:
            self._send_json({"error": "image_base64 is required"}, status=400)
            return
        if not AGENT_PROFILES:
            self._send_json({"error": "No agent profiles are enabled on the GB10 host"}, status=503)
            return

        selected_profile = resolve_agent_profile(selected_agent_profile)
        if selected_profile is None:
            available_profiles = [profile["agent_profile"] for profile in AGENT_PROFILES]
            self._send_json(
                {
                    "error": f"Unknown selected_agent_profile: {selected_agent_profile}",
                    "available_profiles": available_profiles,
                },
                status=400,
            )
            return

        run_id = str(uuid.uuid4())
        result = run_agent_profile(selected_profile, task_type, prompt, voice_transcript, image_base64)
        results = [result]
        selected = result
        speech_text = make_speech_text(selected, task_type)

        response = {
            "run_id": run_id,
            "task_type": task_type,
            "selected_speech_agent": selected["agent_profile"],
            "results": results,
            "speech_text": speech_text,
        }

        run_record = build_run_record(
            run_id=run_id,
            session_id=session_id,
            task_type=task_type,
            user_prompt=prompt,
            voice_transcript=voice_transcript,
            capture_metadata=capture_metadata,
            image_base64=image_base64,
            image_mime_type=str(body.get("image_mime_type", "")),
            requested_profile=selected_profile,
            result=selected,
            response=response,
        )
        append_run_record(run_record)

        self._send_json(response)

    def log_message(self, format: str, *args: Any) -> None:
        print(f"{self.address_string()} - {format % args}")

    def _read_json(self) -> dict[str, Any]:
        content_length = int(self.headers.get("Content-Length", "0"))
        raw_body = self.rfile.read(content_length).decode("utf-8")
        if not raw_body:
            return {}
        try:
            return json.loads(raw_body)
        except json.JSONDecodeError as exc:
            self._send_json({"error": f"Invalid JSON: {exc}"}, status=400)
            return {}

    def _send_json(self, payload: dict[str, Any], status: int = 200) -> None:
        encoded = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def _send_export(self) -> None:
        output = io.StringIO()
        fieldnames = [
            "run_id",
            "session_id",
            "task_type",
            "capture_source",
            "captured_at_ms",
            "capture_mode",
            "sample_index",
            "voice_transcript",
            "image_bytes_base64",
            "raw_image_stored",
            "raw_image_path",
            "requested_agent_profile",
            "selected_speech_agent",
            "model_id",
            "runtime",
            "prompt_version",
            "latency_ms",
            "confidence",
            "speech_text",
            "created_at_ms",
        ]
        writer = csv.DictWriter(output, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(export_rows(load_persisted_runs(), fieldnames))
        encoded = output.getvalue().encode("utf-8")

        self.send_response(200)
        self.send_header("Content-Type", "text/csv")
        self.send_header("Content-Disposition", "attachment; filename=experiment_runs.csv")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


def run_agent_profile(
    profile: dict[str, str],
    task_type: str,
    user_prompt: str,
    voice_transcript: str,
    image_base64: str,
) -> dict[str, Any]:
    started = time.perf_counter()
    agent_profile = profile["agent_profile"]
    model_id = profile["model_id"]
    runtime = profile["runtime"]
    prompt = build_prompt(agent_profile, task_type, user_prompt, voice_transcript)

    try:
        if runtime == "ollama":
            answer = ollama_generate(model_id=model_id, prompt=prompt, image_base64=image_base64)
        elif runtime == "vllm":
            answer = vllm_generate(profile=profile, prompt=prompt, image_base64=image_base64)
        else:
            raise RuntimeError(f"Unsupported runtime: {runtime}")
        latency_ms = int((time.perf_counter() - started) * 1000)
        return {
            "agent_profile": agent_profile,
            "model_id": model_id,
            "runtime": runtime,
            "prompt_version": PROMPT_VERSION,
            "answer": answer,
            "observations": extract_observations(answer),
            "locations": [],
            "confidence": 0.65,
            "uncertainties": task_uncertainties(task_type, used_fallback=False),
            "latency_ms": latency_ms,
        }
    except Exception as exc:
        latency_ms = int((time.perf_counter() - started) * 1000) + 24
        answer = mock_answer(agent_profile, task_type, user_prompt, voice_transcript, image_base64, str(exc))
        return {
            "agent_profile": agent_profile,
            "model_id": model_id,
            "runtime": "mock",
            "prompt_version": "mock-fallback-v1",
            "answer": answer,
            "observations": [f"{runtime} inference was unavailable; returned fallback text."],
            "locations": [
                {
                    "label": "primary visual area",
                    "position": "center",
                    "confidence": 0.5,
                }
            ],
            "confidence": 0.5,
            "uncertainties": task_uncertainties(task_type, used_fallback=True),
            "latency_ms": latency_ms,
        }


def build_run_record(
    run_id: str,
    session_id: str,
    task_type: str,
    user_prompt: str,
    voice_transcript: str,
    capture_metadata: dict[str, Any],
    image_base64: str,
    image_mime_type: str,
    requested_profile: dict[str, str],
    result: dict[str, Any],
    response: dict[str, Any],
) -> dict[str, Any]:
    created_at_ms = int(time.time() * 1000)
    raw_image_path = ""
    if STORE_RAW_IMAGES:
        try:
            raw_image_path = persist_raw_image(run_id, image_base64, image_mime_type)
        except Exception:
            raw_image_path = ""
    return {
        "run_id": run_id,
        "session_id": session_id,
        "task_type": task_type,
        "prompt": user_prompt,
        "capture_source": capture_metadata.get("source", "unknown"),
        "captured_at_ms": capture_metadata.get("captured_at_ms"),
        "capture_mode": capture_metadata.get("mode", "single_capture"),
        "sample_index": capture_metadata.get("sample_index"),
        "voice_transcript": voice_transcript,
        "image_mime_type": image_mime_type,
        "image_bytes_base64": len(image_base64),
        "raw_image_stored": bool(raw_image_path),
        "raw_image_path": raw_image_path,
        "requested_agent_profile": requested_profile["agent_profile"],
        "selected_speech_agent": result["agent_profile"],
        "model_id": result.get("model_id", ""),
        "runtime": result.get("runtime", ""),
        "prompt_version": result.get("prompt_version", ""),
        "latency_ms": result.get("latency_ms", 0),
        "confidence": result.get("confidence", 0.0),
        "speech_text": response.get("speech_text", ""),
        "result": result,
        "response": response,
        "created_at_ms": created_at_ms,
    }


def append_run_record(record: dict[str, Any]) -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    RUNS.append(record)
    with RUNS_JSONL_PATH.open("a", encoding="utf-8") as output:
        output.write(json.dumps(record, ensure_ascii=True, separators=(",", ":")))
        output.write("\n")


def load_persisted_runs() -> list[dict[str, Any]]:
    if not RUNS_JSONL_PATH.exists():
        return list(RUNS)

    runs: list[dict[str, Any]] = []
    with RUNS_JSONL_PATH.open("r", encoding="utf-8") as input_file:
        for line in input_file:
            cleaned = line.strip()
            if not cleaned:
                continue
            try:
                loaded = json.loads(cleaned)
            except json.JSONDecodeError:
                continue
            if isinstance(loaded, dict):
                runs.append(loaded)
    return runs


def export_rows(runs: list[dict[str, Any]], fieldnames: list[str]) -> list[dict[str, Any]]:
    return [
        {field: run.get(field, "") for field in fieldnames}
        for run in runs
    ]


def persist_raw_image(run_id: str, image_base64: str, image_mime_type: str) -> str:
    import base64

    extension = {
        "image/jpeg": ".jpg",
        "image/jpg": ".jpg",
        "image/png": ".png",
        "image/webp": ".webp",
    }.get(image_mime_type.lower(), ".bin")
    RAW_IMAGE_DIR.mkdir(parents=True, exist_ok=True)
    path = RAW_IMAGE_DIR / f"{run_id}{extension}"
    with path.open("wb") as output:
        output.write(base64.b64decode(image_base64, validate=False))
    return str(path)


def build_prompt(agent_profile: str, task_type: str, user_prompt: str, voice_transcript: str) -> str:
    shared = (
        "You are assisting a smart-glasses experiment. Be concise and practical. "
        "Report uncertainty when fine details are unclear. Do not identify people by name. "
        "For faces, only count, locate, and give non-identifying visible descriptions."
    )
    task_instruction = {
        "board_text": (
            "Task: read visible board text. Preserve wording when legible. "
            "Mention unreadable regions."
        ),
        "tabletop_items": (
            "Task: identify small items on the table and describe approximate positions "
            "relative to the camera and table."
        ),
        "faces": (
            "Task: count visible faces and describe approximate positions only. "
            "Do not identify, verify, or name anyone."
        ),
        "general_query": "Task: answer the visual query with uncertainty.",
    }.get(task_type, "Task: answer the visual query with uncertainty.")

    profile_instruction = (
        "Fast profile: give the shortest useful answer."
        if agent_profile.endswith("_fast")
        else "Detail profile: inspect small text, small objects, spatial layout, and uncertainty."
    )
    spoken = (
        f"Spoken user query transcript: {voice_transcript}"
        if voice_transcript
        else "Spoken user query transcript: none"
    )
    return "\n".join([shared, task_instruction, profile_instruction, f"User prompt: {user_prompt}", spoken])


def ollama_generate(model_id: str, prompt: str, image_base64: str) -> str:
    payload = {
        "model": model_id,
        "prompt": prompt,
        "images": [image_base64],
        "stream": False,
        "options": {
            "temperature": 0.1,
        },
    }
    response = ollama_request("/api/generate", payload=payload, timeout=OLLAMA_TIMEOUT_SECONDS)
    answer = str(response.get("response", "")).strip()
    if not answer:
        raise RuntimeError(f"Ollama model {model_id} returned an empty response")
    return answer


def ollama_request(path: str, payload: dict[str, Any] | None = None, timeout: float = 5.0) -> dict[str, Any]:
    url = f"{OLLAMA_BASE_URL}{path}"
    data = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method="POST" if payload else "GET")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def vllm_generate(profile: dict[str, str], prompt: str, image_base64: str) -> str:
    payload = {
        "model": profile["model_id"],
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": f"data:image/jpeg;base64,{image_base64}",
                        },
                    },
                ],
            }
        ],
        "temperature": float(profile.get("temperature", VLLM_TEMPERATURE)),
        "max_tokens": int(float(profile.get("max_tokens", VLLM_MAX_TOKENS))),
        "stream": False,
    }
    response = vllm_request(
        f"{profile['base_url']}/chat/completions",
        payload=payload,
        timeout=float(profile.get("timeout_seconds", VLLM_TIMEOUT_SECONDS)),
        api_key=profile.get("api_key", ""),
    )
    choices = response.get("choices", [])
    if not choices:
        raise RuntimeError(f"vLLM model {profile['model_id']} returned no choices")
    message = choices[0].get("message", {})
    answer = extract_chat_message_text(message.get("content", ""))
    if not answer:
        raise RuntimeError(f"vLLM model {profile['model_id']} returned an empty response")
    return answer


def vllm_request(
    url: str,
    payload: dict[str, Any] | None = None,
    timeout: float = 5.0,
    api_key: str = "",
) -> dict[str, Any]:
    data = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"

    effective_api_key = api_key or VLLM_API_KEY
    if effective_api_key:
        headers["Authorization"] = f"Bearer {effective_api_key}"

    request = urllib.request.Request(url, data=data, headers=headers, method="POST" if payload else "GET")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def extract_chat_message_text(content: Any) -> str:
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts = []
        for item in content:
            if isinstance(item, dict):
                text = item.get("text") or item.get("content")
                if text:
                    parts.append(str(text))
            elif item:
                parts.append(str(item))
        return "\n".join(parts).strip()
    if content is None:
        return ""
    return str(content).strip()


def ollama_is_reachable() -> bool:
    try:
        ollama_request("/api/tags", timeout=1.5)
        return True
    except Exception:
        return False


def endpoint_is_reachable(health_url: str, api_key: str = "") -> bool:
    headers = {"Accept": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    request = urllib.request.Request(health_url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=1.5):
            return True
    except Exception:
        return False


def vllm_is_reachable() -> bool:
    if endpoint_is_reachable(VLLM_HEALTH_URL, VLLM_API_KEY):
        return True
    try:
        vllm_request(f"{VLLM_BASE_URL}/models", timeout=1.5, api_key=VLLM_API_KEY)
        return True
    except Exception:
        return False


def installed_ollama_models() -> set[str]:
    try:
        payload = ollama_request("/api/tags", timeout=2.0)
    except Exception:
        return set()
    return {
        str(model.get("name", ""))
        for model in payload.get("models", [])
        if model.get("name")
    }


def describe_models() -> list[dict[str, Any]]:
    has_ollama_profile = any(profile["runtime"] == "ollama" for profile in AGENT_PROFILES)
    installed = installed_ollama_models() if has_ollama_profile else set()
    ollama_reachable = ollama_is_reachable() if has_ollama_profile else False

    models = []
    for profile in AGENT_PROFILES:
        model_id = profile["model_id"]
        runtime = profile["runtime"]
        if runtime == "ollama":
            if model_id in installed:
                status = "available"
            elif ollama_reachable:
                status = "not_pulled"
            else:
                status = "ollama_unreachable"
        elif runtime == "vllm":
            status = vllm_model_status(profile)
        else:
            status = "unsupported_runtime"
        models.append({**profile, "status": status, "prompt_version": PROMPT_VERSION})
    return models


def resolve_agent_profile(requested_agent_profile: str) -> dict[str, str] | None:
    if requested_agent_profile:
        for profile in AGENT_PROFILES:
            if profile["agent_profile"] == requested_agent_profile:
                return profile
        return None

    for preferred in ("local_nemotron_detail", "local_qwen_detail", "local_gemma_fast", "local_gemma_detail"):
        for profile in AGENT_PROFILES:
            if profile["agent_profile"] == preferred:
                return profile
    return AGENT_PROFILES[0] if AGENT_PROFILES else None


def vllm_model_status(profile: dict[str, str]) -> str:
    try:
        payload = vllm_request(
            f"{profile['base_url']}/models",
            timeout=2.0,
            api_key=profile.get("api_key", ""),
        )
    except urllib.error.HTTPError:
        return "models_endpoint_unavailable"
    except Exception:
        return "server_unreachable"

    model_ids = {
        str(model.get("id", ""))
        for model in payload.get("data", [])
        if isinstance(model, dict) and model.get("id")
    }
    if not model_ids:
        return "server_reachable"
    if profile["model_id"] in model_ids:
        return "available"
    return "model_not_listed"


def make_speech_text(result: dict[str, Any], task_type: str) -> str:
    if result.get("runtime") == "mock":
        return {
            "board_text": "Local model is unavailable. The GB10 host received the board text image.",
            "tabletop_items": "Local model is unavailable. The GB10 host received the tabletop image.",
            "faces": "Local model is unavailable. The GB10 host received the non-identifying face-location image.",
            "general_query": "Local model is unavailable. The GB10 host received the image.",
        }.get(task_type, "Local model is unavailable. The GB10 host received the image.")

    answer = str(result.get("answer", ""))
    cleaned = re.sub(r"\s+", " ", answer.replace("*", "")).strip()
    if not cleaned:
        return "No usable model response was returned."
    if task_type == "faces":
        prefix = "Non-identifying face result: "
    else:
        prefix = ""
    clipped = cleaned[:220].rsplit(" ", 1)[0] if len(cleaned) > 220 else cleaned
    return f"{prefix}{clipped}"


def extract_observations(answer: str) -> list[str]:
    cleaned = re.sub(r"\s+", " ", answer).strip()
    if not cleaned:
        return []
    sentences = re.split(r"(?<=[.!?])\s+", cleaned)
    return [sentence[:180] for sentence in sentences[:3] if sentence]


def task_uncertainties(task_type: str, used_fallback: bool) -> list[str]:
    uncertainties = ["Model output is not independently verified."]
    if task_type == "faces":
        uncertainties.append("Face task is non-identifying by design.")
    if used_fallback:
        uncertainties.append("The selected runtime was unavailable or the selected model was not ready.")
    return uncertainties


def mock_answer(
    agent_profile: str,
    task_type: str,
    prompt: str,
    voice_transcript: str,
    image_base64: str,
    reason: str,
) -> str:
    image_note = f"Received image payload with {len(image_base64)} base64 characters."
    prompt_note = f" Prompt: {prompt[:120]}" if prompt else ""
    voice_note = f" Voice query: {voice_transcript[:120]}" if voice_transcript else ""
    fallback_note = f" Fallback reason: {reason[:160]}"
    if task_type == "board_text":
        return f"{agent_profile} fallback board-text pass. {image_note}{prompt_note}{voice_note}{fallback_note}"
    if task_type == "tabletop_items":
        return f"{agent_profile} fallback tabletop pass. {image_note}{prompt_note}{voice_note}{fallback_note}"
    if task_type == "faces":
        return f"{agent_profile} fallback face count/location pass. {image_note}{prompt_note}{voice_note}{fallback_note}"
    return f"{agent_profile} fallback visual query pass. {image_note}{prompt_note}{voice_note}{fallback_note}"


def main() -> None:
    server = ThreadingHTTPServer((HOST, PORT), Gb10Handler)
    print(f"GB10 local host listening on http://{HOST}:{PORT}")

    if ENABLE_OLLAMA:
        print(f"Ollama base URL: {OLLAMA_BASE_URL}")
        print(f"Fast model: {FAST_MODEL}")
        print(f"Detail model: {DETAIL_MODEL}")
    else:
        print("Ollama profiles disabled")

    if ENABLE_VLLM:
        print(f"vLLM base URL: {VLLM_BASE_URL}")
        print(f"vLLM health URL: {VLLM_HEALTH_URL}")
        print(f"vLLM model: {VLLM_MODEL}")
        print(f"vLLM agent profile: {VLLM_AGENT_PROFILE}")
    else:
        print("vLLM/Nemotron profile disabled")

    if ENABLE_QWEN:
        print(f"Qwen base URL: {QWEN_BASE_URL}")
        print(f"Qwen health URL: {QWEN_HEALTH_URL}")
        print(f"Qwen model: {QWEN_MODEL}")
        print(f"Qwen agent profile: {QWEN_AGENT_PROFILE}")
    else:
        print("Qwen profile disabled")

    server.serve_forever()


if __name__ == "__main__":
    main()
