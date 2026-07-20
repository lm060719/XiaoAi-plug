package com.xiaoai.plug.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoai.plug.config.AiProvider
import com.xiaoai.plug.ui.ConfigViewModel
import com.xiaoai.plug.ui.TestState
import com.xiaoai.plug.ui.nav.CardContentPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AiEndpointScreen(vm: ConfigViewModel, bottomInset: Dp, onBack: () -> Unit) {
    val config by vm.config.collectAsStateWithLifecycle()
    val testState by vm.testState.collectAsStateWithLifecycle()
    var keyVisible by remember { mutableStateOf(false) }

    val provider = config.aiProvider
    val providers = AiProvider.entries

    SubScreen(title = "AI 接入", bottomInset = bottomInset, onBack = onBack) {
        item { SmallTitle("服务商") }
        item {
            Card(Modifier.fillMaxWidth()) {
                WindowDropdownPreference(
                    title = "接入方式",
                    items = providers.map { it.label },
                    selectedIndex = providers.indexOf(provider),
                    // 只存 key，不动用户已填的地址/模型 —— 那两个留空就自动跟着新服务商的默认走，
                    // 填过的则原样保留，切回来还在。
                    onSelectedIndexChange = { i ->
                        vm.update { it.copy(provider = providers[i].key) }
                    }
                )
            }
        }
        item {
            Text(
                text = "xAI 和硅基流动都是 OpenAI 兼容协议，Anthropic 走自己的 /messages。",
                fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        item { SmallTitle("端点") }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = CardContentPadding) {
                TextField(
                    value = config.endpoint,
                    onValueChange = { v -> vm.update { it.copy(endpoint = v) } },
                    // 留空时这行灰字就是实际会用的地址，不是单纯的提示
                    label = provider.defaultEndpoint,
                    useLabelAsPlaceholder = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
                Text(
                    text = "API 地址，可省略末尾路径。留空即使用上方灰字的默认地址。",
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                TextField(
                    value = config.apiKey,
                    onValueChange = { v -> vm.update { it.copy(apiKey = v) } },
                    label = "API Key",
                    useLabelAsPlaceholder = false,
                    // 密钥默认打码：这一页随时可能被人瞄到，也可能被截图发出去问问题
                    visualTransformation = if (keyVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                imageVector = if (keyVisible) MiuixIcons.Hide else MiuixIcons.Show,
                                contentDescription = if (keyVisible) "隐藏" else "显示",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )

                TextField(
                    value = config.model,
                    onValueChange = { v -> vm.update { it.copy(model = v) } },
                    label = provider.defaultModel,
                    useLabelAsPlaceholder = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
                Text(
                    text = "模型名。留空即使用上方灰字的默认模型。",
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }

        item { SmallTitle("系统提示词") }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = CardContentPadding) {
                TextField(
                    value = config.systemPrompt,
                    onValueChange = { v -> vm.update { it.copy(systemPrompt = v) } },
                    label = "系统提示词（可留空）",
                    useLabelAsPlaceholder = false,
                    minLines = 3,
                    // 不封顶的话长提示词会把输入框撑到几屏高，下面的测试连接按钮就够不着了
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
            }
        }

        item { SmallTitle("连通性") }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = CardContentPadding) {
                Column(Modifier.fillMaxWidth()) {
                    TextButton(
                        text = if (testState is TestState.Running) "测试中…" else "测试连接",
                        onClick = { vm.testConnection() },
                        enabled = testState !is TestState.Running,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val (msg, color) = when (val s = testState) {
                        is TestState.Ok -> "通了，模型回复：${s.reply}" to Color(0xFF34C759)
                        is TestState.Failed -> "失败：${s.message}" to Color(0xFFFF3B30)
                        else -> null to Color.Unspecified
                    }
                    if (msg != null) {
                        Text(
                            text = msg,
                            color = color,
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    Text(
                        text = "测试时会临时关掉全部工具，只验证地址、密钥和模型名，不会真去动设备。",
                        fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }
    }
}
