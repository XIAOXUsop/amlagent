"""SessionClient 的协议回归测试。"""

import json
import unittest

from http_client import SessionClient


class FakeResponse:
    def __init__(self, status: int, payload: object) -> None:
        self.status = status
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        return None

    def read(self) -> bytes:
        return json.dumps(self.payload).encode("utf-8")


class RecordingOpener:
    def __init__(self) -> None:
        self.requests = []

    def open(self, request, timeout):
        self.requests.append((request, timeout))
        return FakeResponse(200, {"ok": True})


class SessionClientTest(unittest.TestCase):
    def test_write_request_includes_configured_csrf_header(self) -> None:
        client = SessionClient("http://localhost:8080", timeout=15)
        opener = RecordingOpener()
        client.opener = opener
        client.csrf_header = "X-XSRF-TOKEN"
        client.csrf_token = "csrf-value"

        status, payload = client.request("POST", "/api/cases", {"customerId": "C003"})

        request, timeout = opener.requests[0]
        self.assertEqual(status, 200)
        self.assertEqual(payload, {"ok": True})
        self.assertEqual(request.get_header("X-xsrf-token"), "csrf-value")
        self.assertEqual(timeout, 15)

    def test_read_request_does_not_include_csrf_header(self) -> None:
        client = SessionClient("http://localhost:8080")
        opener = RecordingOpener()
        client.opener = opener
        client.csrf_token = "csrf-value"

        client.request("GET", "/api/cases/1")

        request, _ = opener.requests[0]
        self.assertIsNone(request.get_header("X-xsrf-token"))


if __name__ == "__main__":
    unittest.main()
