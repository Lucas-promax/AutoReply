import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Lock, Thread

from capture.chat_text.client import capture_wechat
from core import ReplyOptions, build_prompt, load_settings, normalize_messages, recommend_replies
from core.model_profiles import SETTINGS_PATH, public_profiles, read_config, read_profiles, save_profile, write_config
from core.replies import build_background_prompt, summarize_background

DIRECTORY = Path(__file__).resolve().parent
LEGACY_PREFERENCES = DIRECTORY / "preferences.json"
ASSETS = {
    "/": ("index.html", "text/html"),
    "/app.js": ("app.js", "text/javascript"),
    "/style.css": ("style.css", "text/css"),
}


def text_option(data, key):
    value = data.get(key, "")
    if not isinstance(value, str):
        raise ValueError(f"{key} 必须是文字。")
    return value


def preferences(data):
    if not isinstance(data, dict):
        raise ValueError("回复设置必须是 JSON 对象。")
    enabled = data.get("background_enabled", False)
    if not isinstance(enabled, bool):
        raise ValueError("对话背景设置无效。")
    options = ReplyOptions(
        count=data.get("count", 3),
        personal_style=text_option(data, "personal_style"),
        reply_style=text_option(data, "reply_style"),
    )
    return {"count": options.count, "personal_style": options.personal_style,
            "reply_style": options.reply_style, "background_enabled": enabled}


def load_preferences():
    return preferences(read_config().get("reply_preferences", {}))


def save_preferences(data):
    values = preferences(data)
    config = read_config()
    config["reply_preferences"] = values
    write_config(config)
    return values


def migrate_preferences():
    config = read_config() if SETTINGS_PATH.exists() else {}
    if "reply_preferences" in config:
        return
    previous = json.loads(LEGACY_PREFERENCES.read_text(encoding="utf-8-sig")) if LEGACY_PREFERENCES.exists() else {}
    config["reply_preferences"] = preferences(previous)
    write_config(config)


def generate(data):
    history = data.get("previous_replies", [])
    if not isinstance(history, list) or not all(isinstance(reply, str) for reply in history):
        raise ValueError("上一轮候选格式有误。")
    saved = load_preferences()
    background_enabled = saved.pop("background_enabled")
    saved.update(idea=text_option(data, "idea"), correction=text_option(data, "correction"),
                 previous_replies=tuple(history),
                 conversation_background=text_option(data, "background") if background_enabled else "")
    options = ReplyOptions(**saved)
    messages = normalize_messages(data.get("snapshot"))
    prompt = build_prompt(messages, options=options)
    request = {"messages": prompt}
    try:
        settings = load_settings()
        request["model"] = settings.model
        replies = recommend_replies(messages, settings=settings, options=options)
    except (ValueError, RuntimeError, OSError) as error:
        return {"request": request, "error": str(error)}
    return {"request": request, "replies": replies}


def generate_background(data):
    if not load_preferences()["background_enabled"]:
        raise ValueError("请先在设置中开启对话背景。")
    messages = normalize_messages(data.get("snapshot"))
    request = {"messages": build_background_prompt(messages)}
    try:
        settings = load_settings()
        request["model"] = settings.model
        background = summarize_background(request["messages"], settings=settings)
    except (ValueError, RuntimeError, OSError) as error:
        return {"request": request, "error": str(error)}
    return {"request": request, "background": background}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def send_bytes(self, status, body, content_type):
        self.send_response(status)
        self.send_header("Content-Type", content_type + "; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'")
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, data, status=200):
        self.send_bytes(status, json.dumps(data, ensure_ascii=False).encode("utf-8"), "application/json")

    def valid_host(self):
        return self.headers.get("Host") == f"127.0.0.1:{self.server.server_port}"

    def do_GET(self):
        if not self.valid_host():
            self.send_json({"error": "请从本地启动入口打开 AutoReply。"}, 403)
        elif self.path in ASSETS:
            name, mime = ASSETS[self.path]
            self.send_bytes(200, (DIRECTORY / name).read_bytes(), mime)
        elif self.path == "/api/preferences":
            try:
                self.send_json(load_preferences())
            except (ValueError, OSError) as error:
                self.send_json({"error": str(error)}, 400)
        elif self.path == "/api/profiles":
            try:
                self.send_json(public_profiles(read_profiles()))
            except (ValueError, OSError) as error:
                self.send_json({"error": str(error)}, 400)
        else:
            self.send_json({"error": "页面不存在。"}, 404)

    def do_POST(self):
        origin = f"http://127.0.0.1:{self.server.server_port}"
        if not self.valid_host() or self.headers.get("Origin") != origin:
            self.send_json({"error": "请求来源无效。"}, 403)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 8_000_000 or self.headers.get_content_type() != "application/json":
                raise ValueError("请求正文格式无效或过大。")
            data = json.loads(self.rfile.read(length))
            if not isinstance(data, dict):
                raise ValueError("请求正文必须是 JSON 对象。")
            if self.path == "/api/capture":
                snapshot = json.loads(capture_wechat())
                records = [message.to_record() for message in normalize_messages(snapshot)]
                self.send_json({"snapshot": snapshot, "records": records})
            elif self.path == "/api/window/topmost":
                enabled = data.get("enabled")
                if not isinstance(enabled, bool):
                    raise ValueError("置顶状态无效。")
                self.server.window.on_top = enabled
                self.send_json({"enabled": self.server.window.on_top})
            elif self.path == "/api/generate":
                result = generate(data)
                self.send_json(result, 502 if "error" in result else 200)
            elif self.path == "/api/background":
                result = generate_background(data)
                self.send_json(result, 502 if "error" in result else 200)
            elif self.path == "/api/profiles":
                with self.server.settings_lock:
                    result = save_profile(data)
                self.send_json(result)
            elif self.path == "/api/preferences":
                with self.server.settings_lock:
                    values = save_preferences(data)
                self.send_json(values)
            else:
                self.send_json({"error": "接口不存在。"}, 404)
        except (ValueError, RuntimeError, OSError, TimeoutError) as error:
            self.send_json({"error": str(error)}, 400)


def main():
    try:
        import webview
    except ImportError as error:
        raise SystemExit("Install the desktop dependency: .venv\\Scripts\\python.exe -m pip install -r requirements.txt") from error
    migrate_preferences()
    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        server.settings_lock = Lock()
        url = f"http://127.0.0.1:{server.server_port}/"
        server.window = webview.create_window(
            "AutoReply", url, width=510, height=740, min_size=(390, 420),
            on_top=True, text_select=True, background_color="#edf3ef",
        )

        worker = Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            webview.start(gui="edgechromium")
        finally:
            server.shutdown()
            worker.join()


if __name__ == "__main__":
    main()
