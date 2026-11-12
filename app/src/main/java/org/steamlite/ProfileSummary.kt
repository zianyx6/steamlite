package org.steamlite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun MeScreen(
    model: LiteModel,
    onOpenStatus: () -> Unit
) {
    val repo = model.community
    val stats by repo.library.collectAsState()
    val libraryStatus by repo.libraryStatus.collectAsState()
    val online by model.steam.online.collectAsState()
    val selfAvatar by model.steam.selfAvatar.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    val appVersion = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    var aboutSheet by remember { mutableStateOf(false) }
    val active = LocalRootTabActive.current
    LaunchedEffect(active, online, model.steam.owner) { if (active && online && stats == null) repo.refreshLibrary() }

    val owner = model.steam.owner
    val name = model.steam.personaName().ifBlank { "我的 Steam" }

    fun copyId() {
        if (owner.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Steam ID", owner))
        scope.launch { snackbar.showSnackbar("已复制 Steam ID") }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp + LocalFloatingNavigationPadding.current)
        ) {
            item { PageHeader("我的") }

            if (online) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(54.dp).clip(RoundedCornerShape(17.dp))
                                .background(Brush.linearGradient(listOf(Color(0x424C9BF5), Color(0x1A4C9BF5)))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                name.take(1),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentText
                            )
                            if (selfAvatar.isNotBlank()) {
                                AsyncImage(
                                    model = selfAvatar,
                                    contentDescription = name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Row(
                                Modifier.clip(RoundedCornerShape(8.dp)).clickable { copyId() }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(owner, fontSize = 11.5.sp, color = Text3, maxLines = 1)
                                Spacer(Modifier.width(6.dp))
                                SteamIcon(Glyph.Copy, 12.dp, Text3, 1.8f)
                            }
                        }
                    }
                }

                item {
                    StatsCard(
                        count = stats?.count?.toString() ?: "—",
                        countSub = when {
                            stats == null -> "尚未同步"
                            stats!!.priced > 0 -> "已获取 ${stats!!.priced} 款报价"
                            stats!!.pricing -> "正在获取报价"
                            else -> "报价暂不可用"
                        },
                        hours = stats?.let { String.format(Locale.US, "%,.0f", it.games.sumOf { g -> g.minutes } / 60.0) } ?: "—",
                        hoursSub = stats?.let { String.format(Locale.US, "%.1f 小时 / 2 周", it.games.sumOf { g -> g.recent } / 60.0) },
                        value = stats?.takeIf { it.priced > 0 }?.let { String.format(Locale.US, "\$%,.2f", it.cents / 100.0) } ?: "—",
                        valueSub = "PK 商店现价合计",
                        recent = stats?.let { String.format(Locale.US, "%.1f", it.games.sumOf { g -> g.recent } / 60.0) } ?: "—",
                        recentSub = "近两周游玩"
                    )
                }

                if (libraryStatus.isNotEmpty() && (libraryStatus.contains("失败") || libraryStatus.contains("正在"))) {
                    item {
                        StatusBanner(
                            libraryStatus,
                            if (libraryStatus.contains("失败")) Tone.Warn else Tone.Info,
                        )
                    }
                }
            } else {
                item { LoginCard(model) }
            }

            item { SettingsLabel("账号与数据") }
            item {
                SettingsGroup {
                    SettingsRow(Glyph.Refresh, "连接状态", value = if (online) "已连接" else "未连接", onClick = onOpenStatus)
                    SettingsRow(Glyph.Refresh, if (model.syncing) "同步中…" else "同步全部数据", value = null, showDivider = false,
                        onClick = if (online && !model.syncing) ({ model.syncAll() }) else null)
                }
            }
            if (model.syncStatus.isNotEmpty()) {
                item { MetaLine(model.syncStatus, Glyph.Info) }
            }

            item { SettingsLabel("消息") }
            item {
                SettingsGroup { NotificationSettings(model.notifications) }
            }

            item { SettingsLabel("通用") }
            item {
                SettingsGroup {
                    SettingsRow(Glyph.Info, "关于", value = null, showDivider = false, onClick = { aboutSheet = true })
                }
            }

            item { SettingsLabel("危险操作") }
            item {
                SettingsGroup {
                    SettingsRow(
                        Glyph.Logout,
                        "退出并清除本地会话",
                        danger = true,
                        showDivider = false,
                        onClick = {
                            model.steam.logout()
                            scope.launch { snackbar.showSnackbar("已退出并清除本地会话") }
                        }
                    )
                }
            }

            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Steam Lite", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Text2)
                        Spacer(Modifier.width(6.dp))
                        Text(appVersion, fontSize = 10.5.sp, color = Text3)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "非官方客户端 · 与 Valve 无关联",
                        fontSize = 10.5.sp,
                        color = Color(0x7A6E7A91)
                    )
                }
            }
        }
    }

    if (aboutSheet) AboutSheet { aboutSheet = false }
}

@Composable
private fun SettingsLabel(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 0.dp, start = 4.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = Text3
    )
}

@Composable
private fun StatsCard(
    count: String,
    countSub: String,
    hours: String,
    hoursSub: String?,
    value: String,
    valueSub: String,
    recent: String,
    recentSub: String
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Surface1).border(1.dp, Line, shape)
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            StatCell("游戏数量", count, null, countSub, Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(Line))
            StatCell("累计游玩", hours, "h", hoursSub, Modifier.weight(1f))
        }
        Hairline()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            StatCell("现价估算", value, null, valueSub, Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(Line))
            StatCell("近两周", recent, "h", recentSub, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LoginCard(model: LiteModel) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val guard by model.steam.guard.collectAsState()
    val busy by model.steam.busy.collectAsState()
    LaunchedEffect(guard) { if (guard.isNotEmpty()) password = "" }

    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Surface1).border(1.dp, Line, shape).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(AccentSoft), contentAlignment = Alignment.Center) { SteamIcon(Glyph.Shield, 20.dp, Accent) }
            Spacer(Modifier.width(10.dp))
            Text("登录 Steam", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Text1)
        }
        Spacer(Modifier.height(4.dp))
        Text("输入账号密码后，按提示完成 Steam Guard 验证。密码仅用于直接向 Steam 认证。", fontSize = 11.5.sp, color = Text3, lineHeight = 18.sp)
        Spacer(Modifier.height(16.dp))

        LoginField(username, { username = it }, "Steam 账号名", enabled = !busy)
        Spacer(Modifier.height(10.dp))
        LoginField(password, { password = it }, "密码", password = true, enabled = !busy)

        Spacer(Modifier.height(14.dp))
        AccentButton(
            if (busy) "登录处理中…" else "登录 Steam",
            { model.steam.login(username, password) },
            Modifier.fillMaxWidth(),
            enabled = !busy && username.isNotBlank() && password.isNotEmpty()
        )

        if (busy) {
            Spacer(Modifier.height(10.dp))
            Text("正在连接 Steam，可能需要几秒", fontSize = 10.5.sp, color = Text3)
            Spacer(Modifier.height(8.dp))
            GhostButton("取消登录", { model.steam.cancelLogin() }, Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(12.dp))
        GhostButton("检测 Steam 连接", { model.steam.probeConnection() }, Modifier.fillMaxWidth(), enabled = !busy, glyph = Glyph.Refresh)
    }
}

@Composable
private fun LoginField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    password: Boolean = false,
    number: Boolean = false,
    enabled: Boolean = true
) {
    var reveal by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(11.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(color = Text1, fontSize = 13.5.sp),
        cursorBrush = SolidColor(Accent),
        visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.NumberPassword else if (password) KeyboardType.Password else KeyboardType.Text,
            imeAction = ImeAction.Next
        ),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().height(46.dp).clip(shape).background(Surface2).border(1.dp, Line, shape)
                    .padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(label, fontSize = 13.5.sp, color = Text3)
                    inner()
                }
                if (password) Text(if (reveal) "隐藏" else "显示", fontSize = 11.sp, color = AccentText,
                    modifier = Modifier.clickable(enabled = enabled) { reveal = !reveal }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
            }
        }
    )
}
