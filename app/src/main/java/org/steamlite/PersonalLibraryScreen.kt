package org.steamlite

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

@Composable
fun MeScreen(model: LiteModel, onOpenStatus: () -> Unit) {
    val online by model.steam.online.collectAsState()
    val hasSession by model.steam.hasSession.collectAsState()
    val owner by model.steam.sessionOwner.collectAsState()
    val avatar by model.steam.selfAvatar.collectAsState()
    val stats by model.community.library.collectAsState()
    val libraryStatus by model.community.libraryStatus.collectAsState()
    var query by rememberSaveable(owner) { mutableStateOf("") }
    var settings by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val games = remember(stats?.games, query) { OwnedLibrary.search(stats?.games.orEmpty(), query) }
    val name = model.steam.personaName().ifBlank { "我的 Steam" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp + LocalFloatingNavigationPadding.current)) {
        item {
            PageHeader("我的", leading = { IconSquare(Glyph.Settings, { settings = true }, label = "打开设置") }) {
                val dark = LocalSteamDark.current
                IconSquare(if (dark) Glyph.Sun else Glyph.Moon, LocalSteamThemeToggle.current,
                    label = if (dark) "切换到浅色模式" else "切换到深色模式")
            }
        }
        if (hasSession || online) {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    PersonAvatar(name, avatar.ifBlank { null }, presence = null, size = 54.dp)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (owner.isNotBlank()) Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Steam ID", owner))
                        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(owner, fontSize = 11.5.sp, color = Text3)
                            Spacer(Modifier.width(6.dp)); SteamIcon(Glyph.Copy, 12.dp, Text3)
                        }
                    }
                }
                StatsCard(count = stats?.count?.toString() ?: "—",
                    countSub = stats?.let { "已获取 ${it.priced} 款报价" } ?: if (online) "正在同步" else "等待恢复连接",
                    hours = stats?.let { String.format(Locale.US, "%,.0f", it.games.sumOf { game -> game.minutes } / 60.0) } ?: "—",
                    hoursSub = stats?.let { String.format(Locale.US, "%.1f 小时 / 2 周", it.games.sumOf { game -> game.recent } / 60.0) },
                    value = stats?.takeIf { it.priced > 0 }?.let { String.format(Locale.US, "\$%,.2f", it.cents / 100.0) } ?: "—",
                    valueSub = "PK 商店现价合计",
                    recent = stats?.let { String.format(Locale.US, "%.1f", it.games.sumOf { game -> game.recent } / 60.0) } ?: "—",
                    recentSub = "近两周游玩")
            }
            item {
                SectionHeader("我的游戏库") { Text("${games.size} / ${stats?.games?.size ?: 0}", fontSize = 11.sp, color = Text3) }
                SearchField(query, { query = it }, "搜索已拥有游戏名称或 AppID", Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                if (libraryStatus.isNotBlank()) MetaLine(libraryStatus, Glyph.Info)
                if (model.detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = Surface3)
                if (model.error.startsWith("详情失败")) StatusBanner(model.error, Tone.Warn)
            }
            items(games, key = { it.id }, contentType = { "owned-game" }) { game ->
                GameRow(game.id, "", game.name.ifBlank { "未命名游戏" },
                    if (game.recent > 0) String.format(Locale.US, "近两周 %.1f 小时", game.recent / 60.0) else "已拥有 · AppID ${game.id}",
                    if (game.minutes == 0L) "尚未游玩" else String.format(Locale.US, "%.1f 小时", game.minutes / 60.0),
                    0, "", { model.details(game.id) }, trailing = { SteamIcon(Glyph.ChevronRight, 16.dp, Text3) })
            }
            if (stats != null && games.isEmpty()) item {
                EmptyState(Glyph.Search, if (query.isBlank()) "游戏库暂无游戏" else "没有匹配的已拥有游戏",
                    if (query.isBlank()) "Steam 返回的游戏会显示在这里。" else "换个游戏名或 AppID 试试。")
            }
        } else item { LoginCard(model) }
    }
    if (settings) Dialog(onDismissRequest = { settings = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
            AccountSettingsScreen(model, { settings = false; onOpenStatus() }, { settings = false })
        }
    }
}
