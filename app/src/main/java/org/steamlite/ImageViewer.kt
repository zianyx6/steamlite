package org.steamlite

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImageViewer(url: String, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var zoom by remember(url) { mutableFloatStateOf(1f) }
    var dx by remember(url) { mutableFloatStateOf(0f) }
    var dy by remember(url) { mutableFloatStateOf(0f) }
    var status by remember(url) { mutableStateOf("") }
    var saving by remember(url) { mutableStateOf(false) }
    var pendingBytes by remember(url) { mutableStateOf<ByteArray?>(null) }
    var retry by remember(url) { mutableIntStateOf(0) }
    var failed by remember(url) { mutableStateOf(false) }
    fun reset() {
        zoom = 1f
        dx = 0f
        dy = 0f
    }
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
        val data = pendingBytes
        pendingBytes = null
        if (uri == null || data == null) {
            saving = false
        } else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                        ?: error("无法写入")
                }
                status = "图片已保存"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "保存失败：${e.message}"
            } finally {
                saving = false
            }
        }
    }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().clipToBounds()
                        .pointerInput(url) {
                            detectTransformGestures { _, pan, factor, _ ->
                                zoom = (zoom * factor).coerceIn(1f, 6f)
                                if (zoom == 1f) reset() else {
                                    val maxX = size.width * (zoom - 1) / 2
                                    val maxY = size.height * (zoom - 1) / 2
                                    dx = (dx + pan.x).coerceIn(-maxX, maxX)
                                    dy = (dy + pan.y).coerceIn(-maxY, maxY)
                                }
                            }
                        }
                        .pointerInput(url) { detectTapGestures(onDoubleTap = { if (zoom > 1f) reset() else zoom = 2f }) },
                    contentAlignment = Alignment.Center
                ) {
                    key(retry) {
                        val request = remember(url, context) {
                            ImageRequest.Builder(context).data(url).size(2560).build()
                        }
                        AsyncImage(request, "查看图片", Modifier.fillMaxSize().graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = dx
                            translationY = dy
                        }, contentScale = ContentScale.Fit, onError = { failed = true; status = "图片加载失败" })
                    }
                }
                if (status.isNotBlank()) {
                    Text(status, Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
                        color = Text2, style = MaterialTheme.typography.labelSmall)
                }
                Surface(
                    Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 12.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = Surface1,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Line)
                ) {
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        ImageTool(Glyph.Close, "关闭", action = close)
                        ImageTool(Glyph.Minus, "缩小", zoom > 1f) {
                            zoom = (zoom / 1.5f).coerceAtLeast(1f)
                            dx = 0f
                            dy = 0f
                        }
                        ImageTool(Glyph.Plus, "放大", zoom < 6f) { zoom = (zoom * 1.5f).coerceAtMost(6f) }
                        ImageTool(if (failed) Glyph.Refresh else Glyph.Fit, if (failed) "重试" else "适应屏幕") {
                            reset()
                            if (failed) { failed = false; status = ""; retry++ }
                        }
                        ImageTool(Glyph.Download, "保存图片", !saving) {
                            saving = true
                            status = "正在下载原图"
                            scope.launch {
                                try {
                                    val (data, extension) = readImage(url)
                                    pendingBytes = data
                                    status = ""
                                    destination.launch("SteamLite-${System.currentTimeMillis()}.$extension")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    pendingBytes = null
                                    saving = false
                                    status = "保存失败：${e.message}"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageTool(glyph: Glyph, label: String, enabled: Boolean = true, action: () -> Unit) {
    IconButton(action, Modifier.semantics { contentDescription = label }, enabled = enabled) {
        SteamIcon(glyph, 21.dp, if (enabled) Text1 else Text3)
    }
}
