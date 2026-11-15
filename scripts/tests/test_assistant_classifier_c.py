import importlib.util
import json
from pathlib import Path
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("assistant_classifier_c", ROOT / "scripts/assistant-classifier-c.py")
C = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(C)


def item(intent="ACTIVITIES", search=None):
    return {
        "action": "LOCATE",
        "intent": intent,
        "search": search,
        "reason": None,
        "selections": [],
        "question": None,
    }


class AssistantClassifierCTest(unittest.TestCase):
    def test_review_prompt_is_one_global_batch_review(self):
        common = C.AB.experiment_common_instruction()
        prompt = C.review_instruction(common)
        self.assertTrue(prompt.startswith(common))
        self.assertIn("一次整体复核", prompt)
        self.assertIn("不得开启第三次复核", prompt)
        self.assertNotIn('"control":"NEXT"', prompt)

    def test_c_uses_exactly_two_calls_and_retains_both_classifications(self):
        initial = {"control": "DONE", "items": [item(search="相机")]}
        final = {"control": "DONE", "items": [item(search="相机"), item("MY_LOANS")]}
        replies = iter([
            (json.dumps(initial, ensure_ascii=False), {"latencySeconds": 1.0}),
            (json.dumps(final, ensure_ascii=False), {"latencySeconds": 2.0}),
        ])
        original = C.AB.call_model
        try:
            C.AB.call_model = lambda *unused: next(replies)
            observation, network = C.run_c(
                "deepseek", {"model": "fixture"}, "secret",
                "initial", "review", "查相机，再查借用", 18, 18, None,
            )
        finally:
            C.AB.call_model = original
        self.assertEqual(initial, observation["initial"]["classification"])
        self.assertEqual(final, observation["final"]["classification"])
        self.assertEqual(["initial", "review"], [call["stage"] for call in network])

    def test_invalid_initial_candidate_stops_without_review(self):
        replies = iter([('{"control":"DONE","items":[]}', {"latencySeconds": 1.0})])
        original = C.AB.call_model
        calls = 0
        try:
            def fake(*unused):
                nonlocal calls
                calls += 1
                return next(replies)
            C.AB.call_model = fake
            observation, network = C.run_c(
                "deepseek", {"model": "fixture"}, "secret",
                "initial", "review", "task", 18, 18, None,
            )
        finally:
            C.AB.call_model = original
        self.assertEqual(1, calls)
        self.assertEqual("FAIL", observation["initial"]["protocol"])
        self.assertEqual(1, observation["initial"]["calls"])
        self.assertEqual("SKIPPED", observation["review"]["protocol"])
        self.assertEqual("initial", network[0]["stage"])

    def test_late_review_is_retained_but_not_promoted(self):
        classification = {"control": "DONE", "items": [item(search="相机")]}
        replies = iter([
            (json.dumps(classification, ensure_ascii=False), {"latencySeconds": 1.0}),
            (json.dumps(classification, ensure_ascii=False), {"latencySeconds": 17.5}),
        ])
        original = C.AB.call_model
        try:
            C.AB.call_model = lambda *unused: next(replies)
            with mock.patch.object(C.time, "monotonic", side_effect=[0.0, 1.0, 18.5]):
                observation, network = C.run_c(
                    "deepseek", {"model": "fixture"}, "secret",
                    "initial", "review", "查相机", 18, 18, None,
                )
        finally:
            C.AB.call_model = original
        self.assertEqual(2, len(network))
        self.assertEqual("BUDGET_EXHAUSTED", observation["review"]["protocol"])
        self.assertEqual(classification, observation["review"]["receivedClassification"])
        self.assertEqual("UNAVAILABLE", observation["final"]["protocol"])

    def test_full_bank_and_first_turn_binding_are_reused(self):
        cases, _ = C.AB.load_cases(C.AB.DEFAULT_CASES)
        self.assertEqual(391, len(cases))
        mt03 = next(case for case in cases if case["id"] == "long-tail-attack-MT03")
        self.assertEqual("先查相机库存，另一个活动地点我忘名字了。", C.AB.first_turn(mt03))


if __name__ == "__main__":
    unittest.main()
