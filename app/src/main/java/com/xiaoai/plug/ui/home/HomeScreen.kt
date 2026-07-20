package com.xiaoai.plug.ui.home

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaoai.plug.ui.ConfigViewModel
import com.xiaoai.plug.ui.nav.CardContentPadding
import com.xiaoai.plug.ui.nav.PageScaffold
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomeScreen(
    bottomInset: Dp,
    onGoLogs: () -> Unit,
    vm: ConfigViewModel = viewModel()
) {
    val context = LocalContext.current
    val config by vm.config.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val reachable by vm.reachable.collectAsStateWithLifecycle()

    PageScaffold(title = "主页", bottomInset = bottomInset) {
        item { SmallTitle("运行状况") }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = CardContentPadding) {
                StatusRow(
                    ok = status.moduleActive,
                    title = "LSPosed 模块",
                    summary = if (status.moduleActive) "已激活"
                    else "未激活。作用域需勾上「超级小爱」"
                )
                StatusRow(
                    ok = status.accessibilityOn,
                    title = "无障碍服务",
                    summary = if (status.accessibilityOn) "已开启"
                    else "未开启。send_message 工具需要它",
                    action = if (status.accessibilityOn) null else ({
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                        Unit
                    })
                )
                if (!reachable) {
                    StatusRow(
                        ok = false,
                        title = "配置服务",
                        summary = "读写配置失败，改动不会生效"
                    )
                }
            }
        }

        item { SmallTitle("今日") }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = CardContentPadding) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatCell(status.todayChats.toString(), "对话")
                    StatCell(status.todayTools.toString(), "工具调用")
                    StatCell(
                        status.todayFailures.toString(),
                        "失败",
                        highlight = status.todayFailures > 0
                    )
                }
                TextButton(text = "查看记录", onClick = onGoLogs, modifier = Modifier.fillMaxWidth())
            }
        }

        item { SmallTitle("快捷开关") }
        item {
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = config.enabled,
                    onCheckedChange = { on -> vm.update { it.copy(enabled = on) } },
                    title = "启用替换",
                    summary = if (config.endpoint.isBlank()) "尚未填写 API 地址"
                    else config.model.ifBlank { "未填模型名" }
                )
                SwitchPreference(
                    checked = config.speakAnswer,
                    onCheckedChange = { on -> vm.update { it.copy(speakAnswer = on) } },
                    title = "语音播报",
                    summary = "TTS替换，关闭即输出时静音"
                )
                SwitchPreference(
                    checked = config.blockViewJump,
                    onCheckedChange = { on -> vm.update { it.copy(blockViewJump = on) } },
                    title = "拦截跳转设置页",
                    summary = "说「查看…」时不跳系统设置"
                )
                SwitchPreference(
                    checked = config.blockWebSearch,
                    onCheckedChange = { on -> vm.update { it.copy(blockWebSearch = on) } },
                    title = "拦截跳转搜索",
                    summary = "答不上来时改由 AI 回答"
                )
            }
        }

        item {
            Text(
                text = "修改后结束运行超级小爱生效。",
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
            )
        }
    }
}

@Composable
private fun StatusRow(
    ok: Boolean,
    title: String,
    summary: String,
    action: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (ok) Color(0xFF34C759) else Color(0xFFFF3B30))
        )
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(text = title, fontWeight = FontWeight.Medium)
            Text(
                text = summary,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
        }
        if (action != null) {
            TextButton(text = "去开启", onClick = action)
        }
    }
}

@Composable
private fun StatCell(value: String, label: String, highlight: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = MiuixTheme.textStyles.title2.fontSize,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) Color(0xFFFF3B30) else MiuixTheme.colorScheme.onBackground
        )
        Text(
            text = label,
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            color = MiuixTheme.colorScheme.onBackgroundVariant
        )
    }
}
