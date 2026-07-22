package io.mo.xiaoaiplug.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 底栏上方一闪而过的半透明提示文字。
 *
 * 用在「说一声就够、不需要用户处理」的失败上 —— 比如没 root 所以开关没打开。
 * 这类信息不值得在页面里长期占一块地方：用户看完就该消失，留着反而像个待办。
 *
 * 消失由调用方控制(把 [message] 置 null),这里只负责淡入淡出。
 */
@Composable
fun BoxScope.BottomToast(
    message: String?,
    bottomInset: Dp,
    modifier: Modifier = Modifier
) {
    // 记住最后一条非空文案。message 置 null 之后淡出动画还要拿它渲染 ——
    // 直接读 message 会在淡出的第一帧变成空字符串,看起来是"先瞬间空掉、再慢慢淡",
    // 而不是整条一起淡出。
    var last by remember { mutableStateOf("") }
    LaunchedEffect(message) { if (message != null) last = message }

    AnimatedVisibility(
        visible = message != null,
        // 出现要利落,消失要慢 —— 慢淡出才是"自己飘走了",快淡出像是被谁关掉的。
        enter = fadeIn(tween(durationMillis = 180)),
        exit = fadeOut(tween(durationMillis = 900)),
        modifier = modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = bottomInset + 12.dp, start = 24.dp, end = 24.dp)
    ) {
        // 没有底板、没有圆角 —— 就是一行浮在内容上的半透明小字。
        Text(
            text = last,
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
