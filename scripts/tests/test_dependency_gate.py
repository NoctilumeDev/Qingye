import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).parents[1] / "check-dependencies.py"
spec = importlib.util.spec_from_file_location("dependency_gate", SCRIPT)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class DependencyGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "backend").mkdir()
        (self.root / "backend/pom.xml").write_text("fixture\n", encoding="utf-8")
        self.bom = {"bomFormat": "CycloneDX", "specVersion": "1.6", "components": [
            {"group": "fixture", "name": "lib", "version": "1", "licenses": [{"license": {"id": "MIT"}}]}]}
        self.tree = {"children": [{"groupId": "fixture", "artifactId": "lib", "version": "1", "scope": "test"}]}
        self.inventory = m.inventory(self.bom, self.tree)
        self.report = {"results": [{"source": {"type": "sbom"}, "packages": [{
            "package": {"ecosystem": "Maven", "name": "fixture:lib", "version": "1"},
            "vulnerabilities": [{"id": "GHSA-fixture", "summary": "Fixture", "severity": []}]}]}]}
        self.findings = m.scan_findings(self.report, self.inventory, 1)
        self.register = {"dependency_findings": [{"version": "fixture:lib@1", "id": "GHSA-fixture",
                         "status": "UNRESOLVED", "evidence": [{"path": "backend/pom.xml"}], "risk_accepted": False}]}
        self.policy = {"schema": "qingye-dependency-policy/v1", "source_revision": "a" * 40,
                       "components": self.inventory, "advisories": self.findings,
                       "applicability_inputs": {"backend/pom.xml": hashlib.sha256(b"fixture\n").hexdigest()}}

    def validate(self, **kwargs):
        args = dict(current=self.inventory, findings=self.findings, policy=self.policy, register=self.register, root=self.root)
        args.update(kwargs)
        return m.validate_policy(**args)

    def test_unchanged_unresolved_remains_visible(self):
        self.assertEqual(self.validate(), {"UNRESOLVED": 1})
        self.assertFalse(self.register["dependency_findings"][0]["risk_accepted"])

    def test_missing_duplicate_and_wrong_format_sbom_fail(self):
        for change in ({"components": []}, {"components": self.bom["components"] * 2}, {"specVersion": "1.0"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                m.inventory({**self.bom, **change}, self.tree)
        with self.assertRaises(ValueError):
            m.inventory(self.bom, {"children": []})

    def test_license_scope_and_version_changes_require_review(self):
        for field, value in (("licenses", ["unknown"]), ("scopes", ["runtime"])):
            changed = copy.deepcopy(self.inventory)
            changed["fixture:lib@1"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.validate(current=changed)
        with self.assertRaises(ValueError):
            self.validate(current={"fixture:lib@2": self.inventory["fixture:lib@1"]})

    def test_partial_duplicate_missing_and_failed_scans_are_not_clean(self):
        variants = [{}, {"results": []}, {"results": [{"source": {"type": "sbom"}, "packages": []}]}]
        duplicate = copy.deepcopy(self.report)
        duplicate["results"][0]["packages"] *= 2
        variants.append(duplicate)
        for report in variants:
            with self.subTest(report=report), self.assertRaises(ValueError):
                m.scan_findings(report, self.inventory, 1)
        for code in (0, 2, -1):
            with self.subTest(code=code), self.assertRaises(ValueError):
                m.scan_findings(self.report, self.inventory, code)

    def test_new_changed_and_disappeared_advisories_require_review(self):
        for change in ({}, {**self.findings, "fixture:lib@1/GHSA-new": "a" * 64},
                       {key: "b" * 64 for key in self.findings}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.validate(findings=change)
        changed = copy.deepcopy(self.report)
        changed["results"][0]["packages"][0]["vulnerabilities"][0]["severity"] = [{"score": "changed"}]
        self.assertNotEqual(m.scan_findings(changed, self.inventory, 1), self.findings)

    def test_clean_scanner_exit_requires_full_package_coverage(self):
        report = copy.deepcopy(self.report)
        report["results"][0]["packages"][0]["vulnerabilities"] = []
        self.assertEqual(m.scan_findings(report, self.inventory, 0), {})
        with self.assertRaises(ValueError):
            m.scan_findings(report, self.inventory, 1)

    def test_confirmed_or_recurring_remediated_findings_block(self):
        for status in ("CONFIRMED", "REMEDIATED"):
            self.register["dependency_findings"][0]["status"] = status
            with self.subTest(status=status), self.assertRaises(ValueError):
                self.validate()

    def retire_fixture(self):
        previous = copy.deepcopy(self.policy)
        history_path = self.root / "docs/governance/dependency-policy.json"
        history_path.parent.mkdir(parents=True)
        history_path.write_text(json.dumps(previous), encoding="utf-8")
        self.git("init", "-q")
        self.git("add", ".")
        self.git("commit", "-qm", "historical fixture")
        historical = self.git("rev-parse", "HEAD").strip()
        (self.root / "backend/pom.xml").write_text("fixture v2\n", encoding="utf-8")
        proof = self.root / "docs/reconciliation.md"
        proof.write_text("fixture evidence\n", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "replacement fixture")
        changed = self.git("rev-parse", "HEAD").strip()
        contract = {"package": "fixture:lib", "manifest": "backend/pom.xml", "evidence": {"docs/reconciliation.md"}}
        self.addCleanup(patch.stopall)
        patch.dict(m.RETIREMENT_CONTRACTS, {"fixture/v1": contract}).start()
        original = self.inventory["fixture:lib@1"]
        self.inventory = {"fixture:lib@2": original}
        self.findings = {}
        self.policy["components"] = self.inventory
        self.policy["advisories"] = {}
        self.policy["applicability_inputs"]["backend/pom.xml"] = hashlib.sha256(b"fixture v2\n").hexdigest()
        self.policy["retired_components"] = {"fixture:lib@1": {
            "contract": "fixture/v1", "replacement": "fixture:lib@2", "historical_revision": historical,
            "change_revision": changed, "advisories": previous["advisories"],
            "evidence": {"docs/reconciliation.md": hashlib.sha256(b"fixture evidence\n").hexdigest()}}}

    def git(self, *args):
        return subprocess.check_output(["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                                        "-c", "commit.gpgSign=false", "-c", "core.autocrlf=false", *args],
                                       cwd=self.root, text=True, encoding="utf-8", stderr=subprocess.PIPE)

    def test_reviewed_upgrade_preserves_historical_unknown_and_not_applicable(self):
        self.retire_fixture()
        for status in ("UNRESOLVED", "NOT_APPLICABLE"):
            self.register["dependency_findings"][0]["status"] = status
            self.assertEqual(self.validate(), {status: 1})
        self.assertFalse(self.register["dependency_findings"][0]["risk_accepted"])

    def test_unexplained_or_unbound_retirement_is_rejected(self):
        self.retire_fixture()
        original = copy.deepcopy(self.policy["retired_components"])
        for field, value in (("replacement", "other:lib@2"), ("change_revision", ""),
                             ("historical_revision", ""), ("advisories", {}),
                             ("evidence", []), ("evidence", ["missing.md"]),
                             ("evidence", ["../outside.md"])):
            self.policy["retired_components"] = copy.deepcopy(original)
            self.policy["retired_components"]["fixture:lib@1"][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                self.validate()
        self.policy["retired_components"] = {}
        with self.assertRaises(ValueError):
            self.validate()

    def test_retirement_cannot_hide_old_version_or_new_version_findings(self):
        self.retire_fixture()
        old = "fixture:lib@1"
        both = {**self.inventory, old: self.inventory["fixture:lib@2"]}
        self.policy["components"] = both
        with self.assertRaises(ValueError):
            self.validate(current=both)
        self.policy["components"] = self.inventory
        for key in ("fixture:lib@1/GHSA-fixture", "fixture:lib@2/GHSA-fixture", "fixture:lib@2/GHSA-new"):
            findings = {key: "d" * 64}
            self.policy["advisories"] = findings
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.validate(findings=findings)

    def test_retirement_cannot_close_a_confirmed_defect(self):
        self.retire_fixture()
        self.register["dependency_findings"][0]["status"] = "CONFIRMED"
        with self.assertRaisesRegex(ValueError, "remediation evidence"):
            self.validate()

    def test_valid_shaped_but_forged_history_or_evidence_is_rejected(self):
        self.retire_fixture()
        original = copy.deepcopy(self.policy["retired_components"])
        for field, value in (("historical_revision", "0" * 40), ("change_revision", "f" * 40),
                             ("change_revision", original["fixture:lib@1"]["historical_revision"]),
                             ("advisories", {"fixture:lib@1/GHSA-fixture": "0" * 64}),
                             ("evidence", {"backend/pom.xml": hashlib.sha256(b"fixture v2\n").hexdigest()}),
                             ("evidence", {"docs/reconciliation.md": "0" * 64})):
            self.policy["retired_components"] = copy.deepcopy(original)
            self.policy["retired_components"]["fixture:lib@1"][field] = value
            with self.subTest(field=field), self.assertRaises((ValueError, subprocess.CalledProcessError)):
                self.validate()
        self.policy["retired_components"] = original
        (self.root / "docs/reconciliation.md").write_text("changed evidence", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "evidence content changed"):
            self.validate()

    def test_squashed_manifest_can_bind_the_retained_source_commit(self):
        self.retire_fixture()
        history = self.policy["retired_components"]["fixture:lib@1"]["historical_revision"]
        self.git("checkout", "-qb", "squashed", history)
        (self.root / "backend/pom.xml").write_text("fixture v2\n", encoding="utf-8")
        (self.root / "docs/reconciliation.md").write_text("fixture evidence\n", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "squashed delivery")
        self.assertEqual(self.validate(), {"UNRESOLVED": 1})

    def test_changed_missing_or_removed_applicability_evidence_blocks(self):
        path = self.root / "backend/pom.xml"
        path.write_bytes(b"fixture\r\n")
        self.validate()  # Checkout newline normalization does not change source semantics.
        path.write_text("changed", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "input changed"):
            self.validate()
        path.unlink()
        with self.assertRaises(OSError):
            self.validate()
        self.policy["applicability_inputs"] = {}
        with self.assertRaises(ValueError):
            self.validate()

    def test_later_manifest_requires_a_bound_review_and_preserves_original_history(self):
        self.retire_fixture()
        retirement = self.policy["retired_components"]["fixture:lib@1"]
        original = copy.deepcopy(retirement)
        prior = self.commit_policy("retain reviewed original upgrade")
        (self.root / "backend/pom.xml").write_text("fixture v2; sibling upgraded\n", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "review sibling dependency upgrade")
        reviewed = self.git("rev-parse", "HEAD").strip()
        self.policy["applicability_inputs"]["backend/pom.xml"] = hashlib.sha256(
            b"fixture v2; sibling upgraded\n").hexdigest()
        with self.assertRaisesRegex(ValueError, "manifest review revision"):
            self.validate()
        for value in ("", "f" * 40, original["historical_revision"], original["change_revision"]):
            retirement["manifest_review_revision"] = value
            with self.subTest(value=value), self.assertRaises((ValueError, subprocess.CalledProcessError)):
                self.validate()
        retirement["manifest_review_revision"] = reviewed
        for value in (None, "", "f" * 40, original["historical_revision"], reviewed):
            retirement["prior_policy_revision"] = value
            with self.subTest(prior=value), self.assertRaises((ValueError, subprocess.CalledProcessError)):
                self.validate()
        retirement["prior_policy_revision"] = prior
        self.assertEqual(self.validate(), {"UNRESOLVED": 1})
        self.assertEqual({k: v for k, v in retirement.items()
                          if k not in {"manifest_review_revision", "prior_policy_revision"}}, original)
        # Squash delivery can retain a reviewed source commit off the main ancestry.
        self.git("checkout", "-qb", "squashed-later", prior)
        (self.root / "backend/pom.xml").write_text("fixture v2; sibling upgraded\n", encoding="utf-8")
        (self.root / "docs/reconciliation.md").write_text("fixture evidence\n", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "squashed later review")
        self.assertEqual(self.validate(), {"UNRESOLVED": 1})
        retirement["replacement"] = "fixture:lib@3"
        with self.assertRaisesRegex(ValueError, "current version"):
            self.validate()
        retirement["replacement"] = "fixture:lib@2"
        (self.root / "docs/reconciliation.md").write_text("tampered", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "evidence content changed"):
            self.validate()

    def commit_policy(self, message):
        (self.root / "docs/governance/dependency-policy.json").write_text(
            json.dumps(self.policy), encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", message)
        return self.git("rev-parse", "HEAD").strip()

    def test_review_after_squashed_original_upgrade(self):
        self.retire_fixture()
        retirement = self.policy["retired_components"]["fixture:lib@1"]
        self.git("checkout", "-qb", "original-squashed", retirement["historical_revision"])
        (self.root / "backend/pom.xml").write_text("fixture v2\n", encoding="utf-8")
        (self.root / "docs/reconciliation.md").write_text("fixture evidence\n", encoding="utf-8")
        prior = self.commit_policy("squashed original upgrade with policy")
        self.assertEqual(self.validate(), {"UNRESOLVED": 1})
        (self.root / "backend/pom.xml").write_text("fixture v2; sibling upgraded\n", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "later sibling upgrade")
        retirement["manifest_review_revision"] = self.git("rev-parse", "HEAD").strip()
        retirement["prior_policy_revision"] = prior
        self.policy["applicability_inputs"]["backend/pom.xml"] = hashlib.sha256(
            b"fixture v2; sibling upgraded\n").hexdigest()
        self.assertEqual(self.validate(), {"UNRESOLVED": 1})

    def test_later_review_cannot_relabel_an_unrelated_commit_as_original_upgrade(self):
        self.retire_fixture()
        retirement = self.policy["retired_components"]["fixture:lib@1"]
        original = retirement["change_revision"]
        prior = self.commit_policy("retain original policy")
        self.git("checkout", "-qb", "unrelated-source", retirement["historical_revision"])
        (self.root / "backend/pom.xml").write_text("fixture; comment only\n", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "unrelated comment")
        unrelated = self.git("rev-parse", "HEAD").strip()
        self.git("checkout", "-q", prior)
        self.git("merge", "-s", "ours", "--no-ff", "-qm", "retain unrelated branch", unrelated)
        retirement["change_revision"] = unrelated
        retirement["manifest_review_revision"] = self.git("rev-parse", "HEAD").strip()
        retirement["prior_policy_revision"] = prior
        with self.assertRaisesRegex(ValueError, "original retirement"):
            self.validate()
        retirement["change_revision"] = original
        self.assertEqual(self.validate(), {"UNRESOLVED": 1})

    def test_duplicate_json_keys_and_missing_reports_fail(self):
        path = self.root / "report.json"
        with self.assertRaises(OSError):
            m.read_json(path)
        path.write_text('{"results": [], "results": []}', encoding="utf-8")
        with self.assertRaises(ValueError):
            m.read_json(path)

    def test_failed_maven_does_not_read_stale_reports(self):
        with patch.object(m, "ROOT", self.root), patch.object(m.shutil, "which", return_value="mvn"), \
             patch.object(m.subprocess, "check_output", side_effect=["a" * 40, ""]), \
             patch.object(m.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "mvn")) as run, \
             patch.object(m, "read_json") as reader, patch.object(m, "scanner_binary") as scanner:
            with self.assertRaises(subprocess.CalledProcessError):
                m.execute_scan()
            reader.assert_not_called()
            scanner.assert_not_called()
            self.assertEqual(run.call_count, 1)
            records = list((self.root / "backend/target/dependency-audit").glob("run-*/run.json"))
            self.assertEqual(len(records), 1)
            self.assertEqual(json.loads(records[0].read_text())["state"], "STARTED")

    def test_corrupted_scanner_is_rejected_before_execution(self):
        folder = self.root / ".tools" / ("osv-scanner-" + m.VERSION)
        folder.mkdir(parents=True)
        name = m.BINARIES[("Linux", "x86_64")][0]
        (folder / name).write_bytes(b"not the pinned scanner")
        with patch.object(m, "ROOT", self.root), patch.object(m.platform, "system", return_value="Linux"), \
             patch.object(m.platform, "machine", return_value="x86_64"), self.assertRaisesRegex(ValueError, "checksum"):
            m.scanner_binary()


if __name__ == "__main__":
    unittest.main()
