# 微信对话读取接口

`core` 可以启动 `capture\Capture-WeChat.cmd`。进程需运行在已登录微信的 Windows 桌面会话中，微信主窗口需打开目标对话。

- 成功时退出码为 0，标准输出为一行 UTF-8 JSON。顶层包含 `Source`、`CapturedAtUtc`、`ConversationTitle` 和 `Records`。标题来自微信无障碍控件，无法可靠识别时为 `null`。
- `Records` 是按微信消息列表顺序排列的数组。每项包含从 1 开始的 `Index`、原始 `Text` 和 `Direction`。`incoming` 表示对方发出，`outgoing` 表示自己发出，`unknown` 表示方向无法判定，例如时间分隔行。方向根据微信无障碍控件中头像在消息行的位置判断。群聊中的 `incoming` 只表示其他成员发出。
- 失败时退出码非 0，错误写到标准错误。请先检查退出码，再解析标准输出。成功返回空数组表示消息列表没有条目。

PowerShell 调用方也可以在执行策略允许时导入 `WeChatCapture.psm1`，然后调用 `Get-WeChatConversation` 获取同样结构的对象。

接口读取当前已加载的记录。它不操作微信、不保存聊天记录，也不发起网络请求。
