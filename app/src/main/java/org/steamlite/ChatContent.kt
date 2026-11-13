package org.steamlite

import java.net.URI
import java.net.URLEncoder

sealed interface ChatPart {
    data class Plain(val text: String): ChatPart
    data class Link(val label: String, val url: String): ChatPart
    data class Image(val url: String): ChatPart
    data class Emote(val name: String): ChatPart
}
object ChatContent {
    // NOTE: the emote name class must exclude ':' and 'ː' explicitly. 'ː' (U+02D0) is
    // a *letter* (Lm), so \p{L} would swallow "ːaːːbː" into one name and break every
    // message with two emotes side by side.
    private val tokens = Regex("""\[img\](https://[^\s\[]+)\[/img\]|\[url=(https?://[^\s\]]+)\]([^\[]*)\[/url\]|\[img\s+src=["']?(https://[^\s"'\]]+)["']?\s*\](?:\[/img\])?|\[emoticon\]([^\[\]]+)\[/emoticon\]|\[sticker\s+type="([^"]+)"[^\]]*\]\[/sticker\]|https?://[^\s<>\[\]]+|[:ː][^\s:ː\[\]]{1,80}[:ː]""", RegexOption.IGNORE_CASE)
    fun webUrl(url: String): Boolean = runCatching { val u = URI(url); u.scheme.lowercase() in setOf("https", "http") && !u.host.isNullOrBlank() && u.rawUserInfo == null }.getOrDefault(false)
    fun mediaUrl(url: String): Boolean = runCatching {
        val u = URI(url); val host = u.host?.lowercase().orEmpty()
        u.scheme == "https" && u.rawUserInfo == null && (u.port == -1 || u.port == 443) &&
            listOf("steamstatic.com", "steamusercontent.com", "steamuserimages-a.akamaihd.net", "steamcommunity-a.akamaihd.net", "steamcommunity.com").any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)
    fun emoteUrl(name: String) = "https://community.akamai.steamstatic.com/economy/emoticon/" + URLEncoder.encode(name.trim(':', 'ː'), "UTF-8").replace("+", "%20")
    fun stickerUrl(name: String) = "https://community.akamai.steamstatic.com/economy/sticker/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
    fun emoteUrls(name: String): List<String> {
        val normal = emoteUrl(name)
        val large = normal.replace("/economy/emoticon/", "/economy/emoticonlarge/")
        return listOf(large, normal).flatMap { url ->
            listOf(url, url.replace("community.akamai.", "community.fastly."), url.replace("community.akamai.", "community.cloudflare."))
        }
    }

    /** Display markup returned by Steam; sending uses the /sticker command instead. */
    fun stickerCode(name: String): String {
        require(name.isNotBlank() && name == name.trim() && name.none { it in "\"[]\r\n" || it.isISOControl() })
        return "[sticker type=\"$name\" limit=\"0\"][/sticker]"
    }

    /** A standalone picker sticker must be expanded by Steam, rather than sent as BBCode. */
    fun outgoingMessage(text: String): String {
        val sticker = Regex("""\[sticker\s+type="([^"\[\]\r\n]+)"(?:\s+limit="\d+")?\]\[/sticker\]""", RegexOption.IGNORE_CASE)
            .matchEntire(text.trim()) ?: return text
        val name = sticker.groupValues[1]
        require(name.isNotBlank() && name == name.trim() && name.none { it.isISOControl() })
        return "/sticker $name"
    }

    /** One-line summary for list previews: markup becomes readable words, never raw BBCode. */
    fun preview(text: String): String {
        var out = text
        out = out.replace(Regex("""\[img\][^\[]*\[/img\]""", RegexOption.IGNORE_CASE), "[图片]")
        out = out.replace(Regex("""\[sticker[^\]]*\].*?\[/sticker\]""", RegexOption.IGNORE_CASE), "[贴纸]")
        out = out.replace(Regex("""\[publishedfile[^\]]*\]""", RegexOption.IGNORE_CASE), "[创意工坊]")
        out = out.replace(Regex("""\[previewyoutube[^\]]*\]""", RegexOption.IGNORE_CASE), "[视频]")
        out = out.replace(Regex("""\[/?[a-zA-Z][^\]\s]*[^\]]*\]"""), " ")
        out = out.replace(Regex(""":?[ː][^ː\s]{1,40}[ː]:?"""), "[表情]")
        return out.replace(Regex("\\s+"), " ").trim()
    }
    fun parse(text: String): List<ChatPart> {
        val parts = mutableListOf<ChatPart>(); var offset = 0
        tokens.findAll(text).forEach { m ->
            if(m.range.first > offset) parts += ChatPart.Plain(text.substring(offset, m.range.first))
            val value = m.value
            parts += when {
                m.groupValues[1].isNotBlank() -> if(mediaUrl(m.groupValues[1])) ChatPart.Image(m.groupValues[1]) else ChatPart.Link(m.groupValues[1], m.groupValues[1])
                m.groupValues[2].isNotBlank() -> if(webUrl(m.groupValues[2])) ChatPart.Link(m.groupValues[3], m.groupValues[2]) else ChatPart.Plain(value)
                m.groupValues[4].isNotBlank() -> if(mediaUrl(m.groupValues[4])) ChatPart.Image(m.groupValues[4]) else ChatPart.Link(m.groupValues[4], m.groupValues[4])
                m.groupValues[5].isNotBlank() -> ChatPart.Emote(m.groupValues[5].trim(':', 'ː'))
                m.groupValues[6].isNotBlank() -> ChatPart.Image(stickerUrl(m.groupValues[6]))
                value.startsWith("http", true) -> {
                    val url = value.trimEnd('.', ',', '!', '，', '。')
                    if(mediaUrl(url) && (Regex("(?i)\\.(png|jpe?g|gif|webp)(?:[?#].*)?$").containsMatchIn(url) || URI(url).path.startsWith("/ugc/"))) ChatPart.Image(url)
                    else if(webUrl(url)) ChatPart.Link(value, url) else ChatPart.Plain(value)
                }
                else -> ChatPart.Emote(value.trim(':', 'ː'))
            }
            offset = m.range.last + 1
        }
        if(offset < text.length) parts += ChatPart.Plain(text.substring(offset))
        return parts
    }
}
