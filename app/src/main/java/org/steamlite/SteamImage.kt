package org.steamlite

import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Falls back through [urls] on decode/network errors instead of showing a blank
 * box. Only renders the "retry" affordance when a description is provided.
 */
@Composable
fun SteamImage(urls: List<String>, description: String, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    FallbackImage(urls, description, modifier, scale)
}

@Composable
private fun FallbackImage(
    urls: List<String>,
    description: String,
    modifier: Modifier,
    scale: ContentScale,
    onSuccess: (String) -> Unit = {},
) {
    var index by remember(urls) { mutableIntStateOf(0) }
    Box(modifier, contentAlignment = Alignment.Center) {
        val url = urls.getOrNull(index)
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                contentScale = scale,
                onSuccess = { onSuccess(url) },
                onError = { if (urls.getOrNull(index) == url) index++ },
            )
        } else if (description.isNotBlank()) {
            Text(
                "图片未加载 · 点此重试",
                Modifier.clickable { index = 0 },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val coverPalettes = listOf(
    listOf(Color(0xFF2B2358), Color(0xFF4B3A7C), Color(0xFF1C4A5C)),
    listOf(Color(0xFF4A3A16), Color(0xFF8A6A22), Color(0xFF2A1C10)),
    listOf(Color(0xFF5C2020), Color(0xFFB5482C), Color(0xFF2B1414)),
    listOf(Color(0xFF1E3B2A), Color(0xFF4C7A38), Color(0xFF15281C)),
    listOf(Color(0xFF12212E), Color(0xFF2F6F86), Color(0xFF0D1720)),
    listOf(Color(0xFF2A2416), Color(0xFF8A7440), Color(0xFF1A1710)),
    listOf(Color(0xFF3A1E2E), Color(0xFF7A4A64), Color(0xFF241018))
)

/** Deterministic placeholder so a missing poster still looks intentional. */
fun coverBrush(id: Int): Brush = Brush.linearGradient(coverPalettes[id.mod(coverPalettes.size)])

/**
 * Poster first (the 600×900 capsule only exists on the legacy path and only for
 * older apps), then whatever capsule the API handed us, then the wide header.
 */
fun portraitUrls(id: Int, explicit: String? = null): List<String> = buildList {
    add("https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/$id/library_600x900.jpg")
    add("https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$id/library_600x900.jpg")
    explicit?.takeIf { it.isNotBlank() }?.let { add(it) }
    add("https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/$id/header.jpg")
    add("https://cdn.cloudflare.steamstatic.com/steam/apps/$id/header.jpg")
}.distinct()

// Keep the successful CDN choice when lazy rows leave composition. Coil owns the actual bitmap cache.
private val posterSources = LruCache<List<String>, String>(512)

@Composable
fun PosterCover(id: Int, image: String?, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val urls = remember(id, image) { portraitUrls(id, image) }
    val ordered = remember(urls) {
        val cached = posterSources.get(urls)
        if (cached == null || cached == urls.first()) urls else listOf(cached) + (urls - cached)
    }
    val placeholder = remember(id) { coverBrush(id) }
    Box(modifier.clip(shape).background(placeholder)) {
        FallbackImage(ordered, "", Modifier.fillMaxSize(), ContentScale.Crop) { url ->
            posterSources.put(urls, url)
        }
    }
}

@Composable
fun HeroImage(url: String, modifier: Modifier, fallbackId: Int? = null) {
    val shape = RoundedCornerShape(14.dp)
    val brush: Brush = if (fallbackId != null) coverBrush(fallbackId) else SolidColor(Surface3)
    Box(modifier.clip(shape).background(brush)) {
        if (url.isNotBlank()) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
