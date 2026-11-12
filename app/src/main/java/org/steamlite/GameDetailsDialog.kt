package org.steamlite

import android.content.Intent
import android.net.Uri
import android.text.Html
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.util.Locale

private data class Media(val src: String, val thumb: String, val video: Boolean)

/** Steam returns the store page body as HTML; flatten it to readable paragraphs. */
private fun htmlToText(html: String): String {
    if (html.isBlank()) return ""
    return runCatching {
        val doc = org.jsoup.Jsoup.parse(html)
        doc.select("br").append("\\n")
        doc.select("p").prepend("\\n\\n")
        doc.select("li").prepend("\\n· ")
        doc.select("h1, h2, h3").prepend("\\n\\n")
        doc.text().replace("\\n", "\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }.getOrDefault(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailsDialog(model: LiteModel, open: (String) -> Unit) {
    val data = model.detail ?: return
    val id = data.optInt("steam_appid")
    val online by model.steam.online.collectAsState()
    val busy by model.wishlist.mutating.collectAsState()
    val notice by model.wishlist.notice.collectAsState()
    val error by model.wishlist.error.collectAsState()
    val wish by model.wishlist.snapshot.collectAsState()
    val observation = model.detailObservation
    val inWishlist = wish?.ids?.contains(id) == true
    var confirmRemove by remember(id) { mutableStateOf(false) }
    LaunchedEffect(inWishlist) { if (!inWishlist) confirmRemove = false }

    val context = LocalContext.current
    fun playVideo(url: String) {
        if (url.isBlank()) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(context, "没有可用的视频播放器", Toast.LENGTH_SHORT).show() }
    }

    val priceObj = data.optJSONObject("price_overview")
    val free = data.optBoolean("is_free")
    val price = when {
        free -> "免费"
        priceObj != null -> priceObj.optString("final_formatted")
        else -> "暂无可用价格"
    }
    val discount = priceObj?.optInt("discount_percent") ?: 0
    val original = if (discount > 0) priceObj?.optString("initial_formatted").orEmpty() else ""
    val release = data.optJSONObject("release_date")?.optString("date").orEmpty()
    val name = data.optString("name")

    // Trailers and screenshots share one strip so the top of the sheet is never empty.
    val media = remember(data) {
        buildList {
            val header = data.optString("header_image")
            if (header.isNotBlank()) add(Media(header, header, false))
            data.optJSONArray("movies")?.let { movies ->
                for (index in 0 until minOf(movies.length(), 4)) {
                    val movie = movies.getJSONObject(index)
                    val thumb = movie.optString("thumbnail")
                    val src = movie.optJSONObject("mp4")?.optString("max").orEmpty()
                        .ifBlank { movie.optJSONObject("webm")?.optString("max").orEmpty() }
                    if (thumb.isNotBlank()) add(Media(src, thumb, src.isNotBlank()))
                }
            }
            data.optJSONArray("screenshots")?.let { shots ->
                for (index in 0 until minOf(shots.length(), 8)) {
                    val shot = shots.getJSONObject(index)
                    val thumb = shot.optString("path_thumbnail")
                    val full = shot.optString("path_full")
                    if (thumb.isNotBlank()) add(Media(full, thumb, false))
                }
            }
        }
    }

    val description = htmlToText(data.optString("detailed_description"))
        .ifBlank { data.optString("short_description").trim() }
    val languages = Html.fromHtml(data.optString("supported_languages"), Html.FROM_HTML_MODE_LEGACY).toString().trim()

    ModalBottomSheet(
        onDismissRequest = { model.detail = null },
        containerColor = Surface1,
        scrimColor = SheetScrim,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = { SheetHandle() }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 700.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            if (media.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(media.size, key = { it }) { index ->
                        val item = media[index]
                        Box(
                            Modifier
                                .width(286.dp)
                                .height(160.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Surface2)
                                .clickable {
                                    if (item.video) playVideo(item.src)
                                    else if (item.src.isNotBlank()) open(item.src)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = item.thumb,
                                contentDescription = if (item.video) "视频" else "截图",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            if (item.video) {
                                Box(
                                    Modifier.size(46.dp).clip(CircleShape).background(Color(0x99000000)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("▶", fontSize = 15.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(14.dp))
                Text(name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (release.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("发售 · $release", fontSize = 11.5.sp, color = Text3)
                }
                Spacer(Modifier.height(12.dp))
                PriceLine(price, discount, original)

                Spacer(Modifier.height(14.dp))
                if (confirmRemove) {
                    StatusBanner("从 Steam 愿望单移除？", Tone.Warn, "确认移除") {
                        confirmRemove = false
                        model.changeWishlist(id, false)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccentButton(
                            if (inWishlist) "已在愿望单" else "加入愿望单",
                            { if (inWishlist) confirmRemove = true else model.changeWishlist(id, true) },
                            Modifier.weight(1f),
                            enabled = online && !busy && !inWishlist,
                            glyph = Glyph.Heart
                        )
                        GhostButton(
                            "商店页面",
                            { open("https://store.steampowered.com/app/$id/") },
                            Modifier.weight(1f),
                            glyph = Glyph.ChevronRight
                        )
                    }
                }

                if (busy || notice.contains("回读")) StatusBanner(notice, Tone.Info)
                if (error.isNotEmpty()) StatusBanner(error, Tone.Error)

                // Steam store-page body, not the short sidebar blurb.
                if (description.isNotBlank()) {
                    Spacer(Modifier.height(20.dp))
                    Text("游戏介绍", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Text3)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        description,
                        fontSize = 12.5.sp,
                        lineHeight = 20.sp,
                        color = Text2
                    )
                }

                if (languages.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text("语言", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Text3)
                    Spacer(Modifier.height(4.dp))
                    Text(languages, fontSize = 12.sp, lineHeight = 18.sp, color = Text2, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }

                if (observation != null) {
                    Spacer(Modifier.height(18.dp))
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface2).padding(14.dp)
                    ) {
                        Text(observation.label, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = MoneyText)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "本机观察最低 USD " + String.format(Locale.US, "%.2f", observation.lowest / 100.0) +
                                " · " + observation.count + " 次观察",
                            fontSize = 11.5.sp,
                            color = Text2
                        )
                        Text("来源 ${observation.source} · 更新 ${time(observation.last)}", fontSize = 10.5.sp, color = Text3)
                        if (observation.samples.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            observation.samples.takeLast(3).forEach { sample ->
                                Text(
                                    time(sample.time) + "   USD " + String.format(Locale.US, "%.2f", sample.cents / 100.0),
                                    fontSize = 10.5.sp,
                                    color = Text3
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("不是 Steam 全历史最低价，仅本机观察", fontSize = 10.sp, color = Text3)
                    }
                }

                val requirements = data.optJSONObject("pc_requirements")
                if (requirements != null) {
                    Spacer(Modifier.height(20.dp))
                    Text("最低配置", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Text3)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        htmlToText(requirements.optString("minimum")).ifBlank { "暂无数据" },
                        fontSize = 11.5.sp,
                        lineHeight = 18.sp,
                        color = Text2
                    )
                    if (requirements.optString("recommended").isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text("推荐配置", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Text3)
                        Spacer(Modifier.height(4.dp))
                        Text(htmlToText(requirements.optString("recommended")), fontSize = 11.5.sp, lineHeight = 18.sp, color = Text2)
                    }
                }

                Spacer(Modifier.height(22.dp))
                Text(
                    "价格历史可在浏览器打开 steamdb.info 核对，本应用不保证完整性。",
                    fontSize = 10.5.sp,
                    color = Text3,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(10.dp))
                GhostButton("打开价格历史", { open("https://steamdb.info/app/$id/") }, Modifier.fillMaxWidth())
            }
        }
    }
}
