package com.xiaoai.plug.ui.settings

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoai.plug.ui.nav.CardContentPadding
import com.xiaoai.plug.ui.nav.PageScaffold
import com.xiaoai.plug.ui.theme.AccentColor
import com.xiaoai.plug.ui.theme.DarkMode
import com.xiaoai.plug.ui.theme.UiPrefs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(bottomInset: Dp) {
    val context = LocalContext.current
    val prefs = remember(context) { UiPrefs.get(context) }
    val darkMode by prefs.darkMode.collectAsStateWithLifecycle()
    val accent by prefs.accent.collectAsStateWithLifecycle()

    val modes = remember { DarkMode.entries.toList() }

    PageScaffold(title = "设置", bottomInset = bottomInset) {
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
                Text(
                    text = "用你自己的 AI 接管超级小爱的对话内容，界面展示不变。",
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "改完配置需要强退并重新打开超级小爱才会生效。\n" +
                        "探针日志：adb logcat -s XiaoAiProbe",
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
