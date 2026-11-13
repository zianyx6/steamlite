package org.steamlite

import org.junit.Assert.*
import org.junit.Test

class FriendActivityTest {
    @Test fun emptySourcesCannotBecomeTheSteamHomepageImage() {
        val card = FriendActivityParser.parse("""<div class="blotter_block">截图<img src=""><img src=" "><img src="https://steamcommunity.com/"><img src="https://images.steamusercontent.com/ugc/example/photo.jpg"></div>""").single()
        assertEquals(listOf("https://images.steamusercontent.com/ugc/example/photo.jpg"), card.images)
    }
    @Test fun lazyImagesUseTheirRealSourceInsteadOfAnEmptyPlaceholder() {
        val first = "https://images.steamusercontent.com/ugc/example/one.jpg"
        val second = "https://images.steamusercontent.com/ugc/example/two.jpg"
        val card = FriendActivityParser.parse("""<div class="blotter_block">截图<img src="" data-src="$first"><img data-lazy-src="$second"></div>""").single()
        assertEquals(listOf(first, second), card.images)
    }
    @Test fun unsafeLazySourcesDoNotOverrideAValidImageAndEmptyAvatarStaysEmpty() {
        val url = "https://images.steamusercontent.com/ugc/example/photo.jpg"
        val card = FriendActivityParser.parse("""<div class="blotter_block">截图<div class="playerAvatar"><img src=""></div><img data-src="https://evil.example/image.jpg" src="$url"></div>""").single()
        assertNull(card.avatar)
        assertEquals(listOf(url), card.images)
    }
    @Test fun feedOmitsCommentEditorControlsAndKeepsOriginalActivity() {
        val card = FriendActivityParser.parse("""<div class="blotter_block"><a class="blotter_author_block" href="/profiles/123">测试好友</a><p>发布了一张截图</p><div class="commentthread_area">订阅讨论中 格式帮助 提交留言</div></div>""").single()
        assertEquals("测试好友", card.author)
        assertTrue(card.text.contains("发布了一张截图"))
        assertFalse(card.text.contains("格式帮助"))
    }
    @Test fun authorAvatarDoesNotBecomeAnActivityPhoto() {
        val card = FriendActivityParser.parse("""<div class="blotter_block"><div class="blotter_player_avatar"><img src="https://avatars.steamstatic.com/example.jpg"></div><div class="blotter_author_block"><a href="/profiles/123">测试好友</a></div><p>发布了截图</p><img src="https://images.steamusercontent.com/ugc/example/photo.jpg"></div>""").single()
        assertEquals("测试好友", card.author)
        assertEquals("https://avatars.steamstatic.com/example.jpg", card.avatar)
        assertEquals(listOf("https://images.steamusercontent.com/ugc/example/photo.jpg"), card.images)
    }
    @Test fun unwrappedAvatarsAreNotEnlargedAsActivityPhotos() {
        val card = FriendActivityParser.parse("""<div class="blotter_block">好友活动<img src="https://avatars.steamstatic.com/person.jpg"><img src="https://images.steamusercontent.com/ugc/photo.jpg"></div>""").single()
        assertEquals(listOf("https://images.steamusercontent.com/ugc/photo.jpg"), card.images)
    }

    @Test fun avatarSkipsDecorativeFrameAndResolvesTheAuthorId() {
        val avatar = "https://avatars.steamstatic.com/real_full.jpg"
        val card = FriendActivityParser.parse("""
            <div class="blotter_block">
              <a class="blotter_author_block" href="/id/example" data-miniprofile="123">好友</a>
              <div class="blotter_player_avatar">
                <div class="avatarFrame"><img src="https://cdn.fastly.steamstatic.com/steamcommunity/public/images/items/1/frame.png"></div>
                <img data-src="$avatar">
              </div>
              上传了截图
            </div>
        """.trimIndent()).single()
        assertEquals(76561197960265851L, card.authorSteamId)
        assertEquals(avatar, card.avatar)
        assertTrue(card.images.isEmpty())
    }
    @Test fun paginationCannotSendCredentialsToAnotherOriginOrEndpoint() {
        assertTrue(FriendActivityParser.allowedPage("https://steamcommunity.com/profiles/123/ajaxgetusernews/?start=10"))
        assertTrue(FriendActivityParser.allowedPage("https://steamcommunity.com/id/test/ajaxgetusernews"))
        listOf("https://steamcommunity.com.evil.example/profiles/123/ajaxgetusernews", "http://steamcommunity.com/profiles/123/ajaxgetusernews", "https://user@steamcommunity.com/profiles/123/ajaxgetusernews", "https://steamcommunity.com:8443/profiles/123/ajaxgetusernews", "https://steamcommunity.com/login/").forEach { assertFalse(it, FriendActivityParser.allowedPage(it)) }
    }
    @Test fun nativeCardsKeepRealTextDatesAndSafeLinksWithoutScripts() {
        val html = """<div class="blotter_day"><div class="blotter_day_header_date">今天</div><div class="blotter_block" id="item1"><p>测试好友 获得了一项成就</p><script>hidden()</script><a href="/app/123">游戏</a><a href="javascript:alert(1)">unsafe</a><img src="https://images.steamusercontent.com/ugc/test/image.png"><img src="https://untrusted.example/image.png"></div></div>"""
        val card = FriendActivityParser.parse(html).single()
        assertEquals("item1", card.id); assertEquals("今天", card.day)
        assertTrue(card.text.contains("获得了一项成就")); assertFalse(card.text.contains("hidden()"))
        assertEquals(listOf("https://images.steamusercontent.com/ugc/test/image.png"), card.images)
        assertEquals(listOf("游戏" to "https://steamcommunity.com/app/123"), card.links)
    }
    @Test fun emptyFeedDoesNotInventActivity() { assertTrue(FriendActivityParser.parse("<div class='blotter_day'></div>").isEmpty()) }

    @Test fun groupedOwnershipSeparatesGameNamesAndKeepsVisibleReplyCount() {
        val card = FriendActivityParser.parse("""
            <div class="blotter_block">
                <p>好友新拥有了 3 款游戏</p>
                <a href="https://store.steampowered.com/app/123/"><img src="https://shared.fastly.steamstatic.com/cover.jpg"></a>
                <a href="https://store.steampowered.com/sub/456/"></a>
                <a href="https://store.steampowered.com/app/789/">游戏丙</a>
                <p>游戏甲 | 游戏乙 | 游戏丙</p>
                <span class="commentthread_count">0</span>
                <div class="commentthread_comment">
                    <div class="commentthread_comment_text">恭喜</div>
                </div>
            </div>
        """.trimIndent()).single()
        assertEquals(ActivityKind.OwnedGame, card.kind)
        assertEquals(listOf("游戏甲", "游戏乙", "游戏丙"), card.games.map { it.title })
        assertEquals(123, card.games[0].appId)
        assertEquals("https://shared.fastly.steamstatic.com/cover.jpg", card.games[0].cover)
        assertEquals("https://store.steampowered.com/sub/456/", card.games[1].url)
        assertEquals("1", card.commentCount)
    }

    @Test fun postTypesAndExistingRepliesStaySeparateFromTheBody() {
        val html = """
            <div class="blotter_block" id="review">
              <div class="blotter_header">好友发表了游戏测评</div>
              <a href="https://store.steampowered.com/app/123/">示例游戏</a>
              <div class="blotter_review_text">第一段<br>第二段</div>
              <img src="https://community.fastly.steamstatic.com/public/images/userreviews/icon_thumbsDown.png">
              <img src="https://images.steamusercontent.com/ugc/test/screenshot.jpg">
              <div class="blotter_vote_container"><span class="vote_count">12</span></div>
              <div class="commentthread_area">
                <span class="commentthread_count_label">4 条评论</span>
                <div class="commentthread_comment">
                  <a class="commentthread_author_link">示例评论者</a>
                  <div class="commentthread_comment_text">这是一条回复</div>
                </div>
                <button>提交评论</button>
              </div>
            </div>
            <div class="blotter_block">好友上传了 2 张截图</div>
            <div class="blotter_block">好友现在拥有了示例游戏</div>
        """.trimIndent()
        val posts = FriendActivityParser.parse(html)
        assertEquals(listOf(ActivityKind.Review, ActivityKind.Screenshot, ActivityKind.OwnedGame), posts.map { it.kind })
        val review = posts.first()
        assertEquals(123, review.games.single().appId)
        assertEquals("第一段\n第二段", review.text)
        assertEquals(false, review.recommendsGame)
        assertEquals(listOf("https://images.steamusercontent.com/ugc/test/screenshot.jpg"), review.images)
        assertEquals("12", review.likes)
        assertEquals("4", review.commentCount)
        assertEquals(listOf(ActivityReply("示例评论者", "这是一条回复")), review.replies)
    }

    @Test fun reviewFallbackKeepsAuthorParagraphsAndDropsSteamVotingChrome() {
        val post = FriendActivityParser.parse("""
            <div class="blotter_block">
              <a class="blotter_author_block" href="/profiles/123">作者</a><br>
              评测了《ICEY》<br><br>
              有 5 人觉得这篇评测有价值<br><br>
              不推荐<br>过去 2 周 0 小时 / 总时数 2 小时<br><br>
              有难度选择，旁白有中文配音。<br>我更喜欢另一款动作游戏。<br><br>
              这篇评测是否有价值？<br><br>是<br>否<br>欢乐
              <img src="https://community.fastly.steamstatic.com/public/images/userreviews/icon_thumbsDown.png">
              <a href="/id/example/recommended/123/"></a>
            </div>
        """.trimIndent()).single()
        assertEquals(ActivityKind.Review, post.kind)
        assertEquals(false, post.recommendsGame)
        assertEquals("有难度选择，旁白有中文配音。\n我更喜欢另一款动作游戏。", post.text)
        val noVotes = FriendActivityParser.parse("""
            <div class="blotter_block">
                <a href="/id/example/recommended/123/"></a>
                尚未有人觉得这篇评测有价值<br><br>
                推荐<br>过去 2 周 3 小时 / 总时数 3 小时<br><br>
                有难度选择，旁白有中文配音。
            </div>
        """.trimIndent()).single()
        assertEquals("有难度选择，旁白有中文配音。", noVotes.text)
    }
}
