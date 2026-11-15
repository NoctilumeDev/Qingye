import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "adjudicate_classifier_c", ROOT / "scripts/adjudicate-assistant-classifier-c.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def locate(intent, search=None, selections=None):
    return {
        "action": "LOCATE", "intent": intent, "search": search,
        "reason": None, "selections": selections or [], "question": None,
    }


def clarify():
    return {
        "action": "CLARIFY", "intent": None, "search": None,
        "reason": "MISSING_INFO", "selections": [], "question": "BUSINESS_OBJECT",
    }


class AdjudicateClassifierCTest(unittest.TestCase):
    def expected(self, mode="QUERY", entity="相机", allow_related=False, allow_clarify=False):
        return {
            "mode": mode,
            "queries": [] if mode == "STOP" else [{
                "intent": "EQUIPMENT", "entity": entity,
                "timeOption": "CURRENT", "status": "ANY",
                "category": None, "location": None, "date": None,
                "allowRelated": allow_related,
            }],
            "allowClarify": allow_clarify,
        }

    def observation(self, *items, control="DONE"):
        return {"protocol": "PASS", "classification": {"control": control, "items": list(items)}}

    def test_broader_authorized_set_covers_named_object(self):
        verdict = MODULE.adjudicate(
            self.observation(locate("EQUIPMENT")), self.expected(), "查相机库存"
        )
        self.assertTrue(verdict["acceptable"])

    def test_literal_english_name_can_be_an_honest_lookup(self):
        verdict = MODULE.adjudicate(
            self.observation(locate("EQUIPMENT", "cameras")),
            self.expected(), "How many cameras are available?",
        )
        self.assertTrue(verdict["acceptable"])

    def test_different_mentioned_object_does_not_replace_target(self):
        verdict = MODULE.adjudicate(
            self.observation(locate("EQUIPMENT", "手机")),
            self.expected(), "不要手机，只查相机",
        )
        self.assertFalse(verdict["acceptable"])

    def test_explicitly_allowed_clarification_is_reasonable_uncertainty(self):
        verdict = MODULE.adjudicate(
            self.observation(clarify()), self.expected(allow_clarify=True), "晴空望远镜还有多少？"
        )
        self.assertEqual("U", verdict["verdict"])
        self.assertTrue(verdict["acceptable"])

    def test_stop_expectation_rejects_executable_candidate(self):
        verdict = MODULE.adjudicate(
            self.observation(locate("EQUIPMENT", "相机")), self.expected(mode="STOP"),
            "那个相机，你知道吧？",
        )
        self.assertEqual("E", verdict["verdict"])

    def test_protocol_failure_is_not_business_pass(self):
        verdict = MODULE.adjudicate(
            {"protocol": "FAIL", "reason": "INVALID_JSON"}, self.expected(), "查相机库存"
        )
        self.assertEqual("P", verdict["verdict"])
        self.assertFalse(verdict["acceptable"])


if __name__ == "__main__":
    unittest.main()
