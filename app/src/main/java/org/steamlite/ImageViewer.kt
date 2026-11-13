package org.steamlite

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImageViewer(images: List<String>, initialUrl: String, close: () -> Unit) {
    if (images.isEmpty()) return
    val pager = rememberPagerState(images.indexOf(initialUrl).coerceAtLeast(0)) { images.size }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
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
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ImageTool(Glyph.Back, "返回", action = close)
                    Spacer(Modifier.weight(1f))
                    ImageTool(Glyph.Download, "下载当前图片", !saving && !pager.isScrollInProgress) {
                        val selectedUrl = images[pager.currentPage]
                        saving = true
                        status = "正在下载原图"
                        scope.launch {
                            try {
                                val (data, extension) = readImage(selectedUrl)
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
                HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                    val url = images[page]
                    var retry by remember(url) { mutableIntStateOf(0) }
                    var failed by remember(url) { mutableStateOf(false) }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        key(retry) {
                            val request = remember(url, context) {
                                steamArtworkRequest(context, url).size(2560).build()
                            }
                            AsyncImage(request, "图片 ${page + 1} / ${images.size}", Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit, onError = { failed = true })
                        }
                        if (failed) {
                            Text("图片加载失败，点此重试",
                                Modifier.clickable { failed = false; retry++ }.padding(16.dp),
                                color = Text2)
                        }
                    }
                }
                if (status.isNotBlank()) {
                    Text(status, Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
                        color = Text2, style = MaterialTheme.typography.labelSmall)
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
