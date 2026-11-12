package org.steamlite

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val sortLabels = listOf("Steam 顺序", "折扣优先", "价格从低到高", "发售日期")
private val filterLabels = listOf("全部", "正在打折", "未发售", "无购买选项")

@Composable
fun WishlistScreen(model: LiteModel) {
    val repository = model.wishlist
    val snapshot by repository.snapshot.collectAsState()
    val loading by repository.loading.collectAsState()
    val mutating by repository.mutating.collectAsState()
    val error by repository.error.collectAsState()
    val notice by repository.notice.collectAsState()
    val online by model.steam.online.collectAsState()
    val games by repository.games.collectAsState()
    val pricesUpdatedAt by repository.pricesUpdatedAt.collectAsState()
    val pricesCached by repository.pricesCached.collectAsState()

    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var shown by rememberSaveable(query, sort, filter) { mutableIntStateOf(40) }
    var sortSheet by remember { mutableStateOf(false) }
    var actionFor by remember { mutableStateOf<Int?>(null) }

    val active = LocalRootTabActive.current
    LaunchedEffect(active, online, model.steam.owner) { if (active && online) repository.refresh() }

    val term = query.trim()
    val ids = snapshot?.ids.orEmpty()
    val ordered = remember(ids, games, term, filter, sort) {
        val filtered = ids.filter { id ->
            val g = games[id]
            (term.isBlank() || id.toString().contains(term) || g?.name?.contains(term, ignoreCase = true) == true) &&
                when (filter) {
                    1 -> (g?.discount ?: 0) > 0
                    2 -> g?.coming == true
                    3 -> g?.loaded == true && g.cents == null && !g.coming
                    else -> true
                }
        }
        when (sort) {
            1 -> filtered.sortedByDescending { games[it]?.discount ?: -1 }
            2 -> filtered.sortedBy { games[it]?.cents ?: Long.MAX_VALUE }
            3 -> filtered.sortedByDescending { games[it]?.release ?: 0 }
            else -> filtered
        }
    }
    val discountCount = remember(ids, games) { ids.count { (games[it]?.discount ?: 0) > 0 } }
    val syncedAt = snapshot?.time ?: pricesUpdatedAt

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp + LocalFloatingNavigationPadding.current)
        ) {
            item {
                PageHeader("我的愿望单")
            }

            item {
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索游戏名称或 AppID",
                    modifier = Modifier.fillMaxWidth(),
                    trailing = {
                        if (query.isNotEmpty()) {
                            Text(
                                "清除",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Accent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { query = "" }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                )
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    filterLabels.forEachIndexed { index, label ->
                        val count = when (index) {
                            0 -> ids.size
                            1 -> discountCount
                            else -> null
                        }
                        SteamChip(label, filter == index, count = count) { filter = index }
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SteamIcon(Glyph.Refresh, 13.dp, Text3, 1.8f)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            loading -> "正在同步愿望单"
                            syncedAt > 0 -> "已同步 · ${time(syncedAt)}${if (pricesCached) " · 价格缓存" else ""}"
                            else -> "尚未同步"
                        },
                        fontSize = 11.5.sp,
                        color = Text3,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                SectionHeader("全部游戏") {
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable { sortSheet = true }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(sortLabels[sort], fontSize = 12.sp, color = Text2)
                        Spacer(Modifier.width(2.dp))
                        SteamIcon(Glyph.ChevronDown, 13.dp, Text3, 2.1f)
                    }
                }
            }

            if (loading || mutating) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = Accent, trackColor = Surface3) }
            }
            if (error.isNotEmpty()) {
                item { StatusBanner(error, Tone.Error) }
            }
            if (notice.isNotEmpty() && mutating) {
                item { StatusBanner(notice, Tone.Info) }
            }
            if (!online) {
                item { StatusBanner("离线 · 显示本地缓存，登录后可同步", Tone.Warn) }
            }

            when {
                snapshot == null && !loading -> item {
                    EmptyState(
                        glyph = Glyph.Refresh,
                        title = "还没有同步愿望单",
                        body = "登录 Steam 后，这里会列出你收藏的游戏与折扣。",
                        action = null
                    )
                }
                ids.isEmpty() -> item {
                    EmptyState(
                        glyph = Glyph.Heart,
                        title = "愿望单是空的",
                        body = "在商店里点收藏，游戏会出现在这里。"
                    )
                }
                ordered.isEmpty() -> item {
                    EmptyState(
                        glyph = Glyph.Search,
                        title = "没有匹配的游戏",
                        body = "未加载名称的游戏可以用 AppID 搜索。",
                        action = "清除筛选",
                        onAction = { query = ""; filter = 0 }
                    )
                }
                else -> items(ordered.take(shown), key = { it }) { appId ->
                    val game = games[appId]
                    val title = game?.name?.takeIf { it.isNotBlank() } ?: "App $appId"
                    val price = if (game == null) "" else when {
                        game.coming -> "尚未发售"
                        game.price.isNotBlank() -> game.price
                        else -> "无可用价格"
                    }
                    val meta = if (game == null) "价格尚未加载" else when {
                        game.coming -> "未发售 · 加入日程"
                        game.discount > 0 -> "正在打折"
                        game.cents == null -> "当前无购买选项"
                        !game.loaded -> "价格尚未加载"
                        else -> "Steam 商店"
                    }
                    GameRow(
                        gameId = appId,
                        image = "",
                        title = title,
                        meta = meta,
                        price = price,
                        discount = game?.discount ?: 0,
                        original = "",
                        onClick = { model.details(appId) },
                        showDivider = true,
                        trailing = {
                            Box(
                                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).clickable { actionFor = appId },
                                contentAlignment = Alignment.Center
                            ) { SteamIcon(Glyph.More, 18.dp, Text3, 2f) }
                        }
                    )
                }
            }

            if (ordered.size > shown) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.Center) {
                        GhostButton("加载更多", { shown += 40 })
                    }
                }
            }
        }
    }

    if (sortSheet) {
        SortSheet(sort) { sort = it; sortSheet = false }
    }

    actionFor?.let { appId ->
        val game = games[appId]
        GameActionSheet(
            title = game?.name?.takeIf { it.isNotBlank() } ?: "App $appId",
            canRemove = online && !mutating,
            onDetails = { actionFor = null; model.details(appId) },
            onRemove = {
                actionFor = null
                model.changeWishlist(appId, false)
                scope.launch {
                    val result = snackbar.showSnackbar(
                        message = "已从愿望单移除",
                        actionLabel = "撤销",
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) model.changeWishlist(appId, true)
                }
            },
            onClose = { actionFor = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(current: Int, onPick: (Int) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = { onPick(current) },
        containerColor = Surface1,
        scrimColor = SheetScrim,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = { SheetHandle() }
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp).navigationBarsPadding()) {
            Text("排序方式", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Text1)
            Spacer(Modifier.height(10.dp))
            sortLabels.forEachIndexed { index, label ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(index) }
                        .padding(horizontal = 4.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, Modifier.weight(1f), fontSize = 14.sp, color = if (index == current) Text1 else Text2)
                    if (index == current) SteamIcon(Glyph.Check, 16.dp, Accent, 2.2f)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameActionSheet(
    title: String,
    canRemove: Boolean,
    onDetails: () -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = Surface1,
        scrimColor = SheetScrim,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = { SheetHandle() }
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp).navigationBarsPadding()) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Text1, maxLines = 2)
            Spacer(Modifier.height(14.dp))
            SheetAction(Glyph.Info, "查看详情", onDetails)
            SheetAction(Glyph.Heart, "从愿望单移除", onRemove, enabled = canRemove, danger = true)
            Spacer(Modifier.height(18.dp))
            GhostButton("取消", onClose, Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SheetAction(glyph: Glyph, label: String, onClick: () -> Unit, enabled: Boolean = true, danger: Boolean = false) {
    val tint = when {
        !enabled -> Text3
        danger -> DangerText
        else -> Text1
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SteamIcon(glyph, 18.dp, tint, 1.9f)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 14.sp, color = tint)
    }
}
