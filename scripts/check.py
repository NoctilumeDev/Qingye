"""Current maintenance checks. Read-only hygiene; never deletes local state."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
GENERATED = {"node_modules", "target", "dist", "__pycache__", "coverage",
             "playwright-report", "test-results", "runtime", "uploads", ".tools"}
PRIVATE = {"local.ps1", "project.private.config.json", "database.local.json"}


def residual_reason(name):
    path = Path(name)
    if GENERATED.intersection(path.parts):
        return "tracked generated/runtime directory"
    if path.name in PRIVATE or (path.name.startswith(".env") and path.name != ".env.example"):
        return "tracked private environment"
    # Formal logs are retained by their original binding verifier. This never
    # exempts a cache directory or private environment inside artifacts.
    if PROTECTED and path.parts and path.parts[0] in PROTECTED and path.suffix.lower() == ".log":
        return None
    if path.suffix.lower() in {".pyc", ".log", ".pid", ".tmp"}:
        return "tracked transient file"
    return None


def link_errors(root, documents):
    errors = []
    for name in documents:
        document = root / name
        if not document.is_file():
            errors.append(f"missing current document: {name}")
            continue
        text = document.read_text(encoding="utf-8")
        targets = re.findall(r"!?\[[^\]\n]*\]\((<[^>]+>|[^\s)]+)", text)
        targets += re.findall(r"^\[[^\]]+\]:\s*(\S+)", text, re.M)
        for target in targets:
            target = target.strip("<>")
            if target.startswith("#") or urlsplit(target).scheme:
                continue
            relative = unquote(target.split("#", 1)[0].split("?", 1)[0])
            if relative and not (document.parent / relative).exists():
                errors.append(f"{name}: broken local link: {target}")
    return errors


def hygiene():
    files = subprocess.check_output(
        ["git", "ls-files", "-z", "--", "."], cwd=ROOT).decode("utf-8").split("\0")
    files = [name for name in files if name]
    errors = [f"{name}: {reason}" for name in files
              if (reason := residual_reason(name))]
    errors += link_errors(ROOT, CURRENT_DOCS)
    for error in errors:
        print(error, file=sys.stderr)
    if errors:
        return 1
    print(f"Repository hygiene: PASS ({len(files)} tracked paths; current local links)")
    print("Scope: known tracked residues only. Image consumers, retained evidence and")
    print("unique local state require stage review; LOCAL_DORMANT is not certified.")
    return 0


def executable(name):
    found = shutil.which(name + ".cmd") if os.name == "nt" else None
    found = found or shutil.which(name)
    if not found:
        raise RuntimeError(f"Missing {name}; install the documented toolchain first")
    return found


def run(command, cwd=ROOT, env=None):
    print("Run: " + " ".join(map(str, command)), flush=True)
    subprocess.run(command, cwd=cwd, env=env, check=True)


def mysql_environment(prefix, allowed):
    names = [prefix + suffix for suffix in DB_SUFFIXES]
    values = [os.environ.get(name, "") for name in names]
    if not all(values):
        raise RuntimeError("MySQL checks require " + ", ".join(names))
    match = re.fullmatch(r"jdbc:mysql://[^/?]+/([a-z_]+)(?:\?[^#]*)?", values[0])
    if not match or match[1] not in allowed:
        raise RuntimeError("Refusing non-test database URL")
    return os.environ.copy()


def coverage_report(report, classes_dir):
    """Validate measurement completeness, not a coverage percentage threshold."""
    try:
        root = ET.parse(report).getroot()
        if root.tag != "report" or root.find("sessioninfo") is None:
            raise ValueError("missing report or execution session")
        expected = {path.relative_to(classes_dir).with_suffix("").as_posix()
                    for path in classes_dir.rglob("*.class")
                    if path.name not in {"package-info.class", "module-info.class"}}
        reported = [item.get("name") for item in root.findall("./package/class")]
        if not expected or len(reported) != len(set(reported)) or set(reported) != expected:
            raise ValueError("reported classes do not match compiled production classes")
        measurements = {}
        for kind in ("LINE", "BRANCH"):
            counters = root.findall(f"./counter[@type='{kind}']")
            if len(counters) != 1:
                raise ValueError(f"missing or duplicate {kind} counter")
            missed = int(counters[0].get("missed", ""))
            covered = int(counters[0].get("covered", ""))
            if min(missed, covered) < 0 or missed + covered == 0:
                raise ValueError(f"invalid {kind} denominator")
            measurements[kind] = (covered, missed + covered)
        for kind, (covered, total) in measurements.items():
            print(f"Coverage measurement {kind}: {covered}/{total} ({covered / total:.2%})")
        print("Coverage report: valid; no percentage threshold or semantic qualification claimed.")
        return measurements
    except (OSError, ET.ParseError, ValueError) as error:
        raise RuntimeError(f"Coverage report rejected: {error}") from error


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("group", choices=GROUPS)
    args = parser.parse_args()
    try:
        for group in EXPAND.get(args.group, [args.group]):
            check(group)
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Check failed: {error}", file=sys.stderr)
        return 1
    return 0


PROTECTED = set()
CURRENT_DOCS = ["README.md", "AGENTS.md", "ORIGIN.md", "CONTRIBUTING.md", "SECURITY.md", "docs/项目简介.md",
                "docs/assistant-contract.md", "docs/repository-governance.md",
                "docs/governance/risk-register.md",
                "docs/governance/continuous-checks.md",
                "miniprogram/assets/README.md"]
DB_SUFFIXES = ["_URL", "_USER", "_PASSWORD"]
GROUPS = ["hygiene", "client", "h2", "mysql", "static", "dependencies", "quick", "all"]
EXPAND = {"quick": ["hygiene", "client", "h2", "static"], "all": ["hygiene", "client", "h2", "mysql", "static"]}


def check(group):
    if group == "hygiene":
        if hygiene():
            raise RuntimeError("Repository hygiene rejected")
        run([sys.executable, "-B", str(ROOT / "scripts/check-static.py")])
        run([sys.executable, "-B", str(ROOT / "scripts/check-secrets.py")])
        run([sys.executable, "-B", "-m", "unittest", "discover", "-s", "scripts/tests"])
    elif group == "client":
        run([sys.executable, "-B", str(ROOT / "scripts/check-client.py")])
        run([executable("node"), "--test", str(ROOT / "scripts/test-client.cjs")])
    elif group == "dependencies":
        run([sys.executable, "-B", str(ROOT / "scripts/check-dependencies.py")])
    elif group == "h2":
        # A shell left configured for MySQL must not turn this fast group into a
        # destructive test on a database. Clear only the child process variables.
        env = {k: v for k, v in os.environ.items() if not k.startswith("QINGYE_TEST_")}
        run([executable("mvn"), "-B", "-ntp", "clean", "test"], ROOT / "backend", env)
        coverage_report(ROOT / "backend/target/site/jacoco/jacoco.xml", ROOT / "backend/target/classes")
    elif group == "mysql":
        env = mysql_environment("QINGYE_TEST", {"qingye_test"})
        run([executable("mvn"), "-B", "-ntp", "clean", "test"], ROOT / "backend", env)
        coverage_report(ROOT / "backend/target/site/jacoco/jacoco.xml", ROOT / "backend/target/classes")
    elif group == "static":
        # Compile a fresh production class set; this goal neither packages nor runs the application.
        # Keep test/coverage results from h2/mysql by using an isolated Maven output directory.
        run([executable("mvn"), "-B", "-ntp", "-Pstatic-analysis", "clean", "compile",
             "com.github.spotbugs:spotbugs-maven-plugin:4.10.5.0:spotbugs"], ROOT / "backend")
        run([sys.executable, "-B", str(ROOT / "scripts/check-static.py"),
             "--report", str(ROOT / "backend/target/static-analysis/spotbugsXml.xml"),
             "--classes", str(ROOT / "backend/target/static-analysis/classes")])


if __name__ == "__main__":
    sys.exit(main())
