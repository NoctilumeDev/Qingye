"""Adjudicate frozen strategy-A candidates after unchanged Gates 3 and 4.

This evidence tool makes no model calls.  It keeps the already-frozen first-turn
business expectations and classifies both the 155 unacceptable Gate-2 outputs
and the complete 1564-task replay.  Database rows are checked with the frozen
fixture scorer used by the prior full-bank experiment.
"""

from __future__ import annotations

import argparse
import collections
import hashlib
import importlib.util
import json
from pathlib import Path


PROVIDERS = ("deepseek", "qwen", "gpt", "doubao")


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def read_jsonl(path: Path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


def sha256(path: Path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_legacy(path: Path):
    spec = importlib.util.spec_from_file_location("frozen_qingye_legacy_grade", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def locate_items(row):
    classification = row.get("initialClassification") or {}
    return [item for item in classification.get("items", []) if item.get("action") == "LOCATE"]


def second_gate_failure(row, business_case):
    if row["initial"]["verdict"] == "P":
        return "TECHNICAL_OR_PROTOCOL_FAILURE"
    locates = locate_items(row)
    if not locates:
        return "SAFE_NON_EXECUTION_WRONG_STOP"
    expected = row["expected"]
    if expected["mode"] == "STOP":
        return "WRONG_LOCATE_FOR_NONEXECUTABLE_TASK"
    # A valid subset is not a wrong fact, but the whole task is incomplete.
    expected_queries = expected.get("queries", [])
    valid = [item for item in locates if any(
        business_case["query_matches"](item, query, row["modelInput"])
        for query in expected_queries
    )]
    if valid and len(valid) < len(expected_queries) and len(valid) == len(locates):
        return "VALID_SUBSET_BUT_TASK_INCOMPLETE"
    return "WRONG_LOCATE_TARGET_OR_CONDITION"


def make_query_matcher(adjudicator_path: Path):
    spec = importlib.util.spec_from_file_location("classifier_c_adjudicator", adjudicator_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return {"query_matches": module.query_matches}


def plan_requirement(plan):
    return {
        "intent": plan["intent"],
        "entity": plan.get("entity"),
        "timeOption": plan.get("timeOption"),
        "status": plan.get("status"),
        "category": plan.get("category"),
        "location": plan.get("location"),
        "date": plan.get("date"),
    }


def section_matches(section, requirement, legacy, query_matches, model_input):
    result = section["result"]
    pages = section["pages"] or ([result] if result.get("status") == "QUERY" else [])
    if result.get("status") != "QUERY" or not pages:
        return False, "fixed query did not return a query result"
    # First prove that the frozen candidate still covers the original target
    # and retains every hard condition.  A legal wider set may cover a named
    # target, which is an established Qingye business rule.
    if not query_matches(section["item"], requirement, model_input):
        return False, "candidate target or hard condition differs from the user task"
    child = {"result": result, "pages": pages}
    actual = plan_requirement(result["plan"])
    # Then independently prove that Gate 4 returned exactly the rows authorized
    # by its own fixed plan and did not invent or leak fields.
    executed, detail = legacy.query_grade(child, actual)
    if not executed:
        return False, "fixed query result does not match its authorized plan: " + detail
    rows = [item for page in pages for item in page.get("items", [])]
    ids = {item["id"] for item in rows}
    requested_entity = requirement.get("entity")
    literal_entity = actual.get("entity")
    if (literal_entity and literal_entity.casefold() in model_input.casefold()
            and literal_entity != requested_entity and not rows):
        # The product intentionally performs literal lookup only.  A typo,
        # traditional spelling, English name, or unsupported generic name may
        # honestly return no rows; that is a handled no-match, not a wrong fact.
        return True, "LITERAL_LOOKUP_HANDLED"
    expected_ids = legacy.expected_ids(requirement)
    required_ids = set(requirement.get("requiredIds", expected_ids))
    if not required_ids <= ids:
        return False, "legal wider result did not cover required records " + str(sorted(required_ids - ids))
    # When the requested literal has no database match, an unfiltered directory
    # is not evidence that the requested object was checked.
    if requirement.get("entity") not in (None, "ALL") and not expected_ids:
        if actual.get("entity") != requirement.get("entity"):
            return False, "unknown literal was replaced by a wider directory query"
    required_field = requirement.get("requiredField")
    if required_field and any(required_field not in item for item in rows):
        return False, "returned card lacks required business field " + required_field
    return True, "authorized database rows cover the original target"


def match_sections(sections, requirements, legacy, query_matches, model_input):
    checks = [[section_matches(section, requirement, legacy, query_matches, model_input) for requirement in requirements]
              for section in sections]

    def visit(index, used):
        if index == len(sections):
            return set(range(len(requirements))) == used
        return any(checks[index][target][0] and target not in used and visit(index + 1, used | {target})
                   for target in range(len(requirements)))

    complete = len(sections) == len(requirements) and visit(0, set())
    wrong_sections = [index for index, values in enumerate(checks) if not any(ok for ok, _ in values)]
    return complete, wrong_sections, checks


def end_to_end(row, legacy, query_matches):
    expected = row["expected"]
    if row.get("intakeFailure"):
        return {"verdict": "CORRECT_STOP", "detail": "Gate 1 stopped the request: " + row["intakeFailure"],
                "acceptedLocate": 0, "blockedLocate": 0, "blockReasons": []}
    classification = row.get("initialClassification")
    if classification is None:
        return {"verdict": "TECHNICAL_FAILURE", "detail": row["initialBusiness"].get("detail")}
    if row.get("nativeError"):
        return {"verdict": "TECHNICAL_FAILURE", "detail": row["nativeError"]}
    if not all(row["security"].values()):
        return {"verdict": "WRONG_OUTPUT", "detail": "read-only, subject, field or canary boundary failed"}

    sections = row["sections"]
    accepted = sum(1 for item in row["compilations"] if item["accepted"])
    blocked = len(row["compilations"]) - accepted
    base = {"acceptedLocate": accepted, "blockedLocate": blocked,
            "blockReasons": [item.get("failure") for item in row["compilations"] if not item["accepted"]]}

    if expected["mode"] in ("STOP", "OVERFLOW"):
        if sections:
            return {**base, "verdict": "WRONG_QUERY", "detail": "a non-executable task reached Gate 4"}
        return {**base, "verdict": "CORRECT_STOP", "detail": "no business read was released"}

    complete, wrong_sections, checks = match_sections(
        sections, expected.get("queries", []), legacy, query_matches, row["modelInput"]
    )
    if wrong_sections:
        return {**base, "verdict": "WRONG_QUERY", "detail": "at least one released section did not satisfy any valid target",
                "wrongSections": wrong_sections, "checks": checks}
    if complete:
        if expected["mode"] == "MIXED" and not row["stopItems"]:
            return {**base, "verdict": "INCOMPLETE", "detail": "mixed task lost its unresolved or rejected part", "checks": checks}
        literal = any(detail == "LITERAL_LOOKUP_HANDLED" for values in checks for ok, detail in values if ok)
        return {**base, "verdict": "LITERAL_LOOKUP_HANDLED" if literal and expected["mode"] == "QUERY" else
                "QUERY_SUCCESS" if expected["mode"] == "QUERY" else "MIXED_FIRST_TURN_ACCEPTABLE",
                "detail": "all first-turn executable targets are covered by authorized database rows", "checks": checks}
    if sections:
        return {**base, "verdict": "INCOMPLETE", "detail": "only a valid subset of the task was released", "checks": checks}
    return {**base, "verdict": "UNNECESSARY_STOP", "detail": "the task had an executable target but none reached Gate 4"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--review", type=Path, required=True)
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--legacy-grade", type=Path, required=True)
    parser.add_argument("--multiple-oracle", type=Path, required=True)
    parser.add_argument("--classifier-adjudicator", type=Path, required=True)
    parser.add_argument("--expected-commit", required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit("refuse to overwrite output directory")
    args.output.mkdir(parents=True)

    review = read_json(args.review)
    manifest = read_json(args.probe / "manifest.json")
    if manifest.get("sourceCommit") != args.expected_commit or manifest.get("workingTreeClean") is not True:
        raise SystemExit("probe evidence is not bound to the expected clean source commit")
    probe = read_jsonl(args.probe / "results.jsonl")
    if len(review["cases"]) != 1564 or len(probe) != 1564:
        raise SystemExit("expected 1564 review and probe rows")
    keyed_review = {(row["provider"], row["caseId"]): row for row in review["cases"]}
    if set(keyed_review) != {(row["provider"], row["caseId"]) for row in probe}:
        raise SystemExit("probe/review task coordinates differ")

    legacy = load_legacy(args.legacy_grade)
    multiple = read_json(args.multiple_oracle)
    matcher = make_query_matcher(args.classifier_adjudicator)
    ledger = []
    failure_types = collections.Counter()
    for raw in probe:
        original = keyed_review[(raw["provider"], raw["caseId"])]
        raw["initial"] = original["initial"]
        # Restore row-level witness metadata that the pure classification
        # oracle did not need (required IDs/fields and related-set allowance).
        source_requirements = multiple.get(raw["caseId"])
        if source_requirements is None:
            source = legacy.oracle.get(raw["caseId"])
            source_requirements = [] if source is None else [source]
        for requirement in raw["expected"].get("queries", []):
            for source in source_requirements:
                comparable = ("intent", "entity", "timeOption", "status", "category", "location", "date")
                if all(requirement.get(field) == source.get(field) for field in comparable):
                    for field in ("requiredIds", "requiredField", "allowRelated"):
                        if field in source:
                            requirement[field] = source[field]
                    break
        if not original["initial"]["acceptable"]:
            failure_types[second_gate_failure(original, matcher)] += 1
        outcome = end_to_end(raw, legacy, matcher["query_matches"])
        ledger.append({
            "provider": raw["provider"],
            "caseId": raw["caseId"],
            "userTask": raw["userTask"],
            "initialBusiness": original["initial"],
            "expected": raw["expected"],
            "compilations": raw["compilations"],
            "stopItems": raw["stopItems"],
            "sections": raw["sections"],
            "security": raw["security"],
            "outcome": outcome,
        })

    if sum(failure_types.values()) != 155:
        raise SystemExit("second-gate failure decomposition must total 155")
    by_provider = {}
    for provider in PROVIDERS:
        rows = [row for row in ledger if row["provider"] == provider]
        by_provider[provider] = {
            "tasks": len(rows),
            "outcomes": dict(collections.Counter(row["outcome"]["verdict"] for row in rows)),
            "acceptedLocate": sum(row["outcome"].get("acceptedLocate", 0) for row in rows),
            "blockedLocate": sum(row["outcome"].get("blockedLocate", 0) for row in rows),
        }
    summary = {
        "version": "qingye-classifier-a-unchanged-gates-replay-v1",
        "scope": "Frozen A first-turn classifications for four providers x 391 tasks; no model calls",
        "qualificationBoundary": "This is a first-turn frozen replay through unchanged Gates 1, 3 and 4, not a completed multi-turn or production qualification.",
        "secondGateUnacceptable": {"total": 155, "types": dict(failure_types)},
        "unacceptableFlow": {
            failure: dict(collections.Counter(
                row["outcome"]["verdict"] for row in ledger
                if not row["initialBusiness"]["acceptable"]
                and second_gate_failure(keyed_review[(row["provider"], row["caseId"])], matcher) == failure
            ))
            for failure in failure_types
        },
        "byProvider": by_provider,
        "combined": {
            "tasks": len(ledger),
            "outcomes": dict(collections.Counter(row["outcome"]["verdict"] for row in ledger)),
            "acceptedLocate": sum(row["outcome"].get("acceptedLocate", 0) for row in ledger),
            "blockedLocate": sum(row["outcome"].get("blockedLocate", 0) for row in ledger),
            "blockReasons": dict(collections.Counter(
                reason for row in ledger for reason in row["outcome"].get("blockReasons", [])
            )),
        },
        "sourceHashes": {
            "businessReview": sha256(args.review),
            "probeResults": sha256(args.probe / "results.jsonl"),
            "probeSummary": sha256(args.probe / "probe-summary.json"),
            "probeManifest": sha256(args.probe / "manifest.json"),
            "legacyGrade": sha256(args.legacy_grade),
            "multipleOracle": sha256(args.multiple_oracle),
            "classifierAdjudicator": sha256(args.classifier_adjudicator),
        },
    }
    (args.output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    (args.output / "business-ledger.json").write_text(json.dumps(ledger, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
