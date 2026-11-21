# AutoReply for Android

## 安装和使用

1. 把 `dist/AutoReply-android.apk` 发到 Android 8.0 或更新的手机上，打开 APK 安装。
2. 在「设置 → 模型配置」填写 OpenAI 兼容接口的 Base URL、模型 ID 和 API Key，点击「保存并启用」。
3. 在首页点击「粘贴聊天」并长按输入框粘贴，或从支持文本分享的应用选择 AutoReply。可用「我：」和「对方：」标记发言人。核对后点击「确认导入」。
4. 点击「生成回复」，再把喜欢的候选复制到微信。

手机会把确认导入的聊天发送给所选模型，用来生成候选或概括对话背景。模型配置保存在手机的应用私有存储中。

## 读取微信聊天

在 AutoReply 点击「启用微信读取」，开启系统无障碍设置里的「AutoReply 微信读取」。打开要回复的微信聊天，在屏幕上点击「读取聊天」，回到 AutoReply 核对文字和发言人后确认导入。需要最新消息时，在微信重新点击「读取聊天」。

读取范围取决于当前微信页面向 Android 无障碍服务提供的可见文字。读不到时，AutoReply 会打开导入窗口并显示原因，可以在该窗口粘贴已复制的聊天。Android 13 及以上若提示无障碍设置受限，可在系统的 AutoReply 应用信息页面选择「允许受限设置」。[Android 帮助](https://support.google.com/android/answer/12623953?hl=zh-Hans)

## 在 Windows 上构建

在仓库根目录使用 Python 3.11 或更高版本运行：

```powershell
python -B android/build.py
```

首次构建会将检测到的 JDK 复制到 `.android-build/jdk`。也可以用 `--jdk "D:\path\to\jdk-17"` 指定 JDK 17 或更新版本的来源目录。脚本会下载并校验 Android SDK 35 和 Build Tools，SDK、下载缓存、临时文件、本地签名都在项目内的 `.android-build/`。

构建结束后，已签名的安装包位于 `android/dist/AutoReply-android.apk`。保留 `.android-build/local-signing.p12` 可用同一签名构建覆盖安装的更新包。
