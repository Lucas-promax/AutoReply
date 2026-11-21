"""Build the Android APK on Windows using tools stored in this checkout."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from urllib.request import urlopen
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent.parent
ANDROID = ROOT / "android"
BUILD = ROOT / ".android-build"
SDK = BUILD / "sdk"
JDK = BUILD / "jdk"
DIST = ANDROID / "dist"
PLATFORM = SDK / "platforms" / "android-35" / "android.jar"
TOOLS = SDK / "build-tools" / "android-15"
PACKAGES = {
    "platform-35_r02.zip": (
        "0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0",
        SDK / "platforms",
        PLATFORM,
    ),
    "build-tools_r35_windows.zip": (
        "5753c679a1b90bcf6fbc9945a2ce39dfb9e74f1df0831a1e1866ae5b594326f0",
        SDK / "build-tools",
        TOOLS / "aapt2.exe",
    ),
}
API_MIN = 26


def local_jdk(source):
    javac = JDK / "bin" / "javac.exe"
    if javac.is_file():
        return
    if source is None:
        found = shutil.which("javac.exe")
        if not found:
            raise SystemExit("Specify an existing JDK 17 or newer with --jdk PATH.")
        source = Path(found).resolve().parent.parent
    source = source.resolve()
    if not (source / "bin" / "javac.exe").is_file():
        raise SystemExit(f"JDK not found at {source}")
    print("Copying JDK into .android-build/jdk", flush=True)
    shutil.copytree(source, JDK, dirs_exist_ok=True)


def sdk_packages():
    downloads = BUILD / "downloads"
    downloads.mkdir(parents=True, exist_ok=True)
    for filename, (expected, destination, marker) in PACKAGES.items():
        if marker.is_file():
            continue
        archive = downloads / filename
        if not archive.is_file():
            partial = archive.with_suffix(".download")
            print(f"Downloading {filename}", flush=True)
            try:
                with urlopen(f"https://dl.google.com/android/repository/{filename}", timeout=60) as reply:
                    with partial.open("wb") as stream:
                        shutil.copyfileobj(reply, stream)
                verify_archive(partial, expected)
                partial.replace(archive)
            finally:
                partial.unlink(missing_ok=True)
        else:
            verify_archive(archive, expected)
        with ZipFile(archive) as package:
            package.extractall(destination)


def verify_archive(path, expected):
    with path.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != expected:
            raise SystemExit(f"SDK checksum mismatch: {path}")


def prepare_assets(destination):
    web = destination / "web"
    web.mkdir(parents=True)
    index = (ROOT / "gui" / "index.html").read_text(encoding="utf-8")
    mobile = ANDROID / "web"
    index = index.replace(
        '  <script type="module" src="/app.js"></script>',
        '  <link rel="stylesheet" href="/android.css">\n'
        '  <script src="/android.js" defer></script>\n'
        '  <script type="module" src="/app.js"></script>',
    )
    index = index.replace(
        '      <section id="background-panel"',
        (mobile / "actions.html").read_text(encoding="utf-8-sig") + '      <section id="background-panel"',
    )
    index = index.replace('<a class="brand" href="/">AutoReply</a>', '<span class="brand">AutoReply</span>')
    index = index.replace("</body>", (mobile / "import.html").read_text(encoding="utf-8-sig") + "</body>")
    (web / "index.html").write_text(index, encoding="utf-8")
    for filename in ("style.css", "app.js"):
        shutil.copyfile(ROOT / "gui" / filename, web / filename)
    for filename in ("android.css", "android.js"):
        shutil.copyfile(mobile / filename, web / filename)

    sys.dont_write_bytecode = True
    sys.path.insert(0, str(ROOT))
    from core.messages import Message
    from core.replies import build_background_prompt, build_prompt

    example = [Message("message", "example", "未知")]
    prompts = {
        "reply": build_prompt(example)[0]["content"],
        "background": build_background_prompt(example)[0]["content"],
    }
    (destination / "prompts.json").write_text(json.dumps(prompts, ensure_ascii=False), encoding="utf-8")


def build_apk():
    DIST.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="apk-", dir=BUILD) as directory:
        work = Path(directory)
        temp = work / "tmp"
        temp.mkdir()
        tool_env = {
            **os.environ,
            "JAVA_HOME": str(JDK),
            "ANDROID_HOME": str(SDK),
            "ANDROID_SDK_ROOT": str(SDK),
            "ANDROID_USER_HOME": str(BUILD / "android-user"),
            "TEMP": str(temp),
            "TMP": str(temp),
            "TMPDIR": str(temp),
        }
        java_home = f"-Duser.home={BUILD}"
        java_tmp = f"-Djava.io.tmpdir={temp}"

        def run(*args):
            subprocess.run([str(item) for item in args], cwd=work, env=tool_env, check=True)

        java = JDK / "bin" / "java.exe"
        apksigner = (java, java_home, java_tmp, "-jar", TOOLS / "lib" / "apksigner.jar")

        assets = work / "assets"
        resources = work / "resources.zip"
        linked = work / "linked.apk"
        unsigned = work / "unsigned.apk"
        aligned = work / "aligned.apk"
        signed = work / "AutoReply-android.apk"
        classes = work / "classes"
        dex = work / "dex"
        classes.mkdir()
        dex.mkdir()
        prepare_assets(assets)
        aapt = TOOLS / "aapt2.exe"
        run(aapt, "compile", "--dir", ANDROID / "res", "-o", resources)
        run(aapt, "link", "-I", PLATFORM, "--manifest", ANDROID / "AndroidManifest.xml",
            "-A", assets, "-o", linked, resources)
        sources = sorted((ANDROID / "src").glob("*.java"))
        run(JDK / "bin" / "javac.exe", "-J" + java_home, "-J" + java_tmp, "--release", "11",
            "-encoding", "UTF-8", "-classpath", PLATFORM, "-d", classes, *sources)
        run(java, java_home, java_tmp, "-cp", TOOLS / "lib" / "d8.jar", "com.android.tools.r8.D8",
            "--release", "--min-api", API_MIN, "--lib", PLATFORM, "--output", dex,
             *sorted(classes.rglob("*.class")))
        with ZipFile(linked) as base, ZipFile(unsigned, "w") as apk:
            for entry in base.infolist():
                apk.writestr(entry.filename, base.read(entry), compress_type=entry.compress_type)
            for item in sorted(dex.glob("classes*.dex")):
                apk.write(item, item.name)
        run(TOOLS / "zipalign.exe", "-f", "4", unsigned, aligned)

        key = BUILD / "local-signing.p12"
        if not key.is_file():
            run(JDK / "bin" / "keytool.exe", "-J" + java_home, "-J" + java_tmp,
                "-genkeypair", "-keystore", key, "-storetype", "PKCS12", "-storepass", "android",
                "-alias", "autoreply", "-keyalg", "RSA", "-keysize", 2048, "-validity", 10000,
                "-dname", "CN=AutoReply Local Build")
        run(*apksigner, "sign", "--ks", key, "--ks-key-alias", "autoreply",
             "--ks-pass", "pass:android", "--v4-signing-enabled", "false", "--out", signed, aligned)
        run(*apksigner, "verify", "--verbose", signed)
        run(TOOLS / "zipalign.exe", "-c", "4", signed)
        path = DIST / signed.name
        signed.replace(path)
        print(path, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, help="Source JDK to copy into the checkout")
    args = parser.parse_args()
    if os.name != "nt":
        raise SystemExit("This build script uses the Windows Android SDK tools.")
    local_jdk(args.jdk)
    sdk_packages()
    build_apk()


if __name__ == "__main__":
    main()
