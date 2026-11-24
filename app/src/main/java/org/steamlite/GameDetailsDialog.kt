package org.steamlite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

private data class DetailMedia(val src: String, val thumb: String, val video: Boolean)

private fun detailText(html: String): String = runCatching {
    val doc = org.jsoup.Jsoup.parse(html)
    doc.select("br").append("\\n")
    doc.select("p,h1,h2,h3,li").prepend("\\n\\n")
    doc.text().replace("\\n", "\n").lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n").trim()
}.getOrDefault(GameDetailsData.plain(html))

private fun JSONObject.detailNames(key: String): List<String> {
    val items = optJSONArray(key) ?: return emptyList()
    return (0 until items.length()).map { index ->
        items.optJSONObject(index)?.optString("description") ?: items.optString(index)
    }.map { GameDetailsData.plain(it) }.filter { it.isNotBlank() }.distinct()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GameDetailsDialog(model: LiteModel, open: (String) -> Unit) {
    val data = model.detail ?: return
    val id = data.optInt("steam_appid")
    val name = data.optString("name")
    val context = LocalContext.current
    val online by model.steam.online.collectAsState()
    val busy by model.wishlist.mutating.collectAsState()
    val notice by model.wishlist.notice.collectAsState()
    val error by model.wishlist.error.collectAsState()
    val wish by model.wishlist.snapshot.collectAsState()
    val inWishlist = wish?.ids?.contains(id) == true
    var confirmRemove by remember(id) { mutableStateOf(false) }
    LaunchedEffect(inWishlist) { if (!inWishlist) confirmRemove = false }
    val purchases = remember(data) { GameDetailsData.purchases(data) }
    var selectedId by rememberSaveable(id) { mutableIntStateOf(purchases.firstOrNull { !it.subscription }?.id ?: purchases.firstOrNull()?.id ?: 0) }
    val chosen = purchases.find { it.id == selectedId } ?: purchases.firstOrNull()
    val priceObject = data.optJSONObject("price_overview")
    val price = chosen?.price ?: if (data.optBoolean("is_free")) "免费" else priceObject?.optString("final_formatted").orEmpty().ifBlank { "暂无可用价格" }
    val original = chosen?.original ?: priceObject?.optString("initial_formatted").orEmpty()
    val discount = chosen?.discount ?: priceObject?.optInt("discount_percent") ?: 0
    var viewing by remember(id) { mutableStateOf<String?>(null) }
    var expandedDescription by rememberSaveable(id) { mutableStateOf(false) }
    var expandedLanguages by rememberSaveable(id) { mutableStateOf(false) }
    var expandedRequirements by rememberSaveable(id) { mutableStateOf(false) }
    var expandedHistory by rememberSaveable(id) { mutableStateOf(false) }
    val description = remember(data) { detailText(data.optString("detailed_description")).ifBlank { detailText(data.optString("short_description")) } }
    val genres = remember(data) { data.detailNames("genres") }
    val categories = remember(data) { data.detailNames("categories") }
    val developers = remember(data) { data.detailNames("developers") }
    val publishers = remember(data) { data.detailNames("publishers") }
    val languages = model.detailSupplement?.languages?.takeIf { it.isNotEmpty() }
        ?: remember(data) { GameDetailsData.languageFallback(data.optString("supported_languages")) }
    val media = remember(data) { buildList {
        data.optJSONArray("movies")?.let { movies ->
            for (index in 0 until minOf(movies.length(), 4)) {
                val movie = movies.optJSONObject(index) ?: continue
                val thumb = movie.optString("thumbnail")
                val src = movie.optJSONObject("mp4")?.optString("max").orEmpty()
                    .ifBlank { movie.optJSONObject("webm")?.optString("max").orEmpty() }
                if (thumb.isNotBlank() && src.isNotBlank()) add(DetailMedia(src, thumb, true))
            }
        }
        data.optJSONArray("screenshots")?.let { shots ->
            for (index in 0 until shots.length()) {
                val shot = shots.optJSONObject(index) ?: continue
                val thumb = shot.optString("path_thumbnail")
                val full = shot.optString("path_full").ifBlank { thumb }
                if (full.isNotBlank()) add(DetailMedia(full, thumb.ifBlank { full }, false))
            }
        }
    } }
    val header = data.optString("header_image")
    val imageGallery = remember(id, header, media) { (listOf(header) + media.filterNot { it.video }.map { it.src }).filter { it.isNotBlank() }.distinct() }
    fun changeWish() { if (inWishlist) confirmRemove = true else model.changeWishlist(id, true) }
    ModalBottomSheet(
        onDismissRequest = { model.detail = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Bg, scrimColor = SheetScrim, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = { SheetHandle() },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp).navigationBarsPadding()) {
            Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().aspectRatio(460f / 215f)
                .clip(RoundedCornerShape(22.dp)).background(coverBrush(id)).clickable(enabled = imageGallery.isNotEmpty()) { viewing = imageGallery.firstOrNull() }) {
                SteamImage(remember(id, header) { wideArtworkUrls(id, header) }, "游戏封面", Modifier.fillMaxSize())
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    DetailCoverButton(Glyph.Back, "返回") { model.detail = null }
                    DetailCoverButton(Glyph.Heart, if (inWishlist) "已加入愿望单" else "加入愿望单", online && !busy, inWishlist) { changeWish() }
                }
            }
            Column(Modifier.padding(horizontal = 18.dp)) {
                Spacer(Modifier.height(21.dp))
                if (publishers.isNotEmpty()) Text(publishers.joinToString(" · "), fontSize = 10.sp, color = Text3, letterSpacing = 1.sp)
                Spacer(Modifier.height(7.dp))
                SelectionContainer { Text(name, fontSize = 26.sp, lineHeight = 33.sp, fontWeight = FontWeight.SemiBold, color = Text1) }
                FlowRow(Modifier.padding(top = 13.dp, bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    val platforms = data.optJSONObject("platforms")
                    listOf("windows" to "Windows", "mac" to "macOS", "linux" to "Linux").filter { platforms?.optBoolean(it.first) == true }
                        .forEach { DetailTag(it.second) }
                    genres.take(2).forEach { DetailTag(it) }
                }
                DetailPanel {
                    Text(if (chosen != null) "当前所选套餐" else "Steam 商店价格", fontSize = 11.sp, color = Text3)
                    Spacer(Modifier.height(10.dp))
                    DetailPrice(price, discount, original)
                    priceObject?.optString("currency")?.takeIf { it.isNotBlank() }?.let {
                        Text(it, Modifier.padding(top = 5.dp), fontSize = 10.sp, color = Text3)
                    }
                }
                DetailSectionTitle("购买选项", if (purchases.isEmpty()) "" else "${purchases.size} 款套餐")
                if (purchases.isEmpty()) {
                    DetailPanel { Text(if (data.optBoolean("is_free")) "免费游戏，可前往 Steam 获取" else "Steam 暂未提供可用套餐，请在官方商店查看", fontSize = 12.sp, lineHeight = 19.sp, color = Text2) }
                } else Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    purchases.forEach { option -> PurchaseCard(option, option.id == chosen?.id) { selectedId = option.id } }
                }
                Spacer(Modifier.height(18.dp))
                Hairline()
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(if (chosen?.subscription == true) "订阅套餐" else "所选套餐合计", fontSize = 10.sp, color = Text3)
                        Text(price, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = Text1)
                    }
                    AccentButton("前往 Steam 购买", { open(chosen?.url ?: "https://store.steampowered.com/app/$id/") }, glyph = Glyph.ChevronRight)
                }
                if (confirmRemove) StatusBanner("从 Steam 愿望单移除？", Tone.Warn, "确认移除") { confirmRemove = false; model.changeWishlist(id, false) }
                else GhostButton(if (inWishlist) "已在愿望单" else "加入愿望单", { changeWish() }, Modifier.fillMaxWidth(), online && !busy, Glyph.Heart)
                if (busy || notice.contains("回读")) StatusBanner(notice, Tone.Info)
                if (error.isNotEmpty()) StatusBanner(error, Tone.Error)

                if (description.isNotBlank()) {
                    DetailSectionTitle("关于此游戏")
                    DetailPanel {
                        SelectionContainer {
                            Text(description, fontSize = 12.5.sp, lineHeight = 23.sp, color = Text2,
                                maxLines = if (expandedDescription) Int.MAX_VALUE else 5)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextLink(if (expandedDescription) "收起介绍" else "展开介绍") { expandedDescription = !expandedDescription }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = {
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("游戏介绍", description))
                                if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, "已复制游戏介绍", Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.semantics { contentDescription = "复制游戏介绍" }) { SteamIcon(Glyph.Copy, 17.dp, Text2) }
                        }
                    }
                }
                if (media.isNotEmpty()) {
                    DetailSectionTitle("视频与截图", "${media.size} 项")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        items(media.size, key = { it }) { index ->
                            val item = media[index]
                            Box(Modifier.size(235.dp, 132.dp).clip(RoundedCornerShape(16.dp)).background(Surface1).clickable {
                                if (item.video) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.src))) }
                                    .onFailure { Toast.makeText(context, "没有可用的视频播放器", Toast.LENGTH_SHORT).show() }
                                else viewing = item.src
                            }, contentAlignment = Alignment.Center) {
                                SteamImage(listOf(item.thumb), if (item.video) "视频" else "截图", Modifier.fillMaxSize())
                                if (item.video) Text("▶", Modifier.clip(CircleShape).background(Color(0x99000000)).padding(12.dp), color = Color.White)
                            }
                        }
                    }
                }
                DetailSectionTitle("游戏信息")
                DetailPanel {
                    DetailFact("开发商", developers.joinToString("、"))
                    DetailFact("发行商", publishers.joinToString("、"))
                    DetailFact("发行日期", data.optJSONObject("release_date")?.optString("date").orEmpty())
                    DetailFact("游戏类型", genres.joinToString("、"))
                    if (categories.isNotEmpty()) {
                        DetailDivider()
                        Text("功能与支持", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Text1)
                        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            categories.forEach { DetailTag(it) }
                        }
                    }
                    if (languages.isNotEmpty()) {
                        DetailDivider()
                        Text("语言支持", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Text1)
                        LanguageRow("语言", "界面", "字幕", "配音", header = true)
                        (if (expandedLanguages) languages else languages.take(3)).forEach { language ->
                            LanguageRow(language.name, supportMark(language.ui), supportMark(language.subtitles), supportMark(language.audio))
                        }
                        if (languages.any { it.ui == null }) Text("· 表示 Steam 未提供分项数据", fontSize = 10.sp, color = Text3)
                        if (languages.size > 3) DetailLink(if (expandedLanguages) "收起语言" else "查看全部 ${languages.size} 种语言") { expandedLanguages = !expandedLanguages }
                    }
                    val recommendations = data.optJSONObject("recommendations")?.optLong("total")?.takeIf { it > 0 }
                    val achievements = data.optJSONObject("achievements")?.optInt("total")?.takeIf { it > 0 }
                    if (recommendations != null || achievements != null) {
                        DetailDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            recommendations?.let { DetailStat("推荐数量", NumberFormat.getIntegerInstance().format(it), Modifier.weight(1f)) }
                            achievements?.let { DetailStat("成就数量", it.toString(), Modifier.weight(1f)) }
                        }
                    }
                    val website = data.optString("website")
                    if (ChatContent.webUrl(website)) {
                        DetailDivider()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("官方网站", Modifier.weight(1f), fontSize = 12.sp, color = Text2)
                            TextLink("用浏览器打开") { openInBrowser(context, website) }
                        }
                    }
                }
                DetailSectionTitle("玩家评价", "Steam 用户评价")
                ReviewSummaryCard(model.detailSupplement?.reviews.orEmpty(), model.detailSupplementLoading) { open("https://store.steampowered.com/app/$id/#app_reviews_hash") }

                val requirements = data.optJSONObject("pc_requirements")
                if (!requirements?.optString("minimum").isNullOrBlank()) {
                    DetailSectionTitle("系统配置")
                    DetailPanel {
                        DetailLink(if (expandedRequirements) "收起配置" else "查看最低与推荐配置") { expandedRequirements = !expandedRequirements }
                        if (expandedRequirements) {
                            Text(detailText(requirements!!.optString("minimum")), Modifier.padding(top = 12.dp), fontSize = 11.5.sp, lineHeight = 19.sp, color = Text2)
                            requirements.optString("recommended").takeIf { it.isNotBlank() }?.let { Text(detailText(it), Modifier.padding(top = 12.dp), fontSize = 11.5.sp, lineHeight = 19.sp, color = Text2) }
                        }
                    }
                }
                model.detailObservation?.let { observation ->
                    DetailSectionTitle("价格观察")
                    DetailPanel {
                        DetailLink(if (expandedHistory) "收起价格观察" else observation.label) { expandedHistory = !expandedHistory }
                        if (expandedHistory) {
                            Text("本机观察最低 USD " + String.format(Locale.US, "%.2f", observation.lowest / 100.0), Modifier.padding(top = 10.dp), fontSize = 12.sp, color = MoneyText)
                            Text("${observation.count} 次观察 · ${time(observation.last)}\n仅本机记录，并非 Steam 全历史最低价", fontSize = 11.sp, lineHeight = 18.sp, color = Text3)
                        }
                        DetailLink("打开价格历史") { openInBrowser(context, "https://steamdb.info/app/$id/") }
                    }
                }
            }
        }
    }
    viewing?.let { selected -> ImageViewer(imageGallery, selected) { viewing = null } }
}

private fun openInBrowser(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
        .onFailure { Toast.makeText(context, "未找到可用浏览器", Toast.LENGTH_SHORT).show() }
}

@Composable
private fun DetailCoverButton(glyph: Glyph, label: String, enabled: Boolean = true, filled: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(36.dp).clip(CircleShape).background(Color(0xBB141414)).semantics { contentDescription = label }, enabled = enabled) {
        SteamIcon(glyph, 19.dp, if (filled) Color(0xFF96CAFF) else Color.White, filled = filled)
    }
}

@Composable
private fun DetailPanel(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(19.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Surface1).border(1.dp, Line, shape).padding(16.dp), content = content)
}

@Composable
private fun DetailSectionTitle(title: String, meta: String = "") {
    Row(Modifier.fillMaxWidth().padding(top = 27.dp, bottom = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Text1)
        if (meta.isNotBlank()) Text(meta, fontSize = 10.sp, color = Text3)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailPrice(price: String, discount: Int, original: String) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(7.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        DiscountBadge(discount)
        if (discount > 0 && original.isNotBlank()) Text(original, fontSize = 11.sp, color = Text3, textDecoration = TextDecoration.LineThrough)
        Text(price, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Text1)
    }
}

@Composable
private fun PurchaseCard(option: PurchaseOption, selected: Boolean, choose: () -> Unit) {
    val shape = RoundedCornerShape(19.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(if (selected) AccentSoft else Surface1)
        .border(if (selected) 2.dp else 1.dp, if (selected) AccentLine else Line, shape)
        .selectable(selected, onClick = choose, role = Role.RadioButton).padding(15.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (option.subscription) "订阅 · 自动续费" else "购买套餐", Modifier.weight(1f), fontSize = 10.sp, color = if (selected) AccentText else Text3)
            Box(Modifier.size(19.dp).clip(CircleShape).background(if (selected) Accent else Surface2).border(1.dp, if (selected) Accent else Line, CircleShape), contentAlignment = Alignment.Center) {
                if (selected) SteamIcon(Glyph.Check, 13.dp, OnAccent)
            }
        }
        Text(option.name, Modifier.padding(top = 8.dp), fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = Text1)
        if (option.description.isNotBlank()) Text(option.description, Modifier.padding(top = 6.dp), fontSize = 11.sp, lineHeight = 18.sp, color = Text3)
        Spacer(Modifier.height(12.dp))
        DetailPrice(option.price, option.discount, option.original)
    }
}

@Composable
private fun DetailTag(label: String) {
    Text(label, Modifier.clip(RoundedCornerShape(8.dp)).background(Surface2).padding(horizontal = 9.dp, vertical = 7.dp),
        fontSize = 10.sp, lineHeight = 14.sp, color = Text2)
}

@Composable
private fun DetailDivider() { Spacer(Modifier.height(17.dp)); Hairline(); Spacer(Modifier.height(17.dp)) }

@Composable
private fun DetailLink(text: String, onClick: () -> Unit) { Row { TextLink(text, onClick) } }

@Composable
private fun DetailFact(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.width(66.dp), fontSize = 11.sp, lineHeight = 19.sp, color = Text3)
        SelectionContainer { Text(value, fontSize = 12.sp, lineHeight = 19.sp, color = Text1) }
    }
}

private fun supportMark(value: Boolean?): String = when (value) { true -> "✓"; false -> "—"; null -> "·" }

@Composable
private fun LanguageRow(name: String, ui: String, subtitles: String, audio: String, header: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1.7f), fontSize = if (header) 10.sp else 11.sp, color = if (header) Text3 else Text1)
        listOf(ui, subtitles, audio).forEach { value ->
            Text(value, Modifier.weight(1f).semantics { contentDescription = when (value) { "✓" -> "支持"; "—" -> "不支持"; "·" -> "未提供数据"; else -> value } }, textAlign = TextAlign.Center,
                fontSize = 11.sp, color = if (!header && value == "✓") AccentText else Text3)
        }
    }
}

@Composable
private fun DetailStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) { Text(label, fontSize = 10.sp, color = Text3); Text(value, Modifier.padding(top = 5.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Text1) }
}

@Composable
private fun ReviewSummaryCard(reviews: List<GameReviewSummary>, loading: Boolean, openReviews: () -> Unit) {
    var recent by rememberSaveable { mutableStateOf(true) }
    val selected = reviews.find { it.recent == recent } ?: reviews.firstOrNull()
    DetailPanel {
        if (selected == null) {
            if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = Surface3); Spacer(Modifier.height(10.dp)) }
            Text(if (loading) "正在加载 Steam 评价" else "评价摘要暂未获取", fontSize = 12.sp, color = Text3)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                reviews.forEach { summary -> SteamChip(if (summary.recent) "最近 30 天" else "全部评价", selected == summary) { recent = summary.recent } }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${selected.percent}%", Modifier.weight(1f), fontSize = 38.sp, fontWeight = FontWeight.SemiBold, color = Text1)
                Text(selected.verdict.ifBlank { "好评率" }, Modifier.clip(RoundedCornerShape(9.dp))
                    .background(if (selected.percent >= 70) MoneySoft else Surface2).padding(horizontal = 10.dp, vertical = 7.dp),
                    fontSize = 12.sp, color = if (selected.percent >= 70) MoneyText else Text2)
            }
            LinearProgressIndicator(progress = { selected.percent / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Accent, trackColor = Surface3)
            Text("共 ${NumberFormat.getIntegerInstance().format(selected.total)} 条评价 · ${selected.scope}", Modifier.padding(top = 10.dp, bottom = 8.dp), fontSize = 10.sp, color = Text3)
        }
        DetailLink("在 Steam 查看评价", openReviews)
    }
}
