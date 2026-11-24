package org.steamlite

import `in`.dragonbra.javasteam.enums.EResult
import org.json.JSONObject
import java.util.Base64

internal object SessionRecovery {
    fun revoked(result: EResult) = result in setOf(EResult.InvalidPassword, EResult.Expired, EResult.Revoked)
    fun transient(result: EResult) = result in setOf(EResult.NoConnection, EResult.Busy, EResult.Timeout,
        EResult.ServiceUnavailable, EResult.ConnectFailed, EResult.TryAnotherCM, EResult.RateLimitExceeded,
        EResult.LogonSessionReplaced, EResult.LoggedInElsewhere, EResult.AlreadyLoggedInElsewhere)
    fun delayMillis(attempt: Int) = (2000L shl attempt.coerceIn(0, 4)).coerceAtMost(30000L)

    /** Owner hint for local caches only; never used to approve authentication. */
    fun cachedOwner(token: String): String = runCatching {
        val payload = token.split('.').getOrNull(1)?.takeIf { it.length < 16384 } ?: return ""
        JSONObject(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)).optString("sub")
            .takeIf { it.matches(Regex("[0-9]{17}")) }.orEmpty()
    }.getOrDefault("")
}
