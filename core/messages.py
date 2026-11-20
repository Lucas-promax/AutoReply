import re
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Literal

_LOCAL_DATE_LABEL = (
    r"(?:今天|昨天|前天|(?:星期|周)[一二三四五六日天]|"
    r"(?:[0-9]{4}年)?[0-9]{1,2}月[0-9]{1,2}日)"
)
_NUMERIC_DATE_LABEL = r"[0-9]{4}(?P<separator>[-/])[0-9]{1,2}(?P=separator)[0-9]{1,2}"
_TIME_LABEL = re.compile(
    rf"(?:{_LOCAL_DATE_LABEL}\s*|{_NUMERIC_DATE_LABEL}\s+)?"
    r"(?:(?:凌晨|早上|上午|中午|下午|晚上)\s*)?"
    r"(?:[01]?[0-9]|2[0-3])[:：][0-5][0-9]"
)


@dataclass(frozen=True, slots=True)
class Message:
    kind: Literal["message", "time"]
    text: str
    sender: str

    def to_record(self) -> dict[str, str]:
        return {"Kind": self.kind, "Text": self.text, "From": self.sender}


def normalize_messages(payload: Mapping, *, source: str = "wechat") -> list[Message]:
    """Convert a source snapshot to ordered core records."""
    if source != "wechat":
        raise ValueError(f"Unsupported message source: {source}")
    if not isinstance(payload, Mapping) or not isinstance(payload.get("Records"), list):
        raise ValueError("WeChat capture must contain a Records array.")

    title = payload.get("ConversationTitle")
    peer = title.strip() if isinstance(title, str) and title.strip() else "未知"
    messages = []
    for index, row in enumerate(payload["Records"], start=1):
        if not isinstance(row, Mapping):
            raise ValueError(f"WeChat record {index} must be an object.")
        text = row.get("Text")
        if text is None:
            continue
        if not isinstance(text, str):
            raise ValueError(f"WeChat record {index} has a non-string Text.")
        trimmed = text.strip()
        if not trimmed:
            continue
        direction = row.get("Direction")
        if direction == "incoming":
            messages.append(Message("message", text, peer))
        elif direction == "outgoing":
            messages.append(Message("message", text, "我"))
        elif direction == "unknown" and _TIME_LABEL.fullmatch(trimmed):
            messages.append(Message("time", text, "System"))
        else:
            messages.append(Message("message", text, "未知"))
    return messages
