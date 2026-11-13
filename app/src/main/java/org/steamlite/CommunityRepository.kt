package org.steamlite

import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPlayerSteamclient.*
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.future.await

data class OwnedGame(val id: Int, val name: String, val minutes: Long, val recent: Long)
data class LibraryStats(val count: Int, val games: List<OwnedGame>, val cents: Long = 0, val priced: Int = 0, val pricing: Boolean = true)
data class GroupChannel(val id: Long, val name: String)
data class SteamGroup(val id: Long, val name: String, val members: Int, val avatar: String?, val channels: List<GroupChannel>, val avatarUrls: List<String> = emptyList(), val memberIds: List<Long> = emptyList())
data class GroupMessage(val id: String, val sender: Long, val text: String, val time: Long)

class CommunityRepository(private val steam: SteamConnection, private val scope: CoroutineScope) {
    val library = MutableStateFlow<LibraryStats?>(null)
    val libraryStatus = MutableStateFlow("")
    val emoticons = MutableStateFlow<List<String>>(emptyList())
    val emoteStatus = MutableStateFlow("")
    val groups = MutableStateFlow<List<SteamGroup>>(emptyList())
    val groupStatus = MutableStateFlow("")
    val groupMessages = MutableStateFlow<List<GroupMessage>>(emptyList())
    val channelStatus = MutableStateFlow("")
    val sending = MutableStateFlow(false)
    private var selected: Pair<Long, Long>? = null
    private var selection = 0
    private var libraryJob: Job? = null
    private var groupJob: Job? = null
    private var emoteJob: Job? = null
    init {
        scope.launch { steam.online.collect { online -> if(!online) {
            libraryJob?.cancel(); groupJob?.cancel(); emoteJob?.cancel(); selection++
            library.value = null; groups.value = emptyList(); emoticons.value = emptyList(); groupMessages.value = emptyList(); selected = null
            libraryStatus.value = "请登录后同步游戏库"; groupStatus.value = "请登录后同步群组"; emoteStatus.value = "请登录后同步表情"
        } } }
        scope.launch { steam.groupEvents.collect { event -> if(selected == (event.chatGroupId to event.chatId) && steam.online.value) {
            val message = GroupMessage("${event.timestamp}:${event.ordinal}", event.steamidSender, event.message, event.timestamp.toLong() * 1000)
            mergeChannelMessages(listOf(message))
        } } }
    }
    fun refreshEmotes() {
        if(!steam.online.value || emoteJob?.isActive == true) return
        val owner = steam.owner; emoteStatus.value = "正在同步 Steam 表情"
        emoteJob = scope.launch { try {
            val response = withTimeout(25000) { steam.playerService.getEmoticonList(CPlayer_GetEmoticonList_Request.getDefaultInstance()).await() }
            check(response.result == EResult.OK) { response.result.toString() }
            if(owner != steam.owner) return@launch
            emoticons.value = response.body.emoticonsList.map { it.name.trim(':', 'ː') }.filter { it.isNotBlank() }.distinct()
            emoteStatus.value = "已同步 ${emoticons.value.size} 个 Steam 表情"
        } catch(e: CancellationException) { throw e } catch(e: Exception) { emoteStatus.value = "表情同步失败，可重试（${e.javaClass.simpleName}）" } }
    }
    suspend fun syncEmotes() { refreshEmotes(); emoteJob?.join(); check(emoteJob?.isCancelled != true && !emoteStatus.value.contains("失败") && !emoteStatus.value.contains("正在")) { "表情同步未完成" } }
    suspend fun syncGroups() { refreshGroups(); groupJob?.join(); check(groupJob?.isCancelled != true && !groupStatus.value.contains("失败") && !groupStatus.value.contains("正在")) { "群组同步未完成" } }
    suspend fun syncLibrary() {
        refreshLibrary(); libraryJob?.join()
        check(library.value?.pricing == false && libraryJob?.isCancelled != true && !libraryStatus.value.contains("未能") && !libraryStatus.value.contains("限制") && !libraryStatus.value.contains("失败")) { "部分游戏库价格未能更新" }
    }
    fun refreshLibrary() {
        if(!steam.online.value || libraryJob?.isActive == true) return
        val owner = steam.owner; libraryStatus.value = "正在同步游戏库"
        libraryJob = scope.launch { try {
            val response = withTimeout(25000) { steam.playerService.getOwnedGames(CPlayer_GetOwnedGames_Request.newBuilder().setSteamid(owner.toLong()).setIncludeAppinfo(true).setIncludePlayedFreeGames(true).build()).await() }
            check(response.result == EResult.OK) { response.result.toString() }
            check(owner == steam.owner && steam.online.value)
            val games = response.body.gamesList.map { OwnedGame(it.appid, it.name, it.playtimeForever.toLong().coerceAtLeast(0), it.playtime2Weeks.toLong().coerceAtLeast(0)) }
            library.value = LibraryStats(response.body.gameCount, games)
            var sum = 0L; var priced = 0
            for(chunk in games.map { it.id }.chunked(80)) {
                val quotes = steam.wishlistGames(chunk)
                check(owner == steam.owner && steam.online.value)
                quotes.filter { it.loaded && it.cents != null && (it.price.startsWith("$") || it.cents == 0L) }.forEach { sum += it.cents!!; priced++ }
                library.value = LibraryStats(response.body.gameCount, games, sum, priced)
                libraryStatus.value = "游戏库已同步 · 正在估算现价 $priced / ${games.size}"
            }
            library.value = library.value?.copy(pricing = false)
            libraryStatus.value = "Steam 游戏库 · ${time(System.currentTimeMillis())}"
        } catch(e: CancellationException) { throw e } catch(e: Exception) {
            library.value = library.value?.copy(pricing = false)
            libraryStatus.value = if (e is PriceRateLimited) "游戏库已同步；Steam 暂时限制价格查询，已保留部分估价，请稍后重试" else if(library.value == null) "游戏库读取失败，请重试（${e.javaClass.simpleName}）" else "游戏库已同步；部分现价未能获取，可重试"
        } }
    }
    fun refreshGroups() {
        if(!steam.online.value || groupJob?.isActive == true) return
        val owner = steam.owner; groupStatus.value = "正在同步群组"
        groupJob = scope.launch { try {
            val response = withTimeout(25000) { steam.chatService.getMyChatRoomGroups(CChatRoom_GetMyChatRoomGroups_Request.getDefaultInstance()).await() }
            check(response.result == EResult.OK) { response.result.toString() }
            if(owner != steam.owner) return@launch
            groups.value = response.body.chatRoomGroupsList.map { pair -> val g = pair.groupSummary
                val artwork = groupAvatarUrls(g.chatGroupAvatarSha.toByteArray(), g.avatarUgcUrl)
                SteamGroup(g.chatGroupId, g.chatGroupName, g.activeMemberCount, artwork.firstOrNull(), g.chatRoomsList.sortedBy { it.sortOrder }.map { GroupChannel(it.chatId, it.chatName) }, artwork,
                    g.topMembersList.map { 76561197960265728L + Integer.toUnsignedLong(it) })
            }
            groupStatus.value = if(groups.value.isEmpty()) "没有已加入的 Steam 群聊" else "已同步 ${groups.value.size} 个群组"
        } catch(e: CancellationException) { throw e } catch(e: Exception) { groupStatus.value = "群组读取失败，可重试（${e.javaClass.simpleName}）" } }
    }
    suspend fun selectChannel(group: Long, channel: Long) {
        val key = group to channel
        if(selected != key) { selection++; selected = key; groupMessages.value = emptyList() }
        val request = selection; val owner = steam.owner
        channelStatus.value = "正在同步频道"
        try {
            val active = withTimeout(20000) { steam.chatService.setSessionActiveChatRoomGroups(CChatRoom_SetSessionActiveChatRoomGroups_Request.newBuilder().addChatGroupIds(group).build()).await() }
            check(active.result == EResult.OK) { active.result.toString() }
            val response = withTimeout(20000) { steam.chatService.getMessageHistory(CChatRoom_GetMessageHistory_Request.newBuilder().setChatGroupId(group).setChatId(channel).setMaxCount(100).build()).await() }
            check(response.result == EResult.OK) { response.result.toString() }
            if(owner != steam.owner || request != selection || !steam.online.value) return
            val history = response.body.messagesList.filter { !it.deleted }.map { GroupMessage("${it.serverTimestamp}:${it.ordinal}", 76561197960265728L + Integer.toUnsignedLong(it.sender), it.message, it.serverTimestamp.toLong() * 1000) }
            mergeChannelMessages(history)
            channelStatus.value = "最近 ${history.size} 条 · ${if(response.body.moreAvailable) "更多历史可在 Steam 查看" else "已同步"}"
        } catch(e: CancellationException) { throw e } catch(e: Exception) { if(request == selection) channelStatus.value = "频道同步失败，可重试（${e.javaClass.simpleName}）" }
    }
    fun leaveChannel() { selection++; selected = null; groupMessages.value = emptyList() }
    private fun mergeChannelMessages(incoming: List<GroupMessage>) {
        val merged = groupMessages.value.associateBy { it.id }.toMutableMap()
        for (message in incoming) {
            if (message.text.isBlank()) continue
            val text = merged[message.id]?.let { ChatContent.confirmedText(it.text, message.text) } ?: message.text
            merged[message.id] = message.copy(text = text)
        }
        groupMessages.value = merged.values.sortedBy { it.time }.takeLast(500)
    }
    fun sendGroup(group: Long, channel: Long, text: String, accepted: () -> Unit) {
        if(sending.value || text.isBlank() || !steam.online.value) return
        val owner = steam.owner; val request = selection; sending.value = true
        scope.launch { try {
            val response = withTimeout(20000) { steam.chatService.sendChatMessage(CChatRoom_SendChatMessage_Request.newBuilder().setChatGroupId(group).setChatId(channel).setMessage(ChatContent.outgoingMessage(text)).setEchoToSender(true).build()).await() }
            check(response.result == EResult.OK) { response.result.toString() }
            if(owner == steam.owner && request == selection && steam.online.value) {
                val b = response.body
                val confirmed = ChatContent.confirmedText(text, b.modifiedMessage)
                mergeChannelMessages(listOf(GroupMessage("${b.serverTimestamp}:${b.ordinal}", owner.toLong(), confirmed, b.serverTimestamp.toLong() * 1000)))
                channelStatus.value = "Steam 已接受（未确认已读）"
                accepted()
            }
        } catch(e: CancellationException) { throw e } catch(e: Exception) { channelStatus.value = "发送失败，请复制草稿后重试（${e.javaClass.simpleName}）" } finally { sending.value = false } }
    }
}
