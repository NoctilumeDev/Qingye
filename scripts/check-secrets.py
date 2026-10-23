"""Reject a bounded set of credential signatures in current tracked text, without printing values."""
import argparse
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
# Deliberately narrow signatures, not a claim to detect every credential.
RULES = {
    "private-key": re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"),
    "github-token": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{40,})\b"),
    "api-secret-token": re.compile(r"\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{32,}\b"),
    "slack-token": re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{24,}\b"),
}


def findings(text):
    return sorted({(text.count("\n", 0, match.start()) + 1, rule)
                   for rule, pattern in RULES.items() for match in pattern.finditer(text)})


def scan(root):
    root = root.resolve()
    paths = subprocess.check_output(["git", "ls-files", "-z", "--", "."], cwd=root).decode("utf-8").split("\0")
    errors, text_count, binary_count = [], 0, 0
    for name in filter(None, paths):
        path = (root / name).resolve()
        if not path.is_relative_to(root):
            raise ValueError("tracked path escapes the repository")
        data = path.read_bytes()  # Missing/unreadable tracked inputs reject the run.
        if data.startswith((b"\xff\xfe", b"\xfe\xff")):
            text = data.decode("utf-16")
        elif b"\0" in data:
            binary_count += 1
            continue
        else:
            text = data.decode("utf-8-sig")
        text_count += 1
        errors.extend(f"{name}:{line}: {rule} signature (value withheld)" for line, rule in findings(text))
    if not text_count:
        raise ValueError("no tracked text was scanned")
    for error in errors:
        print(error, file=sys.stderr)
    print(f"Credential signatures: {text_count} tracked text files checked; {binary_count} binary files outside scope.")
    return 1 if errors else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    try:
        return scan(args.root)
    except (OSError, UnicodeError, ValueError, subprocess.SubprocessError):
        # Error objects can contain input bytes; never echo them for this scanner.
        print("Credential scan incomplete: repository/path/read/encoding failure; not PASS.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
