"""Package the verified Maven artifacts. Run after copying frontend/dist into server static resources."""
import argparse
import hashlib
from pathlib import Path
import subprocess
from xml.etree import ElementTree
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


ROOT = Path(__file__).resolve().parents[2]


def package(output):
    version = ElementTree.parse(ROOT / "pom.xml").getroot().findtext("{*}version")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    tags = subprocess.check_output(["git", "tag", "--points-at", commit], cwd=ROOT, text=True).splitlines()
    host = ROOT / f"book-server/target/book-server-{version}.jar"
    plugin = ROOT / f"book-plugin-pdf/target/book-plugin-pdf-{version}.jar"
    with ZipFile(host) as jar:
        jar.getinfo("BOOT-INF/classes/static/index.html")
    files = {
        "bookmind.jar": host,
        "plugins/book-plugin-pdf.jar": plugin,
        **{name: ROOT / name for name in (
            "LICENSE", "NOTICE", "bin/bookmind.sh", "bookmind.bat",
            "samples/books/sample-oss-guide.txt", "samples/books/sample-rag-handbook.pdf",
        )},
    }
    output.mkdir(parents=True, exist_ok=True)
    archive = output / "BookMind.zip"
    with ZipFile(archive, "w", compression=ZIP_DEFLATED) as bundle:
        for name, source in files.items():
            if name.endswith((".sh", ".bat")):
                newline = "\r\n" if name.endswith(".bat") else "\n"
                entry = ZipInfo("BookMind/" + name)
                entry.create_system = 3
                entry.external_attr = (0o100755 if name.endswith(".sh") else 0o100644) << 16
                bundle.writestr(entry, source.read_text(encoding="utf-8").replace("\n", newline))
            else:
                bundle.write(source, "BookMind/" + name)
    with archive.open("rb") as data:
        checksum = hashlib.file_digest(data, "sha256").hexdigest()
    record = f"""# BookMind 发行记录

- 项目版本 `{version}`
- Git 提交 `{commit}`
- Git 标签 {', '.join(tags) or '无（预览构建）'}
- BookMind.zip SHA-256 `{checksum}`

安装 Java 21，解压整个 ZIP。Windows 双击 `BookMind/bookmind.bat`，
Linux 或 macOS 在解压目录运行 `sh BookMind/bin/bookmind.sh`。
待服务启动后，用浏览器打开 http://localhost:8080，按 Ctrl+C 停止。
默认端口被占用时，可在启动前设置环境变量 `BOOKMIND_PORT`。

随包提供 TXT 和 PDF 两份样书。阅读和检索可离线使用，AI 问答另需配置模型服务。
"""
    (output / "RELEASE.md").write_text(record, encoding="utf-8")
    print(f"{archive}  SHA-256 {checksum}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path, help="发行 ZIP 与校验记录的输出目录")
    package(parser.parse_args().output)
