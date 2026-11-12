package org.steamlite

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Older builds stored a Boolean under a similar key; never trust the stored type. */
private fun readStringSet(prefs: android.content.SharedPreferences, key: String): Set<String> =
    runCatching { prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet() }.getOrDefault(emptySet())

private fun presenceOf(friend: FriendRow): Presence = when {
    friend.playing -> Presence.Playing
    friend.state.contains("离开") || friend.state.contains("闲置") || friend.state.contains("休息") ||
        friend.state.contains("Away") -> Presence.Away
    friend.available -> Presence.Online
    else -> Presence.Offline
}

private fun dayLabel(value: Long): String {
    val key = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val now = System.currentTimeMillis()
    val day = key.format(Date(value))
    return when (day) {
        key.format(Date(now)) -> "今天"
        key.format(Date(now - 86_400_000L)) -> "昨天"
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(value))
    }
}

// ============================================================== hub

@Composable
fun ChatHubScreen(model: LiteModel, openPeerId: Long?, onOpenPeer: (Long?) -> Unit) {
    val people by model.steam.people.collectAsState()
    val online by model.steam.online.collectAsState()
    val owner = model.steam.owner

    var page by rememberSaveable(owner) { mutableIntStateOf(1) }
    val pageStates = key(owner) { rememberSaveableStateHolder() }
    val swipeMenus = remember(owner) { FriendSwipeState() }
    val active = LocalRootTabActive.current
    LaunchedEffect(active, page) { swipeMenus.clear() }
    var search by rememberSaveable(owner) { mutableStateOf("") }

    val peer = openPeerId?.let { id -> people.firstOrNull { it.id == id } ?: FriendRow(id, id.toString(), "", null) }
    if (peer != null) {
        ConversationScreen(model, peer) { onOpenPeer(null) }
        return
    }

    val refreshScope = rememberCoroutineScope()
    var refreshingHub by remember { mutableStateOf(false) }
    val unread = model.steam.recentSessions.collectAsState().value.sumOf { it.unread }

    RefreshContainer(refreshingHub, {
        if (online && !refreshingHub) refreshScope.launch {
            refreshingHub = true
            try { model.steam.syncContacts(); model.steam.syncConversations(); model.community.syncGroups() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Existing repository status exposes refresh failures. */ }
            finally { refreshingHub = false }
        }
    }) { Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            PageHeader("消息")
            MessageTabs(listOf("好友", "会话", "群组"), page, { page = it }, unread)
            Spacer(Modifier.height(12.dp))
            SearchField(
                value = search,
                onValueChange = { search = it },
                placeholder = when (page) {
                    0 -> "搜索好友名称或状态"
                    1 -> "搜索会话"
                    else -> "搜索群组"
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
        }

        if (!online) {
            Box(Modifier.padding(horizontal = 16.dp)) {
                StatusBanner("离线 · 登录后同步 Steam 好友与消息", Tone.Warn)
            }
        }

        Box(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalFriendSwipeState provides swipeMenus) {
                pageStates.SaveableStateProvider(page) {
                    when (page) {
                        0 -> FriendsPage(model, people, search, online) { onOpenPeer(it) }
                        1 -> ConversationsPage(model, people, search, online) { onOpenPeer(it) }
                        else -> GroupsPage(model, online, search)
                    }
                }
            }
        }
    }
}

}
@Composable
private fun FriendsPage(
    model: LiteModel,
    people: List<FriendRow>,
    search: String,
    online: Boolean,
    onOpen: (Long) -> Unit
) {
    val context = LocalContext.current
    val owner = model.steam.owner
    val prefs = remember { context.getSharedPreferences("friend-layout", Context.MODE_PRIVATE) }
    val pins by model.steam.pinnedFriends.collectAsState()
    val pinStatus by model.steam.favoritesStatus.collectAsState()
    val active = LocalRootTabActive.current
    LaunchedEffect(active, owner, online) { if(active && online) { while(true) { runCatching { model.steam.refreshFavorites() }; runCatching { model.steam.refreshFriendRemarks() }; delay(30000) } } }
    var collapsed by remember(owner) { mutableStateOf(readStringSet(prefs, "folded:$owner")) }

    fun toggleSection(key: String) {
        collapsed = if (key in collapsed) collapsed - key else collapsed + key
        prefs.edit().putStringSet("folded:$owner", collapsed).apply()
    }

    val sections = remember(people, search, pins) {
        val filtered = people.filter { it.name.contains(search, true) || it.remark.contains(search, true) || it.state.contains(search, true) }
        val (pinned, rest) = filtered.partition { it.id.toString() in pins }
        val playing = rest.filter { it.playing }
        val away = rest.filter { !it.playing && presenceOf(it) == Presence.Away }
        val idle = rest.filter { !it.playing && it.available && presenceOf(it) != Presence.Away }
        val offline = rest.filter { !it.available && !it.playing }

        val byGame = playing.groupBy(::friendGameKey)
        val soloPlayers = byGame.values.filter { it.size == 1 }.flatten().sortedBy { it.name.lowercase() }
        val games = byGame.filterValues { it.size >= 2 }
            .map { (gameKey, rows) -> Triple(rows.first().game.ifBlank { "游戏名称暂未获取" }, rows, "play:$gameKey") }
            .sortedWith(compareByDescending<Triple<String, List<FriendRow>, String>> { it.second.size }.thenBy { it.first.lowercase() })
        (listOf(Triple("置顶", pinned, "pin")) + games + listOf(
            Triple("其他游戏", soloPlayers, "solo"),
            Triple("在线", idle, "on"),
            Triple("暂离", away, "away"),
            Triple("离线", offline, "off")
        )).filter { it.second.isNotEmpty() }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp + LocalFloatingNavigationPadding.current)) {
        if(pinStatus.contains("失败") || pinStatus.contains("未确认")) item { MetaLine(pinStatus, Glyph.Info) }
        if (sections.isEmpty()) {
            item {
                EmptyState(
                    glyph = if (people.isEmpty()) Glyph.Users else Glyph.Search,
                    title = if (people.isEmpty()) "好友列表尚未同步" else "没有匹配的好友",
                    body = if (people.isEmpty()) "登录 Steam 后会自动同步好友与在线状态。" else "换个名字或状态关键词试试。"
                )
            }
        }
        sections.forEach { (label, rows, key) ->
            val open = key !in collapsed
            item(key = "hdr:$key") {
                FriendSectionHeader("$label · ${rows.size}", open) { toggleSection(key) }
            }
            if (open) {
                items(rows, key = { "f:$key:${it.id}" }) { friend ->
                    FriendSwipeRow(model, friend, friend.id.toString() in pins, online) {
                        FriendRowItem(friend, onOpen)
                    }
                }
            }
        }
    }

}

@Composable
private fun FriendRowItem(friend: FriendRow, onOpen: (Long) -> Unit) {
    PersonRow(
        name = friend.name,
        remark = friend.remark,
        subtitle = friend.state,
        initial = friend.name,
        avatarUrl = friend.avatar,
        presence = presenceOf(friend),
        onClick = { onOpen(friend.id) },
        subtitleContent = { FriendStateLine(friend) },
        leading = if(friend.playing && friend.gameIcon != null) { { SteamImage(listOf(friend.gameIcon), "", Modifier.size(25.dp).clip(RoundedCornerShape(5.dp))) } } else null
    )
}

/**
 * Two different facts — what they are doing and whether they are reachable —
 * used to be crammed into one grey string. Show the game when there is one.
 */
@Composable
private fun FriendStateLine(friend: FriendRow) {
    if (friend.playing && friend.game.isNotBlank()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("正在玩", fontSize = 11.5.sp, color = Text3)
            Spacer(Modifier.width(5.dp))
            Text(
                friend.game,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = Text1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        val color = when {
            !friend.available -> Text3
            friend.state.contains("离开") || friend.state.contains("休息") -> WarnText
            friend.state.contains("忙碌") -> DangerText
            else -> Text2
        }
        Text(
            friend.state.ifBlank { if (friend.available) "在线" else "离线" },
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 1
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Text3)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Line))
    }
}

@Composable
private fun FriendSectionHeader(text: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SteamIcon(if (expanded) Glyph.ChevronDown else Glyph.ChevronRight, 12.dp, Text3, 2.2f)
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Text3)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Line))
    }
}

@Composable
private fun ConversationsPage(
    model: LiteModel,
    people: List<FriendRow>,
    search: String,
    online: Boolean,
    onOpen: (Long) -> Unit
) {
    val owner = model.steam.owner
    val flow = remember(owner) { model.db.dao().conversations(owner) }
    val conversations by flow.collectAsState(initial = emptyList())
    val sessions by model.steam.recentSessions.collectAsState()
    val pins by model.steam.pinnedFriends.collectAsState()
    val status by model.steam.recentStatus.collectAsState()
    val active = LocalRootTabActive.current
    LaunchedEffect(active, online) { if (active && online) model.steam.refreshRecentSessions() }

    val local = remember(conversations) { conversations.associateBy { it.peer } }
    val peopleById = remember(people) { people.associateBy { it.id } }
    val recent = remember(sessions, conversations, local, peopleById, search) {
        (sessions + conversations.map { RecentSession(it.peer, it.time, 0) })
            .groupBy { it.peer }
            .map { (_, rows) -> rows.maxBy { it.time } }
            .sortedByDescending { it.time }
            .filter { session ->
                val friend = peopleById[session.peer]
                search.isBlank() ||
                    friend?.name?.contains(search, true) == true ||
                    friend?.remark?.contains(search, true) == true ||
                    session.peer.toString().contains(search) ||
                    local[session.peer]?.text?.contains(search, true) == true
            }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp + LocalFloatingNavigationPadding.current)) {
        if (status.isNotEmpty() && (status.contains("失败") || status.contains("正在"))) {
            item {
                StatusBanner(
                    status,
                    if (status.contains("失败")) Tone.Warn else Tone.Info,
                )
            }
        }
        if (recent.isEmpty()) {
            item {
                EmptyState(
                    glyph = Glyph.Chat,
                    title = if (online) "还没有会话" else "登录后查看会话",
                    body = if (online) "打开好友资料即可开始同步最近消息。" else "Steam 好友的私聊会显示在这里。"
                )
            }
        }
        items(recent, key = { it.peer }) { session ->
            val message = local[session.peer]
            val friend = peopleById[session.peer]
                ?: FriendRow(session.peer, session.peer.toString(), "", null)
            val preview = if (message != null && message.time >= session.time) {
                ChatContent.preview(message.text).ifBlank { "[贴纸]" }
            } else {
                "点击同步最近消息"
            }
            FriendSwipeRow(model, friend, friend.id.toString() in pins, online) {
            PersonRow(
                name = friend.name,
                remark = friend.remark,
                subtitle = preview,
                initial = friend.name,
                avatarUrl = friend.avatar,
                presence = presenceOf(friend),
                onClick = { onOpen(friend.id) },
                end = {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(time(session.time), fontSize = 10.5.sp, color = Text3)
                        if (session.unread > 0) {
                            Box(
                                Modifier.defaultMinSize(minWidth = 17.dp, minHeight = 17.dp)
                                    .clip(CircleShape).background(Accent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    session.unread.toString(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = OnAccent,
                                    modifier = Modifier.padding(horizontal = 5.dp)
                                )
                            }
                        }
                    }
                }
            )
            }
        }
    }
}

// ============================================================== conversation

@Composable
fun ConversationScreen(model: LiteModel, friend: FriendRow, back: () -> Unit) {
    BackHandler(onBack = back)
    val online by model.steam.online.collectAsState()
    val historyStatus by model.steam.historyStatus.collectAsState()
    val more by model.steam.hasMoreHistory.collectAsState()
    val loading by model.steam.historyLoading.collectAsState()

    var messageLimit by remember(friend.id) { mutableIntStateOf(500) }
    LaunchedEffect(friend.id, online) { if (online) model.steam.loadHistory(friend.id) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(friend.id, online, lifecycle) {
        if (online) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.steam.enterConversation(friend.id)
            try {
            while (true) {
                delay(15000)
                try {
                    model.steam.syncRecentMessages(friend.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep local messages; next foreground cycle retries.
                }
            }
            } finally { model.steam.leaveConversation(friend.id) }
        }
    }

    val flow = remember(friend.id, model.steam.owner, messageLimit) { model.db.dao().messages(model.steam.owner, friend.id, messageLimit) }
    val messages by flow.collectAsState(initial = emptyList())
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        ChatTopBar(
            title = friend.name,
            subtitle = if (friend.playing && friend.game.isNotBlank()) "正在玩 ${friend.game}"
            else friend.state.ifBlank { if (friend.available) "在线" else "离线" },
            initial = friend.name,
            avatarUrl = friend.avatar,
            presence = presenceOf(friend),
            back = back
        )

        if (historyStatus.contains("失败")) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    historyStatus,
                    fontSize = 10.5.sp,
                    color = if (historyStatus.contains("失败")) WarnText else Text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (historyStatus.contains("失败")) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "重试",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Accent,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { model.steam.loadHistory(friend.id) }.padding(2.dp)
                    )
                }
            }
        }

        if (more && messageLimit < 2000) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (loading) "正在加载更早消息" else "加载更早消息",
                    fontSize = 11.5.sp,
                    color = Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = online && !loading) {
                            messageLimit += 100
                            model.steam.loadHistory(friend.id, true)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        val bubbles = remember(messages) {
            messages.map { m -> BubbleData(m.id, m.text, m.outgoing, m.time, null, m.status) }
        }

        if (bubbles.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有消息记录", fontSize = 13.sp, color = Text3)
                    Spacer(Modifier.height(4.dp))
                    Text("发送一条消息开始聊天", fontSize = 11.5.sp, color = Text3)
                }
            }
        } else {
            ChatBubbleList(bubbles, Modifier.weight(1f).fillMaxWidth(), listState)
        }

        MessageComposer(model, "friend:${friend.id}", online) { text, accepted ->
            scrollScope.launch { listState.scrollToItem(0) }
            model.steam.send(friend.id, text)
            accepted()
        }
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    subtitle: String,
    initial: String,
    avatarUrl: String?,
    presence: Presence,
    back: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = back),
            contentAlignment = Alignment.Center
        ) { SteamIcon(Glyph.Back, 20.dp, Text2, 2f) }
        PersonAvatar(initial, avatarUrl, null, 34.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                fontSize = 10.5.sp,
                color = if (presence == Presence.Offline) Text3 else Money,
                maxLines = 1
            )
        }
    }
    Hairline()
}

private data class BubbleData(
    val id: String,
    val text: String,
    val outgoing: Boolean,
    val time: Long,
    val sender: String?,
    val status: String = ""
)

private data class BubbleRow(
    val data: BubbleData,
    val first: Boolean,
    val last: Boolean,
    val day: String?
)

@Composable
private fun ChatBubbleList(items: List<BubbleData>, modifier: Modifier, listState: androidx.compose.foundation.lazy.LazyListState) {
    LaunchedEffect(items.lastOrNull()?.id) {
        if (items.lastOrNull()?.outgoing == true || listState.firstVisibleItemIndex == 0) listState.scrollToItem(0)
    }
    val rows = remember(items) {
        var lastDay: String? = null
        items.mapIndexed { index, item ->
            val prev = items.getOrNull(index - 1)
            val next = items.getOrNull(index + 1)
            val samePrev = prev != null && prev.outgoing == item.outgoing && prev.sender == item.sender &&
                (item.time - prev.time) in 0L..240_000L
            val sameNext = next != null && next.outgoing == item.outgoing && next.sender == item.sender &&
                (next.time - item.time) in 0L..240_000L
            val label = dayLabel(item.time)
            val showDay = label != lastDay
            lastDay = label
            BubbleRow(item, !samePrev, !sameNext, if (showDay) label else null)
        }.asReversed()
    }

    LazyColumn(
        modifier,
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        items(rows, key = { it.data.id }) { row ->
            Column(Modifier.fillMaxWidth()) {
                if (row.day != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.weight(1f).height(1.dp).background(Line))
                        Text(row.day, Modifier.padding(horizontal = 10.dp), fontSize = 10.5.sp, color = Text3)
                        Box(Modifier.weight(1f).height(1.dp).background(Line))
                    }
                }
                ChatBubble(
                    outgoing = row.data.outgoing,
                    text = row.data.text,
                    footer = if (row.data.outgoing && (row.data.status == "发送中" || row.data.status.contains("失败") || row.data.status.contains("未知"))) "${clock(row.data.time)} · ${row.data.status}" else if (row.last) clock(row.data.time) else null,
                    sender = if (!row.data.outgoing) row.data.sender else null,
                    first = row.first
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(
    outgoing: Boolean,
    text: String,
    footer: String?,
    sender: String?,
    first: Boolean
) {
    val tail = 6.dp
    val round = 17.dp
    val shape = RoundedCornerShape(
        topStart = if (!outgoing && first) tail else round,
        topEnd = if (outgoing && first) tail else round,
        bottomStart = round,
        bottomEnd = round
    )
    Row(
        Modifier.fillMaxWidth().padding(top = if (first) 8.dp else 3.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            Modifier
                .widthIn(max = 292.dp)
                .clip(shape)
                .background(if (outgoing) AccentSoft else Surface2)
                .border(1.dp, if (outgoing) AccentLine else Line, shape)
                .padding(horizontal = 13.dp, vertical = 9.dp)
        ) {
            if (!sender.isNullOrBlank()) {
                Text(sender, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = AccentText, maxLines = 1)
                Spacer(Modifier.height(3.dp))
            }
            CompositionLocalProvider(
                LocalContentColor provides Text1
            ) {
                RichMessage(text)
            }
            if (footer != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    footer,
                    fontSize = 10.sp,
                    color = if (footer.contains("失败") || footer.contains("未知")) DangerText else Text3,
                    modifier = Modifier.align(if (outgoing) Alignment.End else Alignment.Start)
                )
            }
        }
    }
}

// ============================================================== composer

/**
 * Renders the draft as it will be sent, sized for the input line rather than for
 * a chat bubble: emotes use an 18sp placeholder so they sit inside the pill
 * instead of being clipped by its line height.
 */
/**
 * Renders the draft as it will be sent. Kept for the group/emoji panel preview.
 */
@Composable
private fun DraftPreview(text: String) {
    val parts = remember(text) { ChatContent.parse(text).take(24) }
    // No horizontalScroll here: a scrollable row collapses inside the text pill.
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        parts.forEach { part ->
            when (part) {
                is ChatPart.Emote -> AsyncImage(
                    model = ChatContent.emoteUrl(part.name),
                    contentDescription = ":" + part.name + ":",
                    modifier = Modifier.size(18.dp).padding(end = 2.dp)
                )
                is ChatPart.Plain -> Text(
                    part.text,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Text1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                is ChatPart.Link -> Text(
                    part.label,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = AccentText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                is ChatPart.Image -> Text("[图片]", fontSize = 13.sp, color = Text3, maxLines = 1)
            }
        }
    }
}

@Composable
fun MessageComposer(model: LiteModel, key: String, enabled: Boolean, send: (String, () -> Unit) -> Unit) {
    var draft by rememberSaveable(key) { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val emotes by model.community.emoticons.collectAsState()
    val stickers by model.steam.stickers.collectAsState()
    var stickerPage by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if(enabled) model.steam.refreshStickers() }
    val status by model.community.emoteStatus.collectAsState()
    LaunchedEffect(enabled) { if (enabled && emotes.isEmpty()) model.community.refreshEmotes() }

    val focusRequester = remember { FocusRequester() }
    val inputHandle = remember { EmoteInputHandle() }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            val shape = RoundedCornerShape(21.dp)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 41.dp)
                    .clip(shape)
                    .background(Surface2)
                    .border(1.dp, Line, shape)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth()) {
                        if (draft.isEmpty()) Text("发送消息", fontSize = 13.5.sp, color = Text3)
                        EmoteInput(
                            value = draft,
                            changed = { draft = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            handle = inputHandle
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            Box(Modifier.size(41.dp).clip(CircleShape).background(if(picker) AccentSoft else Surface3)
                .semantics { contentDescription = "表情与贴纸" }
                .clickable { focusManager.clearFocus(); keyboard?.hide(); attachments = false; picker = !picker }, contentAlignment = Alignment.Center) {
                SteamIcon(Glyph.Emoji, 23.dp, if(picker) Accent else Text2)
            }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(41.dp).clip(CircleShape).background(if(attachments) AccentSoft else Surface3)
                .semantics { contentDescription = "更多功能" }
                .clickable { focusManager.clearFocus(); keyboard?.hide(); picker = false; attachments = !attachments }, contentAlignment = Alignment.Center) {
                SteamIcon(if(attachments) Glyph.Close else Glyph.Plus, 22.dp, if(attachments) Accent else Text2)
            }
            if(draft.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(41.dp).clip(CircleShape).background(if(enabled) Accent else Surface3)
                    .semantics { contentDescription = "发送消息" }
                    .clickable(enabled = enabled) { val submitted = draft; send(submitted) { if(draft == submitted) draft = "" } }, contentAlignment = Alignment.Center) {
                    SteamIcon(Glyph.Send, 19.dp, if(enabled) OnAccent else Text3)
                }
            }
        }
        if (picker) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PickerTab("表情", emotes.size, !stickerPage) { stickerPage = false }
                    Spacer(Modifier.width(6.dp))
                    PickerTab("贴纸", stickers.size, stickerPage) { stickerPage = true }
                    Spacer(Modifier.weight(1f))
                }

                val cells = if (stickerPage) stickers else emotes
                val cell = if (stickerPage) 66.dp else 44.dp
                if (cells.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(132.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            SteamIcon(if (stickerPage) Glyph.Emoji else Glyph.Emoji, 24.dp, Text3, 1.5f)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (stickerPage) "还没有可用贴纸" else "还没有同步表情",
                                fontSize = 12.sp,
                                color = Text2
                            )
                            Spacer(Modifier.height(2.dp))
                            Text("可在“我的”页同步全部数据", fontSize = 10.5.sp, color = Text3)
                        }
                    }
                } else {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(cell),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp),
                        contentPadding = PaddingValues(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(cells.size) { index ->
                            val name = cells[index]
                            val url = if (stickerPage) ChatContent.stickerUrl(name) else ChatContent.emoteUrl(name)
                            Box(
                                Modifier.size(cell).clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (stickerPage) {
                                            // A sticker is a standalone message: send on tap.
                                            if (enabled) {
                                                send(ChatContent.stickerCode(name)) { }
                                                picker = false
                                            }
                                        } else {
                                            inputHandle.insert("ː${name}ː")
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = url,
                                    contentDescription = ":$name:",
                                    modifier = Modifier.fillMaxSize().padding(if (stickerPage) 5.dp else 7.dp)
                                )
                            }
                        }
                    }
                }

                Text(
                    if (stickerPage) "${stickers.size} 个贴纸 · 点按直接发送"
                    else if (status.isNotBlank()) status
                    else "${emotes.size} 个表情 · 点按插入到光标处",
                    fontSize = 9.5.sp,
                    color = Text3,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                )
            }
        }
        if(attachments) {
            Hairline()
            Row(Modifier.fillMaxWidth().background(Surface1).padding(horizontal = 20.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ImageAttachmentButton(model, key, enabled) { open -> AttachmentTile("图片", Glyph.Image, enabled, onClick = open) }
                val peer = key.takeIf { it.startsWith("friend:") }?.substringAfter(':')?.toLongOrNull()
                AttachmentTile("语音通话", Glyph.Phone, enabled && peer != null, if(peer == null) "仅私聊" else null) { peer?.let { model.steam.voice.request(it) } }
            }
        }
    }
}

@Composable
private fun PickerTab(label: String, count: Int, active: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(
        targetValue = if (active) AccentSoft else Color.Transparent,
        label = "picker-tab-bg"
    )
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(background).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (active) AccentText else Text2
        )
        Spacer(Modifier.width(5.dp))
        Text(
            count.toString(),
            fontSize = 10.sp,
            color = if (active) AccentText else Text3
        )
    }
}

@Composable
private fun AttachmentTile(label: String, glyph: Glyph, enabled: Boolean, note: String? = null, onClick: () -> Unit) {
    Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Surface2)
            .border(1.dp, Line, RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
            SteamIcon(glyph, 29.dp, if(enabled) Text1 else Text3)
        }
        Spacer(Modifier.height(7.dp))
        Text(label, fontSize = 11.5.sp, color = if(enabled) Text2 else Text3)
        if(note != null) Text(note, fontSize = 9.sp, color = Text3)
    }
}

// ============================================================== groups

@Composable
private fun GroupsPage(model: LiteModel, online: Boolean, search: String) {
    val repo = model.community
    val groups by repo.groups.collectAsState()
    val people by model.steam.people.collectAsState()
    val status by repo.groupStatus.collectAsState()
    var groupId by rememberSaveable(model.steam.owner) { mutableStateOf<Long?>(null) }
    var channelId by rememberSaveable(model.steam.owner) { mutableStateOf<Long?>(null) }
    val active = LocalRootTabActive.current
    LaunchedEffect(active, online) { if (active && online) repo.refreshGroups() }
    DisposableEffect(active) { onDispose { if (active) repo.leaveChannel() } }

    val group = groups.firstOrNull { it.id == groupId }
    val failed = status.contains("失败")

    Row(Modifier.fillMaxSize()) {
        if(groups.isNotEmpty()) LazyColumn(
            Modifier.width(68.dp).fillMaxHeight().background(Surface1),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 10.dp + LocalFloatingNavigationPadding.current),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if(groupId == null) AccentSoft else Surface2)
                    .clickable { groupId = null; channelId = null }, contentAlignment = Alignment.Center) {
                    SteamIcon(Glyph.Users, 24.dp, if(groupId == null) Accent else Text3)
                }
                Spacer(Modifier.height(10.dp)); Hairline()
            }
            items(groups, key = { "server:${it.id}" }) { server ->
                val corner by androidx.compose.animation.core.animateDpAsState(if(server.id == groupId) 13.dp else 24.dp, label = "group selection")
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(corner))
                    .background(if(server.id == groupId) AccentSoft else Surface2)
                    .border(1.dp, if(server.id == groupId) Accent else Line, RoundedCornerShape(if(server.id == groupId) 13.dp else 24.dp))
                    .clickable { groupId = server.id; channelId = null }, contentAlignment = Alignment.Center) {
                    GroupAvatar(server, 44.dp, people)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
    when {
        group != null -> {
            val channel = group.channels.firstOrNull { it.id == channelId } ?: group.channels.firstOrNull()
            GroupChatView(model, group, channel, online, { channelId = it }, { groupId = null; channelId = null })
        }
        else -> {
            val visible = remember(groups, search) { groups.filter { search.isBlank() || it.name.contains(search, true) } }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp + LocalFloatingNavigationPadding.current)) {
                if (status.isNotEmpty() && (failed || status.contains("正在"))) {
                    item {
                        StatusBanner(status, if (failed) Tone.Warn else Tone.Info)
                    }
                }
                if (visible.isEmpty()) {
                    item {
                        if (failed) {
                            EmptyState(
                                glyph = Glyph.Alert,
                                title = "群组读取失败",
                                body = "同步群组时出错了，可能是网络波动或 Steam 服务暂时不可用。",
                                tone = Tone.Warn,
                                action = null
                            )
                        } else if (status.contains("正在") && online) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                androidx.compose.material3.CircularProgressIndicator(Modifier.size(26.dp), color = Accent, strokeWidth = 2.dp)
                                Text("正在加载群组", fontSize = 12.sp, color = Text3, modifier = Modifier.padding(top = 14.dp))
                            }
                        } else {
                            EmptyState(
                                glyph = Glyph.Users,
                                title = if (!online) "请先连接 Steam" else if (search.isNotBlank()) "没有匹配的群组" else "还没有加入群组",
                                body = if (search.isNotBlank() && online) "换个关键词再试试。" else "连接 Steam 后，你加入的群组和文字频道会显示在这里。",
                                action = null
                            )
                        }
                    }
                }
                items(visible, key = { it.id }) { g ->
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(Surface1).border(1.dp, Line, RoundedCornerShape(16.dp))) {
                        Row(
                            Modifier.fillMaxWidth().clickable { groupId = g.id }.padding(horizontal = 12.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GroupAvatar(g, 48.dp, people)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(g.name.ifBlank { "未命名群组" }, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(2.dp))
                                Text("${g.members} 位活跃成员 · ${g.channels.size} 个频道", fontSize = 11.5.sp, color = Text3)
                            }
                            SteamIcon(Glyph.ChevronRight, 15.dp, Text3, 2f)
                        }
                    }
                }
            }
        }
    }        }
    }

}

@Composable
private fun GroupAvatar(group: SteamGroup, size: androidx.compose.ui.unit.Dp, people: List<FriendRow> = emptyList()) {
    val memberImages = group.memberIds.mapNotNull { id -> people.firstOrNull { it.id == id }?.avatar }.distinct().take(4)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * .3f)).background(coverBrush(group.id.toInt())),
        contentAlignment = Alignment.Center
    ) {
        Text(group.name.ifBlank { "群" }.take(2), fontSize = 13.sp, color = Text2)
        if (group.avatarUrls.isNotEmpty() || !group.avatar.isNullOrBlank()) {
            SteamImage(group.avatarUrls.ifEmpty { listOfNotNull(group.avatar) }, "", Modifier.fillMaxSize())
        } else if (memberImages.isNotEmpty()) {
            Column(Modifier.fillMaxSize().background(Surface2).padding(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                memberImages.chunked(2).forEach { row -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { url -> SteamImage(listOf(url), "", Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp))) }
                    if(row.size == 1 && memberImages.size > 1) Spacer(Modifier.weight(1f))
                } }
            }
        }
    }
}

/** Discord-style channel strip: one row, always visible above the chat. */
@Composable
private fun ChannelBar(group: SteamGroup, selected: Long?, onSelect: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        group.channels.forEach { channel ->
            val active = channel.id == selected
            val shape = RoundedCornerShape(50)
            Row(
                Modifier
                    .clip(shape)
                    .background(if (active) AccentSoft else Surface1)
                    .border(1.dp, if (active) AccentLine else Line, shape)
                    .clickable { onSelect(channel.id) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("#", fontSize = 11.sp, color = if (active) AccentText else Text3)
                Spacer(Modifier.width(4.dp))
                Text(
                    channel.name,
                    fontSize = 11.5.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) AccentText else Text2,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun GroupChatView(
    model: LiteModel,
    group: SteamGroup,
    channel: GroupChannel?,
    online: Boolean,
    onChannel: (Long) -> Unit,
    back: () -> Unit
) {
    val repo = model.community
    val active = LocalRootTabActive.current
    BackHandler(enabled = active, onBack = back)
    val scope = rememberCoroutineScope()
    val messages by repo.groupMessages.collectAsState()
    val status by repo.channelStatus.collectAsState()
    val sending by repo.sending.collectAsState()
    val friends by model.steam.people.collectAsState()
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()

    LaunchedEffect(active, group.id, channel?.id, online) {
        if (active && online && channel != null) {
            repo.selectChannel(group.id, channel.id)
            while (true) {
                delay(30000)
                repo.selectChannel(group.id, channel.id)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(bottom = LocalFloatingNavigationPadding.current)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = back),
                contentAlignment = Alignment.Center
            ) { SteamIcon(Glyph.Back, 20.dp, Text2, 2f) }
            GroupAvatar(group, 38.dp, friends)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    group.name.ifBlank { "未命名群组" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Text1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${group.members} 位成员 · ${group.channels.size} 个频道",
                    fontSize = 10.5.sp,
                    color = Text3,
                    maxLines = 1
                )
            }
        }

        ChannelBar(group, channel?.id, onChannel)
        Hairline()

        if (channel == null) {
            EmptyState(
                glyph = Glyph.Chat,
                title = "没有可见的文字频道",
                body = "Steam 未返回该群组的文字频道，可能需要在官方客户端中查看。"
            )
            return@Column
        }

        if (status.isNotEmpty() && status.contains("失败")) {
            Box(Modifier.padding(horizontal = 16.dp)) {
                StatusBanner(status, Tone.Warn)
            }
        }

        val bubbles = remember(messages, friends) {
            messages.map { m ->
                BubbleData(
                    id = m.id,
                    text = m.text,
                    outgoing = m.sender.toString() == model.steam.owner,
                    time = m.time,
                    sender = friends.firstOrNull { it.id == m.sender }?.name
                        ?: if (m.sender.toString() == model.steam.owner) "我" else m.sender.toString()
                )
            }
        }
        if (bubbles.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("# ${channel.name}", fontSize = 13.sp, color = Text3)
                    Spacer(Modifier.height(4.dp))
                    Text("这个频道还没有消息", fontSize = 11.5.sp, color = Text3)
                }
            }
        } else {
            ChatBubbleList(bubbles, Modifier.weight(1f).fillMaxWidth(), listState)
        }
        MessageComposer(model, "group:${group.id}:${channel.id}", online && !sending) { text, accepted ->
            scrollScope.launch { listState.scrollToItem(0) }
            repo.sendGroup(group.id, channel.id, text, accepted)
        }
    }
}

// ============================================================== sheets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FriendActionSheet(name: String, pinned: Boolean, onTogglePin: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = Surface1,
        scrimColor = SheetScrim,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = { SheetHandle() }
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp).navigationBarsPadding()) {
            Text(name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text("置顶与 Steam 同步，仅在好友页显示。", fontSize = 11.5.sp, color = Text3)
            Spacer(Modifier.height(16.dp))
            GhostButton(
                if (pinned) "取消置顶" else "置顶好友",
                onTogglePin,
                Modifier.fillMaxWidth(),
                glyph = if (pinned) Glyph.Close else Glyph.Plus
            )
            Spacer(Modifier.height(10.dp))
            GhostButton("关闭", onClose, Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
        }
    }
}
