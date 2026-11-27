"""Start the launcher from the extracted release ZIP and exercise its public endpoints."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.parse import urlencode
from urllib.request import urlopen
from zipfile import ZipFile


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def smoke(archive, shell):
    with archive.open("rb") as data:
        checksum = hashlib.file_digest(data, "sha256").hexdigest()
    check(checksum in archive.with_name("RELEASE.md").read_text(encoding="utf-8"), "ZIP checksum mismatch")
    with tempfile.TemporaryDirectory(prefix="BookMind smoke ") as tmp:
        home = Path(tmp) / "BookMind"
        with ZipFile(archive) as bundle:
            bundle.extractall(tmp)
            mode = bundle.getinfo("BookMind/bin/bookmind.sh").external_attr >> 16
            check(mode & 0o111, "Missing executable mode for bin/bookmind.sh")
            (home / "bin/bookmind.sh").chmod(mode & 0o777)
        env = dict(os.environ, BOOKMIND_PORT="0", BOOKMIND_LLM_API_KEY="")
        for name in ("BOOKMIND_LIBRARY_DIR", "BOOKMIND_PLUGINS_DIR", "BOOKMIND_DATA_DIR"):
            env.pop(name, None)
        if os.name == "nt" and not shell:
            env["BOOKMIND_LAUNCHER"] = str(home / "bookmind.bat")
            command = 'cmd.exe /d /s /c ""%BOOKMIND_LAUNCHER%""'
        else:
            command = ([shell] if shell else []) + [(home / "bin/bookmind.sh").as_posix()]
        log = Path(tmp) / "startup.log"
        with log.open("wb") as output:
            process = subprocess.Popen(command, cwd=tmp, env=env, stdin=subprocess.DEVNULL,
                                       stdout=output, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline and process.poll() is None:
                    match = re.search(rb"Tomcat started on port (\d+)", log.read_bytes())
                    if match:
                        break
                    time.sleep(0.25)
                else:
                    raise RuntimeError("Host did not start")
                verify_endpoints(int(match[1]))
            except Exception:
                print(log.read_bytes().decode("utf-8", errors="replace"))
                raise
            finally:
                if process.poll() is None:
                    if os.name == "nt":
                        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], check=True)
                    else:
                        process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)
    print("PASS: launcher, frontend assets, health, sample books, PDF plugin, search and reader")


def verify_endpoints(port):
    def get(path):
        with urlopen(f"http://127.0.0.1:{port}{path}", timeout=10) as response:
            return response.read()

    html = get("/").decode()
    assets = re.findall(r'(?:src|href)="(/assets/[^"]+)"', html)
    check('id="root"' in html and any(asset.endswith(".js") for asset in assets), "Frontend missing")
    for asset in assets:
        check(get(asset), f"Empty frontend asset: {asset}")
    check(json.loads(get("/api/health"))["status"] == "UP", "Unhealthy host")
    plugins = json.loads(get("/api/plugins"))
    check(any(p["id"] == "pdf-pdfbox" and p["status"] == "ACTIVE" for p in plugins), "PDF plugin inactive")
    books = json.loads(get("/api/books"))
    check({book["format"] for book in books} == {"txt", "pdf"}, "Expected packaged TXT and PDF samples")
    pdf_id = next(book["id"] for book in books if book["format"] == "pdf")
    hits = json.loads(get("/api/rag/search?" + urlencode({"q": "蓝鹭", "bookId": pdf_id})))
    hit = next((item for item in hits if item["bookId"] == pdf_id and item["locator"] == "page:2"), None)
    check(hit, "Missing PDF search hit on page 2")
    chapter = json.loads(get(f"/api/books/{pdf_id}/chapters/{hit['chapterIndex']}"))
    check("蓝鹭校验词" in chapter["content"], "Missing PDF chapter text")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("zip", type=Path)
    parser.add_argument("--shell", help="POSIX shell for checking bin/bookmind.sh on Windows")
    args = parser.parse_args()
    smoke(args.zip.resolve(), args.shell)
