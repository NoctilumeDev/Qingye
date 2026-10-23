"""Reject unreviewed SpotBugs changes; retained candidates are not accepted risks."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REGISTER = ROOT / "docs/governance/risk-register.json"
STATES = {"UNRESOLVED", "CONFIRMED", "NOT_APPLICABLE", "REMEDIATED"}


def require(condition, message):
    # Explicit checks also run under python -O.
    if not condition:
        raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def text(value):
    return isinstance(value, str) and bool(value.strip())


def sha(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{40}", value)


def key(item):
    return item["hash"], item["occurrence"]


def load_register(path):
    data = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    require(isinstance(data, dict) and data.get("schema") == "qingye-risk-register/v1", "wrong risk schema")
    require(sha(data.get("source_revision")) and sha(data.get("review_base")), "missing source coordinates")
    scanner = data.get("scanner")
    require(scanner == {"plugin": "com.github.spotbugs:spotbugs-maven-plugin:4.10.5.0",
                        "engine": "4.10.5", "effort": "Max", "threshold": "Low", "include_tests": False},
            "scanner contract changed; review checker and baseline together")
    ids, fingerprints = set(), set()
    for group in ("dependency_findings", "static_findings"):
        items = data.get(group)
        require(isinstance(items, list), f"missing {group}")
        for item in items:
            require(isinstance(item, dict), f"invalid item in {group}")
            identifier = item.get("id")
            require(text(identifier) and identifier not in ids, "missing or duplicate risk id")
            ids.add(identifier)
            owner = item.get("owner")
            require(isinstance(owner, dict) and text(owner.get("repository")) and text(owner.get("role")),
                    f"{identifier}: missing owner")
            require(item.get("status") in STATES, f"{identifier}: unknown state")
            require(item.get("risk_accepted") is False, f"{identifier}: this gate cannot authorize risk acceptance")
            require(sha(item.get("source_revision")), f"{identifier}: missing source revision")
            for field in ("rationale", "next_action"):
                require(text(item.get(field)), f"{identifier}: missing {field}")
            evidence = item.get("evidence")
            require(isinstance(evidence, list) and evidence and all(evidence), f"{identifier}: missing evidence")
            if item["status"] == "REMEDIATED":
                resolution = item.get("resolution")
                require(isinstance(resolution, dict) and sha(resolution.get("revision")),
                        f"{identifier}: remediation requires a fix revision")
                proof = resolution.get("evidence")
                require(isinstance(proof, list) and proof and all(text(entry) for entry in proof),
                        f"{identifier}: remediation requires regression evidence")
            if group == "static_findings":
                require(isinstance(item.get("hash"), str) and re.fullmatch(r"[0-9a-f]{1,32}", item["hash"]),
                        f"{identifier}: bad detector hash")
                require(type(item.get("occurrence")) is int and item["occurrence"] >= 0, f"{identifier}: bad occurrence")
                require(key(item) not in fingerprints, f"{identifier}: duplicate detector identity")
                fingerprints.add(key(item))
                require(text(item.get("type")) and text(item.get("class")), f"{identifier}: missing detector location")
                require(type(item.get("priority")) is int and 1 <= item["priority"] <= 3, f"{identifier}: bad priority")
                require(type(item.get("rank")) is int and 1 <= item["rank"] <= 20, f"{identifier}: bad rank")
    return data


def parse_report(path, classes_dir, engine):
    root = ET.parse(path).getroot()
    require(root.tag == "BugCollection" and root.get("version") == engine, "missing/wrong scanner version")
    errors = root.findall("./Errors")
    require(len(errors) == 1 and errors[0].get("errors") == "0" and errors[0].get("missingClasses") == "0"
            and len(errors[0]) == 0, "analysis errors or missing classes")
    summaries = root.findall("./FindBugsSummary")
    require(len(summaries) == 1, "missing/duplicate analysis summary")
    summary = summaries[0]
    expected = {p.relative_to(classes_dir).with_suffix("").as_posix().replace("/", ".")
                for p in classes_dir.rglob("*.class") if p.name not in {"module-info.class", "package-info.class"}}
    reported = [c.get("class") for c in summary.findall("./PackageStats/ClassStats")]
    require(expected and len(reported) == len(set(reported)) and set(reported) == expected,
            "analyzed classes do not match all compiled production classes")
    require(int(summary.get("total_classes", "-1")) == len(expected), "invalid analyzed class total")
    items, identities = [], set()
    for bug in root.findall("./BugInstance"):
        primary = bug.find("./Class[@primary='true']")
        require(primary is not None, "finding without a primary class")
        item = {"hash": bug.get("instanceHash"), "occurrence": int(bug.get("instanceOccurrenceNum", "-1")),
                "type": bug.get("type"), "class": primary.get("classname"),
                "priority": int(bug.get("priority", "-1")), "rank": int(bug.get("rank", "-1"))}
        require(text(item["hash"]) and re.fullmatch(r"[0-9a-f]{1,32}", item["hash"])
                and item["occurrence"] >= 0 and text(item["type"]) and item["class"] in expected,
                "invalid detector identity/location")
        require(1 <= item["priority"] <= 3 and 1 <= item["rank"] <= 20, "invalid detector severity")
        require(key(item) not in identities, "duplicate detector occurrence")
        identities.add(key(item))
        items.append(item)
    require(int(summary.get("total_bugs", "-1")) == len(items), "invalid finding total")
    return items


def compare(findings, register):
    retained = {key(item): item for item in register["static_findings"]}
    current = {key(item): item for item in findings}
    errors = []
    for identity, item in current.items():
        previous = retained.get(identity)
        if previous is None:
            errors.append(f"NEW: {item['type']} in {item['class']} ({identity})")
        elif any(item[field] != previous[field] for field in ("type", "class", "priority", "rank")):
            errors.append(f"CHANGED: {previous['id']} detector or severity changed")
        elif previous["status"] in {"CONFIRMED", "REMEDIATED"}:
            errors.append(f"BLOCKED: {previous['id']} is {previous['status']} but still reported")
    for identity, item in retained.items():
        if identity not in current and item["status"] != "REMEDIATED":
            errors.append(f"MISSING: {item['id']} requires explicit reconciliation, not silent closure")
        if item["status"] == "CONFIRMED" and identity not in current:
            errors.append(f"BLOCKED: {item['id']} still requires remediation evidence")
    require(not errors, "\n".join(errors))
    return Counter(item["status"] for item in retained.values())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--register", type=Path, default=REGISTER)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--classes", type=Path)
    args = parser.parse_args()
    try:
        require((args.report is None) == (args.classes is None), "report and classes must be supplied together")
        register = load_register(args.register)
        print("Risk register: valid metadata; no risk acceptance implied.")
        if args.report is not None:
            findings = parse_report(args.report, args.classes, register["scanner"]["engine"])
            states = compare(findings, register)
            print(f"Static change gate: PASS ({len(findings)} current detector instances)")
            print(f"Retained dispositions: {dict(states)}")
            print("PASS means no unreviewed detector change, NOT zero defects or deployment safety.")
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError) as error:
        print(f"Static/risk gate rejected: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
