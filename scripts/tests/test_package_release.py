"""Candidate packaging and startup controls, using isolated files and a fake Java command."""
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("package_release", ROOT / "scripts/package-release.py")
PACK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACK)


class PackageReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="qingye-package-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "source"
        self.root.mkdir()
        self.output = Path(self.temporary.name) / "candidate"
        sources = {
            "backend/pom.xml": b"<project/>", "LICENSE": b"project license", "NOTICE": b"project notice",
            "docs/deployment.md": b"local candidate instructions", "docs/governance/dependency-policy.json": b'{"components":{}}',
            "scripts/start-production.ps1": b"production entry", "scripts/package-release.py": b"packager source",
            "backend/src/main/java/cn/qingye/QingyeApplication.java": b"class QingyeApplication {}",
            "miniprogram/app.js": b"App({})", "miniprogram/app.wxss": b"page {}",
            "miniprogram/app.json": b'{"pages":["pages/home/index"]}',
            "miniprogram/sitemap.json": b'{"rules":[]}',
            "miniprogram/project.config.json": b'{"appid":"touristappid","setting":{"urlCheck":false}}',
            "miniprogram/utils/config.js": b"module.exports={baseUrl:'http://127.0.0.1:8087'}",
            "miniprogram/assets/README.md": b"asset source", "miniprogram/assets/icons/LICENSE.txt": b"icon license",
            "miniprogram/assets/icons/NOTICE.txt": b"icon notice", "miniprogram/assets/campus/hero.jpg": b"fixture image",
        }
        for suffix in ("js", "json", "wxml", "wxss"):
            sources["miniprogram/pages/home/index." + suffix] = b"fixture page"
        for name in PACK.RESOURCES:
            sources["backend/src/main/resources/" + name] = (name + " fixture").encode()
        self.sources = sources
        for name, content in sources.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        for arguments in (["init", "-q"], ["add", "."],
                          ["-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture"]):
            subprocess.run(["git", *arguments], cwd=self.root, check=True, capture_output=True)
        self.jar = Path(self.temporary.name) / "application.jar"
        self.make_jar()
        self.sbom = Path(self.temporary.name) / "bom.cdx.json"
        self.sbom.write_text(json.dumps({"bomFormat": "CycloneDX", "components": [
            {"group": "example", "name": "library", "version": "1", "licenses": [{"license": {"id": "Apache-2.0"}}]}]}), encoding="utf-8")

    def make_jar(self, change=None, extra=None, extra_root=None):
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w") as library:
            library.writestr("META-INF/LICENSE.txt", b"nested license")
            library.writestr("META-INF/NOTICE", b"nested notice")
            library.writestr("../../LICENSE", b"archive path is never extracted")
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Main-Class: org.springframework.boot.loader.launch.JarLauncher\n")
            archive.writestr("BOOT-INF/classes/cn/qingye/QingyeApplication.class", b"fixture bytecode")
            for name in PACK.RESOURCES:
                archive.writestr("BOOT-INF/classes/" + name,
                                 b"stale" if name == change else self.sources["backend/src/main/resources/" + name])
            if extra:
                archive.writestr("BOOT-INF/classes/" + extra, b"must not ship")
            if extra_root:
                archive.writestr(extra_root, b"must not ship")
            archive.writestr("BOOT-INF/lib/library-1.jar", nested.getvalue())

    def package(self, allow_dirty=False):
        return PACK.package(self.root, self.jar, self.sbom, self.output, allow_dirty)

    def test_candidate_preserves_notices_hashes_and_requires_client_configuration(self):
        self.package()
        manifest = json.loads((self.output / "SOURCE_MANIFEST.json").read_text(encoding="utf-8"))
        self.assertFalse(manifest["working_tree_dirty"])
        self.assertEqual(manifest["status"], "LOCAL_CANDIDATE_NOT_PUBLIC_RELEASE")
        self.assertEqual(len(manifest["base_head"]), 40)
        project = json.loads((self.output / "miniprogram/project.config.json").read_text(encoding="utf-8"))
        self.assertEqual(project["appid"], "REPLACE_WITH_WECHAT_APPID")
        self.assertTrue(project["setting"]["urlCheck"])
        self.assertIn(".invalid", (self.output / "miniprogram/utils/config.js").read_text())
        dependencies = json.loads((self.output / "THIRD_PARTY/runtime-dependencies.json").read_text())
        self.assertEqual(len(dependencies["dependencies"][0]["notices"]), 3)
        for line in (self.output / "SHA256SUMS").read_text().splitlines():
            expected, name = line.split("  ", 1)
            self.assertEqual(PACK.digest((self.output / name).read_bytes()), expected, name)
        self.assertFalse((self.output.parent / "LICENSE").exists())

    def test_dirty_requires_explicit_flag_and_private_files_are_never_copied(self):
        for name in ("local.ps1", ".env", "sql/qingye.sql", "miniprogram/project.private.config.json",
                     "miniprogram/utils/database.local.json", "miniprogram/assets/private.pem"):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture-private-do-not-copy", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "--allow-dirty"):
            self.package()
        self.assertFalse(self.output.exists())
        self.package(allow_dirty=True)
        manifest = json.loads((self.output / "SOURCE_MANIFEST.json").read_text())
        self.assertTrue(manifest["working_tree_dirty"])
        self.assertTrue(manifest["dirty_allowed"])
        self.assertFalse(any("private-do-not-copy" in path.read_text(errors="ignore")
                             for path in self.output.rglob("*") if path.is_file()))
        self.assertFalse(any(item["path"].endswith("project.private.config.json") for item in manifest["source_files"]))

    def test_existing_output_is_not_touched(self):
        self.output.mkdir()
        sentinel = self.output / "keep.txt"
        sentinel.write_text("existing artifact")
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.package()
        self.assertEqual(sentinel.read_text(), "existing artifact")
        self.assertEqual(list(self.output.iterdir()), [sentinel])

    def test_supplied_sbom_gap_is_exposed_in_actual_runtime_inventory(self):
        self.sbom.write_text('{"bomFormat":"CycloneDX","components":[{"name":"library","version":"2"}]}')
        self.package()
        inventory = json.loads((self.output / "THIRD_PARTY/runtime-dependencies.json").read_text())
        dependency = inventory["dependencies"][0]
        self.assertEqual(dependency["jar"], "library-1.jar")
        self.assertEqual(dependency["sbom_status"], "MISSING_OR_AMBIGUOUS")
        self.assertEqual(dependency["sbom_matches"], [])
        self.assertTrue(dependency["notices"])

    def test_stale_or_extra_jar_resource_is_rejected_before_output(self):
        for parameters in ({"change": "schema.sql"}, {"extra": "application-local.yml"}, {"extra_root": ".env"}):
            with self.subTest(parameters=parameters):
                self.make_jar(**parameters)
                with self.assertRaisesRegex(ValueError, "JAR resource|JAR entry"):
                    self.package()
                self.assertFalse(self.output.exists())

    def test_missing_page_and_empty_sbom_are_rejected(self):
        page = self.root / "miniprogram/pages/home/index.wxml"
        page.unlink()
        with self.assertRaisesRegex(ValueError, "Missing mini-program page"):
            self.package(allow_dirty=True)
        self.assertFalse(self.output.exists())
        page.write_bytes(self.sources["miniprogram/pages/home/index.wxml"])
        self.sbom.write_text('{"bomFormat":"CycloneDX","components":[]}')
        with self.assertRaisesRegex(ValueError, "nonempty CycloneDX"):
            self.package()
        self.assertFalse(self.output.exists())


FAKE_JAVA = """
import json, os, sys
print(json.dumps(dict(demo=os.environ.get('QINGYE_DEMO'), redis=os.environ.get('QINGYE_REDIS'),
                     mq=os.environ.get('QINGYE_MQ'), bind=os.environ.get('QINGYE_BIND'), arguments=sys.argv[1:])))
sys.exit(int(os.environ['QINGYE_FIXTURE_EXIT']))
"""


class ProductionStartupTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shell = shutil.which("pwsh") or shutil.which("powershell")
        if not cls.shell:
            raise RuntimeError("Production startup regression requires PowerShell")

    def startup(self, changes=None, exit_code=0, development=False, omit_exit=False):
        with tempfile.TemporaryDirectory(prefix="qingye production ") as temporary:
            root = Path(temporary)
            (root / "scripts").mkdir()
            (root / "backend").mkdir()
            entry = "start.ps1" if development else "start-production.ps1"
            script = root / "scripts" / entry
            shutil.copyfile(ROOT / "scripts" / entry, script)
            if omit_exit:
                original = script.read_text(encoding="utf-8")
                self.assertIn("exit $qingyeJavaExit", original)
                script.write_text(original.replace("exit $qingyeJavaExit", "# fixture removes exit propagation"), encoding="utf-8")
            (root / "backend/qingye-0.1.0.jar").write_bytes(b"fake; Java is replaced")
            if not development:
                (root / "local.ps1").write_text("throw 'local config must not execute'")
            binary = root / "bin"
            binary.mkdir()
            fake_java = root / "fake-java.py"
            fake_java.write_text(FAKE_JAVA, encoding="utf-8")
            if os.name == "nt":
                (binary / "java.cmd").write_text('@echo off\n"%QINGYE_FIXTURE_PYTHON%" "%QINGYE_FIXTURE_JAVA%" %*\nexit /b %ERRORLEVEL%\n')
                (binary / "mvn.cmd").write_text('@echo Unexpected Maven: -SkipBuild was not honored 1>&2\n@exit /b 97\n')
            else:
                (binary / "java").write_text('#!/bin/sh\nexec "$QINGYE_FIXTURE_PYTHON" "$QINGYE_FIXTURE_JAVA" "$@"\n')
                (binary / "mvn").write_text('#!/bin/sh\necho "Unexpected Maven: -SkipBuild was not honored" >&2\nexit 97\n')
                (binary / "java").chmod(0o755)
                (binary / "mvn").chmod(0o755)
            env = {key: value for key, value in os.environ.items() if not key.upper().startswith("QINGYE_")}
            env.update(QINGYE_DB_URL="jdbc:mysql://localhost/qingye", QINGYE_DB_USER="fixture",
                       QINGYE_DB_PASSWORD="fixture-only-password", QINGYE_WX_APPID="fixture-appid",
                       QINGYE_WX_SECRET="fixture-only-wechat-material", QINGYE_TOKEN_SECRET="fixture-only-independent-signing-material-32",
                       QINGYE_DEMO="true", QINGYE_REDIS="true", QINGYE_MQ="true",
                       QINGYE_FIXTURE_EXIT=str(exit_code), QINGYE_FIXTURE_PYTHON=sys.executable,
                       QINGYE_FIXTURE_JAVA=str(fake_java), PATH=str(binary) + os.pathsep + os.environ.get("PATH", ""))
            env.update(changes or {})
            return subprocess.run([self.shell, "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                                   "-File", str(script), *(["-SkipBuild"] if development else [])],
                                  env=env, cwd=root.parent, capture_output=True,
                                  encoding="utf-8", timeout=15)

    def test_forces_production_flags_does_not_source_local_and_preserves_java_failure(self):
        result = self.startup(exit_code=7)
        self.assertEqual(result.returncode, 7, result.stderr)
        observed = json.loads(result.stdout)
        self.assertEqual([observed[key] for key in ("demo", "redis", "mq")], ["false"] * 3)
        self.assertEqual(observed["bind"], "127.0.0.1")
        self.assertIn("--qingye.worker-enabled=true", observed["arguments"])
        self.assertIn("--qingye.demo-enabled=false", observed["arguments"])
        self.assertIn("--qingye.redis-enabled=false", observed["arguments"])
        self.assertIn("--qingye.mq-enabled=false", observed["arguments"])

    def test_missing_short_or_placeholder_configuration_never_runs_java(self):
        for changes in ({"QINGYE_TOKEN_SECRET": ""}, {"QINGYE_TOKEN_SECRET": "short"},
                        {"QINGYE_WX_SECRET": ""}, {"QINGYE_DB_PASSWORD": "REPLACE_WITH_PASSWORD"},
                        {"QINGYE_DB_URL": "jdbc:mysql://localhost/"}):
            with self.subTest(changes=changes):
                result = self.startup(changes)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout.strip(), "")

    def test_success_and_explicit_bind_are_retained(self):
        result = self.startup({"QINGYE_BIND": "0.0.0.0"})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["bind"], "0.0.0.0")

    def test_development_skip_build_preserves_java_failure(self):
        result = self.startup(exit_code=7, development=True)
        self.assertEqual(result.returncode, 7, result.stderr)
        self.assertEqual(json.loads(result.stdout)["arguments"], ["-jar", "target/qingye-0.1.0.jar"])
        # The old behavior must fail this boundary; no test wrapper repairs it.
        old = self.startup(exit_code=7, development=True, omit_exit=True)
        self.assertEqual(old.returncode, 0, old.stderr)


if __name__ == "__main__":
    unittest.main()
