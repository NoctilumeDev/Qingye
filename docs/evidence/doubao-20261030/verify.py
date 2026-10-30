"""Verify and re-score the retained Doubao run; no API calls or product mutations."""
import collections
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
import sys
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parent


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def lines(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def phase_rows(root, phase):
    rows = [r for shard in range(4) for r in lines(root / phase / str(shard) / "results.jsonl")]
    require(len({r["caseId"] for r in rows}) == len(rows), "duplicate task in " + phase)
    return {r["caseId"]: r for r in rows}


def main():
    manifest = read(ROOT / "publication-manifest.json")
    archive = ROOT / manifest["archive"]
    require(digest(archive.read_bytes()) == manifest["archiveSha256"], "archive hash differs")
    for name, expected in manifest["publicCopies"].items():
        require(digest((ROOT / name).read_bytes()) == expected, "public copy differs: " + name)
    patterns = [r"\bsk-(?:proj-|svcacct-|ws-)?[A-Za-z0-9_.-]{20,}",
                r"\bark-[a-f0-9-]{32,}", r"Bearer [A-Za-z0-9_.-]{15,}",
                r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"]
    with tempfile.TemporaryDirectory(prefix="qingye-doubao-verify-") as temp:
        dest = Path(temp).resolve()
        with zipfile.ZipFile(archive) as z:
            require(len(z.namelist()) == len(set(z.namelist())), "duplicate archive member")
            require(set(z.namelist()) == set(manifest["members"]), "archive inventory differs")
            for name in z.namelist():
                parts = PurePosixPath(name)
                require(not parts.is_absolute() and ".." not in parts.parts and "\\" not in name,
                        "unsafe archive path")
                target = (dest / name).resolve()
                require(target.is_relative_to(dest), "archive path escapes temporary directory")
                data = z.read(name)
                require(digest(data) == manifest["members"][name], "member hash differs: " + name)
                text = data.decode("utf-8-sig")
                require(not any(re.search(p, text) for p in patterns), "credential pattern: " + name)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)

        full, pilot = dest / "full", dest / "pilot"
        original = read(full / "manifest.json")
        hashes = read(full / "artifact-hashes.json")
        # Original manifests also cover omitted class files and the local classpath.
        for inventory in [original["files"], hashes]:
            for name, expected in inventory.items():
                path = full / name.replace("\\", "/")
                if path.exists():
                    require(digest(path.read_bytes()) == expected, "original hash differs: " + name)
                else:
                    require(name.endswith(".class") or name == "classpath.txt", "missing original input: " + name)
        for entry in manifest["sourceReadback"]:
            variants = {entry["gitLfSha256"], entry["gitCrlfSha256"]}
            require(entry["recordedHash"] in variants and entry["workingHash"] in variants,
                    "unexplained source mismatch: " + entry["path"])
            require(entry["sameAsPublicationBase"] is True, "publication base changed source")

        spec = importlib.util.spec_from_file_location("frozen_doubao_grade", full / "grade.py")
        grader = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(grader)
        grader.oracle = read(full / "business-oracle.json")
        cases = read(full / "cases.json")
        require(len(cases) == len({c["id"] for c in cases}) == 391, "case bank differs")
        base = phase_rows(full, "base")
        continuation = phase_rows(full, "continuation")
        require(set(base) == {c["id"] for c in cases}, "base tasks differ from bank")
        require(len(continuation) == 37 and set(continuation) <= set(base), "continuations differ")
        selection = read(full / "continuation-selection.json")
        require({c["id"] for c in selection} == set(continuation), "selection differs")
        audit = read(full / "continuation-audit.json")
        expected_audit = {p["caseId"]: p for p in audit["prefix"]}
        for cid, row in continuation.items():
            first = base[cid]
            require(row["modelAttempts"][0]["candidate"] == first["modelAttempts"][0]["candidate"],
                    "first candidate changed: " + cid)
            for key in ["status", "reason", "plan", "items", "intent", "qualificationFailure"]:
                require(row["observations"][0]["result"].get(key) == first["observations"][0]["result"].get(key),
                        "first result changed: " + cid)
            require([p.get("items") for p in row["observations"][0]["pages"]] ==
                    [p.get("items") for p in first["observations"][0]["pages"]], "first pages changed")
            require(all(expected_audit[cid][key] is True for key in
                        ["sameCandidate", "sameFirstResult", "sameFirstPages"]), "prefix audit differs")

        fixture = read(full / "gpt/run1-0/fixture.json")
        references = read(full / "gpt/run1-0/references.json")
        for phase in ["base", "continuation"]:
            for shard in range(4):
                folder = full / phase / str(shard)
                require(read(folder / "summary.json")["state"] == "COMPLETED", "incomplete shard")
                require(read(folder / "fixture.json") == fixture, "fixture differs")
                require(read(folder / "references.json") == references, "references differ")
        for row in [*base.values(), *continuation.values()]:
            flags = row["security"]
            require(set(flags) == {"subject", "canary", "fields", "readonly"} and
                    all(value is True for value in flags.values()), "security flags failed")

        final = {**base, **continuation}
        scores = read(full / "task-results.json")
        require(len(scores) == len({s["caseId"] for s in scores}) == 391, "score inventory differs")
        for saved in scores:
            actual = grader.grade(final[saved["caseId"]])
            require(all(saved[k] == v for k, v in actual.items()), "score changed: " + saved["caseId"])
        summary = read(full / "summary.json")
        require(dict(collections.Counter(s["verdict"] for s in scores)) == summary["counts"], "final counts differ")
        require(dict(collections.Counter(grader.grade(r)["verdict"] for r in base.values())) ==
                summary["baseCounts"], "base counts differ")
        transitions = collections.Counter(grader.grade(base[cid])["verdict"] + " -> " +
                                          grader.grade(row)["verdict"] for cid, row in continuation.items())
        require(dict(transitions) == summary["continuationTransitions"], "transitions differ")
        stops = read(full / "stop-review.json")
        require({s["caseId"] for s in scores if s["verdict"] == "STOP"} ==
                {s["caseId"] for s in stops["rows"]}, "stop coverage differs")
        require(dict(collections.Counter(s["stopCategory"] for s in stops["rows"])) == stops["counts"],
                "stop subclass counts differ")

        network = [r for phase in ["base", "continuation"] for shard in range(4)
                   for r in lines(full / phase / str(shard) / "network.jsonl")]
        live = [r for r in network if r["mode"].startswith("LIVE_")]
        require(len(live) == summary["network"]["liveCalls"] == 436 and
                len(network) - len(live) == summary["network"]["replayedFirstCandidates"] == 37,
                "call counts differ")
        usage = collections.Counter()
        for row in live:
            require(row["httpStatus"] == 200, "HTTP failure")
            require(row["upstreamPayload"] == {**row["payload"], "thinking": {"type": "disabled"}},
                    "unexpected transport mutation")
            body = json.loads(row["body"])
            require(body["model"] == summary["model"], "response model differs")
            require(all(c["finish_reason"] == "stop" for c in body["choices"]), "incomplete response")
            for key in ["prompt_tokens", "completion_tokens", "total_tokens"]:
                usage[key] += body["usage"].get(key, 0)
            usage["reasoning_tokens"] += body["usage"].get("completion_tokens_details", {}).get("reasoning_tokens", 0)
        require(dict(usage) == summary["network"]["usage"], "usage differs")
        elapsed = sorted(row["elapsed"] for row in live)
        latency = {k: round(elapsed[int((len(elapsed) - 1) * p)], 3)
                   for k, p in [("p50", .5), ("p90", .9), ("p95", .95)]}
        latency["max"] = max(elapsed)
        require(latency == summary["network"]["latencySecondsAll"], "latency differs")
        require(sum(t > 18 for t in elapsed) == summary["network"]["successfulHttpOver18s"] == 0,
                "deadline counts differ")
        pilot_rows = phase_rows(pilot, "base")
        pilot_counts = dict(collections.Counter(grader.grade(r)["verdict"] for r in pilot_rows.values()))
        require(len(pilot_rows) == 29 and pilot_counts == {"CORRECT": 5, "STOP": 6, "FAULT": 18},
                "pilot counts differ")
        print(json.dumps({"verifiedTasks": len(scores), "counts": summary["counts"],
                          "continuations": len(continuation), "liveCalls": len(live),
                          "pilotCountsSeparate": pilot_counts, "stopSubclasses": stops["counts"],
                          "archiveMembers": len(manifest["members"]), "newModelCalls": 0}, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print("Evidence verification failed: " + str(error), file=sys.stderr)
        sys.exit(1)
