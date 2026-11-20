import json
from pathlib import Path
from tempfile import NamedTemporaryFile
from urllib.parse import urlsplit
from uuid import uuid4

SETTINGS_PATH = Path(__file__).with_name("settings.json")


def read_profiles(path: Path = SETTINGS_PATH) -> dict:
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    except (OSError, ValueError) as error:
        raise ValueError("无法读取 core/settings.json，请检查文件及 JSON 格式。") from error
    if not isinstance(data, dict):
        raise ValueError("settings.json 必须是 JSON 对象。")
    if "profiles" not in data:
        data["profiles"] = [{
            "id": "default",
            "alias": data.get("model") or "默认模型",
            **{key: data.get(key, "") for key in ("base_url", "api_key", "model")},
        }]
        data["active_profile"] = "default"
    if not isinstance(data["profiles"], list) or not all(
        isinstance(profile, dict) and isinstance(profile.get("id"), str)
        for profile in data["profiles"]
    ):
        raise ValueError("settings.json 中的模型配置列表无效。")
    return data


def public_profiles(config: dict) -> dict:
    profiles = []
    for profile in config["profiles"]:
        item = {key: profile.get(key, "") for key in ("id", "alias", "base_url", "model")}
        item["has_api_key"] = bool(profile.get("api_key"))
        profiles.append(item)
    return {"active": config.get("active_profile"), "profiles": profiles}


def save_profile(data: dict, path: Path = SETTINGS_PATH) -> dict:
    config = read_profiles(path)
    profile_id = data.get("id")
    current = next((item for item in config["profiles"] if item["id"] == profile_id), None)
    if profile_id is not None and current is None:
        raise ValueError("所选模型配置已不存在。")
    if data.get("activate_only") is True:
        if current is None:
            raise ValueError("请选择已有的模型配置。")
    else:
        fields = {}
        for key in ("alias", "base_url", "model", "api_key"):
            value = data.get(key, "")
            if not isinstance(value, str):
                raise ValueError("模型配置字段必须是文字。")
            fields[key] = value.strip()
        fields["alias"] = fields["alias"] or fields["model"]
        fields["api_key"] = fields["api_key"] or (current or {}).get("api_key", "")
        url = urlsplit(fields["base_url"])
        if (url.scheme not in ("http", "https") or not url.hostname or
                url.username is not None or url.fragment):
            raise ValueError("请输入有效的 HTTP 或 HTTPS Base URL。")
        if not fields["model"] or not fields["api_key"]:
            raise ValueError("请输入模型 ID 和 API Key。")
        if current is None:
            current = {"id": uuid4().hex}
            config["profiles"].append(current)
        current.update(fields)

    config["active_profile"] = current["id"]
    for key in ("base_url", "api_key", "model"):
        config.pop(key, None)
    path = Path(path)
    with NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as stream:
        pending = Path(stream.name)
        try:
            json.dump(config, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        except BaseException:
            stream.close()
            pending.unlink(missing_ok=True)
            raise
    try:
        pending.replace(path)
    finally:
        pending.unlink(missing_ok=True)
    return public_profiles(config)
