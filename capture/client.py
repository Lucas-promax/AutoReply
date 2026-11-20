import locale
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def capture_wechat() -> str:
    """Return the capture command's UTF-8 JSON snapshot."""
    try:
        result = subprocess.run(
            ["cmd.exe", "/d", "/c", r"capture\Capture-WeChat.cmd"],
            cwd=ROOT,
            capture_output=True,
            creationflags=subprocess.CREATE_NO_WINDOW,
            timeout=60,
        )
    except subprocess.TimeoutExpired as error:
        raise RuntimeError("读取微信会话超时。") from error
    if result.returncode:
        error = result.stderr.decode(locale.getencoding(), errors="replace").strip()
        raise RuntimeError(f"微信采集失败（退出码 {result.returncode}）。\n{error}")
    return result.stdout.decode("utf-8").rstrip("\r\n")
