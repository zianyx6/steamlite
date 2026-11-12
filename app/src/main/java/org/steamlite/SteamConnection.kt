package org.steamlite

import `in`.dragonbra.javasteam.enums.*
import `in`.dragonbra.javasteam.steam.authentication.*
import `in`.dragonbra.javasteam.steam.handlers.steamuser.*
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.*
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.*
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.*
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import `in`.dragonbra.javasteam.networking.steam3.ProtocolTypes
import `in`.dragonbra.javasteam.steam.discovery.ServerRecord
import `in`.dragonbra.javasteam.steam.discovery.SmartCMServerList
import `in`.dragonbra.javasteam.util.log.LogListener
import `in`.dragonbra.javasteam.util.log.LogManager
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.*
import `in`.dragonbra.javasteam.types.SteamID
import `in`.dragonbra.javasteam.rpc.service.FriendMessages
import `in`.dragonbra.javasteam.rpc.service.FriendMessagesClient
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesFriendmessagesSteamclient.*
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.EAuthSessionGuardType
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPlayerSteamclient.*
import `in`.dragonbra.javasteam.types.AsyncJobSingle
import kotlinx.coroutines.future.await
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.security.Security
import javax.crypto.Cipher
import org.bouncycastle.jce.provider.BouncyCastleProvider
import `in`.dragonbra.javasteam.util.crypto.CryptoHelper

data class FriendRow(val id: Long, val name: String, val state: String, val avatar: String?, val playing: Boolean = false, val available: Boolean = false, val game: String = "", val gameIcon: String? = null, val gameAppId: Int = 0, val remark: String = "")
data class RecentSession(val peer: Long, val time: Long, val unread: Int)
class SteamConnection(private val vault: SessionVault, private val dao: CacheDao, private val notifications: ChatNotifications) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = SteamClient(SteamConfiguration.create { builder ->
        builder.withProtocolTypes(ProtocolTypes.WEB_SOCKET).withConnectionTimeout(15000)
            .withDefaultPersonaStateFlags(java.util.EnumSet.of(EClientPersonaStateFlag.Status, EClientPersonaStateFlag.Presence, EClientPersonaStateFlag.PlayerName, EClientPersonaStateFlag.GameExtraInfo, EClientPersonaStateFlag.GameDataBlob, EClientPersonaStateFlag.RichPresence, EClientPersonaStateFlag.LastSeen))
    })
    val stickers = MutableStateFlow<List<String>>(emptyList())
    private val personaHandler = PersonaHandler { updateFriends() }
    private var stickerRefresh: CompletableDeferred<Unit>? = null
    private val stickerHandler = StickerHandler { stickers.value = it; stickerRefresh?.complete(Unit) }
    fun refreshStickers() { if(online.value) scope.launch {
        client.send(`in`.dragonbra.javasteam.base.ClientMsgProtobuf<`in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverFriends.CMsgClientGetEmoticonList.Builder>(`in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverFriends.CMsgClientGetEmoticonList::class.java, EMsg.ClientGetEmoticonList))
    } }
    suspend fun syncStickers() {
        val pending = CompletableDeferred<Unit>(); stickerRefresh = pending
        try { refreshStickers(); withTimeout(15000) { pending.await() } }
        finally { if(stickerRefresh === pending) stickerRefresh = null }
    }
    suspend fun syncContacts() {
        check(online.value)
        val response = withTimeout(20000) { personaChat.requestFriendPersonaStates(`in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChat_RequestFriendPersonaStates_Request.getDefaultInstance()).await() }
        check(response.result == EResult.OK) { "好友状态读取未成功" }
        refreshFriendRemarks(); refreshFavorites(); retryGameTitles(); updateFriends()
    }
    suspend fun syncConversations() {
        refreshRecentSessions(); recentJob?.join()
        check(!recentStatus.value.contains("失败")) { "会话同步未完成" }
    }
    private val manager = CallbackManager(client)
    private val user = client.getHandler(SteamUser::class.java)!!
    private val friends = client.getHandler(SteamFriends::class.java)!!
    private val steamApps = client.getHandler(`in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps::class.java)!!
    private val messaging = client.getHandler(SteamUnifiedMessages::class.java)!!.createService<FriendMessages>()
    private val wishlistService = client.getHandler(SteamUnifiedMessages::class.java)!!.createService<WishlistService>()
    private val storeBrowse = client.getHandler(SteamUnifiedMessages::class.java)!!.createService<StoreBrowseService>()
    private val priceRequests = PriceRequestGate()
    val playerService = client.getHandler(SteamUnifiedMessages::class.java)!!.createService<`in`.dragonbra.javasteam.rpc.service.Player>()
    val chatService = client.getHandler(SteamUnifiedMessages::class.java)!!.createService<`in`.dragonbra.javasteam.rpc.service.ChatRoom>()
    private val personaChat = client.getHandler(SteamUnifiedMessages::class.java)!!.createService<`in`.dragonbra.javasteam.rpc.service.Chat>()
    val groupEvents = kotlinx.coroutines.flow.MutableSharedFlow<`in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChatRoom_IncomingChatMessage_Notification.Builder>(extraBufferCapacity = 64)
    fun personaName(): String = friends.getPersonaName().orEmpty()
    private val remarks = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val remarkLock = kotlinx.coroutines.sync.Mutex()
    private val friendEditLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var readingPeer: Long? = null
    private val readTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    suspend fun refreshFriendRemarks() {
        if (!online.value) return
        remarkLock.lock()
        val accountOwner = owner
        try {
            val response = withTimeout(20000) { playerService.getNicknameList(CPlayer_GetNicknameList_Request.getDefaultInstance()).await() }
            check(response.result == EResult.OK) { response.result.toString() }
            check(online.value && owner == accountOwner)
            remarks.value = response.body.nicknamesList.associate { 76561197960265728L + Integer.toUnsignedLong(it.accountid) to it.nickname }
            updateFriends()
        } finally { remarkLock.unlock() }
    }
    suspend fun editFriend(peer: Long, action: String, remark: String = "") {
        check(friendEditLock.tryLock()) { "好友操作正在处理中" }
        val accountOwner = owner
        try {
            check(online.value && peer != accountOwner.toLongOrNull()) { "请先连接 Steam" }
            when (action) {
                "remark" -> {
                    require(remark.length <= 32 && remark.none { it.isISOControl() }) { "备注请使用 32 字以内的单行文字" }
                    val response = withTimeout(20000) { AsyncJobSingle<NicknameCallback>(client, friends.setFriendNickname(SteamID(peer), remark)).await() }
                    check(response.result == EResult.OK) { "Steam 未接受备注：${response.result}" }
                    refreshFriendRemarks()
                    check(remarks.value[peer].orEmpty() == remark) { "备注尚未回读确认，请稍后检查" }
                }
                "block" -> {
                    val response = withTimeout(20000) { playerService.ignoreFriend(CPlayer_IgnoreFriend_Request.newBuilder().setSteamid(peer).setUnignore(false).build()).await() }
                    check(response.result == EResult.OK) { "Steam 未接受屏蔽：${response.result}" }
                    check(EFriendRelationship.from(response.body.friendRelationship) in setOf(EFriendRelationship.Ignored, EFriendRelationship.IgnoredFriend, EFriendRelationship.Blocked)) { "Steam 尚未确认屏蔽状态，请稍后检查" }
                }
                "delete" -> {
                    val response = withTimeout(20000) { playerService.removeFriend(CPlayer_RemoveFriend_Request.newBuilder().setSteamid(peer).build()).await() }
                    check(response.result == EResult.OK) { "Steam 未接受删除：${response.result}" }
                    check(response.body.friendRelationship == EFriendRelationship.None.code()) { "Steam 尚未确认删除状态，请稍后检查" }
                }
                else -> error("不支持的好友操作")
            }
            check(online.value && owner == accountOwner) { "账号会话已变化" }
            if (action != "remark") {
                people.update { rows -> rows.filterNot { it.id == peer } }
                recentSessions.update { rows -> rows.filterNot { it.peer == peer } }
            }
        } finally { friendEditLock.unlock() }
    }
    fun enterConversation(peer: Long) {
        readingPeer = peer
        markConversationRead(peer, recentSessions.value.firstOrNull { it.peer == peer }?.time ?: 0L)
    }
    fun leaveConversation(peer: Long) { if (readingPeer == peer) readingPeer = null }
    private fun markConversationRead(peer: Long, timestamp: Long) {
        if (timestamp > 0) readTimes.merge(peer, timestamp, ::maxOf)
        recentSessions.update { rows -> rows.map { if (it.peer == peer) it.copy(unread = 0) else it } }
        if (online.value && timestamp > 0) runCatching { messaging.ackMessage(CFriendMessages_AckMessage_Notification.newBuilder().setSteamidPartner(peer).setTimestamp((timestamp / 1000).toInt()).build()) }
    }
    /** Own avatar, resolved from the player link details RPC after login. */
    val selfAvatar = MutableStateFlow("")
    private suspend fun loadSelfAvatar() {
        val me = owner.toLongOrNull() ?: return
        runCatching {
            val request = `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPlayerSteamclient.CPlayer_GetPlayerLinkDetails_Request.newBuilder().addSteamids(me).build()
            val response = withTimeout(15000) { playerService.getPlayerLinkDetails(request).await() }
            val hash = response.body.accountsList.firstOrNull()?.publicData?.shaDigestAvatar?.toByteArray()
            val hex = hash?.takeIf { it.isNotEmpty() }?.joinToString("") { "%02x".format(it) }
            if (!hex.isNullOrBlank() && hex.any { c -> c != '0' }) {
                selfAvatar.value = "https://avatars.steamstatic.com/${hex}_full.jpg"
            }
        }
    }
    private val favoritesService = client.getHandler(SteamUnifiedMessages::class.java)!!.createService<FriendsFavoritesService>()
    val pinnedFriends = MutableStateFlow<Set<String>>(emptySet())
    val favoritesStatus = MutableStateFlow("")
    private val favoritesLock = kotlinx.coroutines.sync.Mutex()
    suspend fun refreshFavorites() {
        favoritesLock.lock()
        try { val accountOwner = owner; check(online.value)
            val result = withTimeout(20000) { favoritesService.get().await() }
            check(result.result == EResult.OK) { result.result.toString() }
            check(accountOwner == owner && online.value)
            pinnedFriends.value = result.body.favoritesList.filter { it.accountid != 0 }.map { (76561197960265728L + Integer.toUnsignedLong(it.accountid)).toString() }.toSet()
            favoritesStatus.value = "Steam 置顶已同步"
        } finally { favoritesLock.unlock() }
    }
    fun toggleFavorite(peer: Long) { scope.launch {
        favoritesLock.lock()
        val accountOwner = owner
        try {
            check(online.value)
            val before = withTimeout(20000) { favoritesService.get().await() }
            check(before.result == EResult.OK)
            check(accountOwner == owner && online.value)
            val id = peer.toInt()
            val add = before.body.favoritesList.none { it.accountid == id }
            val entries = before.body.favoritesList.filter { it.accountid != id }.toMutableList()
            if(add) entries += org.steamlite.protocol.FriendsProtocol.Favorite.newBuilder().setAccountid(id).build()
            val written = withTimeout(20000) { favoritesService.set(entries).await() }
            check(written.result == EResult.OK)
            val after = withTimeout(20000) { favoritesService.get().await() }
            check(after.result == EResult.OK && after.body.favoritesList.any { it.accountid == id } == add)
            check(accountOwner == owner && online.value)
            pinnedFriends.value = after.body.favoritesList.filter { it.accountid != 0 }.map { (76561197960265728L + Integer.toUnsignedLong(it.accountid)).toString() }.toSet()
            favoritesStatus.value = "Steam 置顶已同步"
        } catch(e: CancellationException) { throw e } catch(_: Exception) { favoritesStatus.value = "Steam 置顶操作未确认，请重新同步" }
        finally { favoritesLock.unlock() }
    } }
    private val storeWeb = StoreWebClient(this)
    val status = MutableStateFlow("未登录")
    val online = MutableStateFlow(false)
    val voice = SteamVoiceController(client.getHandler(SteamUnifiedMessages::class.java)!!, manager, scope, { owner }, { online.value })
    val busy = MutableStateFlow(false)
    val loginSucceeded = MutableStateFlow(false)
    val qrLogin = MutableStateFlow(false)
    val qrChallenge = MutableStateFlow<String?>(null)
    private var interactiveLogin = false
    val needsGuardCode = MutableStateFlow(false)
    val phoneConfirmationPending = MutableStateFlow(false)
    val guardCodeSubmitting = MutableStateFlow(false)
    @Volatile private var concurrentGuardSession: CredentialsAuthSession? = null
    @Volatile private var activeGuardSession: CredentialsAuthSession? = null
    private var guardSubmitJob: Job? = null
    private fun clearConcurrentGuard(expected: CredentialsAuthSession? = null) {
        if (expected != null && activeGuardSession !== expected) return
        activeGuardSession = null; concurrentGuardSession = null; guardSubmitJob?.cancel(); guardSubmitJob = null
        phoneConfirmationPending.value = false; guardCodeSubmitting.value = false
    }
    val people = MutableStateFlow<List<FriendRow>>(emptyList())
    val recentSessions = MutableStateFlow<List<RecentSession>>(emptyList())
    val recentStatus = MutableStateFlow("")
    private var recentJob: Job? = null
    val guard = MutableStateFlow("")
    val historyStatus = MutableStateFlow("")
    var owner = ""; private set
    val steamId: String get() = owner
    private var account = ""
    private var token = ""
    private var password = ""
    private var active = false
    private var retry = 0
    private var code: CompletableFuture<String>? = null
    private var authenticationJob: Job? = null
    private var generation = 0
    private var probe = false
    private var fallbackTried = false
    private var watchdog: Job? = null
    private var authReady = false
    private val diagnostics = object : LogListener {
        override fun onLog(clazz: Class<*>, message: String?, throwable: Throwable?) {
            if (probe && clazz.simpleName in setOf("CMClient", "WebSocketConnection", "SmartCMServerList", "SteamDirectory")) android.util.Log.i("SteamLiteProbe", "${clazz.simpleName}: ${message.orEmpty()}")
            if (throwable != null && clazz.simpleName in setOf("CMClient", "WebSocketConnection", "SmartCMServerList")) {
                android.util.Log.w("SteamLiteNetwork", clazz.simpleName + ": " + generateSequence(throwable) { it.cause }.joinToString(" -> ") { it.javaClass.simpleName } + "\n" + throwable.stackTrace.take(8).joinToString("\n"))
            }
        }
        override fun onError(clazz: Class<*>, message: String?, throwable: Throwable?) = onLog(clazz, null, throwable)
    }
    init {
        client.addHandler(personaHandler)
        client.addHandler(stickerHandler)
        LogManager.addListener(diagnostics)
        scope.launch {
            runCatching {
                // JavaSteam selects the provider by name. Replace Android's reduced BC
                // in this process with the bundled provider; leave Conscrypt/Keystore intact.
                if (Security.getProvider("BC")?.javaClass != BouncyCastleProvider::class.java) {
                    Security.removeProvider("BC")
                    Security.addProvider(BouncyCastleProvider())
                }
                AuthSessionDetails()
                val keys = java.security.KeyPairGenerator.getInstance("RSA", CryptoHelper.SEC_PROV).apply { initialize(2048) }.generateKeyPair()
                val cipher = Cipher.getInstance("RSA/None/PKCS1Padding", CryptoHelper.SEC_PROV)
                cipher.init(Cipher.ENCRYPT_MODE, keys.public)
                val encrypted = cipher.doFinal("steam-lite-local-self-check".toByteArray())
                cipher.init(Cipher.DECRYPT_MODE, keys.private)
                check(String(cipher.doFinal(encrypted)) == "steam-lite-local-self-check")
                authReady = true
                android.util.Log.i("SteamLiteAuthInit", "Bundled BC provider / JavaSteam RSA roundtrip passed (no account data)")
            }.onFailure { error ->
                android.util.Log.e("SteamLiteAuthInit", error.javaClass.simpleName + ": " + (if(error is LinkageError) error.message?.replace(Regex("[^A-Za-z0-9_/$; .:-]"), "") else "") + "\n" + error.stackTrace.take(12).joinToString("\n"))
                status.value = "认证组件初始化失败，正在修复兼容性"
            }
        }
        manager.subscribe(ConnectedCallback::class.java) {
            if (!active) { scope.launch { client.disconnect() }; return@subscribe }
            watchdog?.cancel()
            if (probe) {
                probe = false; active = false; busy.value = false; status.value = "Steam CM 安全连接成功，可以登录"
                scope.launch { client.disconnect() }
                return@subscribe
            }
            val attempt = generation
            status.value = "Steam 已连接，正在认证"
            authenticationJob = scope.launch {
                runCatching {
                    if (token.isEmpty()) {
                        val details = AuthSessionDetails().apply {
                            username = account; password = this@SteamConnection.password; persistentSession = true
                            deviceFriendlyName = "Steam Lite Android"
                            authenticator = object : IAuthenticator {
                                override fun getDeviceCode(previousCodeWasIncorrect: Boolean) = requestCode(if (previousCodeWasIncorrect) "验证码错误，请重新输入 Steam Guard" else "输入 Steam Guard 验证码")
                                override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean) = requestCode(if (previousCodeWasIncorrect) "验证码错误，请重新输入邮件验证码" else "输入 Steam 邮件验证码")
                                override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
                                    phoneConfirmationPending.value = true
                                    needsGuardCode.value = concurrentGuardSession != null
                                    guard.value = if (needsGuardCode.value) "可在 Steam 手机应用批准登录，也可直接输入令牌码；任一方式验证成功即可登录" else "请在 Steam 官方手机应用确认此次登录，确认后将自动继续"
                                    return CompletableFuture.completedFuture(true)
                                }
                            }
                        }
                        val scanning = qrLogin.value
                        status.value = if (scanning) "正在获取 Steam 登录二维码" else "正在请求 Steam 认证，等待 Steam Guard 挑战"
                        val result = withTimeout(180000) {
                            if (scanning) {
                                val session = client.authentication.beginAuthSessionViaQR(details, this).await()
                                ensureActive()
                                qrChallenge.value = session.challengeUrl
                                status.value = "等待 Steam 手机应用扫码确认"
                                // Steam reports seconds. JavaSteam 1.8.0's polling loop delays in milliseconds.
                                val intervalMillis = (session.pollingInterval * 1000).toLong().coerceAtLeast(1000L)
                                var approval: AuthPollResult?
                                do {
                                    delay(intervalMillis)
                                    approval = session.pollAuthSessionStatus(this).await()
                                    ensureActive()
                                    if (approval == null) qrChallenge.value = session.challengeUrl
                                } while (approval == null)
                                approval
                            } else {
                                val session = client.authentication.beginAuthSessionViaCredentials(details).await()
                                activeGuardSession = session
                                session.allowedConfirmations = preferPhoneApproval(session.allowedConfirmations)
                                if (supportsConcurrentGuard(session.allowedConfirmations)) concurrentGuardSession = session
                                try { session.pollingWaitForResult().await() }
                                finally { clearConcurrentGuard(session) }
                            }
                        }
                        ensureActive()
                        if (!active || generation != attempt) return@launch
                        token = result.refreshToken; account = result.accountName
                        this@SteamConnection.password = ""; details.password = null
                        if (scanning) {
                            qrChallenge.value = null
                            status.value = "扫码已确认，正在登录 Steam"
                        } else {
                            vault.save(JSONObject().put("account", account).put("token", token).toString())
                        }
                    }
                    guard.value = ""
                    needsGuardCode.value = false
                    if (!active || generation != attempt) return@launch
                    user.logOn(LogOnDetails().apply { username = account; accessToken = token; loginID = 149; chatMode = ChatMode.NEW_STEAM_CHAT; uiMode = EUIMode.MobileChat })
                }.onFailure { if (generation == attempt && (it !is CancellationException || it is TimeoutCancellationException)) {
                    val cause = generateSequence(it) { failure -> failure.cause }.last()
                    val resultCode = (cause as? AuthenticationException)?.result
                    android.util.Log.w("SteamLiteAuth", "Authentication failed: ${cause.javaClass.simpleName}, Steam result=${resultCode ?: "unavailable"}")
                    status.value = if(it is TimeoutCancellationException) "认证超时，请重试" else "认证失败：${resultCode ?: cause.javaClass.simpleName}"
                    clearQrLogin()
                    clearConcurrentGuard(); active = false; busy.value = false; password = ""; guard.value = ""; needsGuardCode.value = false; code?.cancel(true); code = null; client.disconnect()
                } }
            }
        }
        manager.subscribe(LoggedOnCallback::class.java) {
            val scanned = qrLogin.value
            if (scanned && it.result == EResult.OK) {
                vault.save(JSONObject().put("account", account).put("token", token).toString())
            }
            clearQrLogin()
            busy.value = false
            if (it.result == EResult.OK) {
                guard.value = ""; needsGuardCode.value = false
                owner = client.steamID?.convertToUInt64().toString(); online.value = true; retry = 0; status.value = "已连接 Steam"
                if (interactiveLogin) { interactiveLogin = false; loginSucceeded.value = true }
                scope.launch { runCatching { refreshFriendRemarks() } }
                refreshRecentSessions()
                scope.launch { loadSelfAvatar() }
                scope.launch { runCatching { dao.deleteEmptyMessages() } }
                friends.setPersonaState(EPersonaState.Online)
                scope.launch { runCatching { withTimeout(20000) { personaChat.requestFriendPersonaStates(`in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChat_RequestFriendPersonaStates_Request.getDefaultInstance()).await() } } }
            } else { guard.value = ""; needsGuardCode.value = false; interactiveLogin = false; status.value = "登录失败：${it.result}"; online.value = false; active = false; if (it.result.toString() in setOf("InvalidPassword", "Expired", "Revoked", "AccessDenied")) { token = ""; vault.clear() }; client.disconnect() }
        }
        manager.subscribe(FriendsListCallback::class.java) {
            updateFriends()
            (0 until friends.getFriendCount()).map { friends.getFriendByIndex(it) }.filter { friends.getFriendRelationship(it) == EFriendRelationship.Friend }.chunked(100).forEach { ids -> friends.requestFriendInfo(ids) }
        }
        manager.subscribe(PersonaStateCallback::class.java) { updateFriends() }
        manager.subscribe(`in`.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback::class.java) { event ->
            if(online.value) {
                event.apps.forEach { (id, info) ->
                    val common = info.keyValues["common"]
                    val name = common["name_localized"]["schinese"].value?.takeIf { it.isNotBlank() } ?: common["name"].value
                    if(!name.isNullOrBlank()) {
                        personaGameNames[id] = name
                        scope.launch { dao.cache(CachedResponse("persona-title:$id", name, System.currentTimeMillis())) }
                    }
                    common["icon"].value?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) }?.let { hash -> personaGameIcons[id] = "https://cdn.akamai.steamstatic.com/steamcommunity/public/images/apps/$id/$hash.jpg" }
                }
                updateFriends()
            }
        }
        manager.subscribe(FriendMsgCallback::class.java) { event ->
            if(event.entryType == EChatEntryType.ChatMsg || event.entryType?.code() == 4) {
                scope.launch { runCatching { syncRecentMessages(event.sender.convertToUInt64()) } }
            }
        }
        manager.subscribeServiceNotification<`in`.dragonbra.javasteam.rpc.service.ChatRoomClient, `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChatRoom_IncomingChatMessage_Notification.Builder> { groupEvents.tryEmit(it.body) }
        manager.subscribeServiceNotification<FriendMessagesClient, CFriendMessages_IncomingMessage_Notification.Builder> { event ->
            val body = event.body
            if (body.chatEntryType == 1 || body.chatEntryType == 4) {
                val accountOwner = owner
                scope.launch {
                    android.util.Log.i("SteamLiteReceive", "um msg len=${body.message.length} echo=${body.localEcho} type=${body.chatEntryType}")
                    if (online.value && owner == accountOwner && body.message.isNotBlank()) {
                        dao.put(Message("$accountOwner:${body.steamidFriend}:${body.rtime32ServerTimestamp}:${body.ordinal}", accountOwner, body.steamidFriend, body.message, body.rtime32ServerTimestamp.toLong() * 1000, body.localEcho, if(body.localEcho) "其他 Steam 客户端已发送" else "已接收"))
                        val receivedAt = body.rtime32ServerTimestamp.toLong() * 1000
                        recentSessions.update { recordRecentMessage(it, body.steamidFriend, receivedAt, !body.localEcho && readingPeer != body.steamidFriend) }
                        if (!body.localEcho && readingPeer == body.steamidFriend) markConversationRead(body.steamidFriend, receivedAt)
                        if(!body.localEcho) notifications.incoming(body.steamidFriend)
                        android.util.Log.i("SteamLiteReceive", "Friend message persisted via UM; outgoing=${body.localEcho}")
                    }
                }
            }
        }
        manager.subscribeServiceNotification<`in`.dragonbra.javasteam.rpc.service.PlayerClient, CPlayer_FriendNicknameChanged_Notification.Builder> { event ->
            if (online.value) {
                val peer = 76561197960265728L + Integer.toUnsignedLong(event.body.accountid)
                remarks.update { it + (peer to event.body.nickname) }; updateFriends()
            }
        }
        manager.subscribe(DisconnectedCallback::class.java) {
            authenticationJob?.cancel()
            qrChallenge.value = null
            clearConcurrentGuard()
            readingPeer = null
            if (owner.isBlank()) { remarks.value = emptyMap(); readTimes.clear() }
            voice.disconnect()
            online.value = false
            if (active && !fallbackTried) {
                fallbackTried = true
                status.value = "节点连接失败，正在查询 Steam 服务器目录"
                scope.launch { client.connect() }
                return@subscribe
            }
            if (active && token.isNotEmpty()) scope.launch { status.value = "连接中断，正在重连"; delay(minOf(60000L, 2000L shl minOf(retry++, 5))); if (active) connect() }
            else if (active) { clearQrLogin(); active = false; busy.value = false; watchdog?.cancel(); password = ""; status.value = "连接失败，请重试" }
        }
        scope.launch { while (isActive) { runCatching { manager.runWaitCallbacks(500) }; delay(10) } }
        vault.read()?.let { saved -> runCatching { val json = JSONObject(saved); account = json.getString("account"); token = json.getString("token"); connect() } }
    }
    private fun requestCode(prompt: String): CompletableFuture<String> {
        phoneConfirmationPending.value = false; guardCodeSubmitting.value = false
        val pending = CompletableFuture<String>()
        code = pending; guard.value = prompt; needsGuardCode.value = true
        return pending
    }
    fun submitCode(value: String) {
        val entered = value.trim().uppercase(java.util.Locale.ROOT)
        if (!entered.matches(Regex("[A-Z0-9]{5}"))) { guard.value = "请输入 Steam 提供的 5 位验证码"; return }
        val session = concurrentGuardSession
        if (session != null) {
            if (guardCodeSubmitting.value) return
            guardCodeSubmitting.value = true
            guardSubmitJob = scope.launch {
                try {
                    withTimeout(20000) { session.sendSteamGuardCode(entered, EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode).await() }
                    if (concurrentGuardSession === session) guard.value = "令牌码已提交，正在等待 Steam 验证；手机确认仍然有效"
                } catch(e: CancellationException) {
                    if (e is TimeoutCancellationException && concurrentGuardSession === session) {
                        guardCodeSubmitting.value = false; guard.value = "令牌码验证超时，可重试或继续等待手机确认"
                    } else throw e
                } catch(e: Exception) {
                    if (concurrentGuardSession === session) {
                        guardCodeSubmitting.value = false
                        val cause = generateSequence<Throwable>(e) { it.cause }.last()
                        guard.value = if ((cause as? AuthenticationException)?.result in setOf(EResult.TwoFactorCodeMismatch, EResult.InvalidLoginAuthCode)) "令牌码错误，请重新输入；也可在手机上确认登录" else "令牌码暂未验证通过，可重试或继续等待手机确认"
                    }
                }
            }
            return
        }
        val pending = code ?: return
        code = null; needsGuardCode.value = false; guard.value = "正在验证验证码"
        pending.complete(entered)
    }
    fun login(name: String, pass: String) { if (active) return; if(!authReady) { status.value = "加密组件尚未准备完成，请稍后重试"; return }; probe = false; interactiveLogin = true; loginSucceeded.value = false; guard.value = ""; generation++; account = name.trim(); password = pass; token = ""; connect() }
    fun cancelLogin() {
        if (online.value) return
        if (qrLogin.value) token = ""
        clearQrLogin()
        clearConcurrentGuard()
        active = false; generation++; interactiveLogin = false; busy.value = false
        watchdog?.cancel(); authenticationJob?.cancel(); code?.cancel(true); code = null
        password = ""; guard.value = ""; needsGuardCode.value = false; status.value = "登录已取消"
        scope.launch { client.disconnect() }
    }
    fun loginWithQr() {
        if (active || online.value) return
        probe = false
        interactiveLogin = true
        loginSucceeded.value = false
        guard.value = ""
        generation++
        account = ""
        password = ""
        token = ""
        qrLogin.value = true
        connect()
    }

    private fun clearQrLogin() {
        qrChallenge.value = null
        qrLogin.value = false
    }
    fun resumeSession() {
        if (token.isNotBlank() && !online.value && !busy.value) connect()
    }
    fun probeConnection() { if(active) return; probe = true; connect() }
    private fun connect() {
        active = true; busy.value = true; fallbackTried = false; status.value = "正在连接 Steam"
        val attempt = generation
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(45000)
            if(active && !client.isConnected && generation == attempt) {
                clearQrLogin()
                active = false; busy.value = false; password = ""; status.value = "Steam 连接超时，请检查网络后重试"; client.disconnect()
            }
        }
        scope.launch {
            runCatching { client.connect(ServerRecord.createWebSocketServer(SmartCMServerList.defaultServerWebSocket)) }.onFailure { clearQrLogin(); active = false; busy.value = false; password = ""; status.value = "Steam 连接异常（${it.javaClass.simpleName}）" }
        }
    }
    private val personaGameNames = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val personaGameIcons = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val requestedPersonaGames = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val pendingPersonaGames = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private var personaGameJob: Job? = null
    private var gameTitleRetryJob: Job? = null
    private val gameTitleRetries = GameTitleRetries()
    val gameTitlesLoading = MutableStateFlow(false)
    fun retryGameTitles() {
        if(!online.value || gameTitlesLoading.value) return
        val ids = personaHandler.snapshots.values.filter { it.gameId != 0L }.map { (it.gameId and 0xffffffL).toInt() }.filter { it > 0 && !personaGameNames.containsKey(it) }.toSet()
        gameTitleRetries.reset(ids); requestedPersonaGames.removeAll(ids)
        updateFriends()
    }

    private val personaTitleHttp by lazy { okhttp3.OkHttpClient.Builder().callTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build() }
    @Synchronized private fun loadPersonaGameNames() {
        if(personaGameJob?.isActive == true || pendingPersonaGames.isEmpty() || !online.value) return
        val accountOwner = owner
        gameTitlesLoading.value = true
        personaGameJob = scope.launch {
            try {
            delay(300)
            while(pendingPersonaGames.isNotEmpty() && accountOwner == owner && online.value) {
                val counts = personaHandler.snapshots.values.filter { it.gameId != 0L }.groupingBy { (it.gameId and 0xffffffL).toInt() }.eachCount()
                val ids = pendingPersonaGames.toList().sortedByDescending { counts[it] ?: 0 }.take(80)
                pendingPersonaGames.removeAll(ids.toSet())
                ids.forEach { gameTitleRetries.started(it, System.currentTimeMillis()) }
                for(app in ids) dao.cached("persona-title:$app")?.body?.takeIf { it.isNotBlank() }?.let { personaGameNames[app] = it }
                updateFriends()
                steamApps.picsGetProductInfo(ids.map { `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest(it) }, emptyList())
                // Public app titles are not always included by StoreBrowse in chat mode.
                for(batch in ids.filter { !personaGameNames.containsKey(it) }.chunked(4)) {
                    batch.map { app -> async {
                        try {
                            val cacheKey = "persona-title:$app"
                            val cached = dao.cached(cacheKey)
                            val name = cached?.body ?: personaTitleHttp.newCall(okhttp3.Request.Builder().url("https://store.steampowered.com/api/appdetails?appids=$app&l=schinese&filters=basic").build()).execute().use { response ->
                                check(response.isSuccessful)
                                JSONObject(response.body!!.string()).optJSONObject(app.toString())?.optJSONObject("data")?.optString("name").orEmpty()
                            }
                            if(name.isNotBlank() && owner == accountOwner) {
                                personaGameNames[app] = name
                                if(cached == null) dao.cache(CachedResponse(cacheKey, name, System.currentTimeMillis()))
                            }
                        } catch(e: CancellationException) { throw e } catch(_: Exception) { /* Keep the actual AppID when title lookup fails. */ }
                    } }.awaitAll()
                    if(owner != accountOwner || !online.value) return@launch
                    updateFriends()
                }
                updateFriends()
            }
            } finally {
                gameTitlesLoading.value = false
                val missing = personaHandler.snapshots.values.filter { it.gameId != 0L }.map { (it.gameId and 0xffffffL).toInt() }.filter { !personaGameNames.containsKey(it) }.toSet()
                requestedPersonaGames.removeAll(missing)
                gameTitleRetryJob?.cancel()
                gameTitleRetries.next(missing)?.let { next ->
                    if(owner == accountOwner && online.value) gameTitleRetryJob = scope.launch {
                        delay((next - System.currentTimeMillis()).coerceAtLeast(1000)); updateFriends()
                    }
                }
            }
        }
    }
    private var friendPublishJob: Job? = null
    @Synchronized private fun updateFriends() {
        if(!online.value || owner.isBlank() || friendPublishJob?.isActive == true) return
        friendPublishJob = scope.launch { delay(100); publishFriends() }
    }
    private fun publishFriends() {
        val accountOwner = owner
        if(accountOwner.isBlank() || !online.value) return
        val updated = (0 until friends.getFriendCount()).map { friends.getFriendByIndex(it) }.filter { friends.getFriendRelationship(it) == EFriendRelationship.Friend }.map { id ->
            val hash = friends.getFriendAvatar(id)?.takeIf { it.size == 20 && it.any { b -> b != 0.toByte() } }?.joinToString("") { "%02x".format(it) }
            val snapshot = personaHandler.snapshots[id.convertToUInt64()]
            val gameId = snapshot?.gameId ?: friends.getFriendGamePlayed(id).toUInt64()
            val appId = (gameId and 0xffffffL).toInt()
            val rawName = snapshot?.game ?: friends.getFriendGamePlayedName(id).orEmpty()
            if(gameId != 0L && rawName.isBlank() && appId > 0 && !personaGameNames.containsKey(appId) && gameTitleRetries.ready(appId, System.currentTimeMillis()) && requestedPersonaGames.add(appId)) pendingPersonaGames.add(appId)
            val game = rawName.ifBlank { if(gameId != 0L) personaGameNames[appId] ?: "游戏名称暂未获取" else "" }
            val state = snapshot?.state?.let { EPersonaState.from(it) } ?: friends.getFriendPersonaState(id)
            // Keep persona state and "what they are playing" separate so the UI can
            // render them on different lines with different emphasis.
            val persona = when(state) { EPersonaState.Online -> "在线"; EPersonaState.Away -> "离开"; EPersonaState.Snooze -> "休息中"; EPersonaState.Busy -> "忙碌"; EPersonaState.LookingToPlay -> "想一起玩"; EPersonaState.LookingToTrade -> "想交易"; else -> "离线" }
            FriendRow(id.convertToUInt64(), friends.getFriendPersonaName(id) ?: id.convertToUInt64().toString(), persona, hash?.let { "https://avatars.steamstatic.com/${it}_full.jpg" }, game.isNotBlank(), state != EPersonaState.Offline, game, personaGameIcons[appId], appId, remarks.value[id.convertToUInt64()].orEmpty())
        }.sortedBy { it.name.lowercase() }
        if(owner != accountOwner || !online.value) return
        people.value = updated
        loadPersonaGameNames()
    }
    fun refreshRecentSessions() {
        if(!online.value || recentJob?.isActive == true) return
        val accountOwner = owner; recentStatus.value = "正在同步会话"
        recentJob = scope.launch { try {
            val response = withTimeout(20000) { messaging.getActiveMessageSessions(CFriendsMessages_GetActiveMessageSessions_Request.newBuilder().setOnlySessionsWithMessages(true).build()).await() }
            check(response.result == EResult.OK)
            if(owner != accountOwner || !online.value) return@launch
            val server = response.body.messageSessionsList.map { RecentSession(76561197960265728L + Integer.toUnsignedLong(it.accountidFriend), it.lastMessage.toLong() * 1000, it.unreadMessageCount) }
            recentSessions.update { current -> mergeRecentMessages(current, server, readTimes) }
            recentStatus.value = "会话已同步 · 点击会话加载历史"
        } catch(e: CancellationException) { throw e } catch(e: Exception) { recentStatus.value = "会话同步失败，可重试" } }
    }
    fun send(peer: Long, text: String) { if (text.isBlank()) return; scope.launch {
        val message = Message(UUID.randomUUID().toString(), owner, peer, text, System.currentTimeMillis(), true, "发送中")
        dao.put(message)
        // Steam expands /sticker into the canonical markup seen by both clients.
        val bbcode = text.contains('[') || text.contains('ː')
        val result = runCatching {
            check(online.value)
            withTimeout(20000) {
                messaging.sendMessage(
                    privateMessageRequest(peer, text)
                ).await()
            }.also { check(it.result == EResult.OK) { it.result.toString() } }
        }
        if (result.isSuccess) {
            val body = result.getOrThrow().body
            android.util.Log.i("SteamLiteSend", "sent len=${text.length} bbcode=$bbcode modified len=${body.modifiedMessage.length}")
            dao.put(message.copy(id = "${message.owner}:$peer:${body.serverTimestamp}:${body.ordinal}", text = body.modifiedMessage.ifBlank { text }, time = body.serverTimestamp.toLong() * 1000, status = "Steam 已接受（未确认已读）"))
            dao.deleteMessage(message.id)
        } else {
            val failure = result.exceptionOrNull()
            val reason = when {
                !online.value -> "Steam 连接已断开，请重新连接后重试"
                failure is TimeoutCancellationException -> "等待 Steam 回执超时，结果未知，请先刷新历史"
                failure is IllegalStateException && failure.message in EResult.entries.map { it.toString() } -> "Steam 拒绝：${failure.message}"
                else -> "连接异常（${failure?.javaClass?.simpleName ?: "Unknown"}），结果未知"
            }
            android.util.Log.w("SteamLiteSend", "send failed online=${online.value} type=${failure?.javaClass?.simpleName}")
            dao.put(message.copy(status = "发送失败 · $reason"))
        }
    } }
    val historyLoading = MutableStateFlow(false)
    /** Independent catch-up request: must not cancel pagination or change its cursor. */
    suspend fun syncRecentMessages(peer: Long) {
        val accountOwner = owner
        if(accountOwner.isBlank() || !online.value) return
        val response = withTimeout(20000) { messaging.getRecentMessages(CFriendMessages_GetRecentMessages_Request.newBuilder().setSteamid1(accountOwner.toLong()).setSteamid2(peer).setCount(100).setMostRecentConversation(true).build()).await() }
        check(response.result == EResult.OK)
        if(owner != accountOwner || !online.value) return
        for(item in response.body.messagesList) {
            // Steam returns sticker messages with an empty body in history. Writing that
            // through would blank out the real "[sticker ...]" row we already have.
            if(item.message.isBlank()) continue
            val mine = (accountOwner.toLong() and 0xffffffffL) == Integer.toUnsignedLong(item.accountid)
            dao.put(Message("$accountOwner:$peer:${item.timestamp}:${item.ordinal}", accountOwner, peer, item.message, item.timestamp.toLong() * 1000, mine, "Steam 已同步"))
        }
    }
    val hasMoreHistory = MutableStateFlow(false)
    private val historyCursors = mutableMapOf<String, Pair<Int, Int>>()
    private var historyJob: Job? = null
    private var historyRequest = 0
    fun loadHistory(peer: Long, older: Boolean = false) {
        val accountOwner = owner; val key = "$accountOwner:$peer"
        val cursor = if(older) historyCursors[key] else null
        if(older && (cursor == null || historyLoading.value)) return
        if(!older) hasMoreHistory.value = false
        historyJob?.cancel(); val request = ++historyRequest
        historyLoading.value = true
        historyJob = scope.launch {
            historyStatus.value = if(older) "正在获取更早消息" else "正在获取近期消息"
            try {
                check(online.value)
                val payload = CFriendMessages_GetRecentMessages_Request.newBuilder().setSteamid1(accountOwner.toLong()).setSteamid2(peer).setCount(100).setMostRecentConversation(!older)
                cursor?.let { payload.setTimeLast(it.first).setOrdinalLast(it.second) }
                val response = withTimeout(20000) { messaging.getRecentMessages(payload.build()).await() }
                check(response.result == EResult.OK) { response.result.toString() }
                if(owner != accountOwner || !online.value || request != historyRequest) return@launch
                val messages = response.body.messagesList
                messages.forEach { item ->
                    // Skip empty history rows: stickers come back blank and would erase
                    // the locally stored "[sticker ...]" text.
                    if(item.message.isBlank()) return@forEach
                    val senderIsMe = (accountOwner.toLong() and 0xffffffffL) == (item.accountid.toLong() and 0xffffffffL)
                    dao.put(Message("$accountOwner:$peer:${item.timestamp}:${item.ordinal}", accountOwner, peer, item.message, item.timestamp.toLong() * 1000, senderIsMe, "Steam 历史"))
                }
                val earliest = messages.minWithOrNull(compareBy({ it.timestamp.toLong() and 0xffffffffL }, { it.ordinal.toLong() and 0xffffffffL }))
                val next = earliest?.let { it.timestamp to it.ordinal }
                if(next != null) historyCursors[key] = next
                hasMoreHistory.value = response.body.moreAvailable && next != null && next != cursor
                historyStatus.value = if(hasMoreHistory.value) "历史已同步，可加载更早消息" else "已加载可用历史"
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(request == historyRequest) historyStatus.value = "历史加载失败，保留本地缓存" }
            finally { if(request == historyRequest) historyLoading.value = false }
        }
    }
    suspend fun readWishlist(): List<Pair<Int, Int>> {
        check(online.value) { "请先登录 Steam" }
        val accountOwner = owner
        val response = withTimeout(20000) { wishlistService.get(accountOwner.toLong()).await() }
        check(response.result == EResult.OK) { "愿望单读取失败：${response.result}" }
        check(online.value && owner == accountOwner) { "账号会话已变化" }
        return response.body.itemsList.sortedBy { it.priority }.map { it.appid to it.priority }
    }
    suspend fun wishlistGames(ids: List<Int>): List<WishGame> {
        check(online.value) { "Steam 已断开" }
        val accountOwner = owner
        val response = priceRequests.request {
            check(online.value && owner == accountOwner) { "账号会话已变化" }
            withTimeout(20000) { storeBrowse.get(ids).await() }.also {
                if (it.result == EResult.RateLimitExceeded) throw PriceRateLimited()
                check(it.result == EResult.OK) { "批量价格读取失败：${it.result}" }
            }
        }
        check(online.value && owner == accountOwner) { "账号会话已变化" }
        return response.body.storeItemsList.map { item ->
            val purchase = item.bestPurchaseOption
            WishGame(if(item.appid > 0) item.appid else item.id, item.name, if(item.hasBestPurchaseOption()) purchase.finalPriceInCents else if(item.isFree) 0L else null,
                if(item.isFree) "免费" else purchase.formattedFinalPrice, purchase.discountPct,
                item.release.steamReleaseDate.toLong(), item.isComingSoon || item.release.isComingSoon, item.success == 1)
        }
    }
    /**
     * Short-lived access token derived from the stored refresh token over the already
     * authenticated CM session. Used only to build a store web session / WebAPI call;
     * never persisted and never written to logs.
     */
    suspend fun appAccessToken(): String {
        check(online.value) { "请先登录 Steam" }
        val id = owner.ifBlank { throw IllegalStateException("账号尚未就绪") }
        val refresh = token.ifEmpty { throw IllegalStateException("缺少会话令牌，请重新登录") }
        val result = withTimeout(30000) {
            client.authentication.generateAccessTokenForApp(SteamID(id.toLong()), refresh).await()
        }
        return result.accessToken.also { check(it.isNotBlank()) { "Steam 未返回访问令牌" } }
    }
    suspend fun changeWishlist(appId: Int, add: Boolean): List<Pair<Int, Int>> {
        require(appId > 0); check(online.value) { "请先登录 Steam" }
        val accountOwner = owner
        storeWeb.write(appId, add)
        // The write response is never trusted: read the authoritative server state back.
        repeat(6) { attempt ->
            if (attempt > 0) delay(if (attempt < 3) 800L else 1500L)
            val items = readWishlist()
            check(owner == accountOwner) { "账号会话已变化" }
            if (items.any { it.first == appId } == add) return items
        }
        throw IllegalStateException(if (add) "Steam 未在预期时间内显示该游戏，已回读但未确认" else "Steam 未在预期时间内移除该游戏，已回读但未确认")
    }
    suspend fun probeStoreSession(): String = storeWeb.probeStore()
    fun logout() { voice.disconnect(); friendPublishJob?.cancel(); gameTitleRetryJob?.cancel(); gameTitleRetries.clear(); gameTitlesLoading.value = false; personaGameJob?.cancel(); pendingPersonaGames.clear(); requestedPersonaGames.clear(); personaGameNames.clear(); personaHandler.snapshots.clear(); stickers.value = emptyList(); pinnedFriends.value = emptySet(); recentJob?.cancel(); recentSessions.value = emptyList(); storeWeb.invalidate(); historyJob?.cancel(); historyRequest++; historyLoading.value = false; hasMoreHistory.value = false; historyCursors.clear(); notifications.clear(); generation++; owner = ""; active = false; busy.value = false; watchdog?.cancel(); authenticationJob?.cancel(); online.value = false; selfAvatar.value = ""; code?.cancel(true); code = null; guard.value = ""; needsGuardCode.value = false; token = ""; password = ""; vault.clear(); people.value = emptyList(); status.value = "未登录"; scope.launch { user.logOff(); client.disconnect(); dao.clearMessages() } }
    fun close() { voice.disconnect(); storeWeb.invalidate(); active = false; authenticationJob?.cancel(); LogManager.removeListener(diagnostics); scope.launch { client.disconnect(); scope.cancel() } }
}
