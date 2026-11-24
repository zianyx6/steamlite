package org.steamlite

import org.junit.Assert.*
import org.junit.Test

class OwnedLibraryTest {
    private val games = listOf(OwnedGame(10, "Counter-Strike", 120, 10), OwnedGame(20, "Hades", 600, 0), OwnedGame(30, "我的游戏", 0, 0))
    @Test fun searchesOnlyOwnedGamesAndDefaultsToMostPlayed() {
        assertEquals(listOf(20, 10, 30), OwnedLibrary.search(games, "").map { it.id })
        assertEquals(listOf(20), OwnedLibrary.search(games, " haDES ").map { it.id })
        assertEquals(listOf(10), OwnedLibrary.search(games, "10").map { it.id })
        assertEquals(listOf(30), OwnedLibrary.search(games, "我的").map { it.id })
        assertTrue(OwnedLibrary.search(games, "not owned").isEmpty())
    }
    @Test fun cacheSurvivesReconnectButNeverCrossesAccountBoundaries() {
        val stats = LibraryStats(3, games, 10000, 2, pricing = true)
        val encoded = OwnedLibrary.encode("account-a", stats)
        val restored = OwnedLibrary.decode("account-a", encoded)!!
        assertEquals(games, restored.games)
        assertEquals(10000L, restored.cents)
        assertFalse(restored.pricing)
        assertNull(OwnedLibrary.decode("account-b", encoded))
        assertNull(OwnedLibrary.decode("", encoded))
        assertNull(OwnedLibrary.decode("account-a", "broken"))
    }
}
