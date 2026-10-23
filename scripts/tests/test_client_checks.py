"""Exercise the real checker process, including optimized Python."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


CHECKER = Path(__file__).resolve().parents[1] / "check-client.py"
ENTRY = """
import importlib.util
from pathlib import Path
import sys
spec = importlib.util.spec_from_file_location('client_checker', sys.argv[1])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
module.CLIENT = Path(sys.argv[2])
sys.argv = [sys.argv[1]]
module.main()
"""


class ClientCheckProcessTest(unittest.TestCase):
    def check_fixture(self, *, optimized, missing=False, tag="view"):
        with tempfile.TemporaryDirectory(prefix="qingye-check-fixture-") as directory:
            root = Path(directory)
            (root / "app.json").write_text(
                json.dumps({"pages": ["pages/index"]}), encoding="utf-8")
            if not missing:
                (root / "pages").mkdir()
                for suffix, content in {".js": "Page({});", ".json": "{}",
                                        ".wxml": f"<{tag}></{tag}>", ".wxss": ""}.items():
                    (root / ("pages/index" + suffix)).write_text(content, encoding="utf-8")
            return subprocess.run(
                [sys.executable, *(["-O"] if optimized else []), "-B", "-c", ENTRY,
                 str(CHECKER), str(root)], capture_output=True, text=True, timeout=30)

    def test_valid_native_page_passes_in_both_python_modes(self):
        for optimized in (False, True):
            with self.subTest(optimized=optimized):
                result = self.check_fixture(optimized=optimized)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("checks passed: 1 pages", result.stdout)

    def test_missing_page_blocks_in_both_python_modes(self):
        for optimized in (False, True):
            with self.subTest(optimized=optimized):
                result = self.check_fixture(optimized=optimized, missing=True)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn("pages/index.js", result.stderr)
                self.assertNotIn("checks passed", result.stdout)

    def test_unsupported_tag_blocks_in_both_python_modes(self):
        for optimized in (False, True):
            with self.subTest(optimized=optimized):
                result = self.check_fixture(optimized=optimized, tag="not-native")
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn("Unsupported native tag", result.stderr)
                self.assertNotIn("checks passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
