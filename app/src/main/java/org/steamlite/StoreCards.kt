package org.steamlite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun wideArtworkUrls(id: Int, explicit: String = ""): List<String> = buildList {
    if (explicit.isNotBlank() && explicit.contains("header")) add(explicit)
    add("https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/$id/header.jpg")
    add("https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$id/header.jpg")
    if (explicit.isNotBlank()) add(explicit)
}.distinct()

@Composable
internal fun DiscountBadge(discount: Int) {
    if (discount <= 0) return
    Text("-${discount.coerceAtMost(100)}%", Modifier.clip(RoundedCornerShape(7.dp)).background(MoneySoft)
        .padding(horizontal = 7.dp, vertical = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MoneyText)
}

@Composable
internal fun StoreGameCard(game: Game, large: Boolean, wished: Boolean, canWish: Boolean, onWish: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(if (large) 23.dp else 19.dp)
    val urls = remember(game.id, game.image) { wideArtworkUrls(game.id, game.image) }
    Column(modifier.clip(shape).background(Surface1).border(1.dp, Line, shape).clickable(onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(460f / 215f).background(coverBrush(game.id))) {
            SteamImage(urls, "", Modifier.fillMaxSize())
            if (large && game.discount > 0) {
                Text("特别优惠", Modifier.align(Alignment.BottomStart).padding(12.dp).clip(RoundedCornerShape(9.dp))
                    .background(Surface1.copy(alpha = .94f)).padding(horizontal = 10.dp, vertical = 6.dp),
                    fontSize = 11.sp, color = Text1)
            }
        }
        Column(Modifier.fillMaxWidth().padding(if (large) 15.dp else 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(game.name, Modifier.weight(1f), fontSize = if (large) 18.sp else 14.sp,
                    lineHeight = if (large) 24.sp else 20.sp, minLines = 2, maxLines = 2,
                    fontWeight = FontWeight.SemiBold, overflow = TextOverflow.Ellipsis, color = Text1)
                if (large) HeartButton(wished, canWish, onWish)
            }
            Text(game.positivePercent?.let { "好评 $it%" } ?: game.release.ifBlank { "Steam 商店" },
                Modifier.padding(top = 5.dp), fontSize = 11.sp, color = Text3, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 37.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DiscountBadge(game.discount)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(if (game.discount > 0) game.original else "", fontSize = 10.sp, color = Text3,
                        textDecoration = TextDecoration.LineThrough, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(game.price.ifBlank { "暂无报价" }, fontSize = if (large) 21.sp else 15.sp,
                        fontWeight = FontWeight.SemiBold, color = Text1, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
