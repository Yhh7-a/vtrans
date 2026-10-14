# VTransOverlay - 安卓 VTuber 直播同声传译悬浮窗 App

专为安卓手机观看 **YouTube / Twitch / TwitCasting** 日语及英语 VTuber 直播打造的实时同声传译悬浮窗应用。

## 核心亮点
1. **Android 10+ 系统内录直抓 (`AudioPlaybackCapture`)**：直接捕获手机内部播放的直播音频（戴耳机看直播也能清晰识别），同时提供麦克风拾音模式备用。
2. **真全局悬浮窗 + 点击穿透锁定 (`SYSTEM_ALERT_WINDOW`)**：悬浮在 YouTube、Twitch 等任意 App 上方；开启 `🔒 穿透` 后，手指可直接透过字幕操作底下的播放器或发送弹幕。
3. **专为 VTuber 优化的双模 AI 同传引擎**：
   - **方案 A（推荐）：Gemini 2.5 Flash 原生多模态音频直译** —— 无需先转文字再机翻，模型直接听取主播语气、笑声与停顿，一步输出地道中文同传字幕。
   - **方案 B：Groq/OpenAI Whisper + DeepSeek/OpenAI 双引擎** —— `whisper-large-v3-turbo` 极速语音识别配合大模型上下文润色。
4. **内置日/英 VTuber 黑话词典与上下文记忆**：自动保留最近 4 句直播上下文，解决主播口语断句跨片段导致的翻译破碎问题；内置 `てぇてぇ -> 贴贴`、`スパチャ -> SC`、`ぽん -> 犯傻`、`助かる -> 活了`、`Pog -> 牛逼` 等高频术语，并支持自定义你推的专属名字与口癖。

## 如何获取安装 APK（两种方式任选）

### 方式一：免电脑！使用 GitHub Actions 云端一键自动打包 APK
1. 注册/登录 GitHub，新建一个仓库（Repository）。
2. 将本工程文件夹内的所有文件上传到该仓库。
3. 点击仓库顶部的 **Actions** 标签页，运行 **Build Android APK** 工作流。
4. 等待约 2~3 分钟编译完成，在页面底部的 **Artifacts** 区域即可直接下载编译好的 `VTransOverlay-APK.zip`，解压后安装 `app-debug.apk` 到安卓手机即可。

### 方式二：使用电脑 Android Studio 本地编译
1. 使用 Android Studio Hedgehog (2023.1.1) 或更新版本打开本工程目录。
2. 等待 Gradle Sync 完成。
3. 连接安卓手机或点击顶部菜单 `Build -> Build Bundle(s) / APK(s) -> Build APK(s)` 即可生成安装包。
