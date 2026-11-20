# AutoReply

AutoReply 是 Windows 微信回复助手。它读取微信当前会话，用配置的模型生成候选回复，供你手动复制。

## 环境与启动

- Windows 桌面版微信。已测试的 `WeChat.exe` **文件版本为 3.9.12.51**，**产品版本为 3.9.12.1000**。其他版本需要重新验证。
- Python 虚拟环境位于项目的 `.venv`，桌面窗口需要 Microsoft Edge WebView2 Runtime。

首次运行时，在项目根目录安装依赖：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

登录微信、打开一个对话，然后双击 `Start-AutoReply.cmd`。AutoReply 会打开独立的 WebView2 小窗。拖动标题栏可移动，右上角可切换置顶，标题栏的最小化按钮可收起窗口。

在「设置 → 模型配置」中填写 OpenAI 兼容接口的 Base URL、API Key 和模型 ID。模型配置和回复偏好保存在 `core/settings.json`，其中 API Key 以明文保存。

## 使用

- 点击「生成回复」或「再次生成」会重新读取微信已加载的消息。如果会话名称变了，AutoReply 会提示并等待再次确认。
- 点击候选回复旁的「复制」，再按需粘贴到微信。
- 「调试面板」显示采集状态、聊天记录和模型请求。「对话背景」可以在回复设置中开启。

## 项目结构

- `capture/chat_text/` 通过 Windows MSAA 读取微信会话。
- `capture/chat_images/` 提供单独的图片导出实验。图片导出说明见 [capture/README.md](capture/README.md)。
- `core/` 负责消息标准化、提示词和模型请求。
- `gui/` 提供本地界面和 WebView2 窗口。
