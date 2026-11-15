"""Pure second-gate strategy C: batch classification plus one global review.

The script never starts Qingye, opens a database, enters the third/fourth gate,
or executes a classified item.  Each task receives at most two model calls:

1. the exact batch classifier used by strategy A;
2. one whole-request review that returns the final batch classification.

The first and final classifications are both retained.  The runner validates
protocol and evidence integrity only; correction, damage, and net business
benefit remain task-level review decisions.
"""

from __future__ import annotations

import argparse
import getpass
import importlib.util
import json
import os
from pathlib import Path
import statistics
import subprocess
import time


ROOT = Path(__file__).resolve().parents[1]
AB_PATH = ROOT / "scripts/assistant-classifier-ab.py"
SPEC = importlib.util.spec_from_file_location("assistant_classifier_ab", AB_PATH)
AB = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AB)

DEFAULT_BUDGET_SECONDS = 18.0


def review_instruction(common: str) -> str:
    return common + """
实验方案C（一次整体复核）：你会收到originalTask和initialClassification。
initialClassification只是待复核候选，不是事实、授权或正确性证据。只根据完整原请求和已有业务合同输出最终分类。
整体检查当前仍有效目标是否完整、改口撤回目标是否已移除、明确条件是否归属正确、否定与排除是否保留、关联任务是否被错误拆分，以及不确定是否被误判为确定。
可以保留、删除、增加或改写候选，但不得输出修改说明，不得开启第三次复核。无法可靠消除的语义冲突，对受影响目标使用CLARIFY；越权目标使用REJECT。
只输出 {"control":"DONE|OVERFLOW","items":[...] } 两个键。items包含1至4个按原始顺序排列的既有六字段子项；超过四项必须OVERFLOW，不能静默截断。不得输出NEXT。
"""


def exception_calls(failure):
    calls = getattr(failure, "network", None)
    if calls is not None:
        return list(calls)
    if isinstance(failure, RuntimeError) and len(failure.args) > 1 and isinstance(failure.args[1], dict):
        return [failure.args[1]]
    return []


def attach_failure_calls(stage, failure, network):
    calls = exception_calls(failure)
    if calls:
        network.extend({"stage": stage, **call} for call in calls)
    return sum(call["stage"] == stage for call in network)


def observation_failure(failure, call_count):
    if isinstance(failure, AB.ProtocolError):
        return {"protocol": "FAIL", "reason": str(failure), "calls": call_count}
    return {
        "protocol": "TRANSPORT_FAILURE",
        "reason": str(failure.args[0]) if failure.args else type(failure).__name__,
        "calls": call_count,
    }


def run_c(provider, config, key, initial_prompt, review_prompt, task,
          per_call_timeout, request_budget, proxy):
    contract, wire = AB.contracts()
    started = time.monotonic()
    network = []

    initial_body = AB.request_body(provider, config["model"], initial_prompt, task)
    try:
        content, call = AB.call_model(
            provider, config, key, initial_body,
            min(per_call_timeout, request_budget), proxy,
        )
        network.append({"stage": "initial", **call})
        initial = AB.decode_batch(content, contract, wire)
    except (AB.ProtocolError, RuntimeError) as failure:
        call_count = attach_failure_calls("initial", failure, network)
        return {
            "initial": observation_failure(failure, call_count),
            "review": {"protocol": "SKIPPED", "reason": "INITIAL_NOT_VALID", "calls": 0},
            "final": {"protocol": "UNAVAILABLE", "reason": "INITIAL_NOT_VALID"},
            "elapsedSeconds": time.monotonic() - started,
        }, network

    initial_observation = {"protocol": "PASS", "classification": initial, "calls": 1}
    remaining = request_budget - (time.monotonic() - started)
    if remaining <= 0:
        return {
            "initial": initial_observation,
            "review": {"protocol": "BUDGET_EXHAUSTED", "reason": "REQUEST_BUDGET", "calls": 0},
            "final": {"protocol": "UNAVAILABLE", "reason": "REQUEST_BUDGET"},
            "elapsedSeconds": time.monotonic() - started,
        }, network

    review_input = AB.compact({
        "originalTask": task,
        "initialClassification": initial,
    })
    review_body = AB.request_body(provider, config["model"], review_prompt, review_input)
    try:
        content, call = AB.call_model(
            provider, config, key, review_body,
            min(per_call_timeout, remaining), proxy,
        )
        network.append({"stage": "review", **call})
        final = AB.decode_batch(content, contract, wire)
    except (AB.ProtocolError, RuntimeError) as failure:
        call_count = attach_failure_calls("review", failure, network)
        return {
            "initial": initial_observation,
            "review": observation_failure(failure, call_count),
            "final": {"protocol": "UNAVAILABLE", "reason": "REVIEW_NOT_VALID"},
            "elapsedSeconds": time.monotonic() - started,
        }, network

    elapsed = time.monotonic() - started
    if elapsed > request_budget:
        return {
            "initial": initial_observation,
            "review": {
                "protocol": "BUDGET_EXHAUSTED",
                "reason": "REQUEST_BUDGET",
                "receivedClassification": final,
                "calls": 1,
            },
            "final": {"protocol": "UNAVAILABLE", "reason": "REQUEST_BUDGET"},
            "elapsedSeconds": elapsed,
        }, network

    return {
        "initial": initial_observation,
        "review": {"protocol": "PASS", "classification": final, "calls": 1},
        "final": {"protocol": "PASS", "classification": final},
        "elapsedSeconds": elapsed,
    }, network


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, round((len(ordered) - 1) * fraction))]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--provider", choices=sorted(AB.PROVIDERS), required=True)
    parser.add_argument("--cases", type=Path, default=AB.DEFAULT_CASES)
    parser.add_argument("--cases-member", default=AB.DEFAULT_MEMBER)
    parser.add_argument("--selection", type=Path)
    parser.add_argument("--proxy")
    parser.add_argument("--per-call-timeout", type=float, default=18.0)
    parser.add_argument("--request-budget", type=float, default=DEFAULT_BUDGET_SECONDS)
    parser.add_argument("--key-env")
    args = parser.parse_args()
    if args.evidence.exists():
        raise SystemExit("Refuse to overwrite an existing evidence directory")
    if args.per_call_timeout <= 0 or args.request_budget <= 0:
        raise SystemExit("Timeouts must be positive")

    cases, case_bytes = AB.load_cases(args.cases, args.cases_member)
    selected, selection_bytes = AB.load_selection(args.selection)
    if selected is not None:
        unknown = selected - {case["id"] for case in cases}
        if unknown:
            raise SystemExit("Unknown selected cases: " + ", ".join(sorted(unknown)))
        cases = [case for case in cases if case["id"] in selected]

    config = dict(AB.PROVIDERS[args.provider])
    env_name = args.key_env or config["keyEnv"]
    key = os.environ.get(env_name) or getpass.getpass(args.provider + " temporary key (hidden): ")
    if not key:
        raise SystemExit("Missing provider credential")

    common = AB.experiment_common_instruction()
    prompts = {
        "initial": AB.batch_instruction(common),
        "review": review_instruction(common),
    }
    args.evidence.mkdir(parents=True)
    (args.evidence / "prompts.json").write_text(
        json.dumps(prompts, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")

    try:
        head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        source_status = subprocess.check_output(
            ["git", "status", "--porcelain"], cwd=ROOT, text=True)
    except (OSError, subprocess.CalledProcessError):
        head = None
        source_status = "UNAVAILABLE"

    tracked_sources = [
        Path(__file__), AB_PATH,
        ROOT / "backend/src/main/resources/assistant-planner.txt",
        ROOT / "backend/src/main/resources/assistant-contract.json",
        ROOT / "backend/src/main/resources/assistant-locator-protocol.json",
        args.selection,
    ]
    manifest = {
        "version": "qingye-second-gate-classifier-c-v1",
        "scope": "batch plus one global review; no database, no third/fourth gate, no execution",
        "sourceCommit": head,
        "provider": args.provider,
        "model": config["model"],
        "endpoint": config["endpoint"],
        "taskCount": len(cases),
        "maximumModelCalls": len(cases) * 2,
        "perCallTimeoutSeconds": args.per_call_timeout,
        "requestBudgetSeconds": args.request_budget,
        "retries": 0,
        "frozenCasesSha256": AB.sha256(case_bytes),
        "selectionSha256": AB.sha256(selection_bytes) if selection_bytes else None,
        "promptSha256": {
            name: AB.sha256(value.encode("utf-8")) for name, value in prompts.items()
        },
        "sourceFilesSha256": {
            str(path.resolve()): AB.sha256(path.read_bytes())
            for path in tracked_sources if path is not None
        },
        "workingTreeClean": source_status == "",
        "workingTreeStatusSha256": AB.sha256(source_status.encode("utf-8")),
        "businessScoring": "pending task-level review of initial and final classifications",
    }
    (args.evidence / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")

    results = []
    task_latencies = []
    call_latencies = {"initial": [], "review": []}
    try:
        for case in cases:
            task = AB.first_turn(case)
            observation, calls = run_c(
                args.provider, config, key, prompts["initial"], prompts["review"], task,
                args.per_call_timeout, args.request_budget, args.proxy,
            )
            for call in calls:
                call_latencies[call["stage"]].append(call["latencySeconds"])
                AB.append_jsonl(
                    args.evidence / "network.jsonl",
                    {"caseId": case["id"], **call},
                )
            task_latencies.append(observation["elapsedSeconds"])
            row = {
                "caseId": case["id"],
                "userTask": case.get("userTask"),
                "modelInput": task,
                "corpus": case.get("corpus"),
                "requiredConditions": case.get("requiredConditions"),
                "acceptableOutcomes": case.get("acceptableOutcomes"),
                "prohibitedBehaviors": case.get("prohibitedBehaviors"),
                "observations": observation,
                "initialBusinessVerdict": None,
                "finalBusinessVerdict": None,
                "reviewEffect": None,
            }
            AB.append_jsonl(args.evidence / "classifications.jsonl", row)
            results.append(row)
    finally:
        key = None

    summary = {
        "tasks": len(results),
        "provider": args.provider,
        "initialProtocol": {
            status: sum(row["observations"]["initial"]["protocol"] == status for row in results)
            for status in ("PASS", "FAIL", "TRANSPORT_FAILURE")
        },
        "reviewProtocol": {
            status: sum(row["observations"]["review"]["protocol"] == status for row in results)
            for status in ("PASS", "FAIL", "TRANSPORT_FAILURE", "BUDGET_EXHAUSTED", "SKIPPED")
        },
        "finalProtocol": {
            status: sum(row["observations"]["final"]["protocol"] == status for row in results)
            for status in ("PASS", "UNAVAILABLE")
        },
        "modelCalls": None,
        "latencySeconds": {
            stage: {
                "calls": len(values),
                "p50": statistics.median(values) if values else None,
                "p95": percentile(values, 0.95),
                "maximum": max(values) if values else None,
            } for stage, values in call_latencies.items()
        },
        "taskElapsedSeconds": {
            "p50": statistics.median(task_latencies) if task_latencies else None,
            "p95": percentile(task_latencies, 0.95),
            "maximum": max(task_latencies) if task_latencies else None,
            "overBudget": sum(value > args.request_budget for value in task_latencies),
        },
        "businessVerdicts": "PENDING_TASK_LEVEL_REVIEW",
        "reviewEffects": "PENDING_INITIAL_VS_FINAL_BUSINESS_REVIEW",
    }
    summary["modelCalls"] = (
        summary["latencySeconds"]["initial"]["calls"]
        + summary["latencySeconds"]["review"]["calls"]
    )
    (args.evidence / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
