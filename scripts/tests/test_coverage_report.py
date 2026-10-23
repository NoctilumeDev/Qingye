import importlib.util
from contextlib import redirect_stdout
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("maintenance", Path(__file__).parents[1] / "check.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

REPORT = """<report name="fixture"><sessioninfo id="fixture"/>
<package name="cn/qingye"><class name="cn/qingye/Example"/></package>
<counter type="LINE" missed="2" covered="8"/>
<counter type="BRANCH" missed="1" covered="3"/></report>"""


class CoverageReportTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.classes = self.root / "classes"
        target = self.classes / "cn/qingye/Example.class"
        target.parent.mkdir(parents=True)
        target.write_bytes(b"class-list fixture; not executed")
        self.report = self.root / "jacoco.xml"

    def measure(self, text=REPORT):
        self.report.write_text(text, encoding="utf-8")
        with redirect_stdout(io.StringIO()):
            return m.coverage_report(self.report, self.classes)

    def test_complete_report_measures_without_inventing_a_threshold(self):
        self.assertEqual(self.measure(), {"LINE": (8, 10), "BRANCH": (3, 4)})
        zero = REPORT.replace('covered="8"', 'covered="0"').replace('covered="3"', 'covered="0"')
        self.assertEqual(self.measure(zero), {"LINE": (0, 2), "BRANCH": (0, 1)})

    def test_missing_malformed_and_unexecuted_reports_are_rejected(self):
        with self.assertRaises(RuntimeError):
            m.coverage_report(self.report, self.classes)
        for text in ("", "<report", "<report/>", REPORT.replace('<sessioninfo id="fixture"/>', "")):
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                self.measure(text)

    def test_omitted_unexpected_and_duplicate_classes_are_rejected(self):
        entry = '<class name="cn/qingye/Example"/>'
        for text in (REPORT.replace(entry, ""), REPORT.replace("Example", "Different"), REPORT.replace(entry, entry * 2)):
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                self.measure(text)

    def test_empty_compiled_class_set_is_rejected(self):
        with patch.object(Path, "rglob", return_value=iter(())), self.assertRaises(RuntimeError):
            self.measure()

    def test_missing_duplicate_negative_or_empty_counters_are_rejected(self):
        counter = '<counter type="LINE" missed="2" covered="8"/>'
        for text in (REPORT.replace(counter, ""), REPORT.replace(counter, counter * 2),
                     REPORT.replace('missed="2"', 'missed="-1"'),
                     REPORT.replace('covered="8"', 'covered="not-a-number"'),
                     REPORT.replace(counter, '<counter type="LINE" missed="0" covered="0"/>')):
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                self.measure(text)

    def test_maven_failure_is_not_replaced_by_a_previous_report(self):
        with patch.object(m, "executable", return_value="mvn"), patch.object(m, "run", side_effect=subprocess.CalledProcessError(1, "mvn")), patch.object(m, "coverage_report") as coverage:
            with self.assertRaises(subprocess.CalledProcessError):
                m.check("h2")
            coverage.assert_not_called()

    def test_mysql_success_also_requires_the_report(self):
        with patch.object(m, "mysql_environment", return_value={}), patch.object(m, "executable", return_value="mvn"), patch.object(m, "run"), patch.object(m, "coverage_report", side_effect=RuntimeError("missing report")) as coverage:
            with self.assertRaisesRegex(RuntimeError, "missing report"):
                m.check("mysql")
            coverage.assert_called_once()
