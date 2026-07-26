# 云端打包 ScreenTranslate（不用在本机装 Android Studio）

本工程已配好 GitHub Actions，只要把代码推到 GitHub，GitHub 的免费服务器会自动编译出 `app-debug.apk`，你下载装手机即可。全程不需要本机有 Java / Android SDK / Gradle。

## 你需要准备
- 一个 **GitHub 账号**（免费，https://github.com 注册）
- **GitHub Desktop**（图形化工具，不用敲命令）：https://desktop.github.com

## 步骤

### 1. 用 GitHub Desktop 把本工程发布到 GitHub
1. 打开 GitHub Desktop → 菜单 **File → Add local repository…**
2. **Local path** 选这个文件夹（即包含 `build.gradle` / `app/` / `.github/` 的那个）
3. 点 **Add**
4. 右侧点 **Publish repository**
   - Name：`ScreenTranslate`
   - 勾选 **Keep this code private** 取消（选 Public，否则 Actions 私有仓库要付费额度）—— 公开也没关系，这只是个翻译小工具
   - 点 **Publish**

> 发布成功 = 自动向 GitHub 推了一次代码 → 立刻触发打包。

### 2. 等 GitHub 把 APK 编出来
1. 浏览器打开你的仓库：`https://github.com/<你的用户名>/ScreenTranslate`
2. 点顶部 **Actions** 标签 → 看到一条 **Build APK** 任务在跑（黄色转圈）
3. 等 **5~10 分钟** 变绿 ✅（首次要下载 Gradle + SDK + 依赖，稍久）
4. 点进这条任务 → 底部 **Artifacts** 区 → 下载 **ScreenTranslate-APK**（是个 zip）

### 3. 安装到手机
1. 解压 zip → 得到 `app-debug.apk`
2. 拷到安卓手机 → 点安装（首次装未知来源 App 需在设置里允许「允许来自此来源的安装」）
3. 打开「屏幕翻译」：
   - 推荐先点 **「下载离线翻译模型」**（首次需联网，建议挂菲律宾节点 VPN）
   - 点 **「开启无障碍点译」** → 系统设置里打开「屏幕翻译 / ScreenTranslate」→ 回 Maya 点蓝色「译」球翻译（可绕开防截屏，能翻全屏）
   - 或点 **「开启截屏翻译」** → 授权悬浮窗 + 截屏 → 出现蓝色小球，点它翻译当前屏（Maya 若防截屏会黑屏，请用上一种）

## 想改翻译走你本地 GLM（不花流量钱）
打开 `app/src/main/java/com/example/screentranslate/Translator.kt`，把：
```kotlin
private const val LOCAL_ENDPOINT: String = ""
```
改成手机能访问到的地址（同一 WiFi 下填你电脑的局域网 IP，如 `http://192.168.1.5:8080/v1/chat/completions`），重新走上面 1→3 步即可。

## 排错
- **Actions 红叉失败**：点进任务看红色日志。最常见是 `Could not determine...` 或 SDK 没装上——本配置已预装 `platforms;android-34` + `build-tools;34.0.0`，一般能过。把红色报错原文发我。
- **Artifact 下载后没有 apk**：确认 Actions 任务变绿、且 Artifacts 区有 `ScreenTranslate-APK`。
- **想重新打包**：在 GitHub Desktop 里随便改一个文件（或什么都不改点 Repository → Push）重新触发；或在 Actions 页点 **Run workflow** 手动触发。
