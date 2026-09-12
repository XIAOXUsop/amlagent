"""Cookie 与 CSRF 感知的 AML 本地基准测试 HTTP 客户端。"""

import http.cookiejar
import json
import urllib.error
import urllib.request


class SessionClient:
    """保留 HttpOnly 登录 Cookie，并为写请求自动附加 CSRF Token。"""

    def __init__(self, base_url: str, timeout: int = 60) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.cookie_jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookie_jar))
        self.csrf_header = "X-XSRF-TOKEN"
        self.csrf_token: str | None = None

    def authenticate(self, username: str, password: str) -> None:
        status, _ = self.request("POST", "/api/auth/login", {"username": username, "password": password})
        if status != 200:
            raise RuntimeError(f"登录失败: {status}")
        status, metadata = self.request("GET", "/api/auth/csrf")
        if status != 200 or not isinstance(metadata, dict):
            raise RuntimeError(f"CSRF Token 获取失败: {status}")
        header_name = metadata.get("headerName")
        if isinstance(header_name, str) and header_name:
            self.csrf_header = header_name
        self.csrf_token = next((cookie.value for cookie in self.cookie_jar if cookie.name == "XSRF-TOKEN"), None)
        if not self.csrf_token:
            raise RuntimeError("CSRF Token Cookie 缺失")

    def request(self, method: str, path: str, body: object | None = None, timeout: int | None = None):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
        headers = {"Content-Type": "application/json"}
        if method.upper() not in {"GET", "HEAD", "OPTIONS", "TRACE"} and self.csrf_token:
            headers[self.csrf_header] = self.csrf_token
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=timeout or self.timeout) as response:
                raw = response.read().decode("utf-8")
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8")
            return error.code, json.loads(raw) if raw else None
