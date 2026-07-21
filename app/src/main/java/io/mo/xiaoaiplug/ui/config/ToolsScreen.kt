package io.mo.xiaoaiplug.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import io.mo.xiaoaiplug.config.Tools
import io.mo.xiaoaiplug.ui.ConfigViewModel
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
        // 「全勾选时也存显式列表」那条语义已经收进 Tools.withEnabled ——
        // 记忆页的「个性化录入」开关写的是同一份 csv，逻辑必须共用一份。
        vm.update { it.copy(enabledTools = Tools.withEnabled(it.enabledTools, name, on)) }
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
            Text(
                text = "已启用 ${enabledNames.size}/${Tools.ALL.size}",
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
            )
        }
        item {
            // 不再套 Card：TextButton 自带灰底，外面再包一层 Card 的灰底就是两层
            // 叠在一起，两颗按钮竖排还会圆角相接，糊成一块分不清是一个控件还是两个。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = "全部启用",
                    onClick = {
                        vm.update { c ->
                            c.copy(enabledTools = Tools.ALL.joinToString(",") { it.name })
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = "全部关闭",
                    onClick = { vm.update { it.copy(enabledTools = Tools.NONE) } },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 搜索框紧贴它要过滤的那份列表，放在批量开关下面
        item {
            TextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = "搜索工具",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
        }
        // root 权限提示
        item {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    text = "⚠️ 某些工具需要在root管理器中授权超级小爱root后才能调用,风险自行掂量",
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(12.dp)
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
            item { SmallTitle("会改变设备状态（慎用）") }
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
