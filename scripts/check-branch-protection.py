"""Read back Qingye main protection. Never changes repository settings."""
import argparse
import json
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
ENDPOINT = "repos/NoctilumeDev/Qingye/branches/main/protection"


def protection_errors(actual, policy):
    errors = []
    if not isinstance(actual, dict) or "message" in actual:
        return ["No readable main protection; missing protection is not PASS"]
    status = actual.get("required_status_checks") or {}
    if status.get("strict") is not True:
        errors.append("Required checks must apply to the up-to-date base")
    checks = {(item.get("context"), item.get("app_id"))
              for item in status.get("checks", [])}
    for check in policy["required_status_checks"]["checks"]:
        if (check["context"], check["app_id"]) not in checks:
            errors.append("Missing required GitHub Actions check: " + check["context"])
    for key, required in (("enforce_admins", True),
                          ("required_conversation_resolution", True),
                          ("allow_force_pushes", False), ("allow_deletions", False)):
        if (actual.get(key) or {}).get("enabled") is not required:
            errors.append(f"{key} must be explicitly {required}")
    review = actual.get("required_pull_request_reviews")
    if not isinstance(review, dict):
        errors.append("Pull requests must be required")
    else:
        if review.get("dismiss_stale_reviews") is not True:
            errors.append("Stale reviews must be dismissed")
        allowances = review.get("bypass_pull_request_allowances") or {}
        if any(allowances.get(key) for key in ("users", "teams", "apps")):
            errors.append("Explicit PR bypass allowances are not permitted")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path,
                        help="Validate a saved GET response; not a live-state claim")
    args = parser.parse_args()
    try:
        policy = json.loads((ROOT / ".github/main-protection.json").read_text(encoding="utf-8"))
        if args.snapshot:
            actual = json.loads(args.snapshot.read_text(encoding="utf-8-sig"))
        else:
            response = subprocess.run(["gh", "api", ENDPOINT], check=True,
                                      capture_output=True, encoding="utf-8", timeout=30)
            actual = json.loads(response.stdout)
        errors = protection_errors(actual, policy)
        if errors:
            for error in errors:
                print(error, file=sys.stderr)
            return 1
        kind = "Saved snapshot" if args.snapshot else "Live GET"
        print(f"{kind}: main protection matches the declared minimum policy")
        print("Scope: configuration readback only; not an attempted forbidden push,")
        print("not test execution, code-review independence, or release qualification.")
        return 0
    except (OSError, ValueError, TypeError, AttributeError, KeyError,
            subprocess.SubprocessError) as error:
        print(f"Protection check did not establish qualification: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
