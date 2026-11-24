package org.steamlite

import org.json.JSONArray
import org.json.JSONObject

internal object OwnedLibrary {
    fun search(games: List<OwnedGame>, query: String): List<OwnedGame> {
        val term = query.trim()
        return games.filter { term.isEmpty() || it.name.contains(term, ignoreCase = true) || it.id.toString().contains(term) }
            .sortedWith(compareByDescending<OwnedGame> { it.minutes }.thenBy { it.name.lowercase(java.util.Locale.ROOT) })
    }
    fun encode(owner: String, stats: LibraryStats): String = JSONObject().put("owner", owner).put("count", stats.count)
        .put("country", stats.region?.country).put("currency", stats.region?.currency)
        .put("cents", stats.cents).put("priced", stats.priced).put("games", JSONArray().apply {
            stats.games.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("minutes", it.minutes).put("recent", it.recent)) }
        }).toString()
    fun decode(owner: String, raw: String): LibraryStats? = runCatching {
        val json = JSONObject(raw)
        if (owner.isBlank() || json.optString("owner") != owner) return null
        val rows = json.getJSONArray("games")
        val games = (0 until rows.length()).mapNotNull { index ->
            val game = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = game.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
            OwnedGame(id, game.optString("name"), game.optLong("minutes").coerceAtLeast(0), game.optLong("recent").coerceAtLeast(0))
        }.distinctBy { it.id }
        val region = StoreRegion.from(json.optString("country"), json.optString("currency"))
        LibraryStats(json.optInt("count", games.size), games, if (region == null) 0 else json.optLong("cents").coerceAtLeast(0), if (region == null) 0 else json.optInt("priced").coerceAtLeast(0), pricing = false, region = region)
    }.getOrNull()
}
