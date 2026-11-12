package org.steamlite

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.PullToRefresh

@Composable
internal fun RefreshContainer(refreshing: Boolean, refresh: () -> Unit, content: @Composable () -> Unit) {
    // PullToRefresh already handles the top-edge gesture and its animation.
    CompositionLocalProvider(LocalOverscrollFactory provides null) {
        PullToRefresh(
            isRefreshing = refreshing,
            onRefresh = refresh,
            modifier = Modifier.fillMaxSize(),
            refreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新", "刷新完成"),
            content = content,
        )
    }
}
