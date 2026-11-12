package org.steamlite

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import kotlinx.coroutines.flow.StateFlow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val NavigationHeight = 60.dp
private val NavigationGap = 8.dp
internal val FloatingNavigationSpace = NavigationHeight + NavigationGap + 6.dp
internal val LocalFloatingNavigationPadding = compositionLocalOf { 0.dp }

private val navigationEntries = listOf(
    Triple(0, "商店", Glyph.Store),
    Triple(1, "愿望单", Glyph.Heart),
    Triple(4, "动态", Glyph.Bell),
    Triple(2, "消息", Glyph.Chat),
    Triple(3, "我的", Glyph.User),
)

@Composable
internal fun rememberNavigationBackdrop(): NavigationBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { NavigationBackdrop(layer) }
}

// Share one display list between the page and the lens. Recording the page twice repeats
// draw modifiers and lazy row rendering on the main thread.
internal class NavigationBackdrop(private val layer: GraphicsLayer) : Backdrop {
    private var pageCoordinates by mutableStateOf<LayoutCoordinates?>(null)
    override val isCoordinatesDependent: Boolean = true

    val capture: Modifier = Modifier
        .onGloballyPositioned { pageCoordinates = it }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val page = pageCoordinates?.takeIf { it.isAttached } ?: return
        val lens = coordinates?.takeIf { it.isAttached } ?: return
        val offset = page.localPositionOf(lens)
        translate(-offset.x, -offset.y) { drawLayer(layer) }
    }
}

@Composable
internal fun SteamBottomBar(
    selected: Int,
    sessions: StateFlow<List<RecentSession>>,
    backdrop: NavigationBackdrop?,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val unreadSessions = sessions.collectAsState()
    val hasUnread by remember(unreadSessions) {
        derivedStateOf { unreadSessions.value.any { it.unread > 0 } }
    }
    val colors = MiuixTheme.colorScheme
    val surface = colors.surfaceContainer
    val dark = isSystemInDarkTheme()
    val tint = surface.copy(alpha = if (dark) 0.56f else 0.61f)
    val outline = colors.dividerLine
    val primary = colors.primary
    val activeTint = AccentText
    val selectionTint = primary.copy(alpha = 0.14f)
    val selection = animateFloatAsState(
        targetValue = navigationEntries.indexOfFirst { it.first == selected }.coerceAtLeast(0).toFloat(),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 560f),
        label = "Floating navigation selection",
    )
    val glass = remember(backdrop, tint, surface, outline, dark) {
        if (backdrop == null) {
            Modifier.shadow(6.dp, CircleShape)
                .background(surface, CircleShape)
                .border(1.dp, outline, CircleShape)
        } else {
            val rim = Brush.verticalGradient(
                listOf(Color.White.copy(alpha = if (dark) 0.33f else 0.94f), outline.copy(alpha = 0.55f)),
            )
            Modifier.shadow(4.dp, CircleShape)
                .border(0.75.dp, rim, CircleShape)
                .drawPlainBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        blur(3.dp.toPx())
                        lens(14.dp.toPx(), 20.dp.toPx(), depthEffect = true)
                    },
                    onDrawSurface = { drawRect(tint) },
                )
        }
    }
    Box(
        modifier.padding(start = 16.dp, end = 16.dp, bottom = NavigationGap)
            .widthIn(max = 480.dp).fillMaxWidth().height(NavigationHeight),
    ) {
        Box(Modifier.matchParentSize().then(glass))
        Row(
            Modifier.fillMaxSize().graphicsLayer()
                .clip(CircleShape)
                .padding(4.dp)
                .drawBehind {
                    val itemWidth = size.width / navigationEntries.size
                    val index = if (layoutDirection == LayoutDirection.Ltr) selection.value
                        else navigationEntries.lastIndex - selection.value
                    drawRoundRect(
                        color = selectionTint,
                        topLeft = Offset(index * itemWidth + 2.dp.toPx(), 0f),
                        size = Size(itemWidth - 4.dp.toPx(), size.height),
                        cornerRadius = CornerRadius(size.height / 2),
                    )
                }
                .selectableGroup(),
        ) {
            navigationEntries.forEach { (id, label, glyph) ->
                val active = id == selected
                val contentColor = if (active) activeTint else colors.onSurface
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(id) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                ) {
                    Box {
                        SteamIcon(glyph, 23.dp, contentColor, if (active) 2f else 1.7f)
                        if (id == 2 && hasUnread) {
                            Box(Modifier.align(Alignment.TopEnd).size(6.dp).background(primary, CircleShape))
                        }
                    }
                    Text(
                        text = label,
                        color = contentColor,
                        style = MiuixTheme.textStyles.footnote2,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}
