package org.steamlite

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold

internal val LocalRootTabActive = compositionLocalOf { true }

class LiteModel(app: Application) : AndroidViewModel(app) {
    val db = Room.databaseBuilder(app, LiteDatabase::class.java, "steam-lite.db").build()
    val notifications = ChatNotifications(app)
    val steam = SteamConnection(SessionVault(app), db.dao(), notifications)
    var openChatRequests by mutableIntStateOf(0)
    val store = StoreRepository(db.dao())
    val wishlist = WishlistRepository(steam, db.dao())
    val community = CommunityRepository(steam, viewModelScope)
    val activity = FriendActivityRepository(steam, viewModelScope)
    val imageUploader = ChatImageUploader(steam)
    var syncing by mutableStateOf(false); private set
    var syncStatus by mutableStateOf(""); private set
    private var lastSyncAttempt = 0L
    fun syncAll() {
        if (syncing || !steam.online.value) return
        if (System.currentTimeMillis() - lastSyncAttempt < 30_000) { syncStatus = "刚刚已同步，请稍后再试"; return }
        syncing = true; lastSyncAttempt = System.currentTimeMillis()
        val owner = steam.owner
        viewModelScope.launch {
            val failed = mutableListOf<String>()
            suspend fun step(name: String, block: suspend () -> Unit) {
                if (!steam.online.value || steam.owner != owner) throw kotlinx.coroutines.CancellationException("account changed")
                syncStatus = "正在同步$name"
                try { block() }
                catch(_: kotlinx.coroutines.TimeoutCancellationException) { failed += name }
                catch(e: kotlinx.coroutines.CancellationException) { throw e }
                catch(_: Exception) { failed += name }
                if (!steam.online.value || steam.owner != owner) throw kotlinx.coroutines.CancellationException("account changed")
            }
            try {
                step("好友资料") { steam.syncContacts() }
                step("会话") { steam.syncConversations() }
                step("群组") { community.syncGroups() }
                step("表情") { community.syncEmotes() }
                step("贴纸") { steam.syncStickers() }
                step("动态") { activity.syncNow() }
                step("愿望单") { wishlist.syncNow(); check(wishlist.error.value.isBlank()) }
                step("游戏库") { community.syncLibrary() }
                step("商店") { search(searchQuery, true); searchJob?.join(); check(error.isBlank()) }
                syncStatus = if (failed.isEmpty()) "全部数据已更新 · ${time(System.currentTimeMillis())}" else "同步结束，${failed.joinToString("、")}未完成，可稍后重试"
            } catch(_: kotlinx.coroutines.CancellationException) { syncStatus = "同步已中止，请连接 Steam 后重试" }
            finally { syncing = false }
        }
    }
    var games by mutableStateOf<List<Game>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf("")
    var info by mutableStateOf("尚未加载")
    var detail by mutableStateOf<JSONObject?>(null)
    var detailObservation by mutableStateOf<PriceObservation?>(null)
    var webCheck by mutableStateOf("")
    fun changeWishlist(appId: Int, add: Boolean) {
        if(wishlist.mutating.value) return
        viewModelScope.launch { wishlist.change(appId, add) }
    }
    fun checkWebSession() {
        if(webCheck == "正在检测") return
        webCheck = "正在检测"
        viewModelScope.launch {
            webCheck = runCatching { steam.probeStoreSession() }.getOrElse { "检测失败：${it.message ?: it.javaClass.simpleName}" }
        }
    }
    var searchOptions by mutableStateOf(StoreSearchOptions())
    var searchQuery by mutableStateOf(""); private set
    var totalResults by mutableIntStateOf(0); private set
    var hasMoreResults by mutableStateOf(false); private set
    var isFeatured by mutableStateOf(true); private set
    private var nextSearchStart = 0
    private var searchJob: kotlinx.coroutines.Job? = null
    fun search(query: String, refresh: Boolean = false) {
        searchJob?.cancel()
        if(searchQuery != query.trim()) games = emptyList()
        searchQuery = query.trim(); nextSearchStart = 0; hasMoreResults = false
        searchJob = viewModelScope.launch { loadStorePage(false, refresh) }
    }
    fun moreResults() { if(!loading && hasMoreResults) searchJob = viewModelScope.launch { loadStorePage(true, false) } }
    private suspend fun loadStorePage(append: Boolean, refresh: Boolean) {
        loading = true; error = ""
        try {
            if(searchQuery.isBlank() && searchOptions == StoreSearchOptions()) {
                val result = store.featured(refresh); games = result.games; totalResults = games.size; hasMoreResults = false
                isFeatured = true
                info = "${if(result.cached) "缓存（可能离线）" else "Steam 实时响应"} · ${time(result.time)}"
            } else {
                val page = store.searchPage(searchQuery, if(append) nextSearchStart else 0, searchOptions, refresh)
                isFeatured = false
                games = (if(append) games + page.games else page.games).distinctBy { it.id }
                totalResults = page.total; nextSearchStart = page.nextStart; hasMoreResults = nextSearchStart < page.total
                info = "${if(page.cached) "缓存（可能离线）" else "Steam 搜索响应"} · 已加载 ${games.size} / 服务端 ${page.total} 条 · ${time(page.time)}"
            }
        } catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: Exception) { error = "加载失败：${e.message}" }
        finally { loading = false }
    }
    private var detailJob: kotlinx.coroutines.Job? = null
    fun details(id: Int) { detailJob?.cancel(); detailJob = viewModelScope.launch {
        detailObservation = null
        try { val data = store.details(id); val observed = store.observations.summary(id); detailObservation = observed; detail = data }
        catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: Exception) { error = "详情失败：${e.message}" }
    } }
    override fun onCleared() { steam.close(); db.close() }
}

fun time(value: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(value))

class MainActivity : ComponentActivity() {
    private val model get() = (application as LiteApplication).model
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PublicImages.install(this)
        if(intent.getBooleanExtra("open_chat", false)) model.openChatRequests++
        setContent { SteamTheme { LiteApp(model) } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); if(intent.getBooleanExtra("open_chat", false)) model.openChatRequests++ }
    override fun onResume() { super.onResume(); model.notifications.appVisible = true }
    override fun onPause() { model.notifications.appVisible = false; super.onPause() }
    override fun onStop() {
        if(model.steam.voice.state.value.phase in setOf("preparing", "ringing", "connecting", "connected")) model.steam.voice.hangup()
        super.onStop()
    }
}

@Composable
fun LiteApp(model: LiteModel = viewModel()) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var visitedTabs by remember { mutableIntStateOf(1 shl tab) }
    val pageStates = rememberSaveableStateHolder()
    var peerId by rememberSaveable { mutableStateOf<Long?>(null) }
    var statusSheet by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val online by model.steam.online.collectAsState()
    fun open(url: String) { openSteamLink(context, url) }
    fun selectTab(index: Int) {
        if (index == tab) return
        peerId = null
        visitedTabs = visitedTabs or (1 shl index)
        tab = index
    }

    LaunchedEffect(online) { if (online) context.startForegroundService(Intent(context, ConnectionService::class.java)) }
    LaunchedEffect(online) { if (!online && model.steam.owner.isBlank()) model.wishlist.clearVisible() }

    val inChat by remember { derivedStateOf { tab == 2 && peerId != null } }

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        MiuixScaffold(
            containerColor = Bg,
        ) { padding ->
            val density = LocalDensity.current
            val ime = WindowInsets.ime
            val imeVisible by remember(ime, density, padding) {
                derivedStateOf { ime.getBottom(density) > with(density) { padding.calculateBottomPadding().roundToPx() } }
            }
            val showNavigation = !inChat && !imeVisible
            val backdrop = rememberNavigationBackdrop()
            val capture = showNavigation && isRuntimeShaderSupported()
            val background = Bg
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                val pageLayer = if (capture) backdrop.capture else Modifier
                Column(Modifier.fillMaxSize().then(pageLayer).background(background)) {
                    if (!online) {
                        val status by model.steam.status.collectAsState()
                        OfflineBar(status) { statusSheet = true }
                    }
                    val pages = remember(visitedTabs) { (0..4).filter { visitedTabs and (1 shl it) != 0 } }
                    Layout(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        content = {
                            pages.forEach { index ->
                                key(index) {
                                    pageStates.SaveableStateProvider(index) {
                                        val active by remember { derivedStateOf { index == tab } }
                                        val pagePadding = if (active && !showNavigation) 0.dp else FloatingNavigationSpace
                                        CompositionLocalProvider(
                                            LocalRootTabActive provides active,
                                            LocalFloatingNavigationPadding provides pagePadding,
                                        ) {
                                            Box(Modifier.fillMaxSize()) {
                                                when (index) {
                                                    0 -> StoreScreen(model) { selectTab(1) }
                                                    1 -> WishlistScreen(model)
                                                    2 -> ChatHubScreen(model, peerId) { peerId = it }
                                                    4 -> FriendActivityScreen(model)
                                                    else -> MeScreen(model) { statusSheet = true }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    ) { measurables, constraints ->
                        // Visited pages retain their composition, but only the selected page is laid out.
                        val page = measurables[pages.indexOf(tab)].measure(constraints)
                        layout(page.width, page.height) { page.placeRelative(0, 0) }
                    }
                }
                if (showNavigation) {
                    SteamBottomBar(tab, model.steam.recentSessions, if (capture) backdrop else null, Modifier.align(Alignment.BottomCenter), ::selectTab)
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (showNavigation) FloatingNavigationSpace else 0.dp)) {
                    SteamSnackbar(it)
                }
            }
        }
    }

    VoiceCallLayer(model)
    SteamLoginDialogs(model.steam)
    GameDetailsDialog(model, ::open)

    if (statusSheet) {
        ConnectionSheet(model) { statusSheet = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionSheet(model: LiteModel, onClose: () -> Unit) {
    val status by model.steam.status.collectAsState()
    val online by model.steam.online.collectAsState()
    val busy by model.steam.busy.collectAsState()
    val owner = model.steam.owner

    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = Surface1,
        scrimColor = SheetScrim,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = { SheetHandle() }
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("连接状态", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Text1)
                    Spacer(Modifier.height(4.dp))
                    Text("手机直连 Steam，凭证不经过第三方服务器", fontSize = 11.5.sp, color = Text3)
                }
                SheetClose(onClose)
            }

            Spacer(Modifier.height(16.dp))
            Hairline()

            Column(Modifier.fillMaxWidth()) {
                ConnectionRow("连接", if (online) "已连接" else if (busy) "连接中" else "未连接", online)
                ConnectionRow("账号", owner.ifBlank { "未登录" }, false)
                ConnectionRow("商店地区", "PK · 待核实", false)
                ConnectionRow("凭证存储", "Android Keystore（本机）", false)
            }

            Spacer(Modifier.height(14.dp))
            StatusBanner(status, if (online) Tone.Info else Tone.Warn)

            Spacer(Modifier.height(18.dp))
            AccentButton(
                "检测 Steam 连接",
                { model.steam.probeConnection() },
                Modifier.fillMaxWidth(),
                enabled = !busy,
                glyph = Glyph.Refresh
            )
            Spacer(Modifier.height(10.dp))
            GhostButton(
                "退出登录",
                { model.steam.logout(); onClose() },
                Modifier.fillMaxWidth(),
                tint = DangerText
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConnectionRow(key: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, Modifier.weight(1f), fontSize = 12.5.sp, color = Text3)
        Text(
            value,
            fontSize = 12.5.sp,
            fontWeight = if (ok) FontWeight.Bold else FontWeight.Medium,
            color = if (ok) Money else Text1,
            maxLines = 1
        )
    }
    Hairline()
}
