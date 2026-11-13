package org.steamlite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
fun FriendActivityScreen(model: LiteModel) {
    val repo = model.activity
    val items by repo.items.collectAsState()
    val busy by repo.loading.collectAsState()
    val error by repo.error.collectAsState()
    val more by repo.hasMore.collectAsState()
    val online by model.steam.online.collectAsState()
    var viewing by remember { mutableStateOf<Pair<List<String>, String>?>(null) }
    val active = LocalRootTabActive.current
    LaunchedEffect(active, online, model.steam.owner) { if (active && online && items.isEmpty()) repo.refresh() }

    RefreshContainer(busy, { repo.refresh() }) { Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PageHeader("动态") }
                    if (busy) {
                        LinearProgressIndicator(
                            Modifier.width(18.dp).height(2.dp),
                            color = Accent,
                            trackColor = Surface3
                        )
                    }
            }
            Text(
                if (items.isEmpty()) "好友的成就、购买与截图会出现在这里"
                else "${items.size} 条好友动态" + if (more) " · 还有更早的" else "",
                fontSize = 11.5.sp,
                color = Text3
            )
            Spacer(Modifier.height(6.dp))
            if (!online) StatusBanner("登录 Steam 后同步好友活动", Tone.Warn)
            if (error.isNotBlank()) StatusBanner(error, Tone.Warn)
        }

        if (items.isEmpty() && busy) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.width(120.dp).height(16.dp).background(Surface3, RoundedCornerShape(6.dp)))
                        Box(Modifier.fillMaxWidth().height(72.dp).background(Surface2, RoundedCornerShape(10.dp)))
                    }
                }
            }
        } else if (items.isEmpty()) {
            EmptyState(
                glyph = if (error.isNotBlank()) Glyph.Alert else Glyph.Bell,
                title = if (error.isNotBlank()) "动态加载失败" else if (!online) "需要先登录" else "还没有好友动态",
                body = when {
                    error.isNotBlank() -> "检查网络后重试，或稍后再同步。"
                    !online -> "登录 Steam 后即可看到好友的最近活动。"
                    else -> "Steam 暂未返回可见的好友动态，换个时间再看看。"
                },
                tone = if (error.isNotBlank()) Tone.Warn else Tone.Info,
                action = null
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp + LocalFloatingNavigationPadding.current)
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    val day = item.day.takeIf { it.isNotBlank() }
                    val showDay = day != null && (index == 0 || items[index - 1].day != item.day)
                    Column(Modifier.fillMaxWidth()) {
                        if (showDay && day != null) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(day, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Text3)
                                Spacer(Modifier.width(10.dp))
                                Box(Modifier.weight(1f).height(1.dp).background(Line))
                            }
                        }
                        ActivityCard(item) { viewing = item.images to it }
                        Spacer(Modifier.height(10.dp))
                    }
                }
                if (more) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                            GhostButton(if (busy) "加载中" else "加载更早", { repo.refresh(true) }, enabled = !busy)
                        }
                    }
                }
            }
        }
    }

    }
    viewing?.let { (images, selected) -> ImageViewer(images, selected) { viewing = null } }
}

@Composable
private fun ActivityCard(item: FriendActivity, onImage: (String) -> Unit) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(18.dp)
    val label = when (item.kind) {
        ActivityKind.Review -> "游戏测评"
        ActivityKind.Screenshot -> "上传截图"
        ActivityKind.OwnedGame -> "购入游戏"
        ActivityKind.Other -> null
    }
    val source = remember(item.links) {
        item.links.firstOrNull { (_, url) ->
            url.startsWith("https://steamcommunity.com/") &&
                (url.contains("/recommended/") || url.contains("sharedfiles/filedetails") || url.contains("/status/"))
        } ?: item.links.firstOrNull { (_, url) ->
            item.games.none { it.url == url } && !url.matches(Regex("https://steamcommunity.com/(id|profiles)/[^/]+/?"))
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Surface1).border(1.dp, Line, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonAvatar(item.author.ifBlank { "好友" }, item.avatar, null, 38.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.author.ifBlank { "好友动态" }, color = Text1, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.day.ifBlank { "Steam 社区" }, color = Text3, fontSize = 10.5.sp)
            }
            if (label != null) {
                Text(label, Modifier.background(AccentSoft, RoundedCornerShape(20.dp)).padding(horizontal = 9.dp, vertical = 5.dp),
                    fontSize = 10.5.sp, color = AccentText)
            }
        }

        when (item.kind) {
            ActivityKind.Review -> {
                item.games.firstOrNull()?.let { ActivityGameCard(it, "测评游戏") }
                if (item.text.isNotBlank()) Column(
                    Modifier.fillMaxWidth().background(Surface2, RoundedCornerShape(12.dp)).padding(12.dp)
                ) { ActivityBody(item.text) }
            }
            ActivityKind.Screenshot -> {
                val title = item.games.firstOrNull()?.title
                Text(if (title.isNullOrBlank()) "游戏截图" else title, color = Text1,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                ActivityBody(item.text)
            }
            ActivityKind.OwnedGame -> {
                if (item.games.isNotEmpty()) item.games.forEach { ActivityGameCard(it, "已加入游戏库") }
                else ActivityBody(item.text)
            }
            ActivityKind.Other -> ActivityBody(item.text)
        }

        if (item.kind != ActivityKind.OwnedGame || item.games.isEmpty()) {
            ActivityPhotos(item.images, onImage)
        }
        if (source != null) {
            Row(
                Modifier.clickable { openSteamLink(context, source.second) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (item.kind == ActivityKind.Review) "查看 Steam 测评" else "查看 Steam 动态",
                    color = AccentText, fontSize = 11.5.sp)
                Spacer(Modifier.width(4.dp))
                SteamIcon(Glyph.ChevronRight, 12.dp, AccentText)
            }
        }
        ActivityInteractions(item)
    }
}

@Composable
private fun ActivityBody(text: String) {
    if (text.isBlank()) return
    var expanded by remember(text) { mutableStateOf(false) }
    var truncated by remember(text) { mutableStateOf(false) }
    Text(text, fontSize = 13.sp, lineHeight = 21.sp, color = Text2,
        maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow })
    if (truncated || expanded) {
        Text(if (expanded) "收起" else "展开全文", color = AccentText, fontSize = 11.5.sp,
            modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 5.dp))
    }
}

@Composable
private fun ActivityGameCard(game: ActivityGame, subtitle: String) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2)
            .clickable { openSteamLink(context, game.url) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        game.appId?.let {
            PosterCover(it, null, Modifier.size(42.dp, 56.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(game.title, color = Text1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Text3, fontSize = 11.sp)
        }
        SteamIcon(Glyph.ChevronRight, 14.dp, Text3)
    }
}

@Composable
private fun ActivityPhotos(images: List<String>, onImage: (String) -> Unit) {
    val columns = if (images.size <= 2) images.size else 3
    if (columns == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        images.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { url ->
                    var failed by remember(url) { mutableStateOf(false) }
                    Box(
                        Modifier.weight(1f).aspectRatio(if (columns == 1) 16f / 9f else 1f)
                            .clip(RoundedCornerShape(9.dp)).background(Surface2).clickable { onImage(url) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (failed) SteamIcon(Glyph.Image, 24.dp, Text3)
                        else AsyncImage(url, "查看动态图片", Modifier.fillMaxSize(),
                            contentScale = if (columns == 1) ContentScale.Fit else ContentScale.Crop,
                            onError = { failed = true })
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ActivityInteractions(item: FriendActivity) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val replies = item.replies
    val count = item.commentCount ?: replies.size.takeIf { it > 0 }?.toString()
    Column(
        Modifier.fillMaxWidth().background(Surface2, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SteamIcon(Glyph.Heart, 14.dp, AccentText)
            Text(item.likes?.let { "$it 人点赞" } ?: "点赞", color = Text2, fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            SteamIcon(Glyph.Chat, 14.dp, AccentText)
            Text(count?.let { "$it 条评论" } ?: "评论", color = Text2, fontSize = 11.sp)
        }
        if (replies.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            replies.take(if (expanded) replies.size else 3).forEach { reply ->
                val line = androidx.compose.ui.text.buildAnnotatedString {
                    if (reply.author.isNotBlank()) {
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = AccentText, fontWeight = FontWeight.SemiBold))
                        append(reply.author)
                        pop()
                        append("：")
                    }
                    append(reply.text)
                }
                Text(line, color = Text2, fontSize = 12.sp, lineHeight = 18.sp)
            }
            if (replies.size > 3) {
                Text(if (expanded) "收起回复" else "展开其余 ${replies.size - 3} 条回复",
                    Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp),
                    color = AccentText, fontSize = 11.sp)
            }
        }
    }
}
