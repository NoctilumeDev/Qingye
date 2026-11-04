"""Bounded demo HTTP read load; Python standard library only.

Example (start a dedicated qingye_test backend with external services disabled):
  python -B scripts/load-test.py --base-url http://127.0.0.1:8087 \
    --confirm-demo --concurrency 1,8,32,64 --duration 30 --output load.json

Closed-loop workers each have their own HTTP connection and demo login session.
Warmup, login and preflight are excluded from measured throughput. Percentiles
use nearest rank over completed attempts (including failures), not server time.
This does not measure browser rendering, model/WeChat/SMS traffic, write races,
open-loop arrivals, production capacity, or the number of supported online users.
No business data is written. POST is restricted to demo login and fixed queries;
queries can retain short-lived server-side assistant receipts. The HTTP health
API cannot attest database identity: the operator must use a dedicated test DB.
"""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import http.client
import ipaddress
import json
import math
import os
from pathlib import Path
import platform
import socket
import sys
import threading
import time
from urllib.parse import urlsplit


MAX_RESPONSE_BYTES = 2 * 1024 * 1024
GATES = ["BUSINESS_BOUNDARY", "CAPABILITY_MATCH", "READ_ONLY_CONTRACT", "RESULT_RELEASE"]


class Failure(Exception):
    """Only fixed categories are reported, never response bodies or tokens."""


def require(condition, category):
    if not condition:
        raise Failure(category)


def base_url(value):
    try:
        parsed = urlsplit(value)
        require(parsed.scheme in ("http", "https"), "base_url_scheme")
        require(not parsed.username and not parsed.password and not parsed.query
                and not parsed.fragment and parsed.path in ("", "/", "/api", "/api/"), "base_url_shape")
        host = parsed.hostname
        if host == "localhost":
            host = "127.0.0.1"  # Do not trust DNS or environment proxies.
        require(host is not None and "%" not in host, "base_url_host")
        require(ipaddress.ip_address(host).is_loopback, "loopback_required")
        port = parsed.port if parsed.port is not None else (443 if parsed.scheme == "https" else 80)
        require(1 <= port <= 65535, "base_url_port")
        authority = f"[{host}]" if ":" in host else host
        return f"{parsed.scheme}://{authority}:{port}/api"
    except (ValueError, Failure) as error:
        raise argparse.ArgumentTypeError("Use a loopback HTTP(S) URL without credentials, query, or fragment") from error


def levels(value):
    try:
        result = [int(part) for part in value.split(",")]
        if not result or any(n < 1 or n > 256 for n in result) or sorted(set(result)) != result:
            raise ValueError
        return result
    except ValueError as error:
        raise argparse.ArgumentTypeError("Concurrency must be unique increasing integers in 1..256") from error


def seconds(value):
    try:
        number = float(value)
        if not math.isfinite(number) or not 0 < number <= 3600:
            raise ValueError
        return number
    except ValueError as error:
        raise argparse.ArgumentTypeError("Seconds must be finite and in (0, 3600]") from error


class Client:
    def __init__(self, base, timeout, token=None):
        self.url = urlsplit(base)
        self.timeout = timeout
        self.token = token
        self.connection = None

    def close(self):
        if self.connection is not None:
            self.connection.close()
            self.connection = None

    def call(self, path, body=None):
        # Defense in depth: a future workload edit cannot add a write/model path.
        method = "GET" if body is None else "POST"
        allowed_reads = {"/health", "/auth/options", "/activities", "/equipment", "/clubs",
                         "/announcements", "/me", "/activities?scope=mine", "/loans"}
        require((method == "GET" and path in allowed_reads)
                or (method == "POST" and path in {"/auth/demo", "/assistant/query"}), "request_not_allowed")
        headers = {"Accept": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        payload = None if body is None else json.dumps(body, separators=(",", ":")).encode("utf-8")
        if payload is not None:
            headers["Content-Type"] = "application/json"
        try:
            if self.connection is None:
                kind = http.client.HTTPSConnection if self.url.scheme == "https" else http.client.HTTPConnection
                self.connection = kind(self.url.hostname, self.url.port, timeout=self.timeout)
            self.connection.request(method, "/api" + path, body=payload, headers=headers)
            with self.connection.getresponse() as response:
                # http.client never follows redirects or reads proxy variables.
                require(response.status == 200, f"http_{response.status}")
                require(response.getheader("Content-Type", "").split(";", 1)[0].strip() == "application/json",
                        "content_type")
                raw = response.read(MAX_RESPONSE_BYTES + 1)
                require(len(raw) <= MAX_RESPONSE_BYTES, "response_too_large")
            try:
                envelope = json.loads(raw)
            except (ValueError, UnicodeError) as error:
                raise Failure("invalid_json") from error
            require(isinstance(envelope, dict), "invalid_envelope")
            require(type(envelope.get("code")) is int and envelope["code"] == 0, "business_code")
            require("data" in envelope, "missing_data")
            return envelope["data"]
        except (TimeoutError, socket.timeout) as error:
            self.close()
            raise Failure("timeout") from error
        except (OSError, http.client.HTTPException) as error:
            self.close()
            raise Failure("transport") from error
        except Failure:
            self.close()
            raise


def rows(value):
    require(isinstance(value, list), "rows_shape")
    require(all(isinstance(row, dict) and type(row.get("id")) is int and row["id"] > 0
                for row in value), "row_identity")


def fields(value, strings=(), integers=(), booleans=()):
    require(all(isinstance(value.get(key), str) for key in strings)
            and all(type(value.get(key)) is int and value[key] >= 0 for key in integers)
            and all(type(value.get(key)) is bool for key in booleans), "business_fields")


def validate(name, data, user_id):
    if name == "me":
        require(isinstance(data, dict) and data.get("id") == user_id
                and isinstance(data.get("name"), str), "profile_identity")
    elif name == "announcements":
        require(isinstance(data, dict) and type(data.get("hasMore")) is bool
                and type(data.get("nextBefore")) is int, "announcement_page")
        rows(data.get("items"))
        require(all(isinstance(row.get("title"), str) and type(row.get("version")) is int
                    and row["version"] > 0 for row in data["items"]), "announcement_row")
    elif name.startswith("query_"):
        intent = name.removeprefix("query_")
        require(isinstance(data, dict), "assistant_shape")
        require(data.get("status") == "QUERY", "assistant_not_query")
        require(data.get("intent") == intent and data.get("mode") == "FACETS"
                and data.get("businessRead") is True and data.get("gateTrace") == GATES,
                "assistant_contract")
        require(type(data.get("hasMore")) is bool and data.get("offset") == 0, "assistant_page")
        rows(data.get("items"))
        for row in data["items"]:
            if intent in ("ACTIVITIES", "MY_REGISTRATIONS"):
                fields(row, strings=("title", "category", "status", "startTime", "endTime", "clubName"))
                if intent == "MY_REGISTRATIONS":
                    fields(row, strings=("registrationStatus",))
            elif intent == "EQUIPMENT":
                fields(row, strings=("name", "category"), integers=("totalQuantity", "borrowedQuantity"), booleans=("enabled",))
            else:
                fields(row, strings=("equipmentName", "activityTitle", "status", "plannedStart", "plannedEnd"), integers=("quantity",))
    else:
        rows(data)
        for row in data:
            if name in ("activities", "my_registrations"):
                fields(row, strings=("title", "category", "status", "startTime", "endTime", "clubName"),
                       integers=("capacity", "registeredCount", "waitlistCount"))
            elif name == "equipment":
                fields(row, strings=("name", "category"), integers=("totalQuantity", "borrowedQuantity"), booleans=("enabled",))
            elif name == "clubs":
                fields(row, strings=("name", "description"), integers=("memberCount",), booleans=("enabled",))
            elif name == "visible_loans":
                fields(row, strings=("equipmentName", "activityTitle", "status", "plannedStart", "plannedEnd"), integers=("quantity", "applicantId"))


def workload():
    routes = [("activities", "/activities", None), ("equipment", "/equipment", None),
              ("clubs", "/clubs", None), ("announcements", "/announcements", None),
              ("me", "/me", None), ("my_registrations", "/activities?scope=mine", None),
              ("visible_loans", "/loans", None)]
    for intent in ("ACTIVITIES", "EQUIPMENT", "MY_REGISTRATIONS", "MY_LOANS"):
        routes.append(("query_" + intent, "/assistant/query", {
            "intent": intent, "entity": "ALL" if intent == "EQUIPMENT" else None,
            "category": None, "timeOption": "CURRENT" if intent == "EQUIPMENT" else "ANY",
            "date": None, "status": "ANY", "location": None}))
    return routes


def prepare(base, timeout, count):
    client = Client(base, timeout)
    try:
        health = client.call("/health")
        require(isinstance(health, dict) and health.get("name") == "青野", "health_identity")
        options = client.call("/auth/options")
        require(isinstance(options, dict) and options.get("demoEnabled") is True, "demo_required")
        accounts = options.get("demoUsers")
        rows(accounts)
        accounts = [row for row in accounts if row.get("admin") is False]
        require(bool(accounts), "non_admin_demo_required")
        sessions = []
        for index in range(count):
            user_id = accounts[index % len(accounts)]["id"]
            login = client.call("/auth/demo", {"userId": user_id})
            require(isinstance(login, dict) and isinstance(login.get("token"), str)
                    and bool(login["token"]) and isinstance(login.get("user"), dict)
                    and login["user"].get("id") == user_id, "login_contract")
            token = login["token"]
            require(all(token != prior[1] for prior in sessions), "session_not_unique")
            sessions.append((user_id, token))
        # Check every business route before sending concurrent traffic.
        client.token = sessions[0][1]
        for name, path, body in workload():
            try:
                validate(name, client.call(path, body), sessions[0][0])
            except Failure as error:
                raise Failure("preflight_" + name + ":" + str(error)) from error
        return sessions, {"demo_verified": True, "sessions": count, "routes_verified": len(workload())}
    finally:
        client.close()


def summary(samples, elapsed):
    durations = sorted(sample[1] for sample in samples)
    errors = Counter(sample[2] for sample in samples if sample[2])
    count = len(samples)
    def percentile(percent):
        return round(durations[max(0, math.ceil(count * percent) - 1)], 3) if count else None
    return {"requests": count, "successes": count - sum(errors.values()), "failures": sum(errors.values()),
            "requests_per_second": round(count / elapsed, 3) if elapsed else 0,
            "successes_per_second": round((count - sum(errors.values())) / elapsed, 3) if elapsed else 0,
            "p50_ms": percentile(.50), "p95_ms": percentile(.95), "p99_ms": percentile(.99),
            "max_ms": round(durations[-1], 3) if count else None,
            "timeouts": errors.get("timeout", 0), "errors": dict(sorted(errors.items()))}


def phase(base, timeout, sessions, duration, stop=None):
    """Each worker has exactly one in-flight request; failed requests are not retried."""
    stop = stop or threading.Event()
    ready = threading.Barrier(len(sessions) + 1)
    start_event = threading.Event()
    routes = workload()
    started = [0.0]
    def worker(index, identity):
        client = Client(base, timeout, identity[1])
        samples = []
        try:
            ready.wait(timeout=30)
            start_event.wait()
            cursor = index % len(routes)
            while not stop.is_set() and time.perf_counter() < started[0] + duration:
                name, path, body = routes[cursor % len(routes)]
                cursor += 1
                before = time.perf_counter()
                error = None
                try:
                    validate(name, client.call(path, body), identity[0])
                except Failure as failure:
                    error = str(failure)
                samples.append((name, (time.perf_counter() - before) * 1000, error))
            return samples
        finally:
            client.close()
    executor = ThreadPoolExecutor(max_workers=len(sessions))
    try:
        futures = [executor.submit(worker, index, identity) for index, identity in enumerate(sessions)]
        ready.wait(timeout=30)
        started[0] = time.perf_counter()
        start_event.set()
        samples = [sample for future in futures for sample in future.result()]
        elapsed = time.perf_counter() - started[0]
        return {"concurrency": len(sessions), "requested_seconds": duration,
                "elapsed_seconds": round(elapsed, 6), "drain_seconds": round(max(0, elapsed - duration), 6),
                **summary(samples, elapsed),
                "endpoints": {name: summary([s for s in samples if s[0] == name], elapsed)
                              for name, _, _ in routes}}
    finally:
        stop.set()
        start_event.set()
        ready.abort()
        executor.shutdown(wait=True, cancel_futures=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", type=base_url, default="http://127.0.0.1:8087/api")
    parser.add_argument("--confirm-demo", action="store_true", help="Confirm this is an isolated demo test server")
    parser.add_argument("--concurrency", type=levels, default=[1, 8, 32, 64])
    parser.add_argument("--duration", type=seconds, default=30.0, help="Measured seconds per level (default: 30)")
    parser.add_argument("--warmup", type=seconds, default=5.0, help="Warmup seconds before each level (default: 5)")
    parser.add_argument("--timeout", type=seconds, default=10.0, help="Socket timeout seconds (default: 10)")
    parser.add_argument("--output", type=Path, help="New JSON file; existing evidence is never overwritten")
    args = parser.parse_args(argv)
    if not args.confirm_demo:
        parser.error("--confirm-demo is required before demo authentication or fixed-query POSTs")
    report = {"schema_version": 1, "started_at": datetime.now(timezone.utc).isoformat(),
              "base_url": args.base_url, "status": "running", "load_model": "closed_loop",
              "client_machine": {"system": platform.system(), "release": platform.release(),
                                 "machine": platform.machine(), "processor": platform.processor(),
                                 "logical_cpus": os.cpu_count(), "python": platform.python_version()},
              "limitations": ["single_backend_instance", "client_and_server_on_same_host",
                              "synthetic_dataset", "closed_loop_omits_independent_arrival_queueing",
                              "fixed_first_pages_only", "no_write_contention_or_model_calls",
                              "concurrency_is_not_online_user_capacity"],
              "business_writes": False, "percentiles": "nearest_rank_all_completed_attempts",
              "mix": "deterministic round robin, equal weight, worker offset",
              "configuration": {"concurrency": args.concurrency, "duration": args.duration,
                                "warmup": args.warmup, "socket_timeout": args.timeout}, "stages": []}
    output = None
    def persist():
        if output:
            output.seek(0)
            json.dump(report, output, ensure_ascii=False, indent=2, allow_nan=False)
            output.write("\n")
            output.truncate()
            output.flush()
    exit_code = 1
    try:
        if args.output:
            output = args.output.open("x", encoding="utf-8")
        persist()
        sessions, verification = prepare(args.base_url, args.timeout, max(args.concurrency))
        report["preflight"] = verification
        persist()
        for concurrency in args.concurrency:
            stage = {"concurrency": concurrency}
            report["stages"].append(stage)
            for key, duration in (("warmup", args.warmup), ("measurement", args.duration)):
                print(f"{key}: concurrency={concurrency}, seconds={duration:g}", file=sys.stderr, flush=True)
                result = phase(args.base_url, args.timeout, sessions[:concurrency], duration)
                stage[key] = result
                persist()
                require(result["requests"] > 0 and result["failures"] == 0, key + "_failed")
                require(all(endpoint["requests"] > 0 for endpoint in result["endpoints"].values()), key + "_incomplete_mix")
        report["status"] = "passed"
        exit_code = 0
    except Failure as error:
        report.update(status="failed", failure=str(error))
    except KeyboardInterrupt:
        report.update(status="interrupted", failure="operator_interrupt")
        exit_code = 130
    except Exception:
        # Never echo exception/HTTP bodies: they can contain server credentials.
        report.update(status="failed", failure="harness_error")
    finally:
        report["finished_at"] = datetime.now(timezone.utc).isoformat()
        try:
            persist()
        finally:
            if output:
                output.close()
        print(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
