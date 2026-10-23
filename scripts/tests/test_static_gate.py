import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).parents[1] / "check-static.py"
spec = importlib.util.spec_from_file_location("static_gate", SCRIPT)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
maintenance_spec = importlib.util.spec_from_file_location("maintenance_static", SCRIPT.with_name("check.py"))
maintenance = importlib.util.module_from_spec(maintenance_spec)
maintenance_spec.loader.exec_module(maintenance)

FINDING = {"hash": "abc", "occurrence": 0, "type": "FIXTURE", "class": "cn.qingye.Example", "priority": 2, "rank": 18}
REPORT = """<BugCollection version="4.10.5"><Errors errors="0" missingClasses="0"/>
<FindBugsSummary total_classes="1" total_bugs="1"><PackageStats><ClassStats class="cn.qingye.Example"/></PackageStats></FindBugsSummary>
<BugInstance instanceHash="abc" instanceOccurrenceNum="0" type="FIXTURE" priority="2" rank="18"><Class primary="true" classname="cn.qingye.Example"/></BugInstance></BugCollection>"""


class StaticGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.classes = self.root / "classes"
        target = self.classes / "cn/qingye/Example.class"
        target.parent.mkdir(parents=True)
        target.write_bytes(b"enumeration-only fixture; never executed")
        self.report = self.root / "report.xml"
        self.register = copy.deepcopy(m.load_register(m.REGISTER))
        self.register["dependency_findings"] = []
        self.register["static_findings"] = [{**FINDING, "id": "fixture-001", "owner": {"repository": "fixture", "role": "maintainer"},
            "status": "UNRESOLVED", "source_revision": "a" * 40, "evidence": ["fixture.java:1"],
            "rationale": "Unresolved fixture", "next_action": "Review", "risk_accepted": False}]

    def parse(self, content=REPORT):
        self.report.write_text(content, encoding="utf-8")
        return m.parse_report(self.report, self.classes, "4.10.5")

    def test_same_unresolved_candidate_is_visible_not_accepted(self):
        self.assertEqual(m.compare(self.parse(), self.register), {"UNRESOLVED": 1})
        self.assertFalse(self.register["static_findings"][0]["risk_accepted"])

    def test_secondary_class_annotation_does_not_replace_primary_location(self):
        report = REPORT.replace('</BugInstance>', '<Class classname="java.sql.Statement"/></BugInstance>')
        self.assertEqual(self.parse(report)[0]["class"], "cn.qingye.Example")
        path = self.root / "risks.json"
        self.register["static_findings"][0]["class"] = ["cn.qingye.Example", "java.sql.Statement"]
        path.write_text(json.dumps(self.register), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "missing detector location"):
            m.load_register(path)

    def test_remediation_requires_fix_and_regression_coordinates(self):
        path = self.root / "risks.json"
        item = self.register["static_findings"][0]
        item["status"] = "REMEDIATED"
        for resolution in (None, {}, {"revision": "b" * 40, "evidence": []}):
            item["resolution"] = resolution
            path.write_text(json.dumps(self.register), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "remediation requires"):
                m.load_register(path)
        item["resolution"] = {"revision": "b" * 40, "evidence": ["regression: fixture fixed"]}
        path.write_text(json.dumps(self.register), encoding="utf-8")
        self.assertEqual(m.load_register(path)["static_findings"][0]["status"], "REMEDIATED")

    def test_new_changed_missing_and_confirmed_findings_fail(self):
        for field, value in (("hash", "def"), ("priority", 1), ("rank", 12), ("type", "OTHER")):
            with self.subTest(field=field), self.assertRaises(ValueError):
                m.compare([{**FINDING, field: value}], self.register)
        with self.assertRaisesRegex(ValueError, "MISSING"):
            m.compare([], self.register)
        self.register["static_findings"][0]["status"] = "CONFIRMED"
        for findings in ([], [FINDING]):
            with self.assertRaisesRegex(ValueError, "BLOCKED"):
                m.compare(findings, self.register)

    def test_remediated_candidate_must_not_reappear(self):
        self.register["static_findings"][0]["status"] = "REMEDIATED"
        self.assertEqual(m.compare([], self.register), {"REMEDIATED": 1})
        with self.assertRaisesRegex(ValueError, "BLOCKED"):
            m.compare([FINDING], self.register)

    def test_malformed_incomplete_error_and_wrong_version_reports_fail(self):
        variants = ["<", "<BugCollection/>", REPORT.replace('4.10.5', '4.0.0'),
                    REPORT.replace('errors="0"', 'errors="1"'), REPORT.replace('missingClasses="0"', 'missingClasses="1"'),
                    REPORT.replace('<Errors errors="0" missingClasses="0"/>', ''),
                    REPORT.replace('total_bugs="1"', 'total_bugs="0"'), REPORT.replace('total_classes="1"', 'total_classes="0"'),
                    REPORT.replace('<ClassStats class="cn.qingye.Example"/>', ''),
                    REPORT.replace('rank="18"', 'rank="unknown"')]
        for content in variants:
            with self.subTest(content=content), self.assertRaises((ValueError, m.ET.ParseError)):
                self.parse(content)

    def test_duplicate_classes_or_findings_and_empty_compilation_fail(self):
        for item in ('<ClassStats class="cn.qingye.Example"/>',
                     '<BugInstance instanceHash="abc" instanceOccurrenceNum="0" type="FIXTURE" priority="2" rank="18"><Class primary="true" classname="cn.qingye.Example"/></BugInstance>'):
            with self.assertRaises(ValueError):
                self.parse(REPORT.replace(item, item * 2))
        with patch.object(Path, "rglob", return_value=iter(())), self.assertRaises(ValueError):
            self.parse()

    def test_register_requires_owner_evidence_state_and_unique_identity(self):
        path = self.root / "risks.json"
        for field, value in (("owner", {}), ("evidence", []), ("status", "PASS"), ("next_action", ""),
                             ("risk_accepted", True), ("source_revision", "main"), ("priority", True)):
            changed = copy.deepcopy(self.register)
            changed["static_findings"][0][field] = value
            path.write_text(json.dumps(changed), encoding="utf-8")
            with self.subTest(field=field), self.assertRaises(ValueError):
                m.load_register(path)
        changed = copy.deepcopy(self.register)
        duplicate = {**changed["static_findings"][0], "id": "fixture-002"}
        changed["static_findings"].append(duplicate)
        path.write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaises(ValueError):
            m.load_register(path)
        with self.assertRaises(ValueError):
            json.loads('{"state": "UNRESOLVED", "state": "PASS"}', object_pairs_hook=m.unique_object)

    def test_process_exit_codes_also_hold_under_optimization(self):
        path = self.root / "risks.json"
        path.write_text(json.dumps(self.register), encoding="utf-8")
        for optimized in (False, True):
            for content, expected in ((REPORT, 0), (REPORT.replace('instanceHash="abc"', 'instanceHash="def"'), 1), ("<", 1)):
                self.report.write_text(content, encoding="utf-8")
                command = [sys.executable] + (["-O"] if optimized else []) + ["-B", str(SCRIPT), "--register", str(path), "--report", str(self.report), "--classes", str(self.classes)]
                result = subprocess.run(command, capture_output=True, text=True)
                self.assertEqual(result.returncode, expected, result.stdout + result.stderr)

    def test_failed_scanner_never_consumes_a_stale_report(self):
        with patch.object(maintenance, "executable", return_value="mvn"), patch.object(maintenance, "run", side_effect=subprocess.CalledProcessError(1, "mvn")) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                maintenance.check("static")
            self.assertEqual(run.call_count, 1)


if __name__ == "__main__":
    unittest.main()
