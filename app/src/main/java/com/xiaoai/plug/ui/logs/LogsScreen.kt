package com.xiaoai.plug.ui.logs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                    text = "还没有记录。强退并重开超级小爱，说一句会触发接管的话就会出现在这里。",
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        items(entries, key = { it.id }) { entry ->
            LogRow(entry, Modifier.animateItem())
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val time = remember(entry.time) {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.time))
    }

    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
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
