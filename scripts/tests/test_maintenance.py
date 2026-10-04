import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("maintenance", Path(__file__).parents[1] / "check.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class MaintenanceBoundaryTest(unittest.TestCase):
    def test_rebuildable_and_private_files_rejected_but_sources_kept(self):
        for name in ["backend/target/app.jar", "x/node_modules/tool.js", "scripts/__pycache__/x.pyc", "local.ps1", ".env"]:
            self.assertIsNotNone(m.residual_reason(name), name)
        for name in ["backend/pom.xml", "src/runtime.ts", "docs/assets/current.png", ".env.example"]:
            self.assertIsNone(m.residual_reason(name), name)
        if m.PROTECTED:
            self.assertIsNone(m.residual_reason("artifacts/original/run.log"))

    def test_current_links_are_checked_and_percent_encoded_assets_resolve(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "图.png").write_bytes(b"fixture")
            (root / "README.md").write_text("[图](%E5%9B%BE.png) [section](#x) [remote](https://example.org)\n", encoding="utf-8")
            self.assertEqual(m.link_errors(root, ["README.md"]), [])
            (root / "图.png").unlink()
            self.assertEqual(len(m.link_errors(root, ["README.md"])), 1)

    def test_live_database_and_missing_credentials_never_enter_test_group(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(RuntimeError):
                m.mysql_environment("TEST", {"test_db"})
            os.environ.update(TEST_URL="jdbc:mysql://localhost/live", TEST_USER="fixture", TEST_PASSWORD="fixture")
            with self.assertRaises(RuntimeError):
                m.mysql_environment("TEST", {"test_db"})
            os.environ["TEST_URL"] = "jdbc:mysql://localhost/test_db?characterEncoding=utf8"
            self.assertEqual(m.mysql_environment("TEST", {"test_db"})["TEST_USER"], "fixture")

    def test_h2_does_not_inherit_mysql_test_target(self):
        if "h2" not in m.GROUPS:
            self.skipTest("Qixu requires real MySQL")
        with patch.dict(os.environ, {"QINGYE_TEST_URL": "jdbc:mysql://localhost/live"}), patch.object(m, "run") as run, patch.object(m, "executable", return_value="mvn"):
            m.check("h2")
            self.assertNotIn("QINGYE_TEST_URL", run.call_args.args[2])
            self.assertIn("QINGYE_TEST_URL", os.environ)
