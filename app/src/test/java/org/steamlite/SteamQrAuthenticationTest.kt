package org.steamlite

import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.*
import com.google.protobuf.ByteString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class SteamQrAuthenticationTest {
    private fun begin() = CAuthentication_BeginAuthSessionViaQR_Response.newBuilder().setClientId(12)
        .setChallengeUrl("https://s.team/q/1/12").setRequestId(ByteString.copyFromUtf8("test-session")).setInterval(5f).build()

    @Test fun httpQrRetainsTheClientTokenAudienceForChatLogon() {
        val request = SteamQrAuthentication.beginRequest()
        assertEquals(EAuthTokenPlatformType.k_EAuthTokenPlatformType_SteamClient, request.platformType)
        assertEquals("Client", request.websiteId)
        assertEquals("Steam Lite Android", request.deviceFriendlyName)
    }

    @Test fun challengeAppearsBeforePollingAndRotatedClientIdIsUsed() = runBlocking {
        val events = mutableListOf<String>()
        val ids = mutableListOf<Long>()
        val delays = mutableListOf<Long>()
        val credentials = pollQrSession(begin(), { request ->
            ids.add(request.clientId)
            assertEquals(ByteString.copyFromUtf8("test-session"), request.requestId)
            events.add("poll")
            if (ids.size == 1) CAuthentication_PollAuthSessionStatus_Response.newBuilder().setNewClientId(34)
                .setNewChallengeUrl("https://s.team/q/1/34").build()
            else CAuthentication_PollAuthSessionStatus_Response.newBuilder().setAccountName("test-account").setRefreshToken("test-token").build()
        }, { events.add(it) }, { delays.add(it) })
        assertEquals("https://s.team/q/1/12", events.first())
        assertEquals(listOf(12L, 34L), ids)
        assertEquals(listOf(5000L, 5000L), delays)
        assertEquals("test-account", credentials.account)
        assertEquals("test-token", credentials.token)
    }

    @Test fun unsafeQrSourcesAreRejected() {
        assertTrue(validSteamQrChallenge("https://s.team/q/1/123"))
        listOf("https://s.team.evil.example/q/1/123", "http://s.team/q/1/123", "https://user@s.team/q/1/123", "https://s.team:8443/q/1/123", "https://steamcommunity.com/").forEach {
            assertFalse(it, validSteamQrChallenge(it))
        }
    }

    @Test fun incompleteApprovalNeverReturnsCredentials() = runBlocking {
        try {
            pollQrSession(begin(), { CAuthentication_PollAuthSessionStatus_Response.newBuilder().setRefreshToken("test-token").build() }, {}, {})
            fail("Incomplete credentials must fail")
        } catch (_: IllegalStateException) { }
    }

    @Test fun cancellingAfterQrDisplayStopsPolling() = runBlocking {
        val visible = CompletableDeferred<Unit>()
        var polls = 0
        val job = launch {
            pollQrSession(begin(), { polls++; error("Polling after cancellation") }, { visible.complete(Unit) }, { awaitCancellation() })
        }
        visible.await()
        job.cancelAndJoin()
        assertEquals(0, polls)
    }
}
