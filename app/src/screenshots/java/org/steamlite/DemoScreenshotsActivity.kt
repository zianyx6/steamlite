package org.steamlite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** Compiled only in the separate .screenshots app. No real accounts, friends or tokens. */
class DemoScreenshotsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        getSharedPreferences("ui", MODE_PRIVATE).edit().putString("theme", intent.getStringExtra("theme") ?: "dark").commit()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        PublicImages.install(this)
        val page = intent.getStringExtra("page") ?: "store"
        val model = if (page == "detail") ViewModelProvider(this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application))[LiteModel::class.java].apply { detail = demoDetail() } else null
        setContent { SteamTheme {
            val sessions = remember { MutableStateFlow(emptyList<RecentSession>()) }
            Box(Modifier.fillMaxSize().background(Bg)) {
                CompositionLocalProvider(LocalFloatingNavigationPadding provides FloatingNavigationSpace) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 100.dp)) {
                        when (page) {
                            "profile" -> {
                                item { PageHeader("我的", leading = { IconSquare(Glyph.Settings, {}) }) { IconSquare(Glyph.Sun, {}) } }
                                item {
                                    Row(Modifier.padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                        PersonAvatar("S", null, null, 54.dp)
                                        Spacer(Modifier.width(13.dp))
                                        Column { Text("Steam Lite 玩家", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Text1); Text("示例账号", fontSize = 11.5.sp, color = Text3) }
                                    }
                                    StatsCard("120", "已获取 120 款报价", "860", "12.5 小时 / 2 周", "$999.00", "PK 商店现价合计", "12.5", "近两周游玩")
                                    SectionHeader("我的游戏库") { Text("4 / 4", fontSize = 11.sp, color = Text3) }
                                    SearchField("", {}, "搜索已拥有游戏名称或 AppID")
                                    MetaLine("Steam 游戏库 · 演示数据", Glyph.Info)
                                }
                                demoGames().forEachIndexed { index, game -> item {
                                    GameRow(game.id, game.image, game.name, "已拥有", "${listOf(128, 96, 72, 48)[index]}.0 小时", 0, "", {})
                                } }
                            }
                            "messages" -> {
                                item {
                                    PageHeader("消息")
                                    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                                        Text("好友", fontSize = 15.sp, color = Text3)
                                        Column { Text("会话", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AccentText); Box(Modifier.padding(top = 5.dp).size(29.dp, 3.dp).clip(RoundedCornerShape(3.dp)).background(Accent)) }
                                        Text("群组", fontSize = 15.sp, color = Text3)
                                    }
                                    Hairline(); Spacer(Modifier.height(12.dp)); SearchField("", {}, "搜索会话"); Spacer(Modifier.height(8.dp))
                                }
                                listOf("星河", "蓝鲸", "微风", "山川", "森林", "月光", "远航", "银河").forEachIndexed { index, name -> item {
                                    PersonRow(name, listOf("今晚一起玩？", "这款游戏正在打折", "收到，稍后见", "分享了一个游戏", "周末一起联机吧", "好呀", "新的截图真不错", "下次再聊")[index], name, null,
                                        if (index < 3) Presence.Online else Presence.Offline, {}, end = { Text("${18-index}:20", fontSize = 10.sp, color = Text3) })
                                } }
                            }
                            else -> {
                                item {
                                    PageHeader("商店")
                                    Row(verticalAlignment = Alignment.CenterVertically) { SearchField("", {}, "搜索中文游戏名称", Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); IconSquare(Glyph.Filter, {}) }
                                    MetaLine("商店折扣 · 演示数据", Glyph.Info)
                                    SectionHeader("今日精选") { Text("1 / 3", fontSize = 11.sp, color = Text3) }
                                    StoreGameCard(demoGames()[0], true, false, false, {}, {}, Modifier.fillMaxWidth())
                                    SectionHeader("折扣推荐") { TextLink("更多折扣") {} }
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        demoGames().drop(1).take(2).forEach { game -> StoreGameCard(game, false, false, false, {}, {}, Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                    SteamBottomBar(if (page == "profile") 3 else if (page == "messages") 2 else 0, sessions, null, Modifier.align(Alignment.BottomCenter)) {}
                }
                if (model != null) GameDetailsDialog(model) {}
            }
        } }
    }
}

private fun demoGames() = listOf(
    Game(1145360, "Hades", "", "USD 8.49", 66, "USD 24.99"),
    Game(1245620, "ELDEN RING 艾尔登法环", "", "USD 35.99", 40, "USD 59.99"),
    Game(367520, "Hollow Knight", "", "USD 7.49", 50, "USD 14.99"),
    Game(413150, "Stardew Valley", "", "USD 8.99", 40, "USD 14.99"),
)

private fun demoDetail() = JSONObject("""{
  "steam_appid":3240220,"name":"Grand Theft Auto V 增强版","publishers":["Rockstar Games"],"developers":["Rockstar North"],
  "header_image":"https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/3240220/header.jpg",
  "platforms":{"windows":true},"genres":[{"description":"动作"},{"description":"冒险"}],
  "price_overview":{"currency":"USD","initial_formatted":"$44.99","final_formatted":"$19.79 USD","discount_percent":56},
  "package_groups":[{"title":"购买游戏","is_recurring_subscription":"false","subs":[
    {"packageid":1251933,"option_text":"Grand Theft Auto V Enhanced & Great White Shark Card - <span class='discount_original_price'>$44.99</span> $19.79 USD","percent_savings_text":"-56%"},
    {"packageid":1148516,"option_text":"Grand Theft Auto V Enhanced - $29.99 USD"}
  ]}],"short_description":"探索开放世界，体验故事与多人合作。","release_date":{"date":"2025 年 3 月 4 日"}
}""")
