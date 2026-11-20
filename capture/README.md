# 微信对话读取接口

`core` 可以启动 `capture\chat_text\Capture-WeChat.cmd`。进程需运行在已登录微信的 Windows 桌面会话中，微信主窗口需打开目标对话。

- 成功时退出码为 0，标准输出为一行 UTF-8 JSON。顶层包含 `Source`、`CapturedAtUtc`、`ConversationTitle` 和 `Records`。标题来自微信无障碍控件，无法可靠识别时为 `null`。
- `Records` 是按微信消息列表顺序排列的数组。每项包含从 1 开始的 `Index`、原始 `Text` 和 `Direction`。`incoming` 表示对方发出，`outgoing` 表示自己发出，`unknown` 表示方向无法判定，例如时间分隔行。方向根据微信无障碍控件中头像在消息行的位置判断。群聊中的 `incoming` 只表示其他成员发出。
- 失败时退出码非 0，错误写到标准错误。请先检查退出码，再解析标准输出。成功返回空数组表示消息列表没有条目。

文本采集实现位于 `chat_text/`。PowerShell 调用方可以在执行策略允许时导入该目录的 `WeChatCapture.psm1`，然后调用 `Get-WeChatConversation` 获取同样结构的对象。

接口读取当前已加载的记录。它不操作微信、不保存聊天记录，也不发起网络请求。

## 导出图片

图片采集实现位于 `chat_images/`。在微信中打开会话，让目标图片完整显示在屏幕上，然后执行：

```powershell
.\capture\chat_images\Export-WeChatImages.cmd "F:\My Pictures\WeChat"
```

省略路径时保存到项目下的 `test/exports/images/`。命令会操作微信的图片查看器和「另存为」，并报告保存路径和像素尺寸。请让微信聊天区保持可见，等待命令完成后再切换窗口。当前只导出画面内完整可见的普通图片，最多 20 张，不会自动翻页。微信界面版本不同可能需要重新验证。保存的文件来自微信查看器，是否为发送端原始文件取决于微信。
