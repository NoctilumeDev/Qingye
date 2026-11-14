"""Pure second-gate A/B classifier experiment.

This script never starts Qingye, never opens a database, and never executes a
classified item.  It compares two output organizations over the same semantic
instruction and the same frozen user tasks:

* batch: one call returns one to four six-field classification items;
* iterative: each call returns one item, followed by DONE or OVERFLOW.

Credentials are read from a named environment variable or a hidden prompt and
are never written to evidence.  Semantic correctness remains a task-level
business review; this script only validates the frozen wire shapes and records
the unmodified observations needed for that review.
"""

from __future__ import annotations

import argparse
import getpass
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess
import time
import urllib.error
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CASES = ROOT / "docs/evidence/doubao-20261030/raw-evidence.zip"
DEFAULT_MEMBER = "full/cases.json"
FROZEN_CASES_SHA256 = "291998f96d1855c97b8ef51d92c0dd7817d464b0668aef44fa55127ba909ffc0"
MAX_ITEMS = 4
MAX_ITERATIVE_CALLS = MAX_ITEMS + 1

PROVIDERS = {
    "deepseek": {
        "endpoint": "https://api.deepseek.com/chat/completions",
        "model": "deepseek-flash",
        "keyEnv": "QINGYE_EXPERIMENT_DEEPSEEK_KEY",
    },
    "qwen": {
        "endpoint": "https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions",
        "model": "qwen-plus",
        "keyEnv": "QINGYE_EXPERIMENT_QWEN_KEY",
    },
    "gpt": {
        "endpoint": "https://api.openai.com/v1/chat/completions",
        "model": "gpt-6-astra",
        "keyEnv": "QINGYE_EXPERIMENT_GPT_KEY",
    },
    "doubao": {
        "endpoint": "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
        "model": "doubao-seed-2-1-pro-260915",
        "keyEnv": "QINGYE_EXPERIMENT_DOUBAO_KEY",
    },
}


class ProtocolError(ValueError):
    pass


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_cases(path: Path, member: str = DEFAULT_MEMBER):
    if path.suffix.lower() == ".zip":
        with zipfile.ZipFile(path) as archive:
            data = archive.read(member)
    else:
        data = path.read_bytes()
    if sha256(data) != FROZEN_CASES_SHA256:
        raise SystemExit("Frozen 391-task input changed; refusing to run")
    cases = json.loads(data)
    if len(cases) != 391 or len({case["id"] for case in cases}) != 391:
        raise SystemExit("Expected 391 unique frozen tasks")
    return cases, data


def load_selection(path: Path | None):
    if path is None:
        return None, None
    data = path.read_bytes()
    value = json.loads(data)
    ids = value["caseIds"] if isinstance(value, dict) else value
    if not ids or len(ids) != len(set(ids)):
        raise SystemExit("Selection must contain unique case IDs")
    return set(ids), data


def first_turn(case):
    turns = case.get("turns")
    if not isinstance(turns, list) or not turns or not isinstance(turns[0], str) or not turns[0]:
        raise SystemExit("Case has no usable first user turn: " + str(case.get("id")))
    return turns[0]


def experiment_common_instruction() -> str:
    planner_path = ROOT / "backend/src/main/resources/assistant-planner.txt"
    lines = planner_path.read_text(encoding="utf-8").splitlines()
    replacements = {
        "只输出一个JSON对象，": (
            "本实验只测第二门分类，不执行查询。输出一个JSON对象，具体外层格式见末尾实验协议；"
            "每个业务子项仍使用既有六字段对象。不得输出SQL、目标用户、身份、任意操作或事实答案。"
        ),
        "从wireProtocol.examples选择对应动作的完整对象并填值，": (
            "每个子项都从wireProtocol.examples选择对应动作的完整对象并填值；"
            "子项保留六个键，没有条件时selections=[]。"
        ),
        "本次语义入口每次定位一个目录。": (
            "识别完整原请求中当前仍有效的业务目标；清楚的改口撤回旧目标。"
            "多个真正独立的目标分别分类，CLARIFY或REJECT只表示该子项如何处理，不表示枚举结束。"
            "关联、交集、比较、统计或依赖执行不能拆成几份独立LOCATE。"
        ),
    }
    hit = {prefix: 0 for prefix in replacements}
    result = []
    for line in lines:
        replaced = False
        for prefix, value in replacements.items():
            if line.startswith(prefix):
                result.append(value)
                hit[prefix] += 1
                replaced = True
                break
        if not replaced:
            result.append(line)
    if set(hit.values()) != {1}:
        raise SystemExit("Production prompt anchors changed; review the experiment prompt explicitly")
    contract = json.loads((ROOT / "backend/src/main/resources/assistant-contract.json").read_text(encoding="utf-8"))
    wire = json.loads((ROOT / "backend/src/main/resources/assistant-locator-protocol.json").read_text(encoding="utf-8"))
    return "\n".join(result) + "\n已有业务合同：" + compact(contract) + "\n单项wireProtocol：" + compact(wire)


def batch_instruction(common: str) -> str:
    return common + """
实验外层协议A（批量分类）：只输出 {"control":"DONE|OVERFLOW","items":[...] } 两个键。
items包含1至4个按原始顺序排列的六字段子项，每项action为LOCATE、CLARIFY或REJECT。
所有当前有效目标已经完整枚举且不超过四项时control=DONE。超过四项时control=OVERFLOW；items可保留前四项用于审查，但它们不代表完整任务。不得输出NEXT。
CLARIFY和REJECT不终止其他独立目标的枚举。不要把已撤回目标、背景提及或关联条件拆成独立目标。
"""


def iterative_instruction(common: str) -> str:
    return common + """
实验外层协议B（迭代分类）：只输出 {"control":"NEXT","item":{...}} 或 {"control":"DONE|OVERFLOW","item":null} 两个键。
NEXT时item必须是下一项按原始顺序排列、尚未返回的六字段子项，action为LOCATE、CLARIFY或REJECT。
没有其他当前有效目标时返回DONE。目标总数超过四项时返回OVERFLOW。CLARIFY和REJECT不结束枚举；不得重复既有子项。
终止检查轮只能返回DONE或OVERFLOW。不要把已撤回目标、背景提及或关联条件拆成独立目标。
"""


def validate_item(item, contract, wire):
    fields = wire["fields"]
    if not isinstance(item, dict) or set(item) != set(fields):
        raise ProtocolError("INVALID_ITEM_FIELDS")
    for field in (set(fields) - {"selections"}):
        if item[field] is not None and not isinstance(item[field], str):
            raise ProtocolError("INVALID_ITEM_FIELD_TYPE")
    selections = item["selections"]
    if not isinstance(selections, list) or len(selections) > wire["maxSelections"]:
        raise ProtocolError("INVALID_SELECTIONS")
    seen = set()
    for selection in selections:
        if not isinstance(selection, dict) or set(selection) != set(wire["selectionFields"]):
            raise ProtocolError("INVALID_SELECTION_FIELDS")
        if any(not isinstance(selection[key], str) or not selection[key] for key in wire["selectionFields"]):
            raise ProtocolError("INVALID_SELECTION_VALUE")
        if selection["field"] in seen:
            raise ProtocolError("DUPLICATE_SELECTION_FIELD")
        seen.add(selection["field"])
    action = item["action"]
    if action == "LOCATE":
        if item["intent"] not in contract["intents"] or item["reason"] is not None or item["question"] is not None:
            raise ProtocolError("INVALID_LOCATE")
    elif action == "CLARIFY":
        if item["reason"] not in wire["clarificationReasons"]:
            raise ProtocolError("INVALID_CLARIFY")
    elif action == "REJECT":
        if (item["intent"], item["search"], item["question"], selections) != (None, None, None, []):
            raise ProtocolError("INVALID_REJECT")
        if item["reason"] not in wire["rejectionReasons"]:
            raise ProtocolError("INVALID_REJECT_REASON")
    else:
        raise ProtocolError("INVALID_ACTION")
    return item


def decode_json_content(content: str):
    try:
        return json.loads(content)
    except json.JSONDecodeError as failure:
        raise ProtocolError("INVALID_JSON") from failure


def decode_batch(content: str, contract, wire):
    value = decode_json_content(content)
    if not isinstance(value, dict) or set(value) != {"control", "items"}:
        raise ProtocolError("INVALID_BATCH_FIELDS")
    if value["control"] not in {"DONE", "OVERFLOW"}:
        raise ProtocolError("INVALID_BATCH_CONTROL")
    if not isinstance(value["items"], list) or not 1 <= len(value["items"]) <= MAX_ITEMS:
        raise ProtocolError("INVALID_BATCH_ITEMS")
    return {"control": value["control"], "items": [validate_item(item, contract, wire) for item in value["items"]]}


def decode_iterative(content: str, contract, wire, termination_only: bool = False):
    value = decode_json_content(content)
    if not isinstance(value, dict) or set(value) != {"control", "item"}:
        raise ProtocolError("INVALID_ITERATIVE_FIELDS")
    control = value["control"]
    if control == "NEXT":
        if termination_only:
            raise ProtocolError("TOO_MANY_ITEMS")
        return {"control": control, "item": validate_item(value["item"], contract, wire)}
    if control not in {"DONE", "OVERFLOW"} or value["item"] is not None:
        raise ProtocolError("INVALID_ITERATIVE_CONTROL")
    return {"control": control, "item": None}


def request_body(provider: str, model: str, instruction: str, user_content: str):
    body = {
        "model": model,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": instruction},
            {"role": "user", "content": user_content},
        ],
    }
    if provider == "gpt":
        body.update(max_completion_tokens=2048, reasoning_effort="low", store=False)
    else:
        body.update(temperature=0, max_tokens=2048)
    if provider == "deepseek":
        body["thinking"] = {"type": "disabled"}
    elif provider == "qwen":
        body["enable_thinking"] = False
    elif provider == "doubao":
        body["thinking"] = {"type": "disabled"}
    return body


def call_model(provider: str, config, key: str, body, timeout: float, proxy: str | None):
    data = compact(body).encode("utf-8")
    handler = urllib.request.ProxyHandler({"https": proxy} if proxy else {})
    opener = urllib.request.build_opener(handler)
    request = urllib.request.Request(
        config["endpoint"], data=data,
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + key},
        method="POST",
    )
    started = time.monotonic()
    try:
        with opener.open(request, timeout=timeout) as response:
            status = response.status
            raw = response.read(1_048_577)
    except urllib.error.HTTPError as failure:
        status = failure.code
        raw = failure.read(1_048_577)
    except (urllib.error.URLError, TimeoutError, OSError) as failure:
        latency = time.monotonic() - started
        record = {
            "provider": provider,
            "endpoint": config["endpoint"],
            "httpStatus": None,
            "latencySeconds": latency,
            "request": body,
            "response": {
                "transportErrorType": type(failure).__name__,
                "transportError": str(failure).replace(key, "[REDACTED]"),
            },
            "usage": None,
        }
        raise RuntimeError("TRANSPORT_ERROR", record) from failure
    latency = time.monotonic() - started
    if len(raw) > 1_048_576:
        raise RuntimeError("RESPONSE_TOO_LARGE")
    decoded = raw.decode("utf-8", "replace").replace(key, "[REDACTED]")
    try:
        envelope = json.loads(decoded)
    except json.JSONDecodeError:
        envelope = {"unparsedBody": decoded}
    record = {
        "provider": provider,
        "endpoint": config["endpoint"],
        "httpStatus": status,
        "latencySeconds": latency,
        "request": body,
        "response": envelope,
        "usage": envelope.get("usage") if isinstance(envelope, dict) else None,
    }
    if status != 200:
        raise RuntimeError("HTTP_" + str(status), record)
    try:
        content = envelope["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as failure:
        protocol_failure = ProtocolError("INVALID_RESPONSE_ENVELOPE")
        protocol_failure.network = [record]
        raise protocol_failure from failure
    if not isinstance(content, str):
        protocol_failure = ProtocolError("INVALID_RESPONSE_CONTENT")
        protocol_failure.network = [record]
        raise protocol_failure
    return content, record


def run_batch(provider, config, key, instruction, task, timeout, proxy):
    body = request_body(provider, config["model"], instruction, task)
    content, network = call_model(provider, config, key, body, timeout, proxy)
    contract, wire = contracts()
    try:
        parsed = decode_batch(content, contract, wire)
    except ProtocolError as failure:
        failure.network = [network]
        raise
    return parsed, [network]


def run_iterative(provider, config, key, instruction, task, timeout, proxy):
    contract, wire = contracts()
    items, network = [], []
    final_control = None
    for index in range(MAX_ITERATIVE_CALLS):
        user = compact({
            "originalTask": task,
            "previousItems": items,
            "step": index + 1,
            "terminationOnly": index == MAX_ITEMS,
        })
        body = request_body(provider, config["model"], instruction, user)
        content, observation = call_model(provider, config, key, body, timeout, proxy)
        network.append(observation)
        try:
            parsed = decode_iterative(content, contract, wire, termination_only=index == MAX_ITEMS)
        except ProtocolError as failure:
            failure.network = list(network)
            raise
        if parsed["control"] == "NEXT":
            items.append(parsed["item"])
            continue
        final_control = parsed["control"]
        break
    if final_control is None:
        raise ProtocolError("MISSING_TERMINATION")
    return {"control": final_control, "items": items}, network


_CONTRACTS = None


def contracts():
    global _CONTRACTS
    if _CONTRACTS is None:
        contract = json.loads((ROOT / "backend/src/main/resources/assistant-contract.json").read_text(encoding="utf-8"))
        wire = json.loads((ROOT / "backend/src/main/resources/assistant-locator-protocol.json").read_text(encoding="utf-8"))
        _CONTRACTS = contract, wire
    return _CONTRACTS


def append_jsonl(path: Path, value):
    with path.open("a", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(value, ensure_ascii=False) + "\n")


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, round((len(ordered) - 1) * fraction))]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--provider", choices=sorted(PROVIDERS), required=True)
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--cases-member", default=DEFAULT_MEMBER)
    parser.add_argument("--selection", type=Path)
    parser.add_argument("--proxy")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--key-env")
    args = parser.parse_args()
    if args.evidence.exists():
        raise SystemExit("Refuse to overwrite an existing evidence directory")
    cases, case_bytes = load_cases(args.cases, args.cases_member)
    selected, selection_bytes = load_selection(args.selection)
    if selected is not None:
        unknown = selected - {case["id"] for case in cases}
        if unknown:
            raise SystemExit("Unknown selected cases: " + ", ".join(sorted(unknown)))
        cases = [case for case in cases if case["id"] in selected]
    config = dict(PROVIDERS[args.provider])
    env_name = args.key_env or config["keyEnv"]
    key = os.environ.get(env_name) or getpass.getpass(args.provider + " temporary key (hidden): ")
    if not key:
        raise SystemExit("Missing provider credential")
    common = experiment_common_instruction()
    prompts = {"batch": batch_instruction(common), "iterative": iterative_instruction(common)}
    args.evidence.mkdir(parents=True)
    (args.evidence / "prompts.json").write_text(json.dumps(prompts, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")
    try:
        head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        head = None
    tracked_sources = [
        Path(__file__),
        ROOT / "backend/src/main/resources/assistant-planner.txt",
        ROOT / "backend/src/main/resources/assistant-contract.json",
        ROOT / "backend/src/main/resources/assistant-locator-protocol.json",
        args.selection,
    ]
    try:
        source_status = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True)
    except (OSError, subprocess.CalledProcessError):
        source_status = "UNAVAILABLE"
    manifest = {
        "version": "qingye-second-gate-classifier-ab-v1",
        "scope": "pure classification; no database, no third/fourth gate, no execution",
        "sourceCommit": head,
        "provider": args.provider,
        "model": config["model"],
        "endpoint": config["endpoint"],
        "taskCount": len(cases),
        "strategies": ["batch", "iterative"],
        "maximumModelCalls": len(cases) * (1 + MAX_ITERATIVE_CALLS),
        "timeoutSeconds": args.timeout,
        "retries": 0,
        "frozenCasesSha256": sha256(case_bytes),
        "selectionSha256": sha256(selection_bytes) if selection_bytes else None,
        "promptSha256": {name: sha256(value.encode("utf-8")) for name, value in prompts.items()},
        "sourceFilesSha256": {
            str(path.resolve()): sha256(path.read_bytes())
            for path in tracked_sources if path is not None
        },
        "workingTreeClean": source_status == "",
        "workingTreeStatusSha256": sha256(source_status.encode("utf-8")),
        "businessScoring": "pending independent task-level review; no automatic semantic pass",
    }
    (args.evidence / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")
    results, latencies = [], {"batch": [], "iterative": []}
    try:
        for position, case in enumerate(cases):
            # The frozen bank uses userTask as a task-level description for some
            # multi-turn cases.  The actual first second-gate input is turns[0].
            task = first_turn(case)
            order = ["batch", "iterative"] if position % 2 == 0 else ["iterative", "batch"]
            row = {
                "caseId": case["id"], "userTask": case.get("userTask"), "modelInput": task,
                "corpus": case.get("corpus"),
                "requiredConditions": case.get("requiredConditions"),
                "acceptableOutcomes": case.get("acceptableOutcomes"),
                "prohibitedBehaviors": case.get("prohibitedBehaviors"),
                "observations": {}, "batchVerdict": None, "iterativeVerdict": None,
                "taskLevelReview": None,
            }
            for strategy in order:
                try:
                    if strategy == "batch":
                        parsed, calls = run_batch(args.provider, config, key, prompts[strategy], task, args.timeout, args.proxy)
                    else:
                        parsed, calls = run_iterative(args.provider, config, key, prompts[strategy], task, args.timeout, args.proxy)
                    row["observations"][strategy] = {"protocol": "PASS", "classification": parsed, "calls": len(calls)}
                    latencies[strategy].extend(call["latencySeconds"] for call in calls)
                    for call in calls:
                        append_jsonl(args.evidence / "network.jsonl", {"caseId": case["id"], "strategy": strategy, **call})
                except ProtocolError as failure:
                    calls = getattr(failure, "network", [])
                    for call in calls:
                        latencies[strategy].append(call["latencySeconds"])
                        append_jsonl(args.evidence / "network.jsonl", {"caseId": case["id"], "strategy": strategy, **call})
                    row["observations"][strategy] = {"protocol": "FAIL", "reason": str(failure), "calls": len(calls)}
                except RuntimeError as failure:
                    detail = failure.args[1] if len(failure.args) > 1 else None
                    if detail:
                        append_jsonl(args.evidence / "network.jsonl", {"caseId": case["id"], "strategy": strategy, **detail})
                    row["observations"][strategy] = {"protocol": "TRANSPORT_FAILURE", "reason": str(failure.args[0])}
                    if str(failure.args[0]) in {"HTTP_400", "HTTP_401", "HTTP_402", "HTTP_403", "HTTP_429"}:
                        raise
            append_jsonl(args.evidence / "classifications.jsonl", row)
            results.append(row)
    finally:
        key = None
    summary = {
        "tasks": len(results),
        "provider": args.provider,
        "protocol": {
            strategy: {
                "PASS": sum(row["observations"].get(strategy, {}).get("protocol") == "PASS" for row in results),
                "FAIL": sum(row["observations"].get(strategy, {}).get("protocol") == "FAIL" for row in results),
                "TRANSPORT_FAILURE": sum(row["observations"].get(strategy, {}).get("protocol") == "TRANSPORT_FAILURE" for row in results),
            } for strategy in ("batch", "iterative")
        },
        "latencySeconds": {
            strategy: {
                "calls": len(values),
                "p50": statistics.median(values) if values else None,
                "p95": percentile(values, 0.95),
                "maximum": max(values) if values else None,
                "over18Seconds": sum(value > 18 for value in values),
            } for strategy, values in latencies.items()
        },
        "semanticBusinessVerdicts": "PENDING_INDEPENDENT_REVIEW",
    }
    (args.evidence / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
