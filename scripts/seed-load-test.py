"""Append a reproducible read-load fixture to a dedicated local qingye_test DB.

Default is read-only planning. --apply requires --confirm-demo and a password in
QINGYE_LOAD_DB_PASSWORD. Uses the installed MySQL 8 CLI, never local.ps1 or app
secrets. Host/database are fixed; port 3306 is forbidden. Existing demo users,
announcements and unrelated records are never changed. Run only after the
operator has stopped competing writers; restart the backend after seeding to
clear caches. Fixture rows are synthetic query data, not API workflow evidence.
"""
import argparse
from datetime import date, datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


PREFIX = "QY_LOAD_V1_"
PATTERN = PREFIX.replace("_", "!_")
EXPECTED = {"club": 100, "club_member": 100, "activity": 3000,
            "equipment": 200, "registration": 1000, "loan": 400}
TABLES = ("app_user", "club", "club_member", "activity", "equipment", "registration", "loan", "announcement")


class SeedError(Exception):
    pass


def require(condition, category):
    if not condition:
        raise SeedError(category)


def port(value):
    number = int(value)
    if not 1024 <= number <= 65535 or number == 3306:
        raise argparse.ArgumentTypeError("Use the dedicated test instance port, never shared port 3306")
    return number


class Database:
    def __init__(self, executable, db_port):
        require(type(db_port) is int and 1024 <= db_port <= 65535 and db_port != 3306, "dedicated_port_required")
        self.command = [executable, "--no-defaults", "--protocol=TCP", "--host=127.0.0.1",
                        f"--port={db_port}", "--user=qingye_qa", "--database=qingye_test",
                        "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names"]
        self.environment = dict(os.environ, MYSQL_PWD=os.environ.get("QINGYE_LOAD_DB_PASSWORD", ""))
        require(bool(self.environment["MYSQL_PWD"]), "QINGYE_LOAD_DB_PASSWORD_required")

    def execute(self, sql):
        try:
            result = subprocess.run(self.command, input=sql, text=True, encoding="utf-8",
                                    capture_output=True, env=self.environment, timeout=120)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise SeedError("mysql_transport_failed") from error
        if result.returncode != 0:
            error = re.search(r"ERROR (\d+).*?at line (\d+)", result.stderr)
            raise SeedError("mysql_error_" + error.group(1) + "_line_" + error.group(2) if error else "mysql_command_failed")
        return result.stdout.strip()

    def json(self, sql):
        try:
            return json.loads(self.execute(sql))
        except ValueError as error:
            raise SeedError("mysql_invalid_json") from error


def owned_counts_sql():
    pieces = [f"'club',(SELECT COUNT(*) FROM club WHERE name LIKE '{PATTERN}club!_%' ESCAPE '!')",
              f"'club_member',(SELECT COUNT(*) FROM club_member m JOIN club c ON c.id=m.club_id WHERE c.name LIKE '{PATTERN}club!_%' ESCAPE '!')",
              f"'activity',(SELECT COUNT(*) FROM activity WHERE title LIKE '{PATTERN}activity!_%' ESCAPE '!')",
              f"'equipment',(SELECT COUNT(*) FROM equipment WHERE name LIKE '{PATTERN}equipment!_%' ESCAPE '!')",
              f"'registration',(SELECT COUNT(*) FROM registration r JOIN activity a ON a.id=r.activity_id WHERE a.title LIKE '{PATTERN}activity!_%' ESCAPE '!')",
              f"'loan',(SELECT COUNT(*) FROM loan WHERE request_key LIKE '{PATTERN}loan!_%' ESCAPE '!')"]
    return "SELECT JSON_OBJECT(" + ",".join(pieces) + ");"


def snapshot(db):
    counts = db.json("SELECT JSON_OBJECT(" + ",".join(f"'{table}',(SELECT COUNT(*) FROM {table})" for table in TABLES) + ");")
    owned = db.json(owned_counts_sql())
    return {"total": counts, "fixture": owned}


def protected_fingerprint(db):
    # Private values never enter the report; only two hashes are compared in memory.
    return db.execute("SET SESSION group_concat_max_len=16777216; SELECT "
                      "SHA2(COALESCE((SELECT GROUP_CONCAT(JSON_ARRAY(id,openid,name,avatar,admin,enabled,created_at) ORDER BY id) FROM app_user),''),256),"
                      "SHA2(COALESCE((SELECT GROUP_CONCAT(JSON_ARRAY(id,builtin_key,builtin_revision,title,body,category,version,created_by,created_at,updated_at,deleted_at) ORDER BY id) FROM announcement),''),256);")


def seed_sql(anchor, db_port, admin, users):
    """All interpolations are validated dates/integers or source-owned constants."""
    anchor = date.fromisoformat(anchor).isoformat()
    require(len(users) == 4 and len(set(users)) == 4, "four_demo_users_required")
    require(type(admin) is int and all(type(user) is int for user in users), "invalid_account_ids")
    require(1024 <= db_port <= 65535 and db_port != 3306, "dedicated_port_required")
    user_choice = "ELT(MOD(n.n-1,4)+1," + ",".join(map(str, users)) + ")"
    users_by_club = "ELT(MOD(MOD(n.n-1,100),4)+1," + ",".join(map(str, users)) + ")"
    registration_user = "ELT(MOD(n.n-1+FLOOR((n.n-1)/4),4)+1," + ",".join(map(str, users)) + ")"
    numbers = ",".join(f"({n})" for n in range(1, 3001))
    empty_fixture = " AND ".join(f"NOT EXISTS(SELECT 1 FROM {table} WHERE {column} LIKE '{PATTERN}{kind}!_%' ESCAPE '!')"
                                 for table, column, kind in (("club", "name", "club"), ("activity", "title", "activity"),
                                                             ("equipment", "name", "equipment"), ("loan", "request_key", "loan")))
    return f"""SET SESSION time_zone='+08:00';
START TRANSACTION;
CREATE TEMPORARY TABLE qingye_load_guard(ok INT NOT NULL CHECK(ok=1));
INSERT INTO qingye_load_guard VALUES ((DATABASE()='qingye_test' AND @@port={db_port} AND GET_LOCK('qingye_load_seed_v1',0)=1));
INSERT INTO qingye_load_guard VALUES (({empty_fixture}));
CREATE TEMPORARY TABLE qingye_load_numbers(n INT PRIMARY KEY);
INSERT INTO qingye_load_numbers VALUES {numbers};
INSERT INTO club(name,description,color,enabled,created_by,created_at)
SELECT CONCAT('{PREFIX}club_',LPAD(n.n,3,'0')),'Synthetic load fixture v1','green',TRUE,{admin},'{anchor} 08:00:00'
FROM qingye_load_numbers n WHERE n.n<=100;
INSERT INTO club_member(club_id,user_id,role,status,joined_at)
SELECT c.id,{user_choice},'MANAGER','ACTIVE','{anchor} 08:00:00'
FROM qingye_load_numbers n JOIN club c ON c.name=CONCAT('{PREFIX}club_',LPAD(n.n,3,'0')) WHERE n.n<=100;
INSERT INTO activity(club_id,created_by,title,description,category,location,start_time,end_time,signup_deadline,capacity,status,reviewed_by,reviewed_at,created_at)
SELECT c.id,{users_by_club},CONCAT('{PREFIX}activity_',LPAD(n.n,4,'0')),
REPEAT('Synthetic campus activity description. ',8),ELT(MOD(n.n-1,5)+1,'ART','SPORT','TECH','VOLUNTEER','OTHER'),
CONCAT('Campus room ',MOD(n.n,20)+1),DATE_ADD('{anchor} 14:00:00',INTERVAL (MOD(n.n,121)-60) DAY),
DATE_ADD('{anchor} 16:00:00',INTERVAL (MOD(n.n,121)-60) DAY),DATE_ADD('{anchor} 14:00:00',INTERVAL (MOD(n.n,121)-61) DAY),
IF(MOD(n.n,10)=3,2,50),ELT(LEAST(MOD(n.n,10)+1,4),'CANCELLED','PENDING','REJECTED','PUBLISHED'),
IF(MOD(n.n,10)=1,NULL,{admin}),IF(MOD(n.n,10)=1,NULL,'{anchor} 08:00:00'),'{anchor} 08:00:00'
FROM qingye_load_numbers n JOIN club c ON c.name=CONCAT('{PREFIX}club_',LPAD(MOD(n.n-1,100)+1,3,'0'));
INSERT INTO equipment(name,category,description,total_quantity,enabled,created_at)
SELECT CONCAT('{PREFIX}equipment_',LPAD(n.n,3,'0')),ELT(MOD(n.n-1,4)+1,'Photo','Sport','Audio','Technology'),
REPEAT('Synthetic equipment description. ',4),20,TRUE,'{anchor} 08:00:00'
FROM qingye_load_numbers n WHERE n.n<=200;
INSERT INTO registration(activity_id,user_id,status,joined_at)
SELECT a.id,{registration_user},ELT(MOD(n.n-1,4)+1,'REGISTERED','REGISTERED','WAITLISTED','CANCELLED'),'{anchor} 09:00:00'
FROM qingye_load_numbers n JOIN activity a ON a.title=CONCAT('{PREFIX}activity_',LPAD(FLOOR((n.n-1)/4)*10+3,4,'0')) WHERE n.n<=1000;
INSERT INTO loan(activity_id,applicant_id,equipment_id,quantity,planned_start,planned_end,reason,status,request_key,reviewed_by,reviewed_at,checked_out_at,returned_at,created_at)
SELECT a.id,a.created_by,e.id,1,'{anchor} 08:00:00','{anchor} 18:00:00','Synthetic read load fixture',
ELT(MOD(n.n-1,6)+1,'PENDING','APPROVED','CHECKED_OUT','RETURNED','CANCELLED','REJECTED'),
CONCAT('{PREFIX}loan_',LPAD(n.n,4,'0')),IF(MOD(n.n-1,6)=0,NULL,{admin}),
IF(MOD(n.n-1,6)=0,NULL,'{anchor} 07:00:00'),IF(MOD(n.n-1,6) IN (2,3),'{anchor} 08:00:00',NULL),
IF(MOD(n.n-1,6)=3,'{anchor} 17:00:00',NULL),'{anchor} 06:00:00'
FROM qingye_load_numbers n
JOIN activity a ON a.title=CONCAT('{PREFIX}activity_',LPAD(FLOOR((n.n-1)/4)*10+3+MOD(n.n-1,4),4,'0'))
JOIN equipment e ON e.name=CONCAT('{PREFIX}equipment_',LPAD(MOD(n.n-1,200)+1,3,'0')) WHERE n.n<=400;
INSERT INTO qingye_load_guard VALUES (((SELECT COUNT(*) FROM club WHERE name LIKE '{PATTERN}club!_%' ESCAPE '!')=100
AND (SELECT COUNT(*) FROM activity WHERE title LIKE '{PATTERN}activity!_%' ESCAPE '!')=3000
AND (SELECT COUNT(*) FROM equipment WHERE name LIKE '{PATTERN}equipment!_%' ESCAPE '!')=200
AND (SELECT COUNT(*) FROM registration r JOIN activity a ON a.id=r.activity_id WHERE a.title LIKE '{PATTERN}activity!_%' ESCAPE '!')=1000
AND (SELECT COUNT(*) FROM loan WHERE request_key LIKE '{PATTERN}loan!_%' ESCAPE '!')=400));
COMMIT;
DO RELEASE_LOCK('qingye_load_seed_v1');
"""


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True, type=port)
    parser.add_argument("--anchor", required=True, type=date.fromisoformat, help="Reproducible campus date: YYYY-MM-DD")
    parser.add_argument("--mysql", default=shutil.which("mysql"))
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--confirm-demo", action="store_true")
    parser.add_argument("--output", type=Path, help="New JSON evidence file; existing files are refused")
    args = parser.parse_args(argv)
    if args.apply and not args.confirm_demo:
        parser.error("--apply requires --confirm-demo for an isolated demo database")
    report = {"started_at": datetime.now(timezone.utc).isoformat(), "status": "failed",
              "host": "127.0.0.1", "port": args.port, "database": "qingye_test",
              "anchor": args.anchor.isoformat(), "prefix": PREFIX, "expected_added": EXPECTED, "apply": args.apply}
    stream = None
    code = 1
    try:
        if args.output:
            stream = args.output.open("x", encoding="utf-8")
        require(bool(args.mysql), "mysql_cli_required")
        db = Database(args.mysql, args.port)
        identity = db.json("SELECT JSON_OBJECT('database',DATABASE(),'port',@@port,'version',@@version);")
        require(identity.get("database") == "qingye_test" and identity.get("port") == args.port, "test_database_identity_required")
        report["server"] = identity
        users = db.json("SELECT JSON_ARRAYAGG(JSON_OBJECT('id',id,'admin',admin)) FROM app_user WHERE enabled=TRUE AND openid IN ('demo:admin','demo:photo','demo:student','demo:sport','demo:other');")
        require(isinstance(users, list), "builtin_demo_accounts_required")
        admins = [user["id"] for user in users if user["admin"] == 1]
        regular = sorted(user["id"] for user in users if user["admin"] == 0)
        require(len(admins) == 1 and len(regular) == 4, "five_builtin_demo_accounts_required")
        before = snapshot(db)
        report["before"] = before
        existing = before["fixture"]
        require(all(count == 0 for count in existing.values()) or existing == EXPECTED, "partial_or_changed_fixture_refused")
        if existing == EXPECTED:
            anchor = db.execute(f"SELECT DATE_FORMAT(MIN(created_at),'%Y-%m-%d') FROM club WHERE name LIKE '{PATTERN}club!_%' ESCAPE '!';")
            require(anchor == args.anchor.isoformat(), "fixture_anchor_mismatch")
            report["status"] = "already_present"
        elif args.apply:
            protected_before = protected_fingerprint(db)
            db.execute(seed_sql(args.anchor.isoformat(), args.port, admins[0], regular))
            after = snapshot(db)
            report["after"] = after
            require(after["fixture"] == EXPECTED, "fixture_count_mismatch")
            require(protected_fingerprint(db) == protected_before, "users_or_announcements_changed")
            report.update(status="seeded", users_and_announcements_unchanged=True)
        else:
            report["status"] = "planned_only"
        code = 0
    except SeedError as error:
        report["failure"] = str(error)
    except Exception:
        report["failure"] = "seed_harness_error"
    finally:
        report["finished_at"] = datetime.now(timezone.utc).isoformat()
        rendered = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False)
        if stream:
            with stream:
                stream.write(rendered + "\n")
        print(rendered)
    return code


if __name__ == "__main__":
    sys.exit(main())
