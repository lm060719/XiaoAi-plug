package io.mo.xiaoaiplug.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.net.toUri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.mo.xiaoaiplug.ui.ConfigViewModel
import io.mo.xiaoaiplug.ui.nav.CardContentPadding
import io.mo.xiaoaiplug.ui.nav.PageScaffold
import io.mo.xiaoaiplug.ui.theme.AccentColor
import io.mo.xiaoaiplug.ui.theme.DarkMode
import io.mo.xiaoaiplug.ui.theme.UiPrefs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val SOURCE_URL = "https://github.com/lm060719/XiaoAi-plug"

@Composable
fun SettingsScreen(bottomInset: Dp, vm: ConfigViewModel = viewModel()) {
    val context = LocalContext.current
    val prefs = remember(context) { UiPrefs.get(context) }
    val darkMode by prefs.darkMode.collectAsStateWithLifecycle()
    val accent by prefs.accent.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()

    val modes = remember { DarkMode.entries.toList() }

    PageScaffold(title = "设置", bottomInset = bottomInset) {
        item { SmallTitle("无障碍") }
        item {
            // 不给 insideMargin：SwitchPreference 自带内边距，再叠一层就比隔壁
            // 「深浅色」缩进得多，一眼看出来没对齐。和主页那张「快捷开关」卡一致。
            Card(Modifier.fillMaxWidth()) {
                // summary 和 enabled 都是常量：任何随检查状态变化的文案/灰化都会让
                // 卡片高度或透明度抖一下，没 root 时 su 几毫秒就失败，抖动看着就是"闪一下"。
                // 检查结果由底部那行提示交代，不占布局。
                SwitchPreference(
                    checked = config.autoFixAccessibility,
                    onCheckedChange = { on -> vm.setAutoFixAccessibility(on) },
                    title = "自动恢复无障碍",
                    summary = "被清后台摘掉权限后自动写回，不用再去设置页开一次"
                )
            }
        }
        item {
            // 脚注放卡片外面。横向不用主页那句footnote的 12.dp —— 这句是在解释上面
            // 那个开关，左边缘要跟开关标题对齐，而 SwitchPreference 自带内边距会把
            // 标题往右推。18.dp 是真机比着量出来的。
            Text(
                text = "需要给本应用授予 root 权限。也可以执行一次 " +
                        "adb shell pm grant ${context.packageName} " +
                        "android.permission.WRITE_SECURE_SETTINGS，之后无需 root 且更快。",
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
            )
        }

        item { SmallTitle("外观") }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = CardContentPadding) {
                Text(text = "深浅色", fontWeight = FontWeight.Medium)
                TabRow(
                    tabs = modes.map { it.label },
                    selectedTabIndex = modes.indexOf(darkMode),
                    onTabSelected = { prefs.setDarkMode(modes[it]) },
                    // 默认底色是 surface(纯黑)，而卡片是 surfaceContainer —— 直接用会在卡片里
                    // 露出一圈黑色底板。放进卡片时要把两个色反过来取。
                    colors = TabRowDefaults.tabRowColors(
                        backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
                        selectedBackgroundColor = MiuixTheme.colorScheme.surfaceContainerHighest
                    ),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )

                Text(
                    text = "主题色",
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    AccentColor.entries.forEach { option ->
                        val selected = option == accent
                        Box(
                            Modifier
                                .padding(end = 16.dp)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(option.color)
                                .then(
                                    if (selected) Modifier.border(
                                        width = 3.dp,
                                        color = MiuixTheme.colorScheme.onBackground,
                                        shape = CircleShape
                                    ) else Modifier
                                )
                                .clickable { prefs.setAccent(option) }
                        )
                    }
                }
            }
        }

        item { SmallTitle("关于") }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = CardContentPadding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "XiaoAi Plug", fontWeight = FontWeight.Medium)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // clip 要在 clickable 之前，否则水波纹是方的、会溢出卡片圆角。
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            // 没装浏览器 / 被策略拦掉都会抛 ActivityNotFoundException，
                            // 这只是个跳转，崩掉设置页不值当。
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri())
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "在 GitHub 上查看源码",
                        color = MiuixTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
