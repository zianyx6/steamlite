package org.steamlite

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream

@Composable fun RichMessage(text: String) {
    val context = LocalContext.current
    var image by remember { mutableStateOf<String?>(null) }
    val parts = remember(text) { ChatContent.parse(text) }
    val inlines = remember(parts) { parts.filterIsInstance<ChatPart.Emote>().associate { e -> e.name to InlineTextContent(Placeholder(25.sp, 25.sp, PlaceholderVerticalAlign.Center)) {
        SteamImage(ChatContent.emoteUrls(e.name), "表情加载失败，点击重试", Modifier.fillMaxSize(), ContentScale.Fit)
    } } }
    val linkColor = AccentText
    val annotated = remember(parts, linkColor, context) { buildAnnotatedString {
        parts.forEach { part -> when(part) {
            is ChatPart.Plain -> append(part.text)
            is ChatPart.Emote -> appendInlineContent(part.name, ":${part.name}:")
            is ChatPart.Link -> withLink(LinkAnnotation.Url(part.url, TextLinkStyles(style = SpanStyle(color = linkColor)), LinkInteractionListener { openSteamLink(context, part.url) })) { append(part.label) }
            is ChatPart.Image -> append("\n")
        } }
    } }
    if(annotated.isNotBlank()) Text(annotated, inlineContent = inlines)
    parts.filterIsInstance<ChatPart.Image>().forEach { part ->
        var failed by remember(part.url) { mutableStateOf(false) }
        if(failed) Row { TextButton(onClick = { image = part.url }) { Text("查看原图") }; TextButton(onClick = { failed = false }) { Text("重试") } }
        else AsyncImage(part.url, "聊天图片，点击放大", Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp).clickable { image = part.url }, contentScale = ContentScale.Fit, onError = { failed = true })
    }
    image?.let { ImageViewer(it) { image = null } }
}

internal suspend fun readImage(url: String, baseClient: OkHttpClient = PublicImages.http): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
    val http = baseClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    var current = url
    repeat(5) {
        check(ChatContent.mediaUrl(current)) { "仅允许保存 Steam 图片地址" }
        http.newCall(Request.Builder().url(current).build()).execute().use { response ->
            if(response.code in 300..399) {
                current = response.request.url.resolve(response.header("Location").orEmpty())?.toString() ?: error("无效跳转")
            } else {
                check(response.isSuccessful) { "图片下载失败 (${response.code})" }
                val body = response.body ?: error("图片为空")
                val limit = 20 * 1024 * 1024
                check(body.contentLength() <= limit) { "图片超过 20 MB" }
                val bytes = ByteArrayOutputStream()
                body.byteStream().use { input -> val buffer = ByteArray(8192); var count = input.read(buffer)
                    while(count != -1) { check(bytes.size() + count <= limit) { "图片超过 20 MB" }; bytes.write(buffer, 0, count); count = input.read(buffer) }
                }
                val result = bytes.toByteArray()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(result, 0, result.size, bounds)
                check(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000) { "不支持的图片格式或图片尺寸过大" }
                val ext = when(bounds.outMimeType) { "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; "image/jpeg" -> "jpg"; else -> error("不支持保存此格式") }
                return@withContext result to ext
            }
        }
    }
    error("图片跳转次数过多")
}
