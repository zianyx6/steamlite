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
import kotlinx.coroutines.sync.withLock
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
    private val qrAuthentication = SteamQrAuthentication()
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
    private val accountService = client.getHandler(SteamUnifiedMessages::class.java)!!.createService<`in`.dragonbra.javasteam.rpc.service.UserAccount>()
    private val regionLock = kotlinx.coroutines.sync.Mutex()
    val storeRegion = MutableStateFlow<StoreRegion?>(null)
    val storeRegionStatus = MutableStateFlow("登录后读取账号商店地区")
    suspend fun requireStoreRegion(refresh: Boolean = false): StoreRegion = regionLock.withLock {
        if (!refresh) storeRegion.value?.let { return@withLock it }
        check(online.value && owner.isNotBlank()) { "账号商店地区尚未就绪，请连接 Steam 后重试" }
        val accountOwner = owner
        storeRegionStatus.value = "正在读取账号商店地区"
        try {
            val response = withTimeout(20000) {
                accountService.getClientWalletDetails(`in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesUseraccountSteamclient.CUserAccount_GetClientWalletDetails_Request.getDefaultInstance()).await()
            }
            check(response.result == EResult.OK) { "商店地区读取失败：${response.result}" }
            val body = response.body
            val country = body.walletCountryCode.ifBlank { body.userCountryCode }.ifBlank {
                val result = withTimeout(15000) { accountService.getUserCountry(`in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesUseraccountSteamclient.CUserAccount_GetUserCountry_Request.getDefaultInstance()).await() }
                check(result.result == EResult.OK) { "账号地区读取未完成" }
                result.body.country
            }.uppercase(java.util.Locale.ROOT)
            val walletCurrency = ECurrencyCode.from(body.currencyCode)?.name.orEmpty()
            val currency = if (StoreRegion.from(country, walletCurrency) != null) walletCurrency else StoreCurrencyResolver.resolve(country)
            val region = StoreRegion.from(country, currency) ?: error("Steam 尚未返回有效的商店地区和币种，请稍后重试")
            check(online.value && owner == accountOwner) { "账号会话已变化" }
            dao.cache(CachedResponse("account-store-region:$accountOwner", JSONObject().put("country", region.country).put("currency", region.currency).toString(), System.currentTimeMillis()))
            synchronized(loginHandshake) {
                check(online.value && owner == accountOwner) { "账号会话已变化" }
                storeRegion.value = region
                storeRegionStatus.value = region.label
            }
            region
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (owner == accountOwner) storeRegionStatus.value = "账号商店地区读取未完成，可重试"
            throw e
        }
    }
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
    val hasSession = MutableStateFlow(false)
    val sessionOwner = MutableStateFlow("")
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
    var owner: String
        get() = sessionOwner.value
        private set(value) {
            if (value != sessionOwner.value) storeRegion.value = null
            sessionOwner.value = value
        }
    val steamId: String get() = owner
    private var account = ""
    private var token = ""
    private var password = ""
    private var active = false
    private var retry = 0
    private var reconnectJob: Job? = null
    private var preferDirectory = false
    private var networkAvailable = true
    private var code: CompletableFuture<String>? = null
    private var authenticationJob: Job? = null
    private val loginHandshake = SteamLoginHandshake()
    val loginClosing = loginHandshake.closing
    private var openingTransport: Job? = null
    private var closingTransport: Job? = null
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
        manager.subscribe(ConnectedCallback::class.java) { synchronized(loginHandshake) {
            val transport = loginHandshake.transport ?: return@subscribe
            val attempt = generation
            if (!isAuthenticating(attempt, transport)) return@subscribe
            watchdog?.cancel()
            if (probe) {
                probe = false
                endLogin("Steam CM 安全连接成功，可以登录")
                return@subscribe
            }
            val scanning = qrLogin.value
            val needsAuthentication = token.isEmpty() && loginHandshake.approved == null
            val loginName = account
            val secret = password
            status.value = "Steam 已连接，正在认证"
            authenticationJob = scope.launch {
                runCatching {
                    val result = if (needsAuthentication) {
                        val details = AuthSessionDetails().apply {
                            username = loginName; password = secret; persistentSession = true
                            deviceFriendlyName = "Steam Lite Android"
                            authenticator = object : IAuthenticator {
                                override fun getDeviceCode(previousCodeWasIncorrect: Boolean) = requestLoginCode(attempt, transport, if (previousCodeWasIncorrect) "验证码错误，请重新输入 Steam Guard" else "输入 Steam Guard 验证码")
                                override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean) = requestLoginCode(attempt, transport, if (previousCodeWasIncorrect) "验证码错误，请重新输入邮件验证码" else "输入 Steam 邮件验证码")
                                override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> = synchronized(loginHandshake) {
                                    if (!isAuthenticating(attempt, transport)) return@synchronized CompletableFuture.completedFuture(false)
                                    phoneConfirmationPending.value = true
                                    needsGuardCode.value = concurrentGuardSession != null
                                    guard.value = if (needsGuardCode.value) "可在 Steam 手机应用批准登录，也可直接输入令牌码；任一方式验证成功即可登录" else "请在 Steam 官方手机应用确认此次登录，确认后将自动继续"
                                    CompletableFuture.completedFuture(true)
                                }
                            }
                        }
                        synchronized(loginHandshake) {
                            if (!isAuthenticating(attempt, transport)) return@launch
                            status.value = if (scanning) "正在获取 Steam 登录二维码" else "正在请求 Steam 认证，等待 Steam Guard 挑战"
                        }
                        withTimeout(180000) {
                            if (scanning) {
                                val session = client.authentication.beginAuthSessionViaQR(details, this).await()
                                synchronized(loginHandshake) {
                                    ensureActive()
                                    if (!isAuthenticating(attempt, transport)) throw CancellationException()
                                    qrChallenge.value = session.challengeUrl
                                    status.value = "等待 Steam 手机应用扫码确认"
                                }
                                // Steam reports seconds. JavaSteam 1.8.0's polling loop delays in milliseconds.
                                val intervalMillis = (session.pollingInterval * 1000).toLong().coerceAtLeast(1000L)
                                var approval: AuthPollResult?
                                do {
                                    delay(intervalMillis)
                                    approval = session.pollAuthSessionStatus(this).await()
                                    synchronized(loginHandshake) {
                                        ensureActive()
                                        if (!isAuthenticating(attempt, transport)) throw CancellationException()
                                        if (approval == null) qrChallenge.value = session.challengeUrl
                                    }
                                } while (approval == null)
                                approval
                            } else {
                                val session = client.authentication.beginAuthSessionViaCredentials(details).await()
                                session.allowedConfirmations = preferPhoneApproval(session.allowedConfirmations)
                                synchronized(loginHandshake) {
                                    ensureActive()
                                    if (!isAuthenticating(attempt, transport)) throw CancellationException()
                                    activeGuardSession = session
                                    if (supportsConcurrentGuard(session.allowedConfirmations)) concurrentGuardSession = session
                                }
                                try { session.pollingWaitForResult().await() }
                                finally { synchronized(loginHandshake) { clearConcurrentGuard(session) } }
                            }
                        }.also { details.password = null }
                    } else null
                    synchronized(loginHandshake) {
                        ensureActive()
                        if (!isAuthenticating(attempt, transport)) return@launch
                        if (result != null) {
                            check(result.accountName.isNotBlank() && result.refreshToken.isNotBlank()) { "Steam 未返回完整的会话凭据" }
                            loginHandshake.approve(SteamLoginHandshake.Credentials(result.accountName, result.refreshToken))
                            password = ""
                        }
                        if (scanning) {
                            qrChallenge.value = null
                            status.value = "扫码已确认，正在登录 Steam"
                        }
                        guard.value = ""
                        needsGuardCode.value = false
                        loginHandshake.submit(SteamLoginHandshake.Credentials(account, token)) { credentials ->
                            user.logOn(LogOnDetails().apply {
                                this.username = credentials.account
                                accessToken = credentials.token
                                loginID = vault.loginId
                                chatMode = ChatMode.NEW_STEAM_CHAT
                                uiMode = EUIMode.MobileChat
                            })
                        }
                        watchdog?.cancel()
                        watchdog = scope.launch {
                            delay(30000)
                            synchronized(loginHandshake) {
                                if (isAuthenticating(attempt, transport) && !online.value) {
                                    status.value = "登录响应超时，正在重新连接"
                                    disconnectTransport(logOff = false)
                                }
                            }
                        }
                    }
                }.onFailure {
                    synchronized(loginHandshake) {
                        if (isAuthenticating(attempt, transport) && (it !is CancellationException || it is TimeoutCancellationException)) {
                            val cause = generateSequence(it) { failure -> failure.cause }.last()
                            val resultCode = (cause as? AuthenticationException)?.result
                            android.util.Log.w("SteamLiteAuth", "Authentication failed: ${cause.javaClass.simpleName}, Steam result=${resultCode ?: "unavailable"}")
                            endLogin(if (it is TimeoutCancellationException) "认证超时，请重试" else "认证失败：${resultCode ?: cause.javaClass.simpleName}")
                        }
                    }
                }
            }
        } }
        manager.subscribe(LoggedOnCallback::class.java) { callback ->
            android.util.Log.i("SteamLiteSession", "logon result=${callback.result}")
            val signedIn = synchronized(loginHandshake) {
                val steamId = client.steamID
                // The network may already have disconnected while this result was queued.
                // Its disconnect callback will retire the transport and retry if appropriate.
                if (callback.result == EResult.OK && steamId == null) return@subscribe
                loginHandshake.complete { finishLogon(callback.result, steamId, it) } == true
            }
            if (signedIn) {
                scope.launch { runCatching { requireStoreRegion(refresh = true) } }
                scope.launch { runCatching { refreshFriendRemarks() } }
                refreshRecentSessions()
                scope.launch { loadSelfAvatar() }
                scope.launch { runCatching { dao.deleteEmptyMessages() } }
                friends.setPersonaState(EPersonaState.Online)
                scope.launch { runCatching { withTimeout(20000) { personaChat.requestFriendPersonaStates(`in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChat_RequestFriendPersonaStates_Request.getDefaultInstance()).await() } } }
            }
        }
        manager.subscribe(LoggedOffCallback::class.java) { event -> synchronized(loginHandshake) {
            android.util.Log.i("SteamLiteSession", "logged_off result=${event.result}")
            if (!active || loginHandshake.closing.value) return@subscribe
            online.value = false
            if (SessionRecovery.revoked(event.result)) {
                token = ""; hasSession.value = false; owner = ""; vault.clear()
                endLogin("Steam 登录凭据已失效，请重新登录")
            } else {
                preferDirectory = true
                status.value = "Steam 会话中断，正在恢复连接"
                disconnectTransport(logOff = false)
            }
        } }
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
                        dao.putFromSteam(Message("$accountOwner:${body.steamidFriend}:${body.rtime32ServerTimestamp}:${body.ordinal}", accountOwner, body.steamidFriend, body.message, body.rtime32ServerTimestamp.toLong() * 1000, body.localEcho, if(body.localEcho) "其他 Steam 客户端已发送" else "已接收"))
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
        manager.subscribe(DisconnectedCallback::class.java) { synchronized(loginHandshake) {
            // A queued event from the retired socket must not tear down an online replacement.
            if (client.isConnected) return@subscribe
            // HTTPS QR acquisition has no chat transport yet; a retired socket cannot cancel it.
            if (active && qrLogin.value && loginHandshake.transport == null) return@subscribe
            authenticationJob?.cancel()
            loginHandshake.disconnected()
            openingTransport = null
            closingTransport = null
            qrChallenge.value = null
            clearConcurrentGuard()
            readingPeer = null
            if (owner.isBlank()) { remarks.value = emptyMap(); readTimes.clear() }
            voice.disconnect()
            online.value = false
            when {
                !active || !networkAvailable -> busy.value = false
                !fallbackTried -> {
                    fallbackTried = true
                    preferDirectory = true
                    status.value = "节点连接失败，正在查询 Steam 服务器目录"
                    openTransport(useDirectory = true)
                }
                token.isNotEmpty() || loginHandshake.approved != null -> {
                    status.value = "连接中断，正在重连"
                    scheduleReconnect()
                }
                else -> endLogin("连接失败，请重试")
            }
        } }
        synchronized(loginHandshake) {
            vault.read()?.let { saved -> runCatching {
                val json = JSONObject(saved)
                account = json.getString("account")
                token = json.getString("token")
                hasSession.value = token.isNotBlank()
                owner = json.optString("steamId").takeIf { it.matches(Regex("[0-9]{17}")) } ?: SessionRecovery.cachedOwner(token)
                val cachedOwner = owner
                scope.launch {
                    val cached = dao.cached("account-store-region:$cachedOwner")
                    val region = cached?.let { runCatching { JSONObject(it.body).let { j -> StoreRegion.from(j.getString("country"), j.getString("currency")) } }.getOrNull() }
                    synchronized(loginHandshake) {
                        if (owner == cachedOwner && storeRegion.value == null) storeRegion.value = region
                    }
                }
                connect()
            } }
        }
        scope.launch { while (isActive) { runCatching { manager.runWaitCallbacks(500) }; delay(10) } }
    }
    private fun requestLoginCode(attempt: Int, transport: SteamLoginHandshake.Transport, prompt: String): CompletableFuture<String> = synchronized(loginHandshake) {
        if (!isAuthenticating(attempt, transport)) return@synchronized CompletableFuture<String>().apply { cancel(false) }
        phoneConfirmationPending.value = false; guardCodeSubmitting.value = false
        val pending = CompletableFuture<String>()
        code = pending; guard.value = prompt; needsGuardCode.value = true
        pending
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
    fun login(name: String, pass: String) = synchronized(loginHandshake) {
        if (!canStartLogin()) return@synchronized
        if (!authReady) { status.value = "加密组件尚未准备完成，请稍后重试"; return@synchronized }
        startInteractiveLogin(name.trim(), pass, scanning = false)
    }

    fun cancelLogin() = synchronized(loginHandshake) {
        if (!active || online.value) return@synchronized
        if (interactiveLogin) token = ""
        endLogin("登录已取消")
    }

    fun loginWithQr() = synchronized(loginHandshake) {
        if (!canStartLogin()) return@synchronized
        probe = false
        interactiveLogin = true
        loginSucceeded.value = false
        guard.value = ""
        generation++
        val attempt = generation
        account = ""; password = ""; token = ""
        qrLogin.value = true
        qrChallenge.value = null
        active = true
        busy.value = true
        status.value = "正在获取 Steam 登录二维码"
        authenticationJob = scope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                val approved = withTimeout(180000) {
                    qrAuthentication.authenticate { challenge -> synchronized(loginHandshake) {
                        ensureActive()
                        if (!active || generation != attempt) throw CancellationException()
                        if (qrChallenge.value == null) android.util.Log.i("SteamLiteQr", "HTTPS challenge ready in ${android.os.SystemClock.elapsedRealtime() - started}ms")
                        qrChallenge.value = challenge
                        status.value = "等待 Steam 手机应用扫码确认"
                    } }
                }
                synchronized(loginHandshake) {
                    ensureActive()
                    if (!active || generation != attempt) return@launch
                    loginHandshake.approve(approved)
                    qrChallenge.value = null
                    connect()
                    status.value = "扫码已确认，正在连接 Steam"
                }
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException) synchronized(loginHandshake) {
                    if (active && generation == attempt) endLogin("二维码已超时，请重新获取")
                } else throw error
            } catch (error: Exception) {
                synchronized(loginHandshake) {
                    if (!active || generation != attempt) return@launch
                    if (qrChallenge.value == null && error is java.io.IOException) {
                        // Preserve the already-tested CM authentication route if HTTPS cannot be reached.
                        connect()
                        status.value = "正在通过备用通道获取二维码"
                    } else endLogin("二维码登录失败，请重试（${error.javaClass.simpleName}）")
                }
            }
        }
    }

    private fun canStartLogin() = !active && !online.value && loginHandshake.transport == null

    private fun finishLogon(result: EResult, steamId: SteamID?, logon: SteamLoginHandshake.Logon): Boolean {
        watchdog?.cancel()
        if (result != EResult.OK) {
            if (SessionRecovery.transient(result)) {
                if (logon.persist) loginHandshake.approve(logon.credentials)
                preferDirectory = true
                status.value = "Steam 暂时无法登录，正在重试（$result）"
                disconnectTransport(logOff = false)
                return false
            }
            if (!logon.persist && SessionRecovery.revoked(result)) {
                token = ""
                hasSession.value = false
                owner = ""
                vault.clear()
            }
            endLogin("登录失败：$result")
            return false
        }
        val credentials = logon.credentials
        run {
            val saved = runCatching {
                vault.save(JSONObject().put("account", credentials.account).put("token", credentials.token)
                    .put("steamId", checkNotNull(steamId).convertToUInt64().toString()).toString())
            }
            if (saved.isFailure && logon.persist) {
                endLogin("无法保存 Steam 会话，请重试")
                return false
            }
        }
        account = credentials.account
        token = credentials.token
        owner = checkNotNull(steamId).convertToUInt64().toString()
        hasSession.value = true
        online.value = true
        retry = 0
        status.value = "已连接 Steam"
        guard.value = ""
        needsGuardCode.value = false
        clearQrLogin()
        busy.value = false
        if (interactiveLogin) {
            interactiveLogin = false
            loginSucceeded.value = true
        }
        return true
    }

    private fun isAuthenticating(attempt: Int, transport: SteamLoginHandshake.Transport) =
        active && generation == attempt && loginHandshake.transport === transport && !loginHandshake.closing.value

    private fun startInteractiveLogin(name: String, pass: String, scanning: Boolean) {
        probe = false
        interactiveLogin = true
        loginSucceeded.value = false
        guard.value = ""
        generation++
        account = name
        password = pass
        token = ""
        qrLogin.value = scanning
        connect()
    }

    private fun clearQrLogin() {
        qrChallenge.value = null
        qrLogin.value = false
    }
    fun resumeSession() = synchronized(loginHandshake) {
        if (networkAvailable && token.isNotBlank() && !online.value && loginHandshake.transport == null && closingTransport?.isActive != true && openingTransport?.isActive != true) connect()
    }

    fun networkChanged(available: Boolean) = synchronized(loginHandshake) {
        networkAvailable = available
        if (available) resumeSession()
        else if (active && (hasSession.value || loginHandshake.approved != null)) {
            online.value = false
            status.value = "网络不可用，等待恢复连接"
            reconnectJob?.cancel()
            disconnectTransport(logOff = false)
        }
    }

    fun probeConnection() = synchronized(loginHandshake) {
        if (!canStartLogin()) return@synchronized
        probe = true
        connect()
    }

    private fun endLogin(message: String, logOff: Boolean = false) {
        active = false
        generation++
        interactiveLogin = false
        probe = false
        loginHandshake.discardApproval()
        clearQrLogin()
        clearConcurrentGuard()
        watchdog?.cancel()
        authenticationJob?.cancel()
        reconnectJob?.cancel()
        code?.cancel(true)
        code = null
        password = ""
        guard.value = ""
        needsGuardCode.value = false
        online.value = false
        busy.value = loginHandshake.transport != null
        status.value = message
        disconnectTransport(logOff)
    }

    private fun connect() {
        reconnectJob?.cancel()
        active = true; busy.value = true; fallbackTried = false; status.value = "正在连接 Steam"
        openTransport(useDirectory = preferDirectory)
    }

    private fun scheduleReconnect() {
        if (!networkAvailable || reconnectJob?.isActive == true) return
        val attempt = generation
        val wait = SessionRecovery.delayMillis(retry++)
        reconnectJob = scope.launch {
            delay(wait)
            synchronized(loginHandshake) {
                if (active && generation == attempt && loginHandshake.transport == null) connect()
            }
        }
    }

    private fun openTransport(useDirectory: Boolean) {
        val transport = loginHandshake.openTransport()
        busy.value = true
        val attempt = generation
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(45000)
            synchronized(loginHandshake) {
                if (isAuthenticating(attempt, transport) && !client.isConnected) endLogin("Steam 连接超时，请检查网络后重试")
            }
        }
        openingTransport = scope.launch {
            runCatching {
                if (useDirectory) client.connect()
                else client.connect(ServerRecord.createWebSocketServer(SmartCMServerList.defaultServerWebSocket))
            }.onFailure {
                synchronized(loginHandshake) {
                    if (loginHandshake.transport !== transport) return@synchronized
                    // A failure before JavaSteam creates a transport has no disconnect callback.
                    if (client.isDisconnected) loginHandshake.disconnected()
                    if (active) endLogin("Steam 连接异常（${it.javaClass.simpleName}）")
                    else if (loginHandshake.transport == null) busy.value = false
                }
            }
        }
    }

    private fun disconnectTransport(logOff: Boolean) {
        val transport = loginHandshake.closeTransport() ?: return
        val opening = openingTransport
        closingTransport = scope.launch {
            // A hung connect() must not stall closing forever.
            runCatching { withTimeout(5000) { opening?.join() } }
            synchronized(loginHandshake) {
                if (loginHandshake.transport !== transport) return@synchronized
                // JavaSteam queues callbacks without request IDs. Wait for its disconnect
                // event to retire this transport before permitting another logon.
                val hadConnection = !client.isDisconnected
                runCatching {
                    if (logOff) user.logOff() else client.disconnect()
                }.onFailure { runCatching { client.disconnect() } }
                // JavaSteam does not guarantee a DisconnectedCallback: disconnecting without
                // a connection is a no-op and a failed socket close can skip the callback.
                // Retire the transport ourselves once the client is already down.
                if (!hadConnection) retireClosedTransport(transport)
            }
            if (loginHandshake.transport !== transport) return@launch
            delay(5000)
            synchronized(loginHandshake) {
                if (loginHandshake.transport !== transport) return@synchronized
                // The disconnect callback never arrived; force the transport open again so
                // a wedged closing state cannot lock out the next login.
                retireClosedTransport(transport)
            }
        }
    }

    /** Completes transport cleanup after endLogin has already cleared the logical session. */
    private fun retireClosedTransport(transport: SteamLoginHandshake.Transport) {
        if (!loginHandshake.disconnected(transport)) return
        openingTransport = null
        closingTransport = null
        busy.value = false
        if (active && (token.isNotBlank() || loginHandshake.approved != null)) scheduleReconnect()
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
            dao.putFromSteam(message.copy(id = "${message.owner}:$peer:${body.serverTimestamp}:${body.ordinal}", text = ChatContent.confirmedText(text, body.modifiedMessage), time = body.serverTimestamp.toLong() * 1000, status = "Steam 已接受（未确认已读）"))
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
            dao.putFromSteam(Message("$accountOwner:$peer:${item.timestamp}:${item.ordinal}", accountOwner, peer, item.message, item.timestamp.toLong() * 1000, mine, "Steam 已同步"))
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
                    dao.putFromSteam(Message("$accountOwner:$peer:${item.timestamp}:${item.ordinal}", accountOwner, peer, item.message, item.timestamp.toLong() * 1000, senderIsMe, "Steam 历史"))
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
        val region = requireStoreRegion()
        val response = priceRequests.request {
            check(online.value && owner == accountOwner) { "账号会话已变化" }
            check(storeRegion.value == region) { "商店地区已变化，请重新同步" }
            withTimeout(20000) { storeBrowse.get(ids, region.country).await() }.also {
                if (it.result == EResult.RateLimitExceeded) throw PriceRateLimited()
                check(it.result == EResult.OK) { "批量价格读取失败：${it.result}" }
            }
        }
        check(online.value && owner == accountOwner && storeRegion.value == region) { "账号或商店地区已变化" }
        return response.body.storeItemsList.map { item ->
            val purchase = item.bestPurchaseOption
            WishGame(if(item.appid > 0) item.appid else item.id, item.name, if(item.hasBestPurchaseOption()) purchase.finalPriceInCents else if(item.isFree) 0L else null,
                if(item.isFree) "免费" else purchase.formattedFinalPrice, purchase.discountPct,
                item.release.steamReleaseDate.toLong(), item.isComingSoon || item.release.isComingSoon, item.success == 1, region.key)
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
    fun logout() {
        val previousOwner = owner
        synchronized(loginHandshake) {
            endLogin("未登录", logOff = online.value)
            owner = ""
            token = ""
            hasSession.value = false
            vault.clear()
        }
        voice.disconnect()
        friendPublishJob?.cancel()
        gameTitleRetryJob?.cancel()
        gameTitleRetries.clear()
        gameTitlesLoading.value = false
        personaGameJob?.cancel()
        pendingPersonaGames.clear()
        requestedPersonaGames.clear()
        personaGameNames.clear()
        personaHandler.snapshots.clear()
        stickers.value = emptyList()
        pinnedFriends.value = emptySet()
        recentJob?.cancel()
        recentSessions.value = emptyList()
        storeWeb.invalidate()
        historyJob?.cancel()
        historyRequest++
        historyLoading.value = false
        hasMoreHistory.value = false
        historyCursors.clear()
        notifications.clear()
        selfAvatar.value = ""
        people.value = emptyList()
        scope.launch { dao.clearMessages(); if (previousOwner.isNotBlank()) dao.deleteCache("owned-library:$previousOwner") }
    }

    fun close() {
        val disconnect = synchronized(loginHandshake) {
            endLogin("未登录")
            closingTransport
        }
        voice.disconnect()
        storeWeb.invalidate()
        LogManager.removeListener(diagnostics)
        scope.launch { disconnect?.join(); scope.cancel() }
    }
}
