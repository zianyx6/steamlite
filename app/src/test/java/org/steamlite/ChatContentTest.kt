package org.steamlite

import org.junit.Assert.*
import org.junit.Test

class ChatContentTest {
    @Test fun outgoingStickerUsesSteamCommandAndPreservesFullName() {
        assertEquals("/sticker Nyan GLaDOS", ChatContent.outgoingMessage(ChatContent.stickerCode("Nyan GLaDOS")))
        assertEquals("/sticker CatSurprise", ChatContent.outgoingMessage("[sticker type=\"CatSurprise\"][/sticker]"))
        assertEquals(listOf(ChatPart.Image(ChatContent.stickerUrl("Nyan GLaDOS"))), ChatContent.parse(ChatContent.stickerCode("Nyan GLaDOS")))
    }
    @Test fun ordinaryMessagesAreNotRewrittenAsStickerCommands() {
        listOf("hello :steamhappy:", "[b]hello[/b]", "/sticker Nyan GLaDOS", "before ${ChatContent.stickerCode("CatSurprise")}").forEach {
            assertEquals(it, ChatContent.outgoingMessage(it))
        }
        assertThrows(IllegalArgumentException::class.java) { ChatContent.stickerCode("bad\n/sticker other") }
        assertThrows(IllegalArgumentException::class.java) { ChatContent.stickerCode("bad\" type=\"other") }
    }
    @Test fun mixedSteamEmotesAndLinksPreserveText() {
        val parts = ChatContent.parse("你好 ːsteamhappyː :steamthumbsup: https://store.steampowered.com/app/34900/")
        assertEquals(listOf("steamhappy", "steamthumbsup"), parts.filterIsInstance<ChatPart.Emote>().map { it.name })
        assertEquals("你好 ", (parts.first() as ChatPart.Plain).text)
        assertEquals("https://store.steampowered.com/app/34900/", parts.filterIsInstance<ChatPart.Link>().single().url)
    }
    @Test fun receivedSteamImageAndBbcodeLink() {
        val url = "https://images.steamusercontent.com/ugc/123/picture.jpg"
        assertEquals(listOf(ChatPart.Image(url)), ChatContent.parse("[img]$url[/img]"))
        assertEquals(listOf(ChatPart.Link("商店", "https://store.steampowered.com/")), ChatContent.parse("[url=https://store.steampowered.com/]商店[/url]"))
    }
    @Test fun mediaRejectsUntrustedAndLocalTargets() {
        listOf("http://images.steamusercontent.com/x.png", "https://steamstatic.com.evil.example/x", "https://127.0.0.1/x", "https://localhost/x", "https://steamstatic.com@evil.example/x", "https://steamstatic.com:8443/x").forEach { assertFalse(it, ChatContent.mediaUrl(it)) }
        assertTrue(ChatContent.mediaUrl("https://images.steamusercontent.com/ugc/x"))
        assertTrue(ChatContent.mediaUrl("https://steamuserimages-a.akamaihd.net/ugc/x"))
    }
    @Test fun steamExtensionlessUgcAndAttributeImages() {
        val url = "https://images.steamusercontent.com/ugc/123/ABC/?imw=1000"
        assertEquals(listOf(ChatPart.Image(url)), ChatContent.parse(url))
        assertEquals(listOf(ChatPart.Image(url)), ChatContent.parse("[img src=\"$url\"][/img]"))
        assertEquals(listOf(ChatPart.Emote("steamhappy")), ChatContent.parse("[emoticon]steamhappy[/emoticon]"))
    }
    @Test fun noExecutableSchemesAndNoLostPlainText() {
        assertFalse(ChatContent.webUrl("javascript:alert(1)"))
        assertFalse(ChatContent.webUrl("intent://x"))
        assertFalse(ChatContent.webUrl("https://user:password@example.com"))
        assertEquals(listOf(ChatPart.Plain("普通聊天文本")), ChatContent.parse("普通聊天文本"))
    }
    @Test fun stickerRemainsAnImageAndAdjacentEmotesStaySeparate() {
        val sticker = ChatContent.parse("[sticker type=\"Test Name\"][/sticker]").single()
        assertEquals(ChatPart.Image(ChatContent.stickerUrl("Test Name")), sticker)
        assertEquals(listOf("a", "b"), ChatContent.parse("ːaːːbː").filterIsInstance<ChatPart.Emote>().map { it.name })
    }
    @Test fun stickerSurvivesPlainOrEmptySteamAcknowledgement() {
        val url = ChatContent.stickerUrl("Beat Mix")
        val original = ChatContent.stickerCode("Beat Mix")
        assertEquals(original, ChatContent.confirmedText(original, ""))
        assertEquals(original, ChatContent.confirmedText(original, "/sticker Beat Mix"))
        assertEquals(listOf(ChatPart.Image(url)), ChatContent.parse("[sticker limit=\"0\" type=\"Beat Mix\"] [/sticker]"))
        assertEquals(listOf(ChatPart.Image(url)), ChatContent.parse(url))
    }
}
