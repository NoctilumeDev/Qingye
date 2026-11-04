"""Collect a reviewable local candidate; never build, upload, or overwrite a package."""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = {"application.yml", "schema.sql", "announcements.json", "assistant-contract.json",
             "assistant-locator-protocol.json", "assistant-planner.txt"}
FIXED_SOURCES = {"backend/pom.xml", "LICENSE", "NOTICE", "docs/deployment.md",
                 "docs/governance/dependency-policy.json", "scripts/start-production.ps1",
                 "scripts/package-release.py"}
CLIENT_ROOT_FILES = {"app.js", "app.json", "app.wxss", "sitemap.json", "project.config.json"}
CLIENT_DIRS = {"pages", "components", "custom-tab-bar", "utils", "assets"}
CLIENT_SUFFIXES = {".js", ".json", ".wxml", ".wxss", ".png", ".jpg", ".jpeg", ".svg"}
ENV_TEMPLATE = """# Field template only: the launcher does not load this file.
# Supply real values through your deployment environment; never commit them.
QINGYE_DB_URL=jdbc:mysql://127.0.0.1:3306/qingye?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
QINGYE_DB_USER=REPLACE_WITH_DATABASE_USER
QINGYE_DB_PASSWORD=REPLACE_WITH_DATABASE_PASSWORD
QINGYE_WX_APPID=REPLACE_WITH_WECHAT_APPID
QINGYE_WX_SECRET=REPLACE_WITH_WECHAT_SECRET
QINGYE_TOKEN_SECRET=REPLACE_WITH_INDEPENDENT_RANDOM_SIGNING_SECRET
QINGYE_BIND=127.0.0.1
QINGYE_PORT=8087
# The launcher fixes demo/Redis/MQ to false and keeps the local task worker.
"""
PUBLIC_TODO = """# 公开发行待办

本目录是本地候选包，不代表已合并、已公开发行或已取得部署资格。

- 仓库保留的 MySQL Connector/J GPL v2 + Universal FOSS Exception 分发核对仍为 UNRESOLVED。
- RabbitMQ、Logback 等实际运行依赖的多许可选择和完整分发材料仍需按本包资产核对；不作兼容性法律判定。
- THIRD_PARTY/runtime-dependencies.json 列出每个嵌入依赖、采集材料及 SBOM 对应情况；没有自带 LICENSE/NOTICE 或未被所提供 SBOM 覆盖的条目需补核对。SBOM 许可标签不等于许可证全文。
- 小程序素材说明及图标许可/修改声明已保留；仓库记录的生成图片参考材料权利链未独立核验。未把 docs 展示背景图纳入本包。
- 真实微信登录、HTTPS、原生编译/真机及部署容量需按本轮运行证据填写，打包不代替验证。

原记录：docs/governance/continuous-checks.md 的“许可与素材来源”。
"""


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def git(root, *arguments):
    return subprocess.check_output(["git", "-c", "core.quotepath=false", *arguments], cwd=root).decode("utf-8")


def private_path(name):
    return any(part.lower() in {"local.ps1", "project.private.config.json", "node_modules", "target"}
               or part.lower().startswith(".env") or ".private." in part.lower() or ".local." in part.lower()
               for part in PurePosixPath(name).parts)


def client_source(name):
    path = PurePosixPath(name)
    if not path.parts or path.parts[0] != "miniprogram" or private_path(name):
        return False
    relative = path.relative_to("miniprogram")
    return (relative.as_posix() in CLIENT_ROOT_FILES
            or (len(relative.parts) > 1 and relative.parts[0] in CLIENT_DIRS
                and (relative.suffix.lower() in CLIENT_SUFFIXES
                     or relative.as_posix() in {"assets/README.md", "assets/icons/LICENSE.txt", "assets/icons/NOTICE.txt"})))


def source_allowed(name):
    if private_path(name):
        return False
    return (name in FIXED_SOURCES or client_source(name)
            or (name.startswith("backend/src/main/java/") and name.endswith(".java"))
            or name in {"backend/src/main/resources/" + resource for resource in RESOURCES})


def read_source(root, name):
    path = root / name
    require(not path.is_symlink() and path.resolve().is_relative_to(root), "Source symlink/outside repository: " + name)
    require(path.is_file(), "Missing source: " + name)
    return path.read_bytes()


def license_entry(name):
    path = PurePosixPath(name)
    return (not name.endswith("/") and path.suffix.lower() in {"", ".txt", ".md", ".html", ".htm", ".rst"} and
            (re.match(r"^(LICENSE|LICENCE|NOTICE|COPYING)([._-]|$)", path.name, re.I)
             or any(part.lower() in {"licenses", "licences"} for part in path.parts)))


def inspect_jar(jar_bytes, sources, sbom):
    """Read nested notices without extractall or trusting archive path names."""
    files, dependencies = {}, []
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as jar:
        names = jar.namelist()
        require(len(names) == len(set(names)), "Duplicate entries in application JAR")
        boot_metadata = {"META-INF/MANIFEST.MF", "META-INF/services/java.nio.file.spi.FileSystemProvider",
                         "META-INF/maven/cn.qingye/qingye/pom.xml", "META-INF/maven/cn.qingye/qingye/pom.properties",
                         "BOOT-INF/classpath.idx", "BOOT-INF/layers.idx"}
        for name in names:
            if name.endswith("/"):
                continue
            require(not private_path(name) and (name in boot_metadata or name.startswith("BOOT-INF/classes/")
                    or (name.startswith("BOOT-INF/lib/") and name.endswith(".jar") and name.count("/") == 2) or
                    (name.startswith("org/springframework/boot/loader/") and name.endswith(".class"))),
                    "Unexpected/private application JAR entry: " + name)
        require("META-INF/MANIFEST.MF" in names and "BOOT-INF/classes/cn/qingye/QingyeApplication.class" in names,
                "Not a Qingye executable Spring Boot JAR")
        resources = {name.removeprefix("BOOT-INF/classes/") for name in names
                     if name.startswith("BOOT-INF/classes/") and not name.endswith(("/", ".class"))}
        require(resources == RESOURCES, "Application JAR resources differ from the runtime allowlist")
        for resource in sorted(RESOURCES):
            require(jar.read("BOOT-INF/classes/" + resource) == sources["backend/src/main/resources/" + resource],
                    "JAR resource is stale/different: " + resource)
        libraries = sorted(name for name in names if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"))
        require(libraries, "Application JAR contains no runtime dependencies")
        for number, name in enumerate(libraries, 1):
            library = PurePosixPath(name).name
            require(name == "BOOT-INF/lib/" + library, "Unexpected nested library path")
            content = jar.read(name)
            folder = f"THIRD_PARTY/notices/{number:03d}-" + re.sub(r"[^A-Za-z0-9_.-]", "_", library)
            notices = []
            with zipfile.ZipFile(io.BytesIO(content)) as nested:
                for index, entry in enumerate(sorted(item for item in nested.namelist() if license_entry(item)), 1):
                    destination = folder + f"/{index:03d}-" + re.sub(r"[^A-Za-z0-9_.-]", "_", PurePosixPath(entry).name)
                    files[destination] = nested.read(entry)
                    notices.append({"archive_path": entry, "package_path": destination, "sha256": digest(files[destination])})
            components = [item for item in sbom["components"]
                          if f"{item.get('name')}-{item.get('version')}.jar" == library]
            dependencies.append({"jar": library, "sha256": digest(content), "notices": notices,
                                 "notice_status": "COLLECTED" if notices else "NO_BUNDLED_LICENSE_OR_NOTICE",
                                 "sbom_status": "MATCHED" if len(components) == 1 else "MISSING_OR_AMBIGUOUS",
                                 "sbom_matches": [{key: item.get(key) for key in ("group", "name", "version", "purl", "licenses")}
                                                  for item in components]})
    files["THIRD_PARTY/runtime-dependencies.json"] = encoded({"scope": "Actual BOOT-INF/lib entries; collection is not a license verdict.",
                                                            "dependencies": dependencies})
    return files


def package(root, jar_path, sbom_path, output, allow_dirty=False):
    root, output = root.resolve(), output.absolute()
    require(not output.exists(), "Output already exists; choose a new directory")
    for source_root in ("backend/src", "miniprogram", "scripts", "docs"):
        require(not output.resolve().is_relative_to(root / source_root), "Output must not be inside a source directory")
    status = git(root, "status", "--porcelain=v1", "--untracked-files=all")
    require(allow_dirty or not status.strip(), "Working tree is dirty; explicitly pass --allow-dirty for a local candidate")
    base = git(root, "rev-parse", "HEAD").strip()
    names = {name for name in git(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard").split("\0") if name}
    sources = {name: read_source(root, name) for name in sorted(names) if source_allowed(name) and (root / name).exists()}
    required = FIXED_SOURCES | {"backend/src/main/resources/" + name for name in RESOURCES} | {
        "miniprogram/app.json", "miniprogram/project.config.json", "miniprogram/utils/config.js",
        "miniprogram/assets/README.md", "miniprogram/assets/icons/LICENSE.txt", "miniprogram/assets/icons/NOTICE.txt"}
    require(required <= sources.keys(), "Required sources absent from Git inventory: " + ", ".join(sorted(required - sources.keys())))
    app = json.loads(sources["miniprogram/app.json"])
    require(isinstance(app.get("pages"), list) and app["pages"], "Mini-program page inventory is empty")
    for page in app["pages"]:
        for suffix in (".js", ".json", ".wxml", ".wxss"):
            require("miniprogram/" + page + suffix in sources, "Missing mini-program page: " + page + suffix)
    sbom_bytes = sbom_path.read_bytes()
    sbom = json.loads(sbom_bytes)
    require(sbom.get("bomFormat") == "CycloneDX" and isinstance(sbom.get("components"), list) and sbom["components"],
            "Supply a nonempty CycloneDX JSON SBOM from the current dependency check")
    jar_bytes = jar_path.read_bytes()
    files = inspect_jar(jar_bytes, sources, sbom)
    files.update({name: content for name, content in sources.items() if client_source(name)})
    files["miniprogram/utils/config.js"] = b"// Replace before compiling for your deployment. No credentials belong here.\nmodule.exports = { baseUrl: 'https://REPLACE_WITH_API_HOST.invalid' }\n"
    project = json.loads(sources["miniprogram/project.config.json"])
    project["appid"] = "REPLACE_WITH_WECHAT_APPID"
    project.setdefault("setting", {})["urlCheck"] = True
    files["miniprogram/project.config.json"] = encoded(project)
    files.update({"backend/qingye-0.1.0.jar": jar_bytes,
                  "database/schema.sql": sources["backend/src/main/resources/schema.sql"],
                  "scripts/start-production.ps1": sources["scripts/start-production.ps1"],
                  "README.md": sources["docs/deployment.md"],
                  "LICENSE": sources["LICENSE"], "NOTICE": sources["NOTICE"],
                  "config/production.env.example": ENV_TEMPLATE.encode("utf-8"),
                  "PUBLIC_RELEASE_TODO.md": PUBLIC_TODO.encode("utf-8"),
                  "THIRD_PARTY/bom.cdx.json": sbom_bytes,
                  "THIRD_PARTY/dependency-policy.json": sources["docs/governance/dependency-policy.json"]})
    files["SOURCE_MANIFEST.json"] = encoded({
        "schema": "qingye-local-candidate/v1", "status": "LOCAL_CANDIDATE_NOT_PUBLIC_RELEASE",
        "created_utc": datetime.now(timezone.utc).isoformat(), "base_head": base,
        "dirty_allowed": allow_dirty, "working_tree_dirty": bool(status.strip()), "git_status": status.splitlines(),
        "source_scope": "Backend production build inputs, mini-program runtime sources, packaging instructions and dependency declarations; not a complete repository snapshot.",
        "build_provenance": "Supplied JAR; application resources checked against source. Java compilation and test evidence remain separate.",
        "artifact_sha256": digest(jar_bytes), "sbom_sha256": digest(sbom_bytes),
        "source_files": [{"path": name, "sha256": digest(content), "bytes": len(content)} for name, content in sorted(sources.items())],
        "package_transformations": ["Mini-program API host and AppID replaced with invalid production placeholders; urlCheck enabled."]})
    files["SHA256SUMS"] = "".join(f"{digest(content)}  {name}\n" for name, content in sorted(files.items())).encode("utf-8")
    # All input validation happens before creating the new output directory.
    output.mkdir(parents=True, exist_ok=False)
    for name, content in sorted(files.items()):
        destination = output / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("xb") as stream:
            stream.write(content)
    return len(files)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--sbom", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--allow-dirty", action="store_true")
    args = parser.parse_args()
    try:
        count = package(ROOT, args.jar, args.sbom, args.output, args.allow_dirty)
        print(f"Local candidate created: {args.output.absolute()} ({count} files)")
        print("Not a public release or deployment qualification. Review SOURCE_MANIFEST.json and PUBLIC_RELEASE_TODO.md.")
        return 0
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"Candidate packaging rejected: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
