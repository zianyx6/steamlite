package org.steamlite

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun StoreScreen(model: LiteModel, onOpenWishlist: () -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf(model.searchQuery) }
    var sortPrice by rememberSaveable { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var min by rememberSaveable { mutableStateOf("") }
    var max by rememberSaveable { mutableStateOf("") }

    val online by model.steam.online.collectAsState()
    val hasSession by model.steam.hasSession.collectAsState()
    val region by model.steam.storeRegion.collectAsState()
    LaunchedEffect(region) { min = ""; max = "" }
    val library by model.community.library.collectAsState()
    val libraryStatus by model.community.libraryStatus.collectAsState()
    val ownedIds = remember(library?.games) { library?.games?.map { it.id }?.toSet().orEmpty() }
    val waitingForLibrary = model.isFeatured && hasSession && library == null
    val highlights = remember(model.recommendations, ownedIds, hasSession, waitingForLibrary) {
        if (waitingForLibrary) emptyList() else model.recommendations.filterNot { hasSession && it.id in ownedIds }.take(8)
    }
    val wish by model.wishlist.snapshot.collectAsState()
    val wishGames by model.wishlist.games.collectAsState()
    val wishBusy by model.wishlist.mutating.collectAsState()
    val notice by model.wishlist.notice.collectAsState()
    val wishError by model.wishlist.error.collectAsState()

    val active = LocalRootTabActive.current
    LaunchedEffect(active) {
        if (active && model.games.isEmpty() && !model.loading && model.error.isBlank()) model.search(model.searchQuery)
    }
    LaunchedEffect(active, online, region) { if (active && online) { model.wishlist.refresh(); if (library == null) model.community.refreshLibrary() } }

    val minCents = min.toDoubleOrNull()?.times(100)
    val maxCents = max.toDoubleOrNull()?.times(100)
    val games = model.games
    val minPositive = model.searchOptions.minPositive
    val ordered = remember(games, minCents, maxCents, minPositive, sortPrice, ownedIds, hasSession, waitingForLibrary, model.isFeatured) {
        val selected = games.filter { game ->
            val cents = game.cents
            val inRange = cents == null || ((minCents == null || cents >= minCents) && (maxCents == null || cents <= maxCents))
            !waitingForLibrary && !(model.isFeatured && hasSession && game.id in ownedIds) && inRange && (minPositive == 0 || (game.positivePercent ?: -1) >= minPositive)
        }
        if (sortPrice) selected.sortedBy { it.cents ?: Long.MAX_VALUE } else selected
    }
    val wishIds = wish?.ids.orEmpty()
    val wishIdSet = remember(wishIds) { wishIds.toSet() }
    val wishDiscounts = remember(wishIds, wishGames, ownedIds, waitingForLibrary) {
        wishIds.mapNotNull { wishGames[it] }
            .filter { !waitingForLibrary && it.discount > 0 && it.id !in ownedIds }
            .sortedByDescending { it.discount }
            .take(6)
    }

    RefreshContainer(model.loading, { model.search(query, true) }) { Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 28.dp + LocalFloatingNavigationPadding.current)
        ) {
            item {
                PageHeader("商店")
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "搜索中文游戏名称",
                        modifier = Modifier.weight(1f),
                        onSearch = { model.search(query, true) },
                        trailing = {
                            if (query.isNotEmpty()) {
                                Text(
                                    "搜索",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { model.search(query, true) }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    IconSquare(
                        Glyph.Filter,
                        { showFilters = true },
                        tint = if (model.searchOptions != StoreSearchOptions()) Accent else Text2,
                        dot = model.searchOptions != StoreSearchOptions()
                    )
                }
            }

            item { MetaLine(model.info, Glyph.Info) }
            if (waitingForLibrary) item {
                MetaLine(if (libraryStatus.contains("失败")) libraryStatus else "正在读取游戏库，以隐藏已拥有游戏", Glyph.Info)
            }

            if (model.loading || model.detailLoading) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp), color = Accent, trackColor = Surface3) }
            }
            if (model.error.isNotEmpty()) {
                item {
                    StatusBanner(model.error, Tone.Error, "重试") { model.search(query, true) }
                }
            }
            if (wishError.isNotEmpty()) {
                item { StatusBanner("愿望单：$wishError", Tone.Warn) }
            }
            if (wishBusy || notice.contains("回读")) {
                item { StatusBanner(notice, Tone.Info) }
            }

            if (model.isFeatured) {
                if (highlights.isNotEmpty()) {
                    item {
                        val pager = rememberPagerState { highlights.size }
                        SectionHeader("今日精选") { Text("${pager.currentPage + 1} / ${highlights.size}", fontSize = 11.sp, color = Text3) }
                        HorizontalPager(pager, pageSpacing = 12.dp, key = { highlights[it].id }) { index ->
                            val game = highlights[index]
                            StoreGameCard(game, large = true, wished = game.id in wishIdSet,
                                canWish = online && !wishBusy && game.id !in wishIdSet,
                                onWish = { model.changeWishlist(game.id, true) }, onOpen = { model.details(game.id) },
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                item {
                    SectionHeader("折扣推荐") {
                        TextLink("更多折扣") {
                            query = ""
                            model.searchOptions = StoreSearchOptions(discountOnly = true)
                            model.search("", true)
                        }
                    }
                }
            }

            if (!model.loading && !waitingForLibrary && ordered.isEmpty() && model.error.isEmpty() && wishError.isEmpty()) {
                item {
                    EmptyState(glyph = Glyph.Search, title = if (model.isFeatured && hasSession) "暂无未拥有的推荐游戏" else "没有符合条件的商品",
                        body = "可搜索其他游戏，或调整筛选条件。", action = "清除筛选",
                        onAction = { model.searchOptions = StoreSearchOptions(); sortPrice = false; min = ""; max = ""; query = ""; model.search("", true) })
                }
            }
            items(ordered.chunked(2), key = { "cards:${it.first().id}" }, contentType = { "game-cards" }) { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { game ->
                        StoreGameCard(game, large = false, wished = game.id in wishIdSet,
                            canWish = online && !wishBusy && game.id !in wishIdSet,
                            onWish = { model.changeWishlist(game.id, true) }, onOpen = { model.details(game.id) }, modifier = Modifier.weight(1f))
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (model.isFeatured) {
                if (wishDiscounts.isNotEmpty()) {
                    item {
                        SectionHeader("愿望单降价") { TextLink("查看全部", onOpenWishlist) }
                    }
                    items(wishDiscounts, key = { "wish:${it.id}" }, contentType = { "game" }) { game ->
                        GameRow(
                            gameId = game.id,
                            image = "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/${game.id}/library_600x900.jpg",
                            title = game.name,
                            meta = "愿望单 · 降价中",
                            price = game.price,
                            discount = game.discount,
                            original = "",
                            onClick = { model.details(game.id) },
                            showDivider = true,
                            trailing = { HeartButton(on = true, enabled = false) {} }
                        )
                    }
                }
            }

            if (model.hasMoreResults) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.Center) {
                        GhostButton(if (model.loading) "加载中" else "加载下一页", { model.moreResults() }, enabled = !model.loading)
                    }
                }
            }
        }
    }

    }
    if (showFilters) {
        StoreFilterSheet(model, query, min, max, { min = it }, { max = it }) { showFilters = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoreFilterSheet(
    model: LiteModel,
    query: String,
    min: String,
    max: String,
    onMin: (String) -> Unit,
    onMax: (String) -> Unit,
    onClose: () -> Unit
) {
    val region by model.steam.storeRegion.collectAsState()
    val hasSession by model.steam.hasSession.collectAsState()
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = Surface1,
        scrimColor = SheetScrim,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = { SheetHandle() }
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp).navigationBarsPadding()) {
            Text("筛选与排序", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Text1)
            Spacer(Modifier.height(4.dp))
            Text("只影响下一次搜索的结果 · 价格以 Steam 返回币种为准", fontSize = 11.5.sp, color = Text3)

            Spacer(Modifier.height(20.dp))
            Text("排序", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Text3)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("相关度" to "_ASC", "价格升序" to "Price_ASC", "发行日期" to "Released_DESC").forEach { (name, value) ->
                    SteamChip(name, model.searchOptions.order == value) {
                        model.searchOptions = model.searchOptions.copy(order = value)
                        model.search(query, true)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("类型", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Text3)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("全部" to "", "游戏" to "998", "DLC" to "21", "软件" to "994").forEach { (name, value) ->
                    SteamChip(name, model.searchOptions.category == value) {
                        model.searchOptions = model.searchOptions.copy(category = value)
                        model.search(query, true)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("价格区间（${region?.currency ?: if (hasSession) "等待地区" else "USD"}）", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Text3)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabeledField(min, onMin, "最低", Modifier.weight(1f))
                Text("—", Modifier.padding(horizontal = 10.dp), color = Text3, fontSize = 12.sp)
                LabeledField(max, onMax, "最高", Modifier.weight(1f))
            }

            Spacer(Modifier.height(22.dp))
            AccentButton("完成", onClose, Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LabeledField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Text1, fontSize = 13.sp),
        cursorBrush = SolidColor(Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { }),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier.clip(shape).background(Surface2).border(1.dp, Line, shape)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Box {
                    if (value.isEmpty()) Text(label, fontSize = 13.sp, color = Text3)
                    inner()
                }
            }
        }
    )
}
