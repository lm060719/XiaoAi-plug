package io.mo.xiaoaiplug.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.mo.xiaoaiplug.ui.ConfigViewModel
import io.mo.xiaoaiplug.ui.config.ConfigTab
import io.mo.xiaoaiplug.ui.home.HomeScreen
import io.mo.xiaoaiplug.ui.logs.LogsScreen
import io.mo.xiaoaiplug.ui.settings.SettingsScreen
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme

class NavTab(val label: String, val icon: ImageVector)

@Composable
fun AppRoot() {
    val tabs = listOf(
        NavTab("主页", MiuixIcons.Home),
        NavTab("配置", MiuixIcons.Tune),
        NavTab("记录", MiuixIcons.Recent),
        NavTab("设置", MiuixIcons.Settings)
    )

    var selected by rememberSaveable { mutableIntStateOf(0) }

    // 和各页里的 viewModel() 拿到的是同一个实例（宿主都是 Activity），
    // 所以设置页那边 showToast 一下，这里就能显示出来。
    val vm: ConfigViewModel = viewModel()
    val toast by vm.toast.collectAsStateWithLifecycle()

    val surfaceColor = MiuixTheme.colorScheme.surface
    // 关键：录制图层前先铺一层不透明底色。少了这句，采样到的是透明像素，
    // 模糊出来就是一坨看不出所以然的灰 —— 上一版就栽在这里。
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // 系统栏 inset 交给各页的 TopAppBar 去处理，这里置零免得两头重复加
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            GlassNavBar(
                tabs = tabs,
                selectedIndex = selected,
                onSelect = { selected = it },
                backdrop = backdrop
            )
        }
    ) { innerPadding ->
        val bottomInset = innerPadding.calculateBottomPadding()
        Box(Modifier.fillMaxSize()) {
            // 底栏要模糊的就是这一层
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                AnimatedContent(
                    targetState = selected,
                    transitionSpec = {
                        // 刻意不做横滑：4 个 tab 横滑的方向会跟二级页的返回手势打架。
                        // 淡入 + 一点点纵向位移，够交代切换关系又不抢戏。
                        val forward = targetState > initialState
                        val offset = if (forward) 24 else -24
                        (fadeIn(spring(stiffness = 600f)) +
                            slideInVertically(spring(stiffness = 500f)) { offset }) togetherWith
                            (fadeOut(spring(stiffness = 600f)) +
                                slideOutVertically(spring(stiffness = 500f)) { -offset })
                    },
                    label = "tab"
                ) { index ->
                    when (index) {
                        0 -> HomeScreen(bottomInset = bottomInset, onGoLogs = { selected = 2 })
                        1 -> ConfigTab(bottomInset = bottomInset)
                        2 -> LogsScreen(bottomInset = bottomInset)
                        else -> SettingsScreen(bottomInset = bottomInset)
                    }
                }
            }
            // 放在 layerBackdrop 那层**外面**：进去的话底栏会把提示条一起采样进模糊里。
            BottomToast(message = toast, bottomInset = bottomInset)
        }
    }
}
