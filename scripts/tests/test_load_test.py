"""Load harness guardrails and failure accounting; no real Qingye service needed."""
import argparse
from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import Mock, patch


SOURCE = Path(__file__).resolve().parents[1] / "load-test.py"
SPEC = importlib.util.spec_from_file_location("qingye_load_test", SOURCE)
LOAD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LOAD)


class LoadHarnessTest(unittest.TestCase):
    def test_loopback_urls_and_rejected_remote_or_credential_urls(self):
        self.assertEqual(LOAD.base_url("http://localhost:8087"), "http://127.0.0.1:8087/api")
        self.assertEqual(LOAD.base_url("http://[::1]:8087/api/"), "http://[::1]:8087/api")
        for value in ("http://example.com", "http://127.0.0.1.example.com", "http://192.168.1.1",
                      "http://user:password@127.0.0.1", "http://127.0.0.1:0", "file:///tmp/api",
                      "http://127.0.0.1/path", "http://127.0.0.1?token=secret", "http://127.0.0.1#secret"):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                LOAD.base_url(value)

    def test_bounds_reject_nonfinite_and_unbounded_work(self):
        self.assertEqual(LOAD.levels("1,8,32,64"), [1, 8, 32, 64])
        for value in ("0", "257", "2,1", "1,1", "1,", ""):
            with self.assertRaises(argparse.ArgumentTypeError):
                LOAD.levels(value)
        for value in ("nan", "inf", "-1", "0", "3601"):
            with self.assertRaises(argparse.ArgumentTypeError):
                LOAD.seconds(value)

    def response(self, payload, status=200, content_type="application/json"):
        response = Mock()
        response.status = status
        response.getheader.return_value = content_type
        response.read.return_value = json.dumps(payload).encode("utf-8")
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        client = LOAD.Client("http://127.0.0.1:8087/api", 1)
        client.connection = Mock()
        client.connection.getresponse.return_value = response
        return client

    def test_http_200_business_failures_and_malformed_envelopes_are_failures(self):
        for payload, category in (({"code": 401, "data": []}, "business_code"),
                                  ({"code": False, "data": []}, "business_code"),
                                  ({"code": "0", "data": []}, "business_code"),
                                  ({"code": 0}, "missing_data"), ([], "invalid_envelope")):
            with self.subTest(category=category), self.assertRaisesRegex(LOAD.Failure, category):
                self.response(payload).call("/activities")

    def test_redirects_are_not_followed_and_error_body_is_not_returned(self):
        client = self.response({"token": "should-never-appear"}, status=302)
        connection = client.connection
        with self.assertRaisesRegex(LOAD.Failure, "^http_302$"):
            client.call("/health")
        connection.getresponse.return_value.read.assert_not_called()
        connection.close.assert_called_once()

    def test_oversize_non_json_and_timeout_fail_closed(self):
        client = self.response({})
        client.connection.getresponse.return_value.read.return_value = b"x" * (LOAD.MAX_RESPONSE_BYTES + 1)
        with self.assertRaisesRegex(LOAD.Failure, "response_too_large"):
            client.call("/health")
        client = self.response({})
        client.connection.getresponse.return_value.read.return_value = b"not JSON"
        with self.assertRaisesRegex(LOAD.Failure, "invalid_json"):
            client.call("/health")
        client = self.response({})
        client.connection.request.side_effect = socket.timeout("do not expose secret")
        with self.assertRaisesRegex(LOAD.Failure, "^timeout$"):
            client.call("/health")

    def test_workload_cannot_call_writes_or_model_paths(self):
        for path in ("/assistant", "/auth/wechat", "/admin/announcements", "/activities", "/loans"):
            with self.assertRaisesRegex(LOAD.Failure, "request_not_allowed"):
                LOAD.Client("http://127.0.0.1:8087/api", 1).call(path, {})

    def test_flag_required_before_any_network_io(self):
        with patch.object(LOAD, "prepare") as prepare, redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as result:
            LOAD.main([])
        self.assertEqual(result.exception.code, 2)
        prepare.assert_not_called()

    def test_health_and_demo_check_precede_login(self):
        for responses, expected in (([{"name": "wrong"}], "health_identity"),
                                    ([{"name": "青野"}, {"demoEnabled": False}], "demo_required")):
            client = Mock()
            client.call.side_effect = responses
            with patch.object(LOAD, "Client", return_value=client), self.assertRaisesRegex(LOAD.Failure, expected):
                LOAD.prepare("http://127.0.0.1:8087/api", 1, 2)
            self.assertTrue(all(call.args[0] in ("/health", "/auth/options") for call in client.call.call_args_list))

    def test_sessions_must_be_distinct_for_assistant_isolation(self):
        client = Mock()
        client.call.side_effect = [{"name": "青野"}, {"demoEnabled": True, "demoUsers": [{"id": 1, "admin": False}]},
                                  {"token": "same", "user": {"id": 1}}, {"token": "same", "user": {"id": 1}}]
        with patch.object(LOAD, "Client", return_value=client), self.assertRaisesRegex(LOAD.Failure, "session_not_unique"):
            LOAD.prepare("http://127.0.0.1:8087/api", 1, 2)

    def test_assistant_business_stop_is_not_a_success(self):
        with self.assertRaisesRegex(LOAD.Failure, "assistant_not_query"):
            LOAD.validate("query_ACTIVITIES", {"status": "CLARIFY", "items": []}, 1)
        good = {"status": "QUERY", "intent": "ACTIVITIES", "mode": "FACETS", "businessRead": True,
                "gateTrace": LOAD.GATES, "hasMore": False, "offset": 0, "items": []}
        LOAD.validate("query_ACTIVITIES", good, 1)
        with self.assertRaisesRegex(LOAD.Failure, "assistant_contract"):
            LOAD.validate("query_ACTIVITIES", dict(good, mode="MODEL"), 1)
        with self.assertRaisesRegex(LOAD.Failure, "profile_identity"):
            LOAD.validate("me", {"id": 2, "name": "other user"}, 1)

    def test_percentiles_failure_counts_and_throughput_include_drain(self):
        samples = [("route", value, "timeout" if value == 100 else None) for value in range(1, 101)]
        result = LOAD.summary(samples, 20)
        self.assertEqual((result["p50_ms"], result["p95_ms"], result["p99_ms"]), (50, 95, 99))
        self.assertEqual((result["requests"], result["successes"], result["failures"], result["timeouts"]), (100, 99, 1, 1))
        self.assertEqual(result["requests_per_second"], 5)
        self.assertEqual(result["successes_per_second"], 4.95)

    def test_business_rows_require_actual_payload_fields_not_only_an_id(self):
        for name in ("activities", "my_registrations", "equipment", "clubs", "visible_loans"):
            with self.subTest(name=name), self.assertRaisesRegex(LOAD.Failure, "business_fields"):
                LOAD.validate(name, [{"id": 1}], 1)
        with self.assertRaisesRegex(LOAD.Failure, "business_fields"):
            LOAD.validate("equipment", [{"id": 1, "name": "camera", "category": "photo",
                                         "totalQuantity": True, "borrowedQuantity": 0, "enabled": True}], 1)

    def test_failed_phase_is_preserved_exits_nonzero_and_stops_later_levels(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "result.json"
            output = io.StringIO()
            failed = {"requests": 2, "failures": 1, "errors": {"assistant_not_query": 1}}
            with patch.object(LOAD, "prepare", return_value=([(1, "PRIVATE_TOKEN")], {"demo_verified": True})), \
                    patch.object(LOAD, "phase", return_value=failed) as phase, redirect_stdout(output), redirect_stderr(io.StringIO()):
                code = LOAD.main(["--confirm-demo", "--output", str(destination)])
            self.assertNotEqual(code, 0)
            phase.assert_called_once()
            report = json.loads(destination.read_text(encoding="utf-8"))
            self.assertEqual(report["status"], "failed")
            self.assertEqual(report["stages"][0]["warmup"], failed)
            self.assertNotIn("PRIVATE_TOKEN", output.getvalue() + destination.read_text(encoding="utf-8"))

    def test_existing_evidence_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "result.json"
            destination.write_text("first failure", encoding="utf-8")
            with patch.object(LOAD, "prepare") as prepare, redirect_stdout(io.StringIO()):
                code = LOAD.main(["--confirm-demo", "--output", str(destination)])
            self.assertNotEqual(code, 0)
            self.assertEqual(destination.read_text(encoding="utf-8"), "first failure")
            prepare.assert_not_called()

    def test_phase_classifies_failure_without_retry_or_hiding_partial_success(self):
        clients = []
        def client_factory(*args):
            client = Mock()
            client.call.side_effect = LOAD.Failure("business_code")
            clients.append(client)
            return client
        with patch.object(LOAD, "Client", side_effect=client_factory):
            result = LOAD.phase("http://127.0.0.1:8087/api", 1, [(1, "A"), (1, "B")], .01)
        attempts = sum(client.call.call_count for client in clients)
        self.assertGreater(attempts, 0)
        self.assertEqual(result["requests"], attempts)
        self.assertEqual(result["failures"], attempts)
        self.assertEqual(result["errors"], {"business_code": attempts})
        self.assertEqual(sum(endpoint["requests"] for endpoint in result["endpoints"].values()), attempts)


if __name__ == "__main__":
    unittest.main()
