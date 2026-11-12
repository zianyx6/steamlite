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
    var viewing by remember { mutableStateOf<String?>(null) }
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
                        ActivityCard(item) { viewing = it }
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
    viewing?.let { ImageViewer(it) { viewing = null } }
}

@Composable
private fun ActivityCard(item: FriendActivity, onImage: (String) -> Unit) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(18.dp)
    var expanded by remember(item.id) { mutableStateOf(false) }
    var truncated by remember(item.id) { mutableStateOf(false) }
    val body = remember(item.text) { ChatContent.preview(item.text) }
    val links = remember(item.links) { item.links.sortedBy { (_, url) -> if (Regex("https://steamcommunity.com/(id|profiles)/[^/]+/?$").matches(url)) 1 else 0 } }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Surface1)
            .border(1.dp, Line, shape)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.avatar != null) PersonAvatar(item.author.ifBlank { "好友" }, item.avatar, null, 38.dp)
            else Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
                SteamIcon(Glyph.Bell, 18.dp, Accent, 1.7f)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.author.ifBlank { "好友动态" }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.day.ifBlank { "Steam 社区" }, fontSize = 10.5.sp, color = Text3, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(body.ifBlank { "好友更新了一条动态" }, fontSize = 13.sp, lineHeight = 21.sp, color = Text2,
            maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow })
        if (truncated || expanded) Text(if (expanded) "收起" else "展开全文", fontSize = 11.5.sp, color = AccentText,
            modifier = Modifier.padding(top = 6.dp).clickable { expanded = !expanded })

        if (item.images.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            item.images.chunked(2).forEach { row ->
              Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { url ->
                    var failed by remember(url) { mutableStateOf(false) }
                    Box(Modifier.weight(1f).aspectRatio(if (item.images.size == 1) 16f / 9f else 1.35f)
                        .clip(RoundedCornerShape(12.dp)).background(Surface2).clickable { onImage(url) }, contentAlignment = Alignment.Center) {
                    if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SteamIcon(Glyph.Image, 24.dp, Text3)
                        Text("点击查看图片", fontSize = 10.sp, color = Text3, modifier = Modifier.padding(top = 6.dp))
                    } else
                    AsyncImage(
                        model = url,
                        contentDescription = "活动图片",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = if (item.images.size == 1) ContentScale.Fit else ContentScale.Crop,
                        onError = { failed = true }
                    )
                    }
                }
                if (row.size == 1 && item.images.size > 1) Spacer(Modifier.weight(1f))
              }
            }
        }

        if (item.links.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                links.take(if (expanded) 8 else 2).forEach { (label, url) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Surface2)
                            .clickable { openSteamLink(context, url) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            fontSize = 11.sp,
                            color = AccentText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(3.dp))
                        SteamIcon(Glyph.ChevronRight, 11.dp, Accent, 2f)
                    }
                }
            }
        }
    }
}
