package io.mo.xiaoaiplug.auto

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 把无障碍服务开回来。
 *
 * **为什么需要它:** MIUI/HyperOS 的安全中心把「清后台 / 强行停止」当成风险信号,
 * 会主动把非白名单应用从 `Settings.Secure.enabled_accessibility_services` 里摘掉。
 * 用户的体感是"授权过期了,又要重新授权一遍",实际上没有任何东西过期 ——
 * 只是那一行配置被别人改了。
 *
 * `enabled_accessibility_services` 是**唯一的真相来源**:系统的
 * AccessibilityManagerService 监听这个 key,值里有我们的组件就绑定,没有就解绑。
 * 所以把那行写回去,服务立刻重新连上,不需要用户碰任何界面。
 *
 * 两条写入路径,按代价从低到高:
 *  1. `WRITE_SECURE_SETTINGS` —— 一次 `pm grant` 之后永久有效(签名级权限,
 *     重装才丢),直接走 ContentResolver,不起进程,几毫秒。
 *  2. `su -c settings put` —— 没授权时的兜底。要 fork 一个 root shell,几百毫秒。
 *
 * 两条都不通就只能让用户自己去设置页,那时才是真的没辙。
 */
object AccessibilityGuard {

    private const val TAG = "XiaoAiProbe"

    /** 写完设置后等系统回连的上限。真机上通常 200~600ms 就连上了。 */
    private const val CONNECT_TIMEOUT_MS = 5000L

    /** 设置里已经有我们时,先给系统这么久自己把服务绑上,别急着 poke。 */
    private const val GRACE_MS = 1200L
    private const val POLL_MS = 100L
    private const val SHELL_TIMEOUT_SEC = 5L

    /** 探测 root 时可能要等用户在 KernelSU 弹窗上点「允许」,给宽一点。 */
    private const val ROOT_CHECK_TIMEOUT_SEC = 15L

    private fun self(ctx: Context): String =
        ComponentName(ctx.packageName, UiAutoService::class.java.name).flattenToString()

    /**
     * 有没有写系统设置的本事 —— 开关打开前拿它做准入检查。
     *
     * **不是只看 root。** 授过 `WRITE_SECURE_SETTINGS` 的话根本不需要 root,
     * 这条路还更快;只认 root 会把一个明明能用的配置拦在门外。
     *
     * 会 fork 一个 su 进程,**必须在后台线程调**。首次调用可能弹 KernelSU 的授权框,
     * 所以超时给得比平时宽 —— 那几秒是在等用户点「允许」,不是卡住了。
     */
    fun canSelfHeal(ctx: Context): Boolean = hasSecureSettingsPermission(ctx) || hasRoot()

    fun hasSecureSettingsPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED

    /** `su -c id` 能跑出 uid=0 才算数 —— 光看 su 存不存在不够,授权可能被拒。 */
    fun hasRoot(): Boolean = try {
        val proc = ProcessBuilder("su", "-c", "id")
            .redirectErrorStream(true)
            .directory(File("/"))
            .start()
        if (!proc.waitFor(ROOT_CHECK_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            false
        } else {
            proc.inputStream.bufferedReader().readText().contains("uid=0")
        }
    } catch (t: Throwable) {
        Log.i(TAG, "root check failed: ${t.javaClass.simpleName}")
        false
    }

    /**
     * 系统设置里是否列着我们。
     *
     * 比 `UiAutoService.instance != null` 早一步 —— 用户刚在设置页打开、服务还没
     * 连上的窗口期,这里已经是 true 了。
     */
    fun isEnabledInSettings(ctx: Context): Boolean =
        currentList(ctx).any { ComponentName.unflattenFromString(it)?.flattenToString() == self(ctx) }

    private fun currentList(ctx: Context): List<String> =
        Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':').filter { it.isNotBlank() }

    /**
     * 确保服务在跑。返回 `null` 表示可用,否则是给模型/用户看的失败说明。
     *
     * 已经在跑时零开销直接返回 —— 这是绝大多数调用的路径,不要在它前面加任何
     * 会起进程或读设置的东西。
     *
     * @param autoFix 允许自动写回系统设置。对应设置页那个开关,**只管自动的那条路** ——
     *   用户在主页手动点「去开启」是明确授意,那条路径固定传 true。
     */
    fun ensureRunning(ctx: Context, autoFix: Boolean = true): String? {
        if (UiAutoService.isRunning()) return null
        if (!autoFix) {
            return "error: 无障碍服务未运行，且「自动恢复无障碍」已关闭。" +
                    "请去系统设置里手动开启，或在本模块设置页打开该开关。"
        }

        // 设置里还列着我们、只是还没连上 —— 大概率是进程刚起来、系统正在绑定的
        // 空窗期(应用冷启动时就在这个窗口里)。先白等一下,别急着去 poke:
        // poke 会先把自己摘掉再加回来,在这种时候纯属把一次正在进行的绑定打断重来。
        if (isEnabledInSettings(ctx) && awaitConnection(GRACE_MS)) {
            Log.i(TAG, "accessibility service connected on its own, no poke needed")
            return null
        }

        Log.i(TAG, "accessibility service not running, attempting self-heal")
        val how = poke(ctx) ?: return failure(ctx, "没有可用的写入方式")

        if (awaitConnection(CONNECT_TIMEOUT_MS)) {
            Log.i(TAG, "accessibility service restored via $how")
            return null
        }
        return failure(ctx, "已用 $how 写回设置，但等 ${CONNECT_TIMEOUT_MS / 1000} 秒仍未连上")
    }

    /** 轮询等服务连上。@return 是否在超时前连上了。 */
    private fun awaitConnection(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (UiAutoService.isRunning()) return true
            Thread.sleep(POLL_MS)
        }
        return UiAutoService.isRunning()
    }

    private fun failure(ctx: Context, detail: String): String =
        "error: 无障碍服务未运行，自动恢复失败($detail)。" +
                "请去系统设置里手动开启，或执行:\n" +
                "  adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"

    /**
     * 把自己从设置里摘掉、再加回去,强制系统重新绑定。
     *
     * **为什么要先摘掉。** 只在"设置里没有我们"时追加是不够的:被 force-stop 之后,
     * 设置里那一行**往往还在**,只是服务连接断了,而 AccessibilityManagerService
     * 不会主动重连 —— 它只在这个 key 的值**发生变化**时才重新绑定。
     * 值没变就写一遍等于什么都没做。摘掉再写回去才构成一次真正的变化。
     *
     * 副作用只落在我们自己这一条上:`without` 保留了别人的组件,不会误伤用户的
     * 输入法或 TalkBack。
     *
     * @return 成功时返回用了哪条路径(拿去打日志),两条都不通返回 null。
     */
    private fun poke(ctx: Context): String? {
        val me = self(ctx)
        val without = currentList(ctx)
            .filter { ComponentName.unflattenFromString(it)?.flattenToString() != me }
        val with = without + me

        writeDirect(ctx, without.joinToString(":"), with.joinToString(":"))?.let { return it }
        return writeViaRoot(without.joinToString(":"), with.joinToString(":"))
    }

    /** 路径 1:有 WRITE_SECURE_SETTINGS 就自己写,不起进程。 */
    private fun writeDirect(ctx: Context, without: String, with: String): String? = try {
        val cr = ctx.contentResolver
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without)
        Thread.sleep(POLL_MS)
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, with)
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        "WRITE_SECURE_SETTINGS"
    } catch (t: Throwable) {
        // 没 grant 过就是 SecurityException,属于预期内,降级到 root。
        Log.i(TAG, "direct secure-settings write unavailable: ${t.javaClass.simpleName}")
        null
    }

    /** 路径 2:root。一次 `su` 跑完三条命令 —— 分三次起进程要多花小半秒。 */
    private fun writeViaRoot(without: String, with: String): String? {
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val cmd = "settings put secure $key ${quote(without)}" +
                " ; settings put secure $key ${quote(with)}" +
                " ; settings put secure ${Settings.Secure.ACCESSIBILITY_ENABLED} 1"
        return try {
            val proc = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .directory(File("/"))
                .start()
            if (!proc.waitFor(SHELL_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                Log.w(TAG, "su timed out while restoring accessibility")
                return null
            }
            if (proc.exitValue() == 0) "root" else {
                Log.w(TAG, "su exited ${proc.exitValue()}: ${proc.inputStream.bufferedReader().readText()}")
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "su unavailable: ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * 空串必须写成 `""` 而不是什么都不写 —— `settings put secure key`(缺参数)
     * 是语法错误,整条 `;` 链会在第一步就断掉,后面的写回根本不执行。
     */
    private fun quote(s: String): String =
        if (s.isEmpty()) "\"\"" else "'" + s.replace("'", "'\\''") + "'"
}
