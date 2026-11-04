"""Fixture preparation safety checks; no database connection or writes."""
import argparse
from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import os
from pathlib import Path
import re
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location("qingye_seed_load_test", Path(__file__).resolve().parents[1] / "seed-load-test.py")
SEED = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SEED)


class SeedLoadTest(unittest.TestCase):
    def test_shared_and_invalid_ports_are_refused(self):
        for number in ("3306", "0", "80", "65536"):
            with self.assertRaises(argparse.ArgumentTypeError):
                SEED.port(number)
        self.assertEqual(SEED.port("19324"), 19324)

    def test_password_is_not_a_process_argument_and_connection_is_pinned(self):
        with patch.dict(os.environ, QINGYE_LOAD_DB_PASSWORD="test-fixture-password"):
            db = SEED.Database("mysql", 19324)
        self.assertNotIn("test-fixture-password", " ".join(db.command))
        self.assertIn("--no-defaults", db.command)
        self.assertIn("--database=qingye_test", db.command)
        self.assertIn("--host=127.0.0.1", db.command)
        self.assertIn("--port=19324", db.command)
        self.assertIn("--user=qingye_qa", db.command)

    def test_sql_guards_precede_all_business_inserts_and_uses_one_transaction(self):
        sql = SEED.seed_sql("2026-11-04", 19324, 1, [2, 3, 4, 5])
        self.assertLess(sql.index("DATABASE()='qingye_test'"), sql.index("INSERT INTO club("))
        self.assertIn("@@port=19324", sql)
        self.assertIn("CHECK(ok=1)", sql)
        self.assertIn("GET_LOCK('qingye_load_seed_v1',0)", sql)
        self.assertEqual(sql.count("START TRANSACTION;"), 1)
        self.assertEqual(sql.count("COMMIT;"), 1)
        self.assertNotRegex(sql, re.compile(r"\b(UPDATE|DELETE|DROP|TRUNCATE|REPLACE)\b", re.IGNORECASE))
        self.assertNotRegex(sql, r"INSERT INTO (app_user|announcement)")
        self.assertIn("QY!_LOAD!_V1!_club!_%' ESCAPE '!'", sql)

    def test_sql_inputs_do_not_accept_sql_fragments(self):
        with self.assertRaises(ValueError):
            SEED.seed_sql("2026-11-04';DROP TABLE club;--", 19324, 1, [2, 3, 4, 5])
        with self.assertRaises(SEED.SeedError):
            SEED.seed_sql("2026-11-04", 3306, 1, [2, 3, 4, 5])
        with self.assertRaises(SEED.SeedError):
            SEED.seed_sql("2026-11-04", 19324, 1, [2, 3, 4, "5;"])

    def test_apply_requires_explicit_confirmation_before_connection(self):
        with patch.object(SEED, "Database") as database, redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            SEED.main(["--port", "19324", "--anchor", "2026-11-04", "--apply"])
        database.assert_not_called()

    def test_runtime_database_mismatch_refuses_mutation(self):
        db = Mock()
        db.json.return_value = {"database": "qingye", "port": 19324}
        with patch.object(SEED, "Database", return_value=db), redirect_stdout(io.StringIO()):
            result = SEED.main(["--port", "19324", "--anchor", "2026-11-04", "--mysql", "mysql", "--apply", "--confirm-demo"])
        self.assertNotEqual(result, 0)
        db.execute.assert_not_called()

    def test_default_only_plans_and_never_executes_insert_sql(self):
        db = Mock()
        db.json.side_effect = [{"database": "qingye_test", "port": 19324},
                               [{"id": n, "admin": int(n == 1)} for n in range(1, 6)],
                               {}, {key: 0 for key in SEED.EXPECTED}]
        output = io.StringIO()
        with patch.object(SEED, "Database", return_value=db), redirect_stdout(output):
            result = SEED.main(["--port", "19324", "--anchor", "2026-11-04", "--mysql", "mysql"])
        self.assertEqual(result, 0)
        self.assertIn('"planned_only"', output.getvalue())
        db.execute.assert_not_called()

    def test_partial_fixture_is_refused_without_repairing_or_deleting(self):
        db = Mock()
        db.json.side_effect = [{"database": "qingye_test", "port": 19324},
                               [{"id": n, "admin": int(n == 1)} for n in range(1, 6)],
                               {}, dict(SEED.EXPECTED, activity=2999)]
        output = io.StringIO()
        with patch.object(SEED, "Database", return_value=db), redirect_stdout(output):
            result = SEED.main(["--port", "19324", "--anchor", "2026-11-04", "--mysql", "mysql", "--apply", "--confirm-demo"])
        self.assertNotEqual(result, 0)
        self.assertIn('partial_or_changed_fixture_refused', output.getvalue())
        db.execute.assert_not_called()


if __name__ == "__main__":
    unittest.main()
