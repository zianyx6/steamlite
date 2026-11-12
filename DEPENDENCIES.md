# 技术调查与第三方依赖

## 实际源码检查

2026-11-08 读取上游源码，未复制 Monica / SteamKit / node-steam-user 实现到产品。

| 项目 | 源码证据与维护快照 | 许可证 / 结论 |
| --- | --- | --- |
| [Monica](https://github.com/Monica-Pass/Monica) | `Monica for Android/.../steam/service/SteamLoginImportService.kt` 使用 RSA 公钥、BeginAuthSessionViaCredentials、Guard 更新与 Poll；`SteamApiClient.kt` 提供 protobuf HTTP 与 community 域限制；`SteamSessionRefreshService.kt` 检测令牌过期并刷新；`SteamAuthenticatorService.kt` 是移除验证器而非聊天。完整 clone 下载慢，改为读取 GitHub 原始文件。 | GPL-3.0，认证状态处理、域限制、Guard UI 思路可参考；直接复用须履行 GPL。未发现足以证明具备 CM 私聊的证据，不以此作为聊天库。本工程没有复制其代码。近期发布活跃，未进行安全审计。 |
| [SteamKit](https://github.com/SteamRE/SteamKit) | 本地 shallow clone a6f60da（2026-11-06）；认证样例、SteamAuthentication 与 SteamFriends 源码覆盖 CM、好友、消息。 | LGPL-2.1；当前需要 .NET 10，Android 需引入跨语言运行时，增加体积与维护成本，未纳入 APK。协议仅参考。 |
| [node-steam-user](https://github.com/DoctorMcKay/node-steam-user) | shallow clone 02818bd（2025-12-04）；`components/chat.js` 有 chatMessage、getChatHistory 和消息事件。 | MIT；适合协议参考与显式授权后的测试工具，APK 不嵌入 Node.js。抓取快照不等于持续维护承诺。 |
| [JavaSteam](https://github.com/Longi94/JavaSteam) | 主分支 fcd0dd9（2026-10-28）；选用 Maven 1.8.0，另下载发布版 sources.jar，按该版实际 API 实现认证、SteamFriends 和回调。 | MIT；JVM 原生适合 Kotlin，避免额外语言运行时；依赖较多，Android 兼容与占用必须实测，未声称已验证。 |

## 直接依赖

- Android Gradle Plugin 8.7.3 / Gradle 8.9 / AndroidX Compose、Material3、Activity、Lifecycle、Room：Apache-2.0。
- Kotlin 2.2.21 / kotlinx.coroutines：Apache-2.0。
- JavaSteam 1.8.0：MIT。传递依赖包括 Ktor、OkHttp、protobuf、commons-lang3（Apache-2.0）和 Kotlin。须随发布保留 notices。
- OkHttp（声明 4.12.0，JavaSteam 传递版本可能使最终解析升级）：Apache-2.0。
- Coil 2.7.0：Apache-2.0。
- Miuix UI 0.9.4：Apache-2.0，用于 Compose 主题和基础组件。
- Backdrop 2.0.1：Apache-2.0，用于悬浮导航栏的实时背景折射。
- 平台 Android Keystore、JSONObject：Android 平台组件。

本清单不是已完成的全量传递许可证审计。正式发布前保存 Gradle resolved dependency report，附带所有适用 LICENSE / NOTICE，检查漏洞、版本和 Android 适配。当前没有抓取 SteamDB 价格数据，只提供浏览器链接；没有接入第三方账号代理。

最终 Room 为 2.8.4（修复 Kotlin 2.2 元数据兼容问题）；protobuf-java 4.31.1 显式声明。发布版 JavaSteam 的官方备用服务器定义仅用于目录超时回退，没有禁用 TLS。


Bouncy Castle bcprov-jdk18on 1.86 是 JavaSteam 必需的额外加密依赖，现已显式引入。许可证原文从实际依赖 JAR 的 META-INF/LICENSE.md 提取到 third-party/BouncyCastle-LICENSE.md。仅在应用进程替换名为 BC 的精简提供器；未移除系统 Conscrypt 或 Android Keystore。MuMu 验证 JavaSteam RSA 使用正常。APK 体积增加见 TEST_RESULTS.md。

## 愿望单与 StoreBrowse 协议

客户端最小 protobuf 模型位于 app/src/main/proto/wishlist.proto，字段编号参考 SteamTracking/Protobufs 的 webui/service_wishlist.proto 和 steam/steammessages_storebrowse.steamclient.proto；使用 protoc 4.31.1 生成 WishProtocol.java 并提交，构建不需要下载 protoc。再生成命令：protoc --java_out=app/src/main/java -I app/src/main/proto app/src/main/proto/wishlist.proto。请求通过既有 CM 会话发送到 Wishlist / StoreBrowse unified service，没有第三方认证代理。协议兼容性需随 Steam 变更维护。

测试依赖新增 JUnit 4.13.2（EPL-1.0），仅 testImplementation，不进入 APK。


基础通知使用 Android 平台 NotificationManager / NotificationChannel / PendingIntent 与既有 Activity Result API，无新增运行时第三方依赖。权限参考 https://developer.android.com/develop/ui/views/notifications/notification-permission 。


新增 Jsoup 1.24.1（MIT），只解析 Steam 返回的公开搜索 HTML，不执行网页脚本；许可证从实际 JAR 提取为 third-party/Jsoup-LICENSE。公开搜索页面结构来源于 Steam 自身 search/results 接口；robots.txt 检查时未禁止 search 路径，这不等同于对未来接口稳定性或使用条款的承诺。



0.5.0 补充：Coil GIF 2.7.0（io.coil-kt:coil-gif，Apache-2.0），用于 GIF/动态 WebP 表情和图片解码。
# 动态贴纸解码

- APNG4Android `apng:3.0.5`（Apache-2.0）：解码 Steam APNG 贴纸，仅通过 Coil 接口集成。
- AndroidX `vectordrawable-animated:1.2.0`（Apache-2.0）：提供 Animatable2Compat 动画生命周期接口。

