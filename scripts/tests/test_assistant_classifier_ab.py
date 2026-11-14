import importlib.util
import json
from pathlib import Path
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("assistant_classifier_ab", ROOT / "scripts/assistant-classifier-ab.py")
AB = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AB)


def item(action="LOCATE", intent="ACTIVITIES", reason=None, question=None):
    if action == "REJECT":
        intent = None
    return {
        "action": action,
        "intent": intent,
        "search": None,
        "reason": reason,
        "selections": [],
        "question": question,
    }


class AssistantClassifierExperimentTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.contract, cls.wire = AB.contracts()

    def test_common_semantics_are_identical_and_only_envelopes_differ(self):
        common = AB.experiment_common_instruction()
        batch = AB.batch_instruction(common)
        iterative = AB.iterative_instruction(common)
        self.assertTrue(batch.startswith(common))
        self.assertTrue(iterative.startswith(common))
        self.assertIn("实验外层协议A", batch[len(common):])
        self.assertIn("实验外层协议B", iterative[len(common):])
        self.assertNotIn("实验外层协议B", batch)
        self.assertNotIn("实验外层协议A", iterative)

    def test_batch_accepts_mixed_item_classifications(self):
        value = {
            "control": "DONE",
            "items": [
                item(),
                item("CLARIFY", "EQUIPMENT", "MISSING_INFO", "OBJECT_NAME"),
                item("REJECT", reason="PRIVATE_DATA"),
            ],
        }
        decoded = AB.decode_batch(json.dumps(value), self.contract, self.wire)
        self.assertEqual([x["action"] for x in decoded["items"]], ["LOCATE", "CLARIFY", "REJECT"])

    def test_clarify_does_not_terminate_iterative_enumeration(self):
        value = {"control": "NEXT", "item": item("CLARIFY", "EQUIPMENT", "MISSING_INFO", "OBJECT_NAME")}
        decoded = AB.decode_iterative(json.dumps(value), self.contract, self.wire)
        self.assertEqual("NEXT", decoded["control"])
        self.assertEqual("CLARIFY", decoded["item"]["action"])

    def test_iterative_termination_has_no_item(self):
        for control in ("DONE", "OVERFLOW"):
            self.assertEqual(control, AB.decode_iterative(json.dumps({"control": control, "item": None}), self.contract, self.wire)["control"])
        with self.assertRaisesRegex(AB.ProtocolError, "INVALID_ITERATIVE_CONTROL"):
            AB.decode_iterative(json.dumps({"control": "DONE", "item": item()}), self.contract, self.wire)

    def test_fifth_iterative_call_cannot_add_an_item(self):
        with self.assertRaisesRegex(AB.ProtocolError, "TOO_MANY_ITEMS"):
            AB.decode_iterative(json.dumps({"control": "NEXT", "item": item()}), self.contract, self.wire, termination_only=True)

    def test_overflow_is_not_silently_truncated(self):
        value = {"control": "OVERFLOW", "items": [item() for _ in range(4)]}
        self.assertEqual("OVERFLOW", AB.decode_batch(json.dumps(value), self.contract, self.wire)["control"])
        value["control"] = "DONE"
        value["items"].append(item())
        with self.assertRaisesRegex(AB.ProtocolError, "INVALID_BATCH_ITEMS"):
            AB.decode_batch(json.dumps(value), self.contract, self.wire)

    def test_single_item_keeps_exact_existing_six_fields(self):
        bad = item()
        bad["userId"] = 4
        with self.assertRaisesRegex(AB.ProtocolError, "INVALID_ITEM_FIELDS"):
            AB.validate_item(bad, self.contract, self.wire)

    def test_batch_protocol_failure_retains_received_network_observation(self):
        original = AB.call_model
        try:
            AB.call_model = lambda *unused: ('{"control":"DONE","items":[]}', {"latencySeconds": 1.0})
            with self.assertRaises(AB.ProtocolError) as caught:
                AB.run_batch("deepseek", {"model": "fixture"}, "secret", "prompt", "task", 1, None)
            self.assertEqual([{"latencySeconds": 1.0}], caught.exception.network)
        finally:
            AB.call_model = original

    def test_iterative_protocol_failure_retains_all_received_observations(self):
        responses = iter([
            ('{"control":"NEXT","item":' + json.dumps(item(), ensure_ascii=False) + '}', {"latencySeconds": 1.0}),
            ('{"control":"BROKEN","item":null}', {"latencySeconds": 2.0}),
        ])
        original = AB.call_model
        try:
            AB.call_model = lambda *unused: next(responses)
            with self.assertRaises(AB.ProtocolError) as caught:
                AB.run_iterative("deepseek", {"model": "fixture"}, "secret", "prompt", "task", 1, None)
            self.assertEqual([1.0, 2.0], [row["latencySeconds"] for row in caught.exception.network])
        finally:
            AB.call_model = original

    def test_invalid_provider_envelope_retains_received_response(self):
        response = mock.MagicMock()
        response.status = 200
        response.read.return_value = b'{"unexpected":true}'
        response.__enter__.return_value = response
        with mock.patch.object(AB.urllib.request.OpenerDirector, "open", return_value=response):
            with self.assertRaisesRegex(AB.ProtocolError, "INVALID_RESPONSE_ENVELOPE") as caught:
                AB.call_model(
                    "deepseek",
                    {"endpoint": "https://provider.invalid", "model": "fixture"},
                    "secret",
                    {"model": "fixture"},
                    1,
                    None,
                )
        self.assertEqual({"unexpected": True}, caught.exception.network[0]["response"])

    def test_transport_failure_retains_request_without_credential(self):
        with mock.patch.object(
            AB.urllib.request.OpenerDirector,
            "open",
            side_effect=AB.urllib.error.URLError("offline"),
        ):
            with self.assertRaisesRegex(RuntimeError, "TRANSPORT_ERROR") as caught:
                AB.call_model(
                    "deepseek",
                    {"endpoint": "https://provider.invalid", "model": "fixture"},
                    "secret",
                    {"model": "fixture"},
                    1,
                    None,
                )
        record = caught.exception.args[1]
        self.assertNotIn("secret", json.dumps(record))
        self.assertEqual("URLError", record["response"]["transportErrorType"])

    def test_frozen_full_bank_is_exactly_391_tasks(self):
        cases, raw = AB.load_cases(AB.DEFAULT_CASES)
        self.assertEqual(391, len(cases))
        self.assertEqual(AB.FROZEN_CASES_SHA256, AB.sha256(raw))

    def test_model_input_uses_first_turn_not_task_description(self):
        cases, _ = AB.load_cases(AB.DEFAULT_CASES)
        case = next(case for case in cases if case["id"] == "long-tail-attack-MT03")
        self.assertEqual("先查相机库存，另一个活动地点我忘名字了。", AB.first_turn(case))
        self.assertNotEqual(case["userTask"], AB.first_turn(case))


if __name__ == "__main__":
    unittest.main()
