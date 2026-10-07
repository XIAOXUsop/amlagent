"""Check the real Compose configuration without starting containers."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which("docker"), "Docker CLI is required for Compose checks")
class MonitoringConfigTest(unittest.TestCase):
    def compose(self, monitoring: bool, password: str | None = None) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env.pop("GF_SECURITY_ADMIN_PASSWORD", None)
        env.pop("GF_SECURITY_ADMIN_USER", None)
        if password is not None:
            env["GF_SECURITY_ADMIN_PASSWORD"] = password
        with tempfile.TemporaryDirectory(prefix="aml-compose-test-") as directory:
            env_file = Path(directory) / "empty.env"
            env_file.write_text("", encoding="utf-8")
            command = ["docker", "compose", "--env-file", str(env_file), "-f", "docker-compose.yml"]
            if monitoring:
                command += ["-f", "docker-compose.monitoring.yml"]
            command += ["config", "--format", "json"]
            return subprocess.run(
                command, cwd=ROOT, env=env, capture_output=True, text=True, encoding="utf-8", timeout=30, check=False
            )

    def test_basic_dependencies_need_no_monitoring_password(self) -> None:
        result = self.compose(False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(set(json.loads(result.stdout)["services"]), {"mysql", "pgvector", "redis"})

    def test_monitoring_rejects_missing_and_empty_password(self) -> None:
        for password in (None, ""):
            with self.subTest(password_is_empty=password == ""):
                result = self.compose(True, password)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("GF_SECURITY_ADMIN_PASSWORD", result.stderr)

    def test_monitoring_uses_the_explicit_password(self) -> None:
        password = "monitoring-fixture-password"
        result = self.compose(True, password)
        self.assertEqual(result.returncode, 0, result.stderr)
        services = json.loads(result.stdout)["services"]
        self.assertIn("prometheus", services)
        self.assertEqual(services["grafana"]["environment"]["GF_SECURITY_ADMIN_PASSWORD"], password)
        self.assertEqual(services["grafana"]["environment"]["GF_SECURITY_ADMIN_USER"], "admin")


if __name__ == "__main__":
    unittest.main()
