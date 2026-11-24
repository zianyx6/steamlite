package org.steamlite

import `in`.dragonbra.javasteam.enums.EResult
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SessionRecoveryTest {
    @Test fun transientNetworkFailuresAndSessionReplacementMustNotRevokeSavedCredentials() {
        listOf(EResult.NoConnection, EResult.ServiceUnavailable, EResult.TryAnotherCM, EResult.Timeout,
            EResult.LogonSessionReplaced, EResult.LoggedInElsewhere).forEach { result ->
            assertTrue(SessionRecovery.transient(result))
            assertFalse(SessionRecovery.revoked(result))
        }
        assertFalse(SessionRecovery.revoked(EResult.AccessDenied))
    }
    @Test fun confirmedRevocationRequiresFreshAuthentication() {
        listOf(EResult.InvalidPassword, EResult.Expired, EResult.Revoked).forEach { assertTrue(SessionRecovery.revoked(it)) }
    }
    @Test fun retriesBackOffAndStayBounded() {
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L, 30000L, 30000L), (0..5).map(SessionRecovery::delayMillis))
    }
    @Test fun legacyTokenOwnerIsOnlyAStrictCacheHint() {
        fun token(payload: String) = "header." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".signature"
        assertEquals("76561198000000000", SessionRecovery.cachedOwner(token("""{"sub":"76561198000000000"}""")))
        listOf("invalid", token("""{"sub":"../../other-account"}"""), token("{}"), token("not-json")).forEach {
            assertEquals("", SessionRecovery.cachedOwner(it))
        }
    }
}
