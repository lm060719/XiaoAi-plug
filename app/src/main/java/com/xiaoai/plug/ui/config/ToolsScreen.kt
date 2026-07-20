package com.xiaoai.plug.ui.config

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoai.plug.config.Tools
import com.xiaoai.plug.ui.ConfigViewModel
import com.xiaoai.plug.ui.nav.CardContentPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ToolsScreen(vm: ConfigViewModel, bottomInset: Dp, onBack: () -> Unit) {
    val config by vm.config.collectAsStateWithLifecycle()
    var keyword by remember { mutableStateOf("") }

    val enabledNames = remember(config.enabledTools) {
        Tools.enabled(config.enabledTools).map { it.name }.toSet()
    }

    fun setEnabled(name: String, on: Boolean) {
        val next = if (on) enabledNames + name else enabledNames - name
        vm.update {
            // 全勾选时也存成显式列表：存空串的话以后新增工具会被自动打开，
            // 用户以为自己关掉的东西又冒出来。（这条语义在旧界面里就有，不能丢。）
            it.copy(enabledTools = next.joinToString(",").ifEmpty { Tools.NONE })
        }
    }

    val matched = remember(keyword) {
        val k = keyword.trim()
        if (k.isEmpty()) Tools.ALL
        else Tools.ALL.filter {
            it.name.contains(k, true) || it.description.contains(k, true)
        }
    }
    val readOnly = matched.filter { !it.mutating }
    val mutating = matched.filter { it.mutating }

    SubScreen(title = "工具", bottomInset = bottomInset, onBack = onBack) {
        item {
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = config.useNativeTools,
                    onCheckedChange = { on -> vm.update { it.copy(useNativeTools = on) } },
                    title = "原生 function calling",
                    summary = "比让模型在正文里写标记可靠得多；端点不支持时会自动降级"
                )
            }
        }

        item {
            TextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = "搜索工具",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
        }

        item {
            Text(
                text = "已启用 ${enabledNames.size}/${Tools.ALL.size}",
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
            )
        }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = CardContentPadding) {
                TextButton(
                    text = "全部启用",
                    onClick = {
                        vm.update { c ->
                            c.copy(enabledTools = Tools.ALL.joinToString(",") { it.name })
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    text = "全部关闭",
                    onClick = { vm.update { it.copy(enabledTools = Tools.NONE) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (readOnly.isNotEmpty()) {
            item { SmallTitle("只读") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    readOnly.forEach { spec ->
                        SwitchPreference(
                            checked = spec.name in enabledNames,
                            onCheckedChange = { on -> setEnabled(spec.name, on) },
                            title = spec.name,
                            summary = spec.description
                        )
                    }
                }
            }
        }

        if (mutating.isNotEmpty()) {
            item { SmallTitle("会改变设备状态") }
            item {
                Text(
                    // 这一组单独拎出来是有来历的：曾经出过「我们没接管、模型却照跑」
                    // 把微信真打开了的事故，动作类工具的风险跟只读工具完全不是一回事。
                    text = "这些工具会启动应用、改设置、动音量。只有在本轮确实由本模块接管时才会执行。" +
                        "带 root 的还需要在 KernelSU 里给超级小爱授权。",
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    mutating.forEach { spec ->
                        SwitchPreference(
                            checked = spec.name in enabledNames,
                            onCheckedChange = { on -> setEnabled(spec.name, on) },
                            title = spec.name,
                            summary = spec.description
                        )
                    }
                }
            }
        }

        if (matched.isEmpty()) {
            item {
                Text(
                    text = "没有匹配「$keyword」的工具",
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
