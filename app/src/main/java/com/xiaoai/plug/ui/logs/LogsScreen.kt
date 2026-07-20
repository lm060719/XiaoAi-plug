package com.xiaoai.plug.ui.logs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaoai.plug.config.LogEntry
import com.xiaoai.plug.ui.LogsViewModel
import com.xiaoai.plug.ui.nav.CardContentPadding
import com.xiaoai.plug.ui.nav.PageScaffold
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val TABS = listOf("全部", "对话", "工具", "错误")
private val TAB_TYPES = listOf(null, LogEntry.TYPE_CHAT, LogEntry.TYPE_TOOL, LogEntry.TYPE_ERROR)

@Composable
fun LogsScreen(bottomInset: Dp, vm: LogsViewModel = viewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val keyword by vm.keyword.collectAsStateWithLifecycle()
    val typeFilter by vm.typeFilter.collectAsStateWithLifecycle()
    val selectedTab = TAB_TYPES.indexOf(typeFilter).coerceAtLeast(0)

    PageScaffold(
        title = "记录",
        bottomInset = bottomInset,
        actions = {
            // 顶栏用图标按钮，文字按钮在这里会变成两坨占半屏的灰块
            IconButton(onClick = { vm.refresh() }) {
                Icon(
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = "刷新",
                    tint = MiuixTheme.colorScheme.onBackground
                )
            }
            IconButton(onClick = { vm.clear() }) {
                Icon(
                    imageVector = MiuixIcons.Delete,
                    contentDescription = "清空",
                    tint = MiuixTheme.colorScheme.onBackground
                )
            }
        }
    ) {
        item {
            TabRow(
                tabs = TABS,
                selectedTabIndex = selectedTab,
                onTabSelected = { vm.setType(TAB_TYPES[it]) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
        }

        item {
            TextField(
                value = keyword,
                onValueChange = { vm.setKeyword(it) },
                label = "搜索内容",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
        }

        if (entries.isEmpty()) {
            item {
                Text(
                    text = "暂时没有记录",
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        items(entries, key = { it.id }) { entry ->
            LogRow(entry, Modifier.animateItem(), onDelete = { vm.delete(entry.id) })
        }
    }
}

/** 划过卡片宽度的这个比例，松手即删；没到就弹回去。 */
private const val DELETE_FRACTION = 1f / 3f

/**
 * 划到底时的跟手系数：手指走 1px，卡片只走这么多。
 * 起手是 1.0（完全跟手），线性压到这个值 —— 越往后越沉，
 * 让人能感觉到「已经拉到头了」，而不是一路轻飘飘滑出去。
 */
private const val MIN_DRAG_FACTOR = 0.35f

@Composable
private fun LogRow(entry: LogEntry, modifier: Modifier = Modifier, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val time = remember(entry.time) {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.time))
    }

    // 卡片实际宽度：阈值和阻尼都按它算。布局完成前是 0，下面一律 coerceAtLeast(1f) 兜底。
    var widthPx by remember { mutableFloatStateOf(0f) }
    // Animatable 而不是普通 State：拖动要 snapTo 跟手，松手要 animateTo 回弹或飞出，
    // 两种更新得共用一个值，中途再拖还能打断正在播的动画。
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
    ) {
        // 底层：删除提示。matchParentSize 不参与测量，所以整个 Box 的高度
        // 仍然由上面那张卡片决定，跟没加这层时一样。
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 进度用 lambda 传进去、在里面才读 offsetX —— 直接在 LogRow 里读的话，
            // 整行（卡片和所有文字）每帧都要重组，拖动时白烧一堆 CPU。
            DeleteHint { -offsetX.value / widthPx.coerceAtLeast(1f) }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            val w = widthPx.coerceAtLeast(1f)
                            val next = if (delta < 0) {
                                // 阻尼压的是**增量**不是总位移。压总位移的话，同一根手指
                                // 在同一个位置上卡片的落点会随路径变化，看着像在打滑。
                                val progress = (-offsetX.value / w).coerceIn(0f, 1f)
                                val factor = 1f - (1f - MIN_DRAG_FACTOR) * progress
                                offsetX.value + delta * factor
                            } else {
                                // 往回拉不加阻尼：反悔的时候要立刻跟手
                                offsetX.value + delta
                            }
                            offsetX.snapTo(next.coerceIn(-w, 0f))
                        }
                    },
                    orientation = Orientation.Horizontal,
                    enabled = !expanded,
                    onDragStopped = {
                        val w = widthPx.coerceAtLeast(1f)
                        if (-offsetX.value >= w * DELETE_FRACTION) {
                            // 先让卡片飞完再删。反过来的话行会先塌掉，飞出去的动画看不见。
                            offsetX.animateTo(-w, tween(160))
                            onDelete()
                        } else {
                            offsetX.animateTo(0f)
                        }
                    }
                ),
            // Card 默认内边距是 0，不给的话文字会顶到边缘被圆角裁掉
            insideMargin = CardContentPadding,
            onClick = { expanded = !expanded }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = typeLabel(entry.type),
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = typeColor(entry.type)
                )
                Text(
                    text = time,
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(start = 8.dp).weight(1f)
                )
                if (entry.durationMs >= 0) {
                    Text(
                        text = "${entry.durationMs}ms",
                        fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                        // 慢得离谱的那几条要一眼能挑出来
                        color = if (entry.durationMs > 8000) Color(0xFFFF9500)
                        else MiuixTheme.colorScheme.onBackgroundVariant
                    )
                }
                if (!entry.ok) {
                    Text(
                        text = "失败",
                        fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                        color = Color(0xFFFF3B30),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            Text(
                text = entry.title,
                fontWeight = FontWeight.Medium,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                modifier = Modifier.padding(top = 4.dp)
            )

            AnimatedVisibility(visible = expanded) {
                Column {
                    Text(
                        text = entry.detail,
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        fontFamily = FontFamily.Monospace,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * 卡片底下那个删除图标。[progress] = 已划出的比例（0~1）。
 *
 * 单独抽成一个 composable 只为一件事：把「每帧读 offset」的重组范围圈在这里。
 * 过了删除阈值就变红 —— 松手到底删不删，得在松手**之前**就看得出来。
 */
@Composable
private fun DeleteHint(progress: () -> Float) {
    val p = progress()
    Icon(
        imageVector = MiuixIcons.Delete,
        contentDescription = "左滑删除这条记录",
        tint = if (p >= DELETE_FRACTION) Color(0xFFFF3B30)
        else MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = Modifier
            .padding(end = 24.dp)
            // 跟着划出的比例淡入，到阈值时正好完全不透明
            .alpha((p / DELETE_FRACTION).coerceIn(0f, 1f))
    )
}

private fun typeLabel(type: String): String = when (type) {
    LogEntry.TYPE_CHAT -> "对话"
    LogEntry.TYPE_TOOL -> "工具"
    LogEntry.TYPE_ERROR -> "错误"
    else -> type
}

private fun typeColor(type: String): Color = when (type) {
    LogEntry.TYPE_CHAT -> Color(0xFF3482FF)
    LogEntry.TYPE_TOOL -> Color(0xFF34C759)
    LogEntry.TYPE_ERROR -> Color(0xFFFF3B30)
    else -> Color.Gray
}
