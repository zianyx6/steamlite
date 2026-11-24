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
internal fun AccountSettingsScreen(model: LiteModel, onOpenStatus: () -> Unit, onBack: () -> Unit) {
    val online by model.steam.online.collectAsState()
    val hasSession by model.steam.hasSession.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    val appVersion = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    var aboutSheet by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
            item { PageHeader("设置", leading = { IconSquare(Glyph.Back, onBack, label = "返回个人主页") }) }
            item { SettingsLabel("账号与数据") }
            item {
                SettingsGroup {
                    SettingsRow(Glyph.Refresh, "连接状态", value = if (online) "已连接" else if (hasSession) "正在恢复" else "未登录", onClick = onOpenStatus)
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
internal fun StatsCard(
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
internal fun LoginCard(model: LiteModel) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val guard by model.steam.guard.collectAsState()
    val busy by model.steam.busy.collectAsState()
    val closing by model.steam.loginClosing.collectAsState()
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
            when {
                closing -> "正在断开 Steam 连接…"
                busy -> "登录处理中…"
                else -> "登录 Steam"
            },
            { model.steam.login(username, password) },
            Modifier.fillMaxWidth(),
            enabled = !busy && username.isNotBlank() && password.isNotEmpty()
        )

        if (busy && !closing) {
            Spacer(Modifier.height(10.dp))
            Text("正在连接 Steam，可能需要几秒", fontSize = 10.5.sp, color = Text3)
            Spacer(Modifier.height(8.dp))
            GhostButton("取消登录", { model.steam.cancelLogin() }, Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(12.dp))
        GhostButton("Steam 扫码登录", model.steam::loginWithQr, Modifier.fillMaxWidth(), enabled = !busy, glyph = Glyph.Qr)
        Spacer(Modifier.height(8.dp))
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
