"""Fresh resolved-dependency change gate. Never accepts retained risks."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("risk_gate", ROOT / "scripts/check-static.py")
risk_gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(risk_gate)
require = risk_gate.require
VERSION = "2.7.0"
RETIREMENT_CONTRACTS = {
    "qingye-rabbitmq-client/v1": {
        "package": "com.rabbitmq:amqp-client",
        "manifest": "backend/pom.xml",
        "evidence": {
            "docs/governance/rabbitmq-maintenance-20261023.md",
            "backend/src/test/java/cn/qingye/RabbitClientBoundaryTest.java",
            "backend/src/test/java/cn/qingye/RabbitBridgeLiveTest.java",
        },
    },
    "qingye-tomcat-authority/v1": {
        "package": "org.apache.tomcat.embed:tomcat-embed-core",
        "manifest": "backend/pom.xml",
        "evidence": {
            "docs/governance/tomcat-maintenance-20261029.md",
            "backend/src/test/java/cn/qingye/TomcatAuthorityBoundaryTest.java",
        },
    },
}
BINARIES = {
    ("Windows", "AMD64"): ("osv-scanner_windows_amd64.exe", "9964ed64ec9a387391eb4cc006ad5c531154821aef29b793e02c448de4686438"),
    ("Linux", "x86_64"): ("osv-scanner_linux_amd64", "160c6253e2922e9e0a6a9771c5c4c40a4b3c6090b34a3cdd5e80c96cc2e4218b"),
}


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=risk_gate.unique_object)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


def coordinate(group, name, version):
    require(all(risk_gate.text(x) for x in (group, name, version)), "unresolved package identity")
    return f"{group}:{name}@{version}"


def inventory(bom, tree):
    require(bom.get("bomFormat") == "CycloneDX" and bom.get("specVersion") == "1.6", "wrong SBOM format")
    resolved = {}

    def visit(node):
        identity = coordinate(node.get("groupId"), node.get("artifactId"), node.get("version"))
        require(node.get("scope") in {"compile", "runtime", "test", "provided", "system"}, "unknown dependency scope")
        resolved.setdefault(identity, set()).add(node["scope"])
        for child in node.get("children", []):
            visit(child)

    for node in tree.get("children", []):
        visit(node)
    require(resolved, "empty resolved dependency tree")
    result = {}
    for item in bom.get("components", []):
        identity = coordinate(item.get("group"), item.get("name"), item.get("version"))
        require(identity not in result and identity in resolved, "duplicate or unexpected SBOM component")
        licenses = []
        for declaration in item.get("licenses", []):
            license_data = declaration.get("license", {})
            label = declaration.get("expression") or license_data.get("id") or license_data.get("name")
            require(risk_gate.text(label), f"{identity}: malformed license declaration")
            licenses.append(label)
        result[identity] = {"scopes": sorted(resolved[identity]), "licenses": sorted(licenses)}
    require(result.keys() == resolved.keys(), "SBOM does not cover the full resolved project dependency tree")
    return dict(sorted(result.items()))


def scan_findings(report, components, exit_code):
    require(exit_code in {0, 1}, f"scanner failed with exit {exit_code}")
    results = report.get("results")
    require(isinstance(results, list) and len(results) == 1, "missing or unexpected scanner sources")
    require(results[0].get("source", {}).get("type") == "sbom", "scanner did not consume the SBOM")
    scanned, findings = set(), {}
    for item in results[0].get("packages", []):
        package = item.get("package", {})
        require(package.get("ecosystem") == "Maven", "unexpected ecosystem")
        require(risk_gate.text(package.get("name")) and risk_gate.text(package.get("version")), "missing scanned package")
        identity = package["name"] + "@" + package["version"]
        require(identity in components and identity not in scanned, "unexpected or duplicate scanned package")
        scanned.add(identity)
        for advisory in item.get("vulnerabilities", []):
            identifier = advisory.get("id")
            require(risk_gate.text(identifier), "advisory without identity")
            key = identity + "/" + identifier
            require(key not in findings, "duplicate advisory")
            # Keep changes in applicability, severity and description visible. Database-only timestamps are excluded.
            fields = ("id", "aliases", "summary", "details", "affected", "severity", "database_specific", "withdrawn")
            findings[key] = digest({field: advisory.get(field) for field in fields})
    require(scanned == components.keys(), "scanner coverage does not match all resolved packages")
    require(exit_code == (1 if findings else 0), "scanner exit disagrees with its finding report")
    return dict(sorted(findings.items()))


def verify_retirement_history(root, identity, retirement, policy, contract):
    def git(*args):
        return subprocess.check_output(["git", *args], cwd=root, text=True, encoding="utf-8",
                                       stderr=subprocess.PIPE, timeout=30)

    historical, changed = retirement["historical_revision"], retirement["change_revision"]
    reviewed = retirement.get("manifest_review_revision", changed)
    # A syntactically valid SHA is not proof that a commit exists or belongs to this history.
    for revision in dict.fromkeys((historical, changed, reviewed)):
        try:
            git("cat-file", "-e", revision + "^{commit}")
        except subprocess.CalledProcessError:
            # Squash/rebase may not retain the source commit as a main ancestor.
            # Retrieve only its exact object; unavailable history fails closed.
            git("fetch", "--no-tags", "origin", revision)
            git("cat-file", "-e", revision + "^{commit}")
    git("merge-base", "--is-ancestor", historical, changed)
    git("merge-base", "--is-ancestor", changed, reviewed)
    git("merge-base", "--is-ancestor", historical, "HEAD")
    previous = json.loads(git("show", historical + ":docs/governance/dependency-policy.json"),
                          object_pairs_hook=risk_gate.unique_object)
    require(identity in previous.get("components", {}), "retired coordinate absent from historical policy")
    archived = {k: v for k, v in previous.get("advisories", {}).items() if k.startswith(identity + "/")}
    require(archived == retirement["advisories"], "historical advisory fingerprints do not match")
    manifest = contract["manifest"]
    original_manifest = git("show", historical + ":" + manifest)
    changed_manifest = git("show", changed + ":" + manifest)
    require(original_manifest != changed_manifest, "retirement change did not modify the manifest")
    # A later dependency upgrade may change the shared manifest. Retain the
    # original retirement evidence and bind the subsequent review separately.
    reviewed_manifest = git("show", reviewed + ":" + manifest)
    require(hashlib.sha256(reviewed_manifest.encode()).hexdigest() == policy["applicability_inputs"].get(manifest),
            "manifest review revision is not bound to the reviewed current manifest")


def validate_policy(current, findings, policy, register, root=ROOT):
    require(policy.get("schema") == "qingye-dependency-policy/v1" and risk_gate.sha(policy.get("source_revision")), "invalid dependency policy")
    require(current == policy.get("components"), "dependency versions, scopes or declared licenses changed; review the inventory")
    previous = policy.get("advisories", {})
    changed = sorted(k for k in set(findings) | set(previous) if findings.get(k) != previous.get(k))
    require(not changed, "new/changed/disappeared advisories require reconciliation: " + ", ".join(changed))
    retained = {item["version"] + "/" + item["id"]: item for item in register["dependency_findings"]}
    # A reviewed upgrade retires a component coordinate, not its historical
    # findings. Never relabel NOT_APPLICABLE/UNRESOLVED as a fabricated fix.
    retired = policy.get("retired_components", {})
    require(isinstance(retired, dict), "invalid retired component records")
    retired_keys = set()
    for identity, retirement in retired.items():
        require(isinstance(retirement, dict) and identity not in current,
                "retired component is still present")
        contract = RETIREMENT_CONTRACTS.get(retirement.get("contract"))
        require(contract and identity.rsplit("@", 1)[0] == contract["package"],
                "retirement requires an applicable evidence contract")
        replacement = retirement.get("replacement")
        require(isinstance(replacement, str) and replacement in current
                and identity.rsplit("@", 1)[0] == replacement.rsplit("@", 1)[0],
                "retirement must identify the current version of the same package")
        require(risk_gate.sha(retirement.get("change_revision"))
                and risk_gate.sha(retirement.get("historical_revision"))
                and risk_gate.sha(retirement.get("manifest_review_revision", retirement.get("change_revision"))),
                "retirement requires exact historical and change revisions")
        history = {k for k, item in retained.items() if item["version"] == identity}
        require(all(retained[k]["status"] != "CONFIRMED" for k in history),
                "confirmed defect requires explicit remediation evidence, not coordinate retirement")
        reviewed = retirement.get("advisories")
        require(history and isinstance(reviewed, dict) and history == reviewed.keys()
                and all(isinstance(v, str) and len(v) == 64
                        and all(c in "0123456789abcdef" for c in v) for v in reviewed.values()),
                "retirement must retain every historical advisory fingerprint")
        require(not history.intersection(findings), "retired finding recurred")
        proof = retirement.get("evidence")
        require(isinstance(proof, dict) and proof.keys() == contract["evidence"],
                "retirement requires the contracted reconciliation and regression evidence")
        for name, expected in proof.items():
            path = (root / name).resolve()
            require(path.is_relative_to(root.resolve()) and path.is_file(),
                    "retirement evidence missing or outside repository")
            actual = hashlib.sha256(path.read_text(encoding="utf-8").encode()).hexdigest()
            require(actual == expected, "retirement evidence content changed: " + name)
        verify_retirement_history(root, identity, retirement, policy, contract)
        retired_keys.update(history)
    require(set(findings) == {k for k, v in retained.items()
                             if v["status"] != "REMEDIATED" and k not in retired_keys},
            "dependency risk register and current findings disagree")
    require(all(retained[k]["status"] in {"NOT_APPLICABLE", "UNRESOLVED"} for k in findings), "confirmed or recurring remediated dependency defect blocks this gate")
    # Revisit conditional dispositions if their cited runtime inputs change. This is not an automatic re-triage.
    inputs = policy.get("applicability_inputs")
    require(isinstance(inputs, dict) and inputs, "missing applicability inputs")
    required_paths = {entry["path"] for item in register["dependency_findings"] for entry in item["evidence"]
                      if isinstance(entry, dict) and entry.get("path", "").startswith("backend/")}
    require(set(inputs) == required_paths, "applicability input set differs from the retained source evidence")
    for name, expected in inputs.items():
        path = (root / name).resolve()
        require(path.is_relative_to(root.resolve()), "applicability input escapes the repository")
        # Git checkouts may normalize LF to CRLF; hash logical UTF-8 source, not checkout-specific newlines.
        actual = hashlib.sha256(path.read_text(encoding="utf-8").encode()).hexdigest()
        require(actual == expected, f"{name}: cited applicability input changed; review affected dispositions")
    return Counter(item["status"] for item in retained.values())


def scanner_binary():
    identity = (platform.system(), platform.machine())
    require(identity in BINARIES, "unsupported scanner platform; no unverified binary fallback")
    name, expected = BINARIES[identity]
    directory = ROOT / ".tools" / ("osv-scanner-" + VERSION)
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / name
    if not target.exists():
        url = f"https://github.com/google/osv-scanner/releases/download/v{VERSION}/{name}"
        with urllib.request.urlopen(url, timeout=60) as response:
            binary = response.read()
        require(hashlib.sha256(binary).hexdigest() == expected, "downloaded scanner checksum mismatch")
        target.write_bytes(binary)
        if os.name != "nt":
            target.chmod(0o755)
    require(hashlib.sha256(target.read_bytes()).hexdigest() == expected, "scanner checksum mismatch")
    return target


def execute_scan():
    maven = shutil.which("mvn.cmd" if os.name == "nt" else "mvn")
    require(maven, "Maven is missing")
    parent = ROOT / "backend/target/dependency-audit"
    parent.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix="run-", dir=parent))
    print(f"Dependency evidence: {output}", flush=True)
    record = {"source_revision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "working_tree_changed": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()),
              "scanner_version": VERSION, "state": "STARTED"}
    (output / "run.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
    config = output / "osv-scanner.toml"
    config.write_text("# No ignore rules or package overrides.\n", encoding="utf-8")
    subprocess.run([maven, "-B", "-ntp", "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree",
                    "-DoutputType=json", f"-DoutputFile={output / 'dependency-tree.json'}"], cwd=ROOT / "backend", check=True, timeout=600)
    subprocess.run([maven, "-B", "-ntp", "org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom",
                    "-DincludeTestScope=true", "-DincludeLicenseText=false", "-DschemaVersion=1.6", "-DoutputFormat=json",
                    "-DoutputName=bom.cdx", f"-DoutputDirectory={output}"], cwd=ROOT / "backend", check=True, timeout=600)
    components = inventory(read_json(output / "bom.cdx.json"), read_json(output / "dependency-tree.json"))
    result = subprocess.run([str(scanner_binary()), "scan", "source", "--sbom", str(output / "bom.cdx.json"),
                             "--config", str(config), "--all-packages", "--format", "json",
                             "--output-file", str(output / "osv.json")], cwd=output, timeout=180)
    record.update(state="SCANNER_RETURNED", scanner_exit=result.returncode)
    (output / "run.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
    findings = scan_findings(read_json(output / "osv.json"), components, result.returncode)
    policy = read_json(ROOT / "docs/governance/dependency-policy.json")
    states = validate_policy(components, findings, policy, risk_gate.load_register(risk_gate.REGISTER))
    record.update(state="CHANGE_GATE_PASS", packages=len(components), advisories=len(findings), retained_states=dict(states),
                  retired_components=sorted(policy.get("retired_components", {})))
    (output / "run.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
    print(f"Dependency change gate: PASS ({len(components)} resolved packages, {len(findings)} retained advisories)")
    print(f"Historical dispositions (including retired versions): {dict(states)}")
    print(f"Reviewed retired coordinates: {sorted(policy.get('retired_components', {}))}; no risk acceptance or license-compatibility certification.")


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    try:
        execute_scan()
    except (OSError, ValueError, KeyError, TypeError, AttributeError, subprocess.SubprocessError) as error:
        print(f"Dependency gate rejected: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
