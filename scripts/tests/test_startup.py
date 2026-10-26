"""Execute the real startup script with command stubs, never an application."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
RUNNER = r"""
$ErrorActionPreference = 'Stop'
$global:fixtureBuilds = 0
function global:mvn {
    $global:fixtureBuilds++
    $global:LASTEXITCODE = 0
}
function global:java {
    [ordered]@{
        demo = $env:QINGYE_DEMO
        bind = $env:QINGYE_BIND
        redis = $env:QINGYE_REDIS
        mq = $env:QINGYE_MQ
        builds = $global:fixtureBuilds
        arguments = @($args)
    } | ConvertTo-Json -Compress
    $global:LASTEXITCODE = 0
}
$fixtureArgs = @{
    SkipBuild = $env:QINGYE_FIXTURE_SKIP -eq 'true'
    RealWechat = $env:QINGYE_FIXTURE_REAL -eq 'true'
    Redis = $env:QINGYE_FIXTURE_REDIS -eq 'true'
    Mq = $env:QINGYE_FIXTURE_MQ -eq 'true'
}
& $env:QINGYE_FIXTURE_SCRIPT @fixtureArgs
"""


class StartupModeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shell = shutil.which("pwsh") or shutil.which("powershell")
        if not cls.shell:
            raise RuntimeError("Startup regression requires PowerShell (pwsh or powershell)")

    def startup(self, demo=None, local_demo=None, real=False, bind="127.0.0.1",
                middleware=False, skip_build=True):
        with tempfile.TemporaryDirectory(prefix="qingye-startup-") as directory:
            fixture = Path(directory)
            (fixture / "scripts").mkdir()
            (fixture / "backend").mkdir()
            script = fixture / "scripts" / "start.ps1"
            shutil.copyfile(ROOT / "scripts" / "start.ps1", script)
            runner = fixture / "runner.ps1"
            runner.write_text(RUNNER, encoding="utf-8")
            if local_demo is not None:
                self.assertIn(local_demo, ("true", "false"))
                (fixture / "local.ps1").write_text(
                    "$env:QINGYE_DEMO = '" + local_demo + "'\n", encoding="utf-8")
            env = {k: v for k, v in os.environ.items()
                   if not k.upper().startswith("QINGYE_")}
            env.update(QINGYE_BIND=bind, QINGYE_FIXTURE_SCRIPT=str(script),
                       QINGYE_FIXTURE_REAL=str(real).lower(),
                       QINGYE_FIXTURE_SKIP=str(skip_build).lower(),
                       QINGYE_FIXTURE_REDIS=str(middleware).lower(),
                       QINGYE_FIXTURE_MQ=str(middleware).lower())
            if demo is not None:
                env["QINGYE_DEMO"] = demo
            result = subprocess.run(
                [self.shell, "-NoLogo", "-NoProfile", "-NonInteractive",
                 "-ExecutionPolicy", "Bypass", "-File", str(runner)],
                cwd=fixture, env=env, capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)
            observed = json.loads(result.stdout.strip())
            self.assertEqual(observed["bind"], bind)
            self.assertEqual(observed["arguments"], ["-jar", "target/qingye-0.1.0.jar"])
            self.assertEqual(observed["builds"], 0 if skip_build else 1)
            self.assertEqual(observed["redis"], str(middleware).lower())
            self.assertEqual(observed["mq"], str(middleware).lower())
            return observed["demo"]

    def test_unset_keeps_default_demo(self):
        self.assertEqual(self.startup(), "true")

    def test_explicit_false_survives_loopback_and_lan_bind(self):
        for bind in ("127.0.0.1", "0.0.0.0"):
            with self.subTest(bind=bind):
                self.assertEqual(self.startup(demo="false", bind=bind), "false")

    def test_explicit_true_keeps_demo(self):
        self.assertEqual(self.startup(demo="true"), "true")

    def test_local_config_false_survives_startup(self):
        self.assertEqual(self.startup(demo="true", local_demo="false"), "false")

    def test_real_wechat_overrides_local_demo(self):
        self.assertEqual(self.startup(local_demo="true", real=True), "false")

    def test_real_wechat_without_config_disables_demo(self):
        self.assertEqual(self.startup(real=True), "false")

    def test_middleware_flags_do_not_reenable_demo(self):
        self.assertEqual(self.startup(demo="false", middleware=True), "false")

    def test_offline_build_does_not_reenable_demo(self):
        self.assertEqual(self.startup(demo="false", skip_build=False), "false")


if __name__ == "__main__":
    unittest.main()
