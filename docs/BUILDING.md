# 构建与验证

## 环境

- JDK 21
- Android SDK Platform 37（`platforms;android-37.0`）、Build Tools 36.0.0
- Gradle 9.9.1（使用仓库 Wrapper）、Android Gradle Plugin 9.5.1
- Kotlin / Compose Compiler 2.4.21、KSP 2.3.13
- 最低 Android API 26；目标 API 34

```sh
./gradlew testDebugUnitTest assembleDebug
./gradlew assembleRelease
```

未配置签名时，Release 构建为未签名产物，不能直接安装；直接安装请使用 GitHub Release 附件中的已签名 APK。Debug 构建使用本机调试证书。

## GitHub 自动构建

推送到 `main` 或提交 Pull Request 会运行 JVM 测试、Debug Lint 和 Debug APK 构建。也可进入 [Actions → Android build](https://github.com/zianyx6/steamlite/actions/workflows/android.yml)，点击 **Run workflow** 手动运行。

运行页面的 **Artifacts** 提供测试/Lint 报告和 `SteamLite-debug-运行编号`。下载后解压 ZIP，安装其中的 `app-debug.apk`；产物保留 14 天，下载需要登录 GitHub。

CI 的 Debug APK 用于测试，签名与正式安装包不同，也不保证不同构建间一致；不能直接覆盖正式版。日常使用请下载 [最新正式版](https://github.com/zianyx6/steamlite/releases/latest)。CI 不持有发布私钥，不会自动替换已发布的安装包。

## 发布签名

发布者通过环境变量提供签名资料，不将密钥提交到仓库：

- `STEAMLITE_KEYSTORE`：签名文件路径
- `STEAMLITE_KEYSTORE_PASSWORD`：签名库密码
- `STEAMLITE_KEY_ALIAS`：别名（默认 androiddebugkey）
- `STEAMLITE_KEY_PASSWORD`：私钥密码

1.0 沿用此前测试安装版证书，保证同包名覆盖升级。后续发布必须使用同一私钥；请自行构建者勿用不同证书覆盖官方附件，避免签名不一致。

## 设备验证

JVM 测试包含图片解析、价格限流、消息标识、未读合并、好友菜单互斥及认证方式选择等回归。

设备测试入口：

```sh
./gradlew assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e artworkOnly true org.steamlite.test/org.steamlite.ImageNetworkCheck
```

公开图片检查使用 Steam 公共表情与贴纸，不发送聊天消息。消息、图片上传和语音互通测试应由测试者在已同意测试的会话中手动执行。
