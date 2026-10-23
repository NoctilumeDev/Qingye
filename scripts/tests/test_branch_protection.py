import copy
import importlib.util
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("protection", ROOT / "scripts/check-branch-protection.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
POLICY = json.loads((ROOT / ".github/main-protection.json").read_text(encoding="utf-8"))
CHECKS = ["Qingye · client and hygiene", "Qingye · H2", "Qingye · MySQL"]


def protected():
    return {
        "required_status_checks": {"strict": True, "checks": [
            {"context": name, "app_id": 15368} for name in CHECKS]},
        "enforce_admins": {"enabled": True},
        "required_pull_request_reviews": {"dismiss_stale_reviews": True},
        "required_conversation_resolution": {"enabled": True},
        "allow_force_pushes": {"enabled": False},
        "allow_deletions": {"enabled": False},
    }


class BranchProtectionTest(unittest.TestCase):
    def test_declared_policy_keeps_all_three_checks_without_bypass(self):
        self.assertEqual(POLICY["required_status_checks"]["checks"],
                         [{"context": name, "app_id": 15368} for name in CHECKS])
        self.assertTrue(POLICY["required_status_checks"]["strict"])
        self.assertTrue(POLICY["enforce_admins"])
        self.assertFalse(POLICY["allow_force_pushes"])
        self.assertFalse(POLICY["allow_deletions"])
        self.assertEqual(POLICY["required_pull_request_reviews"]["required_approving_review_count"], 0)

    def test_correct_config_passes_and_additional_checks_are_allowed(self):
        actual = protected()
        self.assertEqual(module.protection_errors(actual, POLICY), [])
        actual["required_status_checks"]["checks"].append({"context": "Extra", "app_id": 15368})
        self.assertEqual(module.protection_errors(actual, POLICY), [])

    def test_each_missing_or_unbound_check_is_rejected(self):
        for index, name in enumerate(CHECKS):
            for change in ("missing", "unbound", "wrong-app"):
                with self.subTest(check=name, change=change):
                    actual = protected()
                    if change == "missing":
                        del actual["required_status_checks"]["checks"][index]
                    else:
                        actual["required_status_checks"]["checks"][index]["app_id"] = (
                            None if change == "unbound" else 1)
                    self.assertTrue(module.protection_errors(actual, POLICY))

    def test_missing_protection_and_weakened_pr_rules_are_rejected(self):
        for actual in ({}, None, {"message": "Branch not protected"}):
            self.assertTrue(module.protection_errors(actual, POLICY))
        for key in ("enforce_admins", "required_conversation_resolution",
                    "allow_force_pushes", "allow_deletions"):
            with self.subTest(key=key):
                actual = protected()
                actual[key]["enabled"] = not actual[key]["enabled"]
                self.assertTrue(module.protection_errors(actual, POLICY))
                del actual[key]
                self.assertTrue(module.protection_errors(actual, POLICY))
        variants = [protected() for _ in range(6)]
        variants[0]["required_status_checks"]["strict"] = False
        variants[1]["required_pull_request_reviews"] = None
        variants[2]["required_pull_request_reviews"]["dismiss_stale_reviews"] = False
        for actual, key in zip(variants[3:], ("users", "teams", "apps")):
            actual["required_pull_request_reviews"]["bypass_pull_request_allowances"] = {key: [{}]}
        for actual in variants:
            self.assertTrue(module.protection_errors(actual, POLICY))

    def test_verifier_does_not_modify_the_readback(self):
        actual = protected()
        before = copy.deepcopy(actual)
        module.protection_errors(actual, POLICY)
        self.assertEqual(actual, before)


if __name__ == "__main__":
    unittest.main()
