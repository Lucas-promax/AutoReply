"""Public surface of the core package."""

from .messages import Message, normalize_messages
from .replies import (
    ReplyError,
    ReplyOptions,
    Settings,
    build_prompt,
    load_settings,
    recommend_replies,
)

__all__ = [
    "Message",
    "ReplyError",
    "ReplyOptions",
    "Settings",
    "build_prompt",
    "load_settings",
    "normalize_messages",
    "recommend_replies",
]
