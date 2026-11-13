package org.steamlite

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class ActivityKind { Review, Screenshot, OwnedGame, Other }
data class ActivityReply(val author: String, val text: String)
data class ActivityGame(val title: String, val url: String?, val appId: Int?, val cover: String? = null)
data class FriendActivity(
    val id: String, val day: String, val text: String, val images: List<String>,
    val links: List<Pair<String, String>>, val author: String = "", val avatar: String? = null,
    val kind: ActivityKind = ActivityKind.Other, val games: List<ActivityGame> = emptyList(),
    val likes: String? = null, val commentCount: String? = null,
    val replies: List<ActivityReply> = emptyList(),
    val recommendsGame: Boolean? = null, val authorSteamId: Long? = null
)
object FriendActivityParser {
    /** Jsoup resolves an empty src to the document URL, which is HTML, not an image. */
    private fun imageSource(image: org.jsoup.nodes.Element): String? =
        listOf("data-src", "data-lazy-src", "data-original", "src").firstNotNullOfOrNull { attribute ->
            if (image.attr(attribute).isBlank()) null else image.absUrl(attribute).takeIf { url ->
                ChatContent.mediaUrl(url) && url.toHttpUrlOrNull()?.encodedPath?.let { it.isNotBlank() && it != "/" } == true
            }
        }
    fun allowedPage(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        return u.scheme == "https" && u.host == "steamcommunity.com" && u.port == 443 && u.username.isEmpty() && u.password.isEmpty() && Regex("/(profiles/[0-9]+|id/[^/]+)/ajaxgetusernews/?").matches(u.encodedPath)
    }
    fun parse(html: String): List<FriendActivity> {
        val doc = Jsoup.parse(html, "https://steamcommunity.com")
        return doc.select(".blotter_block").mapNotNull { block ->
            val replies = block.select(".commentthread_comment, .blotter_comment_entry").mapNotNull { row ->
                val body = row.selectFirst(".commentthread_comment_text, .blotter_comment_text")?.let(::paragraphs).orEmpty()
                if (body.isBlank()) return@mapNotNull null
                val author = row.selectFirst(".commentthread_author_link, .commentthread_comment_author a, .blotter_comment_author")?.text().orEmpty()
                ActivityReply(author, body.take(1500))
            }.distinct().take(30)
            val likes = count(block, ".vote_count, .blotter_vote_count, .blotter_rate_count, [id^=vote_up_count]")
                ?: Regex("(?:共有\\s*)?([0-9][0-9,]*)\\s*(?:人点赞|个赞|likes?)", RegexOption.IGNORE_CASE)
                    .find(block.text())?.groupValues?.get(1)
            val comments = count(block, ".commentthread_count, .commentthread_count_label, .blotter_comment_count, [id^=commentthread_count]")
            val header = block.text().take(250)
            val kind = when {
                block.selectFirst(".blotter_review, .blotter_recommendation, a[href*=/recommended/]") != null ||
                    Regex("发表了.*(?:测评|评测|评价)|撰写了.*(?:测评|评测)|posted a review|wrote a review", RegexOption.IGNORE_CASE).containsMatchIn(header) -> ActivityKind.Review
                block.selectFirst(".blotter_screenshot, .blotter_screenshot_image") != null ||
                    Regex("上传了.*截图|发布了.*截图|uploaded .*screenshots?|posted .*screenshots?", RegexOption.IGNORE_CASE).containsMatchIn(header) -> ActivityKind.Screenshot
                Regex("现在拥有了|新拥有了|购买了|获得了.*游戏|now owns|purchased", RegexOption.IGNORE_CASE).containsMatchIn(header) -> ActivityKind.OwnedGame
                else -> ActivityKind.Other
            }
            block.select("script, style, textarea, input, button, .commentthread_area, .blotter_comment_thread, .blotter_vote_container, .blotter_rate_details, .blotter_comment_entry, .commentthread_comment, .blotter_share, .blotter_control_container").remove()
            val recommendsGame = if (kind == ActivityKind.Review) {
                val voteIcons = block.select("img").mapNotNull { image ->
                    val indicator = listOf("src", "data-src", "alt", "class").joinToString(" ") { image.attr(it) }
                    val vote = reviewVote(indicator) ?: return@mapNotNull null
                    image.remove()
                    vote
                }
                voteIcons.firstOrNull() ?: reviewVote(block.select(".review_box .title, .blotter_review_title").text())
            } else null
            val review = block.selectFirst(".blotter_review_text, .blotter_recommendation_text, .review_box .content, .recommendation .content")
            val text = paragraphs(review ?: block)
            if(text.isBlank()) return@mapNotNull null
            val day = block.parents().firstOrNull { it.hasClass("blotter_day") }?.selectFirst(".blotter_day_header_date")?.text().orEmpty()
            val images = block.select("img").filter { it.parents().none { p -> p.hasClass("avatarMedium") || p.hasClass("playerAvatar") || p.hasClass("blotter_player_avatar") } }.mapNotNull(::imageSource)
                .filterNot { it.toHttpUrlOrNull()?.let { url -> url.host.startsWith("avatars.") || url.encodedPath.startsWith("/avatars/") } == true }.distinct().take(6)
            val anchors = block.select("a[href]")
            val links = anchors.map { it.text().ifBlank { "查看 Steam 内容" } to it.absUrl("href") }
                .filter { ChatContent.webUrl(it.second) }.distinctBy { it.second }.take(8)
            val id = block.id().ifBlank { MessageDigest.getInstance("SHA-256").digest(block.outerHtml().toByteArray()).joinToString("") { "%02x".format(it) } }
            val authorLink = block.select("a.blotter_author_block, .blotter_author_block a[href], a.blotter_author, .blotter_header .friendLink, .blotter_header .whiteLink, a[data-miniprofile]")
                .firstOrNull { it.text().isNotBlank() }
                ?: anchors.firstOrNull { it.text().isNotBlank() && Regex("https://steamcommunity.com/(id|profiles)/[^/?#]+/?").matches(it.absUrl("href")) }
            val author = authorLink?.text().orEmpty()
            val authorSteamId = authorLink?.absUrl("href")?.toHttpUrlOrNull()
                ?.takeIf { it.host == "steamcommunity.com" && it.pathSegments.firstOrNull() == "profiles" }
                ?.pathSegments?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
                ?: authorLink?.attr("data-miniprofile")?.toLongOrNull()
                    ?.takeIf { it in 1..0xffffffffL }?.plus(76561197960265728L)
            val avatar = block.select(".blotter_player_avatar img, .blotter_avatar img, .playerAvatar img, .avatarMedium img, img.playerAvatar, img.avatarMedium")
                .firstNotNullOfOrNull { image ->
                    if (image.parents().any { it.className().contains("avatarframe", ignoreCase = true) } ||
                        image.className().contains("avatarframe", ignoreCase = true)) null
                    else imageSource(image)?.takeUnless { it.contains("/images/items/") }
                }
                ?: block.select("img").firstNotNullOfOrNull { image ->
                    imageSource(image)?.takeIf { url ->
                        val address = url.toHttpUrlOrNull()
                        address?.host?.startsWith("avatars.") == true ||
                            address?.encodedPath?.startsWith("/avatars/") == true
                    }
                }
            val caption = block.selectFirst(".blotter_screenshot_caption, .blotter_screenshot_description")?.let(::paragraphs)
            val ownedNames = if (kind == ActivityKind.OwnedGame) {
                text.lines().flatMap { line ->
                    if (" | " in line) line.split(" | ").map(String::trim).filter(String::isNotEmpty)
                    else emptyList()
                }.distinct()
            } else emptyList()
            val games = activityGames(anchors, ownedNames)
            val body = when {
                review != null -> text
                kind == ActivityKind.Review -> reviewBody(text.removePrefix(author).trimStart())
                !caption.isNullOrBlank() -> caption
                kind == ActivityKind.Screenshot && images.isNotEmpty() -> ""
                else -> text.removePrefix(author).trimStart()
            }
            val commentCount = comments?.replace(",", "")?.toIntOrNull()?.let { maxOf(it, replies.size).toString() }
                ?: replies.size.takeIf { it > 0 }?.toString()
            FriendActivity(id, day, body.take(5000), images, links, author, avatar, kind, games, likes, commentCount, replies, recommendsGame, authorSteamId)
        }
    }

    private fun reviewBody(text: String): String {
        val lines = text.lines()
        val controls = setOf("是", "否", "欢乐", "有趣", "Yes", "No", "Funny", "赞", "分享")
        val prompt = Regex("^(?:这篇(?:评测|测评)是否有价值[？?]?|Was this review helpful\\??)$", RegexOption.IGNORE_CASE)
        val footer = lines.indices.lastOrNull { index ->
            prompt.matches(lines[index].trim()) &&
                lines.drop(index + 1).filter { it.isNotBlank() }.let { it.isNotEmpty() && it.all { line -> line.trim() in controls } }
        } ?: lines.size
        val metadata = listOf(
            Regex("^(?:推荐|不推荐|Recommended|Not Recommended)$", RegexOption.IGNORE_CASE),
            Regex("^(?:有\\s*[\\d,]+\\s*人|尚未有人|还没有人|没有人)觉得这篇(?:评测|测评)有价值[。.]?$"),
            Regex("^评测了\\s*《[^》]+》[。.]?$"),
            Regex("^过去\\s*\\d+\\s*周.*(?:小时|时数).*$"),
            Regex("^(?:[\\d,]+ people found this review helpful|No one has rated this review as helpful yet)[.]?$", RegexOption.IGNORE_CASE)
        )
        return lines.take(footer)
            .dropWhile { it.isBlank() || metadata.any { pattern -> pattern.matches(it.trim()) } }
            .joinToString("\n").trim()
    }

    private fun activityGames(anchors: List<Element>, ownedNames: List<String>): List<ActivityGame> {
        val candidates = anchors.mapNotNull { anchor ->
            val url = anchor.absUrl("href")
            val link = url.toHttpUrlOrNull() ?: return@mapNotNull null
            if (link.host != "store.steampowered.com" || !ChatContent.webUrl(url)) return@mapNotNull null
            val category = link.pathSegments.firstOrNull()
            if (category !in setOf("app", "sub", "bundle")) return@mapNotNull null
            val id = link.pathSegments.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val image = anchor.selectFirst("img")
            val title = listOf(anchor.text(), anchor.attr("title"), image?.attr("alt").orEmpty(), image?.attr("title").orEmpty())
                .firstOrNull { it.isNotBlank() && it != "查看 Steam 内容" }.orEmpty()
            "$category/$id" to ActivityGame(title, url, id.takeIf { category == "app" }, image?.let(::imageSource))
        }.groupBy({ it.first }, { it.second }).values.map { variants ->
            val game = variants.firstOrNull { it.title.isNotBlank() } ?: variants.first()
            game.copy(cover = variants.firstNotNullOfOrNull { it.cover })
        }
        // A grouped post supplies its titles in the same order as its capsule links.
        // Only pair by position when the lists match and existing labels agree.
        if (ownedNames.size == candidates.size && candidates.indices.all {
                candidates[it].title.isBlank() || candidates[it].title.equals(ownedNames[it], ignoreCase = true)
            }) {
            return candidates.mapIndexed { index, game -> game.copy(title = ownedNames[index]) }
        }
        val named = candidates.filter { it.title.isNotBlank() }
        val knownTitles = named.map { it.title.lowercase() }.toSet()
        return named + ownedNames.filterNot { it.lowercase() in knownTitles }
            .map { ActivityGame(it, null, null) }
    }

    private fun reviewVote(text: String): Boolean? = when {
        Regex("thumbs?[_ -]?down|not recommended|不推荐", RegexOption.IGNORE_CASE).containsMatchIn(text) -> false
        Regex("thumbs?[_ -]?up|recommended|^推荐$", RegexOption.IGNORE_CASE).containsMatchIn(text) -> true
        else -> null
    }

    private fun paragraphs(element: Element): String = element.wholeText().lines()
        .map { it.replace(Regex("[\\t \\u00a0]+"), " ").trim() }
        .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()

    private fun count(block: Element, selector: String): String? = block.select(selector).firstNotNullOfOrNull {
        Regex("[0-9][0-9,]*").find(it.text().trim())?.value
    }
}

/** Account-only feed, held in memory. Credentials never leave the fixed Community origin. */
class FriendActivityRepository(private val steam: SteamConnection, private val scope: CoroutineScope) {
    val items = MutableStateFlow<List<FriendActivity>>(emptyList())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow("")
    val hasMore = MutableStateFlow(false)
    private var next: String? = null
    private var job: Job? = null
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS).build()
    suspend fun syncNow() { refresh(); job?.join(); check(error.value.isBlank()) { error.value } }
    init { scope.launch { steam.online.collect { online -> if(!online) { job?.cancel(); items.value = emptyList(); next = null; hasMore.value = false; error.value = "" } } } }
    fun refresh(more: Boolean = false) {
        if(!steam.online.value || loading.value) return
        val owner = steam.owner
        val target = if(more) next ?: return else "https://steamcommunity.com/profiles/$owner/ajaxgetusernews/?l=schinese"
        check(FriendActivityParser.allowedPage(target))
        loading.value = true; error.value = ""
        job = scope.launch {
            try {
                val token = steam.appAccessToken()
                check(owner == steam.owner && steam.online.value)
                val parsed = withContext(Dispatchers.IO) {
                    val cookie = "steamLoginSecure=" + URLEncoder.encode("$owner||$token", "UTF-8")
                    val request = Request.Builder().url(target).header("Cookie", cookie)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                        .header("Accept", "application/json, text/javascript, */*; q=0.01")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Referer", "https://steamcommunity.com/profiles/$owner/home/").build()
                    http.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "Steam 动态请求 HTTP ${response.code}" }
                        val json = JSONObject(response.body?.string().orEmpty())
                        check(json.optBoolean("success")) { "Steam 未返回动态内容，请重新登录后同步" }
                        val html = json.optString("blotter_html")
                        val rows = FriendActivityParser.parse(html)
                        check(rows.isNotEmpty() || html.isBlank()) { "Steam 动态格式发生变化，暂时无法解析" }
                        rows to json.optString("next_request").takeIf { it.isNotBlank() && it != target && FriendActivityParser.allowedPage(it) }
                    }
                }
                if(owner != steam.owner || !steam.online.value) return@launch
                items.value = ((if(more) items.value else emptyList()) + parsed.first).distinctBy { it.id }
                next = parsed.second; hasMore.value = next != null && parsed.first.isNotEmpty()
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(owner == steam.owner) error.value = if(e is IllegalStateException) e.message.orEmpty() else "动态同步失败，请检查网络后重试" }
            finally { loading.value = false }
        }
    }
}
