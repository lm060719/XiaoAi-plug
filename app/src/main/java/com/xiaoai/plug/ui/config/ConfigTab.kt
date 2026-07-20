package com.xiaoai.plug.ui.config

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaoai.plug.config.Tools
import com.xiaoai.plug.ui.ConfigViewModel
import com.xiaoai.plug.ui.nav.PageScaffold
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference

/** 配置 tab 的二级页。null 表示停在入口列表。 */
enum class ConfigRoute { AI, INTERCEPT, SPEAK, TOOLS }

@Composable
fun ConfigTab(bottomInset: Dp, vm: ConfigViewModel = viewModel()) {
    var route by rememberSaveable { mutableStateOf<ConfigRoute?>(null) }

    BackHandler(enabled = route != null) { route = null }

    AnimatedContent(
        targetState = route,
        transitionSpec = {
            // 进二级页：新页从右滑入，列表往左退一点并轻微缩小，做出层级感。返回时反过来。
            val enteringDetail = initialState == null
            if (enteringDetail) {
                (slideInHorizontally(spring(stiffness = 400f)) { it } + fadeIn()) togetherWith
                    (scaleOut(spring(stiffness = 400f), targetScale = 0.94f) + fadeOut())
            } else {
                (scaleIn(spring(stiffness = 400f), initialScale = 0.94f) + fadeIn()) togetherWith
                    (slideOutHorizontally(spring(stiffness = 400f)) { it } + fadeOut())
            }
        },
        label = "config"
    ) { current ->
        when (current) {
            null -> ConfigList(vm, bottomInset) { route = it }
            ConfigRoute.AI -> AiEndpointScreen(vm, bottomInset) { route = null }
            ConfigRoute.INTERCEPT -> InterceptScreen(vm, bottomInset) { route = null }
            ConfigRoute.SPEAK -> SpeakScreen(vm, bottomInset) { route = null }
            ConfigRoute.TOOLS -> ToolsScreen(vm, bottomInset) { route = null }
        }
    }
}

@Composable
private fun ConfigList(
    vm: ConfigViewModel,
    bottomInset: Dp,
    onOpen: (ConfigRoute) -> Unit
) {
    val config by vm.config.collectAsStateWithLifecycle()

    val enabledToolCount = Tools.enabled(config.enabledTools).size
    val interceptCount = listOf(config.blockViewJump, config.blockWebSearch).count { it }

    PageScaffold(title = "配置", bottomInset = bottomInset) {
        item { SmallTitle("接管") }
        item {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "AI 接入",
                    summary = if (!config.isUsable) "未配置"
                    else "${config.aiProvider.label} · ${config.effectiveModel}",
                    onClick = { onOpen(ConfigRoute.AI) }
                )
                ArrowPreference(
                    title = "拦截规则",
                    summary = if (interceptCount == 0) "全部关闭" else "$interceptCount 项已开启",
                    onClick = { onOpen(ConfigRoute.INTERCEPT) }
                )
                ArrowPreference(
                    title = "语音播报",
                    summary = if (config.speakAnswer) "已开启" else "已关闭（只出卡片、不出声）",
                    onClick = { onOpen(ConfigRoute.SPEAK) }
                )
            }
        }

        item { SmallTitle("能力") }
        item {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "工具",
                    summary = "$enabledToolCount/${Tools.ALL.size} 已启用",
                    onClick = { onOpen(ConfigRoute.TOOLS) }
                )
            }
        }
    }
}
