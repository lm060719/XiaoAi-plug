package io.mo.xiaoaiplug.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Tools.isReadOnlyShell] 的真值表。
 *
 * 这是道安全闸:判成"只读"的命令,在**本模块没有接管本轮对话**时也照样会以 root 执行;
 * 判成"动作类"的则会被拒绝。所以两个方向的错都有代价,但不对称 ——
 *  - 该拦的没拦(漏判) = 模型在不归我们的轮次里真的改了设备,**这是事故**
 *  - 该放的没放(误判) = 模型少个查询能力,只是难用
 * 因此实现上是**白名单**(认不出就当动作类),测试里"必须拦截"那组尤其不能删。
 *
 * 为什么值得有这个文件:这段逻辑第一版是靠人眼推演的,漏了引号 ——
 * `dumpsys battery | grep -E 'level|status'` 里的 `|` 是正则的一部分,不是管道,
 * 按字面切会把 `status'` 当成命令名,整条被误判成动作类。跑一遍就抓出来了。
 */
class ShellGateTest {

    private fun assertReadOnly(vararg cmds: String) {
        for (c in cmds) assertEquals("应判为只读: $c", true, Tools.isReadOnlyShell(c))
    }

    private fun assertMutating(vararg cmds: String) {
        for (c in cmds) assertEquals("应判为动作类: $c", false, Tools.isReadOnlyShell(c))
    }

    @Test
    fun `纯查询命令放行`() = assertReadOnly(
        "getprop ro.build.version.release",
        "cat /proc/meminfo",
        "ls -la /sdcard; df -h",
        "uptime",
        "/system/bin/getprop ro.build.id"          // 带路径的可执行文件
    )

    @Test
    fun `串联的查询命令逐段放行`() = assertReadOnly(
        "getprop a && getprop b && getprop c",     // 实测模型查版本时就这么写
        "cat /proc/meminfo | grep MemTotal",
        "ls -la | grep -E 'foo|bar' | wc -l",
        "dumpsys system_update 2>/dev/null || getprop ro.build.display.id"
    )

    @Test
    fun `引号内的分隔符不算分隔符`() = assertReadOnly(
        "dumpsys battery | grep -E 'level|status'",
        "dumpsys battery | grep -E 'level|status|powered|temperature'",   // device_status 的写法
        "grep -E 'a|b' /proc/x",
        "cat 'a>b'",                               // > 在单引号内不是重定向
        "echo '\$(rm -rf /)'"                      // 单引号内是字面量,不会执行
    )

    @Test
    fun `读写混合的命令按子命令区分`() {
        assertReadOnly("settings get system screen_brightness", "pm list packages 2>/dev/null")
        assertMutating("settings put system screen_brightness 100", "pm uninstall com.foo")
    }

    @Test
    fun `会改变设备状态的命令一律拦下`() = assertMutating(
        "am start -a android.intent.action.SYSTEM_UPDATE",   // 真机上发生过:未接管却启动了 Activity
        "svc wifi disable",
        "input tap 100 200",
        "reboot",
        "wm size 1080x1920",
        "ip link set wlan0 down",
        "pm install /sdcard/a.apk"
    )

    @Test
    fun `串在只读命令后面的危险命令拦得住`() = assertMutating(
        "getprop x && rm -rf /data",               // 只看第一段会放行
        "getprop a; am start -n x/y",
        "cat /x | rm -rf /y",
        "ls / & rm -rf /tmp"                       // 后台执行
    )

    @Test
    fun `重定向和命令替换一律拦下`() = assertMutating(
        "echo hi > /sdcard/f",
        "cat /x > /y",
        "cat `which rm`",
        "cat \$(which rm)",
        "echo \"\$(rm -rf /)\"",                   // 双引号内替换仍会执行
        "echo \"`rm -rf /`\""
    )

    @Test
    fun `解析不了的一律当动作类`() = assertMutating(
        "",
        "   ",
        "grep 'unterminated"                       // 引号没闭合,拆不可信
    )
}
