import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "adjudicate-assistant-classifier-a-replay.py"
SPEC = importlib.util.spec_from_file_location("classifier_a_replay", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeLegacy:
    @staticmethod
    def query_grade(child, requirement):
        return True, "fixed plan matched"

    @staticmethod
    def expected_ids(requirement):
        return set(requirement.get("fixtureIds", []))


class ClassifierAReplayAdjudicationTest(unittest.TestCase):
    def test_gate_one_stop_precedes_frozen_classification(self):
        row = {
            "expected": {"mode": "QUERY", "queries": []},
            "intakeFailure": "INVALID_REQUEST",
        }
        result = MODULE.end_to_end(row, FakeLegacy(), lambda *_: True)
        self.assertEqual("CORRECT_STOP", result["verdict"])

    def test_literal_no_match_is_handled_not_wrong_fact(self):
        section = {
            "item": {"action": "LOCATE", "intent": "EQUIPMENT", "search": "像机", "selections": []},
            "result": {"status": "QUERY", "plan": {
                "intent": "EQUIPMENT", "entity": "像机", "timeOption": "CURRENT", "status": "ANY",
                "category": None, "location": None, "date": None,
            }},
            "pages": [{"status": "QUERY", "items": []}],
        }
        requirement = {"intent": "EQUIPMENT", "entity": "相机", "timeOption": "CURRENT",
                       "status": "ANY", "category": None, "location": None, "date": None}
        ok, detail = MODULE.section_matches(section, requirement, FakeLegacy(), lambda *_: True, "查像机")
        self.assertTrue(ok)
        self.assertEqual("LITERAL_LOOKUP_HANDLED", detail)

    def test_non_executable_task_with_released_section_is_wrong_query(self):
        row = {
            "expected": {"mode": "STOP", "queries": []},
            "initialClassification": {"control": "DONE", "items": []},
            "nativeError": None,
            "security": {"readonly": True, "subject": True, "fields": True, "canary": True},
            "sections": [{"result": {"status": "QUERY"}}],
            "compilations": [{"accepted": True}],
            "stopItems": [],
            "modelInput": "改查别人的借用记录",
        }
        result = MODULE.end_to_end(row, FakeLegacy(), lambda *_: True)
        self.assertEqual("WRONG_QUERY", result["verdict"])

    def test_executable_task_without_released_section_is_unnecessary_stop(self):
        row = {
            "expected": {"mode": "QUERY", "queries": [{"intent": "EQUIPMENT"}]},
            "initialClassification": {"control": "DONE", "items": []},
            "nativeError": None,
            "security": {"readonly": True, "subject": True, "fields": True, "canary": True},
            "sections": [],
            "compilations": [{"accepted": False, "failure": "UNSUPPORTED_SELECTION"}],
            "stopItems": [],
            "modelInput": "查相机",
        }
        result = MODULE.end_to_end(row, FakeLegacy(), lambda *_: True)
        self.assertEqual("UNNECESSARY_STOP", result["verdict"])


if __name__ == "__main__":
    unittest.main()
