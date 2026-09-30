"""Validate native mini-program files without npm packages or an extra client."""
import argparse
import html
import json
import re
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT / "miniprogram"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--compiler-dir", help="Installed WeChat wcc-exec directory")
    args = parser.parse_args()
    app = json.loads((CLIENT / "app.json").read_text(encoding="utf-8"))
    for file in CLIENT.rglob("*.json"):
        json.loads(file.read_text(encoding="utf-8"))
    for page in app["pages"]:
        for suffix in (".js", ".json", ".wxml", ".wxss"):
            assert (CLIENT / (page + suffix)).is_file(), page + suffix
    for file in CLIENT.rglob("*.js"):
        subprocess.run(["node", "--check", str(file)], check=True, capture_output=True)
    allowed = {"view", "text", "image", "scroll-view", "input", "textarea", "button", "picker", "switch", "block", "activity-card"}
    for file in CLIENT.rglob("*.wxml"):
        wrapped = '<root xmlns:wx="urn:wechat">' + file.read_text(encoding="utf-8") + '</root>'
        wrapped = re.sub(r'\{\{(.*?)\}\}', lambda m: '{{' + html.escape(html.unescape(m.group(1)), quote=False) + '}}', wrapped, flags=re.S)
        # Native WXML permits boolean attributes; turn them into XML equivalents for structural checks.
        for attr in ("selectable", "scroll-x"):
            wrapped = wrapped.replace(" " + attr + ">", " " + attr + '=\"true\">')
        wrapped = re.sub(r'wx:else(?=\s|>)', 'wx:else=""', wrapped)
        tree = ET.fromstring(wrapped)
        for node in tree.iter():
            assert node.tag == "root" or node.tag in allowed, f"Unsupported native tag {node.tag} in {file}"
    if args.compiler_dir:
        compiler = Path(args.compiler_dir)
        with tempfile.TemporaryDirectory(prefix="qingye-compile-") as out:
            for suffix, exe in ((".wxml", "wcc.exe"), (".wxss", "wcsc.exe")):
                files = [str(p.relative_to(CLIENT)).replace("\\", "/") for p in CLIENT.rglob("*" + suffix)]
                result = subprocess.run([str(compiler / exe), "-o", str(Path(out) / (exe + ".js")), *files], cwd=CLIENT, capture_output=True)
                if result.returncode:
                    raise RuntimeError(result.stderr.decode("utf-8", errors="replace"))
                assert (Path(out) / (exe + ".js")).stat().st_size > 0
    print(f"Native mini-program checks passed: {len(app['pages'])} pages")

if __name__ == "__main__":
    main()
