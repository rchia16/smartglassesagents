#!/usr/bin/env python3
"""GB10 local host API for the Android experiment MVP.

The host keeps the same simple HTTP contract used by the Android app, but it now
tries local Ollama VLM inference before falling back to deterministic mock
responses. It intentionally uses only the Python standard library.
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
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import urlparse


HOST = os.environ.get("GB10_HOST", "0.0.0.0")
PORT = int(os.environ.get("GB10_PORT", "8765"))
OLLAMA_BASE_URL = os.environ.get("OLLAMA_BASE_URL", "http://127.0.0.1:11434").rstrip("/")
OLLAMA_TIMEOUT_SECONDS = float(os.environ.get("OLLAMA_TIMEOUT_SECONDS", "90"))
FAST_MODEL = os.environ.get("GB10_FAST_MODEL", "gemma3:4b")
DETAIL_MODEL = os.environ.get("GB10_DETAIL_MODEL", "gemma3:12b")
PROMPT_VERSION = "local-vlm-v1"
RUNS: list[dict[str, Any]] = []


AGENT_PROFILES = [
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


class Gb10Handler(BaseHTTPRequestHandler):
    server_version = "GB10Host/0.2"

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/health":
            self._send_json(
                {
                    "status": "ok",
                    "service": "gb10-local-host",
                    "ollama_base_url": OLLAMA_BASE_URL,
                    "ollama_reachable": ollama_is_reachable(),
                }
            )
        elif path == "/models":
            self._send_json({"models": describe_models()})
        elif path == "/experiment_runs":
            self._send_json({"runs": RUNS})
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
        capture_metadata = body.get("capture_metadata", {})

        if not image_base64:
            self._send_json({"error": "image_base64 is required"}, status=400)
            return

        run_id = str(uuid.uuid4())
        results = [
            run_agent_profile(profile, task_type, prompt, voice_transcript, image_base64)
            for profile in AGENT_PROFILES
        ]
        selected = select_speech_result(results)
        speech_text = make_speech_text(selected, task_type)

        response = {
            "run_id": run_id,
            "task_type": task_type,
            "selected_speech_agent": selected["agent_profile"],
            "results": results,
            "speech_text": speech_text,
        }

        RUNS.append(
            {
                "run_id": run_id,
                "session_id": session_id,
                "task_type": task_type,
                "capture_source": capture_metadata.get("source", "unknown"),
                "captured_at_ms": capture_metadata.get("captured_at_ms"),
                "capture_mode": capture_metadata.get("mode", "single_capture"),
                "sample_index": capture_metadata.get("sample_index"),
                "voice_transcript": voice_transcript,
                "image_bytes_base64": len(image_base64),
                "selected_speech_agent": selected["agent_profile"],
                "speech_text": speech_text,
                "created_at_ms": int(time.time() * 1000),
            }
        )

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
            "selected_speech_agent",
            "speech_text",
            "created_at_ms",
        ]
        writer = csv.DictWriter(output, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(RUNS)
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
    prompt = build_prompt(agent_profile, task_type, user_prompt, voice_transcript)

    try:
        answer = ollama_generate(model_id=model_id, prompt=prompt, image_base64=image_base64)
        latency_ms = int((time.perf_counter() - started) * 1000)
        return {
            "agent_profile": agent_profile,
            "model_id": model_id,
            "runtime": "ollama",
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
            "observations": ["Ollama inference was unavailable; returned fallback text."],
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


def ollama_is_reachable() -> bool:
    try:
        ollama_request("/api/tags", timeout=1.5)
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
    installed = installed_ollama_models()
    reachable = bool(installed)
    models = []
    for profile in AGENT_PROFILES:
        model_id = profile["model_id"]
        if model_id in installed:
            status = "available"
        elif reachable:
            status = "not_pulled"
        else:
            status = "ollama_unreachable"
        models.append({**profile, "status": status, "prompt_version": PROMPT_VERSION})
    return models


def select_speech_result(results: list[dict[str, Any]]) -> dict[str, Any]:
    for result in results:
        if result.get("runtime") == "ollama":
            return result
    return results[0]


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
        uncertainties.append("Ollama was unavailable or the selected model was not ready.")
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
    print(f"Ollama base URL: {OLLAMA_BASE_URL}")
    print(f"Fast model: {FAST_MODEL}")
    print(f"Detail model: {DETAIL_MODEL}")
    server.serve_forever()


if __name__ == "__main__":
    main()
