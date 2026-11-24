package org.steamlite

import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.*
import com.google.protobuf.MessageLite
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public Steam HTTPS authentication, independent of the chat transport. No credentials are persisted here. */
internal class SteamQrAuthentication {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun authenticate(onChallenge: (String) -> Unit): SteamLoginHandshake.Credentials {
        val begin = CAuthentication_BeginAuthSessionViaQR_Response.parseFrom(call("BeginAuthSessionViaQR", beginRequest()))
        return pollQrSession(begin, { request ->
            CAuthentication_PollAuthSessionStatus_Response.parseFrom(call("PollAuthSessionStatus", request))
        }, onChallenge)
    }

    private suspend fun call(method: String, message: MessageLite): ByteArray {
        val body = FormBody.Builder().add("input_protobuf_encoded", Base64.getEncoder().encodeToString(message.toByteArray())).build()
        val request = Request.Builder().url("https://api.steampowered.com/IAuthenticationService/$method/v1/")
            .header("User-Agent", "SteamLite/1.0 Android").post(body).build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        if (!it.isSuccessful) throw IOException("Steam HTTPS ${it.code}")
                        val status = it.header("x-eresult")?.toIntOrNull()
                        check(status == null || status == 1) { "Steam 二维码认证未通过（$status）" }
                        val bytes = it.body?.bytes() ?: throw IOException("Steam 返回空响应")
                        check(bytes.isNotEmpty() && bytes.size < 64 * 1024) { "Steam 二维码响应不可用" }
                        bytes
                    } }
                    if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                }
            })
        }
    }

    companion object {
        internal fun beginRequest(): CAuthentication_BeginAuthSessionViaQR_Request {
            // Keep the same token audience as the existing CM login, rather than copying an authenticator app's mobile token type.
            val platform = EAuthTokenPlatformType.k_EAuthTokenPlatformType_SteamClient
            return CAuthentication_BeginAuthSessionViaQR_Request.newBuilder().setDeviceFriendlyName("Steam Lite Android")
                .setPlatformType(platform).setWebsiteId("Client")
                .setDeviceDetails(CAuthentication_DeviceDetails.newBuilder().setDeviceFriendlyName("Steam Lite Android")
                    .setPlatformType(platform).setOsType(EOSType.AndroidUnknown.code())).build()
        }
    }
}

internal fun validSteamQrChallenge(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && uri.host == "s.team" && uri.rawUserInfo == null && uri.port == -1 && uri.path.startsWith("/q/")
}.getOrDefault(false)

internal suspend fun pollQrSession(
    begin: CAuthentication_BeginAuthSessionViaQR_Response,
    poll: suspend (CAuthentication_PollAuthSessionStatus_Request) -> CAuthentication_PollAuthSessionStatus_Response,
    onChallenge: (String) -> Unit,
    pause: suspend (Long) -> Unit = { delay(it) },
): SteamLoginHandshake.Credentials {
    check(begin.clientId != 0L && !begin.requestId.isEmpty && validSteamQrChallenge(begin.challengeUrl)) { "Steam 未返回有效登录二维码" }
    var clientId = begin.clientId
    onChallenge(begin.challengeUrl)
    val interval = (begin.interval * 1000).toLong().coerceIn(1000, 10000)
    while (true) {
        pause(interval)
        val response = poll(CAuthentication_PollAuthSessionStatus_Request.newBuilder().setClientId(clientId).setRequestId(begin.requestId).build())
        if (response.hasNewClientId() && response.newClientId != 0L) clientId = response.newClientId
        if (response.refreshToken.isNotBlank()) {
            check(response.accountName.isNotBlank()) { "Steam 未返回完整登录凭据" }
            return SteamLoginHandshake.Credentials(response.accountName, response.refreshToken)
        }
        if (response.newChallengeUrl.isNotBlank()) {
            check(validSteamQrChallenge(response.newChallengeUrl)) { "Steam 返回的二维码地址无效" }
            onChallenge(response.newChallengeUrl)
        }
    }
}
