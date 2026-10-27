import base64
import importlib.util
import json
import struct
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("mock_server.py")
spec = importlib.util.spec_from_file_location("gb10_mock_server", MODULE_PATH)
server_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server_module)


def clip_body() -> dict:
    ftyp = struct.pack(">I4s4s4s", 16, b"ftyp", b"isom", b"isom")
    mvhd = struct.pack(">I4s", 28, b"mvhd") + b"\0" * 12 + struct.pack(">II", 1000, 2000)
    moov = struct.pack(">I4s", 8 + len(mvhd), b"moov") + mvhd
    return {
        "session_id": "test-session",
        "task_type": "general_query",
        "prompt": "What moves?",
        "selected_agent_profile": "local_qwen_detail",
        "video_base64": base64.b64encode(ftyp + moov).decode(),
        "video_mime_type": "video/mp4",
        "capture_metadata": {
            "source": "mock",
            "captured_at_ms": 1,
            "mode": "short_clip",
            "duration_ms": 2000,
            "frame_count": 20,
        },
    }


class ClipTests(unittest.TestCase):
    def test_qwen_receives_native_mp4_video(self):
        profile = {
            "model_id": "Qwen/Qwen3-VL-8B-Instruct",
            "base_url": "http://127.0.0.1:8001/v1",
        }
        with patch.object(server_module, "vllm_request", return_value={
            "choices": [{"message": {"content": "A hand moves an item."}}]
        }) as request:
            answer = server_module.vllm_generate_video(profile, "Describe motion", "YWJj")
        self.assertIn("moves", answer)
        payload = request.call_args.kwargs["payload"]
        video = payload["messages"][0]["content"][1]
        self.assertEqual(video["type"], "video_url")
        self.assertEqual(video["video_url"]["url"], "data:video/mp4;base64,YWJj")

    def test_validation_rejects_bad_clip(self):
        body = clip_body()
        body["capture_metadata"]["duration_ms"] = 5000
        with self.assertRaisesRegex(ValueError, "duration_ms"):
            server_module.validate_clip_request(body)
        body = clip_body()
        body["video_base64"] = "not-base64"
        with self.assertRaisesRegex(ValueError, "video_base64"):
            server_module.validate_clip_request(body)
        body = clip_body()
        video = base64.b64decode(body["video_base64"])
        body["video_base64"] = base64.b64encode(video[:-4] + struct.pack(">I", 6000)).decode()
        with self.assertRaisesRegex(ValueError, "MP4 duration"):
            server_module.validate_clip_request(body)

    def test_clip_endpoint_returns_qwen_result_and_never_mocks_failure(self):
        with tempfile.TemporaryDirectory() as data_dir:
            old_data_dir = server_module.DATA_DIR
            old_runs_path = server_module.RUNS_JSONL_PATH
            server_module.DATA_DIR = Path(data_dir)
            server_module.RUNS_JSONL_PATH = Path(data_dir) / "runs.jsonl"
            server = ThreadingHTTPServer(("127.0.0.1", 0), server_module.Gb10Handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                url = f"http://127.0.0.1:{server.server_port}/analyze_clip"
                payload = json.dumps(clip_body()).encode()
                request = urllib.request.Request(url, payload, {"Content-Type": "application/json"})
                unsupported = clip_body()
                unsupported["selected_agent_profile"] = "local_gemma_fast"
                unsupported_request = urllib.request.Request(
                    url, json.dumps(unsupported).encode(), {"Content-Type": "application/json"}
                )
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(unsupported_request)
                self.assertEqual(error.exception.code, 400)
                error.exception.close()
                with patch.object(server_module, "vllm_generate_video", return_value="An item moves left.") as model:
                    with urllib.request.urlopen(request) as response:
                        result = json.load(response)
                    self.assertEqual(result["results"][0]["runtime"], "vllm")
                    self.assertEqual(result["results"][0]["model_id"], server_module.QWEN_MODEL)
                    self.assertIn("moves left", result["speech_text"])
                    model.assert_called_once()
                self.assertEqual(json.loads(server_module.RUNS_JSONL_PATH.read_text().splitlines()[0])["raw_video_stored"], False)
                with patch.object(server_module, "vllm_generate_video", side_effect=RuntimeError("offline")):
                    with self.assertRaises(urllib.error.HTTPError) as error:
                        urllib.request.urlopen(request)
                    self.assertEqual(error.exception.code, 502)
                    error.exception.close()
            finally:
                server.shutdown()
                server.server_close()
                server_module.DATA_DIR = old_data_dir
                server_module.RUNS_JSONL_PATH = old_runs_path


if __name__ == "__main__":
    unittest.main()
