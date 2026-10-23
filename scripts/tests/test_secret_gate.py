import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).parents[1] / "check-secrets.py"
spec = importlib.util.spec_from_file_location("secret_gate", SCRIPT)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class SecretGateTest(unittest.TestCase):
    def test_signatures_do_not_expose_the_value(self):
        for token in ("ghp_" + "x" * 36, "github_pat_" + "x" * 50, "sk-" + "x" * 48,
                      "xoxb-" + "x" * 36, "-----BEGIN " + "PRIVATE KEY-----"):
            with self.subTest(kind=token[:4]):
                results = m.findings("first line\n" + token)
                self.assertEqual(len(results), 1)
                self.assertEqual(results[0][0], 2)
                self.assertNotIn(token, str(results))
        self.assertEqual(m.findings("${QINGYE_LLM_KEY} <测试密码> qingye-ci-app-only"), [])

    def test_process_rejects_secret_and_unreadable_input_in_normal_and_optimized_modes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            path = root / "fixture.txt"
            path.write_text("placeholder", encoding="utf-8")
            subprocess.run(["git", "add", "fixture.txt"], cwd=root, check=True)
            token = "ghp_" + "z" * 36
            for optimized in (False, True):
                command = [sys.executable] + (["-O"] if optimized else []) + ["-B", str(SCRIPT), "--root", str(root)]
                for content, expected in ((b"placeholder", 0), (token.encode(), 1), (token.encode("utf-16"), 1), (b"\xffbad", 1)):
                    path.write_bytes(content)
                    result = subprocess.run(command, capture_output=True, text=True)
                    self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
                    self.assertNotIn(token, result.stdout + result.stderr)
                path.unlink()
                self.assertEqual(subprocess.run(command, capture_output=True).returncode, 1)


if __name__ == "__main__":
    unittest.main()
