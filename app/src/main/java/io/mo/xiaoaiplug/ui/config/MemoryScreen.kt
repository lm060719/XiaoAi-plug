package io.mo.xiaoaiplug.ui.config

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.mo.xiaoaiplug.config.MemoryEntry
import io.mo.xiaoaiplug.config.MemoryStore
import io.mo.xiaoaiplug.config.Tools
import io.mo.xiaoaiplug.ui.ConfigViewModel
import io.mo.xiaoaiplug.ui.MemoryViewModel
import io.mo.xiaoaiplug.ui.nav.CardContentPadding
import io.mo.xiaoaiplug.ui.nav.CardHorizontalPadding
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 划过卡片宽度的这个比例，松手即删；没到就弹回去。跟记录页保持一致的手感。 */
private const val DELETE_FRACTION = 1f / 3f

/** 划到底时的跟手系数：手指走 1px，卡片只走这么多。起手完全跟手，越往后越沉。 */
private const val MIN_DRAG_FACTOR = 0.35f

@Composable
fun MemoryScreen(
    configVm: ConfigViewModel,
    bottomInset: Dp,
    onBack: () -> Unit,
    vm: MemoryViewModel = viewModel()
) {
    val config by configVm.config.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    // 「记忆个性化录入」= save_memory 这个工具开没开。跟工具页里那个开关是同一份状态，
    // 不另存一个配置项 —— 两份状态迟早会对不上。
    val captureOn = Tools.isEnabled(config.enabledTools, Tools.SAVE_MEMORY)

    var draft by remember { mutableStateOf("") }

    SubScreen(title = "记忆", bottomInset = bottomInset, onBack = onBack) {
        item { SmallTitle("录入") }
        item {
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = captureOn,
                    onCheckedChange = { on ->
                        configVm.update {
                            it.copy(enabledTools = Tools.withEnabled(it.enabledTools, Tools.SAVE_MEMORY, on))
                        }
                    },
                    title = "记忆个性化录入",
                    summary = " AI 聊天中主动记下关于你的信息"
                )
                Text(
                    text = "关掉后 AI 不再主动记新的，但已有的记忆照常生效",
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(horizontal = CardHorizontalPadding, vertical = 8.dp)
                )
            }
        }

        item { SmallTitle("添加") }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = CardContentPadding) {
                Column(Modifier.fillMaxWidth()) {
                    TextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = "写一句话，比如「用户对花生过敏」",
                        useLabelAsPlaceholder = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            text = "添加",
                            onClick = {
                                vm.add(draft)
                                draft = ""
                            },
                            enabled = draft.isNotBlank(),
                            minWidth = 0.dp,
                            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                    if (error != null) {
                        Text(
                            text = error.orEmpty(),
                            color = Color(0xFFFF3B30),
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        item {
            SmallTitle("已记住 ${entries.size}/${MemoryStore.MAX_ROWS} 条")
        }

        if (entries.isEmpty()) {
            item {
                Text(
                    text = "还没有任何记忆。开着上面的开关聊几句，或者自己加一条。",
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(horizontal = CardHorizontalPadding, vertical = 16.dp)
                )
            }
        }

        items(entries, key = { it.id }) { entry ->
            MemoryRow(
                entry = entry,
                modifier = Modifier.animateItem(),
                onSave = { vm.edit(entry.id, it) },
                onDelete = { vm.delete(entry.id) }
            )
        }
    }
}

/**
 * 一条记忆。点开进编辑态(内联 TextField,本仓库里没有对话框的先例),左滑删除。
 * 滑动手感和实现跟记录页那行是同一套,改一处记得两边对齐。
 */
@Composable
private fun MemoryRow(
    entry: MemoryEntry,
    modifier: Modifier = Modifier,
    onSave: (String) -> Unit,
    onDelete: () -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(entry.id, entry.content) { mutableStateOf(entry.content) }
    val time = remember(entry.time) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(entry.time))
    }

    var widthPx by remember { mutableFloatStateOf(0f) }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    androidx.compose.foundation.layout.Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
    ) {
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 进度用 lambda 传进去、在里面才读 offsetX —— 直接在外面读的话整行每帧都要重组
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
                                // 阻尼压的是**增量**不是总位移
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
                    // 编辑态锁住滑动，不然改字的时候手一歪整条就飞出去删了
                    enabled = !editing,
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
            insideMargin = CardContentPadding,
            onClick = { editing = !editing }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (entry.source == MemoryEntry.SOURCE_MANUAL) "手动" else "自动",
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = MiuixTheme.colorScheme.onBackgroundVariant
                )
                Text(
                    text = time,
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            if (!editing) {
                Text(
                    text = entry.content,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            AnimatedVisibility(visible = editing) {
                Column(Modifier.padding(top = 8.dp)) {
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        label = "记忆内容",
                        useLabelAsPlaceholder = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            text = "取消",
                            onClick = {
                                text = entry.content
                                editing = false
                            },
                            minWidth = 0.dp,
                            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        )
                        TextButton(
                            text = "保存",
                            onClick = {
                                onSave(text)
                                editing = false
                            },
                            enabled = text.isNotBlank() && text != entry.content,
                            minWidth = 0.dp,
                            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteHint(progress: () -> Float) {
    val p = progress()
    Icon(
        imageVector = MiuixIcons.Delete,
        contentDescription = "左滑删除这条记忆",
        tint = if (p >= DELETE_FRACTION) Color(0xFFFF3B30)
        else MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = Modifier
            .padding(end = 24.dp)
            .alpha((p / DELETE_FRACTION).coerceIn(0f, 1f))
    )
}
