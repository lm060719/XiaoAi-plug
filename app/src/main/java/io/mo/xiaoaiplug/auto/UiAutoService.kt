package io.mo.xiaoaiplug.auto

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.icu.text.Transliterator
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务:代替用户操作别的应用的界面。
 *
 * 存在的理由:小爱做不了"给某人发微信说某话"(它只会答"暂不支持微信双开"),
 * 而我们的模型手里只有 launch_app —— 最多把微信拉起来。要真的发出去,
 * 只能遍历控件树、找输入框、注入文本、点发送。
 *
 * 跨进程关系:Hook 跑在小爱进程里,本服务跑在模块自己的进程。
 * 两边通过 ConfigProvider.call() 通信(见 ConfigProvider 里的 METHOD_UI_*)。
 * 因为 Provider 和本服务同进程,这里用一个静态实例引用就够了。
 *
 * **只对微信生效**(见 res/xml/ui_auto_service.xml 的 packageNames):
 * 无障碍服务能看到它被授权的所有应用的全部界面内容,范围能小就小。
 */
class UiAutoService : AccessibilityService() {

    /**
     * 汉字转拉丁(带声调)的 ICU 实例。`Latin-ASCII` 那一段把声调符号去掉,
     * 免得取到的"首字母"是个带调的字符(ǎ 之类)。构造一次复用。
     */
    private val han2latin: Transliterator? by lazy {
        runCatching { Transliterator.getInstance("Han-Latin; Latin-ASCII") }
            .onFailure { Log.w(TAG, "ICU Han-Latin 不可用,发消息将只用全名搜索", it) }
            .getOrNull()
    }

    companion object {
        private const val TAG = "XiaoAiProbe"
        const val WECHAT = "com.tencent.mm"
        const val VOICE_ASSIST = "com.miui.voiceassist"

        @Volatile
        var instance: UiAutoService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "UiAutoService connected")
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "UiAutoService destroyed")
        super.onDestroy()
    }

    // 我们是主动驱动型的,不靠事件推动;事件流只用来等界面稳定。
    @Volatile
    private var lastEventAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventAt = SystemClock.uptimeMillis()
    }

    override fun onInterrupt() {}

    // ------------------------------------------------------------ 侦察用

    /**
     * 打印当前窗口的控件树。用来搞清楚微信这一版的真实结构,不靠猜 id。
     *
     * **默认不返回文本内容**,只给结构(类名/资源 id/可点可编辑/文本长度)。
     * 教训:调试时把这棵树整个捞出来看,结果连着聊天记录一起捞了 ——
     * 文件传输助手里存着 API key,直接进了日志和调试输出。
     * 界面文本天然就是用户的隐私数据,默认就该是脱敏的。
     * 真要看内容时显式传 `revealText = true`,并且自己清楚代价。
     */
    fun dumpTree(maxDepth: Int = 40, revealText: Boolean = false): String {
        val root = rootInActiveWindow ?: return "(no active window; 服务没连上或当前无窗口)"
        val sb = StringBuilder()
        sb.append("pkg=").append(root.packageName)
        if (!revealText) sb.append("  (文本已脱敏,只显示长度)")
        sb.append('\n')
        dumpNode(root, 0, maxDepth, revealText, sb)
        return sb.toString()
    }

    private fun dumpNode(
        node: AccessibilityNodeInfo?, depth: Int, maxDepth: Int,
        revealText: Boolean, sb: StringBuilder
    ) {
        if (node == null || depth > maxDepth) return
        repeat(depth) { sb.append("  ") }
        sb.append(node.className?.toString()?.substringAfterLast('.') ?: "?")
        node.viewIdResourceName?.let { sb.append(" #").append(it.substringAfterLast('/')) }
        node.text?.takeIf { it.isNotBlank() }?.let {
            if (revealText) sb.append(" text=\"").append(it).append('"')
            else sb.append(" text[").append(it.length).append("字]")
        }
        // desc 通常是"搜索""返回"这类固定 UI 标签,是我们定位控件的主要依据,
        // 但也可能是"与XXX的对话",同样脱敏。
        node.contentDescription?.takeIf { it.isNotBlank() }?.let {
            if (revealText || it.length <= 6) sb.append(" desc=\"").append(it).append('"')
            else sb.append(" desc[").append(it.length).append("字]")
        }
        if (node.isClickable) sb.append(" [click]")
        if (node.isEditable) sb.append(" [edit]")
        sb.append('\n')
        for (i in 0 until node.childCount) dumpNode(node.getChild(i), depth + 1, maxDepth, revealText, sb)
    }

    // ------------------------------------------------------------ 通用节点操作

    /**
     * 轮询直到找到满足条件的节点,或超时。
     *
     * **不要用"等界面空闲"来代替它。** 早先的 waitIdle 是这么写的:
     *   `if (now - lastEventAt > quietMs) return true`
     * 点击之后 lastEventAt 还停在点击**之前**,于是"已经安静 400ms"当场成立,
     * 一秒都没等就返回了,拿到的还是旧页面的控件树 —— 现象是"没找到搜索输入框",
     * 而事后 dump 明明能看到那个 EditText。
     * 轮询目标节点没有这个歧义:找到才算数,找不到就是真没有。
     */
    private fun waitForNode(
        timeoutMs: Long = 6000,
        pred: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            findAll(rootInActiveWindow) { pred(it) }.firstOrNull()?.let { return it }
            Thread.sleep(120)
        }
        return null
    }

    /** 轮询直到条件**不再**成立(比如等旧页面消失),或超时。 */
    private fun waitUntilGone(timeoutMs: Long = 3000, pred: (AccessibilityNodeInfo) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (findAll(rootInActiveWindow) { pred(it) }.isEmpty()) return
            Thread.sleep(120)
        }
    }

    private fun findAll(
        root: AccessibilityNodeInfo?,
        limit: Int = 400,
        pred: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        root?.let { stack.addLast(it) }
        var seen = 0
        while (stack.isNotEmpty() && out.size < limit && seen < 5000) {
            val n = stack.removeLast()
            seen++
            if (pred(n)) out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        return out
    }

    /** 从节点自身往上找第一个可点击的祖先 —— 列表项的文字本身通常不可点。 */
    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var n = node
        var hops = 0
        while (n != null && hops < 8) {
            if (n.isClickable) return n
            n = n.parent
            hops++
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo?): Boolean {
        val target = clickableSelfOrAncestor(node) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * 汉字 → 拼音首字母:`文件传输助手` → `wjcszs`。
     *
     * 用系统自带的 ICU(minSdk 33,android.icu 一定在),不引第三方拼音库。
     *
     * 返回空串表示"没有可用的首字母写法,别用它搜":
     *  - 名字里压根没有汉字(纯英文/数字昵称)—— 那样取首字母只会得到一个字母,
     *    搜出来一大片,不如直接用原名。
     *  - ICU 不认识这个转换 ID(理论上不会,但拿不到就退回全名,别让整条链路断掉)。
     */
    private fun pinyinInitials(name: String): String {
        val hasHan = name.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        if (!hasHan) return ""
        val latin = runCatching { han2latin?.transliterate(name) }.getOrNull() ?: return ""
        // transliterate 出来是按音节用空格分开的:"wén jiàn chuán shū zhù shǒu"
        val sb = StringBuilder()
        for (syllable in latin.trim().split(Regex("\\s+"))) {
            val c = syllable.firstOrNull { it.isLetterOrDigit() } ?: continue
            sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    /**
     * 往输入框写文本。优先 ACTION_SET_TEXT;失败退回剪贴板粘贴 ——
     * 中文没法走 `input text`(只认 ASCII),微信的自定义输入框也不一定吃 SET_TEXT。
     */
    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            // 哪条路写进去的会影响微信有没有收到"文本变化"通知,进而影响发送键出不出来。
            // 出问题时这行日志能直接分清,省得猜。
            Log.i(TAG, "setText via ACTION_SET_TEXT")
            return true
        }

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("m", text))
        Thread.sleep(120)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "setText via clipboard PASTE, ok=$ok")
        return ok
    }

    /**
     * 屏幕上的确认。
     *
     * 动作类指令做完时,前台已经是被操作的那个应用(微信),小爱的对话界面
     * 连同我们塞进去的答案卡一起被盖掉了 —— 卡片在那种场景下**天然看不见**,
     * 不是渲染 bug。要给用户视觉反馈,只能用能浮在别的应用上面的东西。
     */
    private fun toast(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                Log.i(TAG, "toast failed: $t")
            }
        }
    }

    private fun findSearchButton(): AccessibilityNodeInfo? =
        findAll(rootInActiveWindow) {
            it.contentDescription?.toString() == "搜索" && it.isVisibleToUser
        }.firstOrNull()

    // ------------------------------------------------------------ 微信发消息

    /**
     * 给 [contact] 发 [text]。
     *
     * @param send true = 一路点到发送;false = 只把会话打开、正文填好,最后一下留给用户。
     *
     * 联系人匹配**要求精确**:搜索结果里必须有一条标题和 contact 完全相同。
     * 模糊匹配意味着可能把消息发给另一个人,这个错误没法撤回,宁可失败。
     */
    fun sendWeChat(contact: String, text: String, send: Boolean): String {
        // 等微信真正到前台。**不能只查一次就报错**:小爱的对话窗口会压在微信上面,
        // 刚 launch_app 完当前窗口还是 com.miui.voiceassist。真机日志里因此烧了 5 轮
        // 工具调用、23 秒 —— 模型反复 launch_app、最后自己用 run_shell 拼
        // `am start && sleep 3` 才绕过去。等它自己让出焦点就行。
        var root0 = rootInActiveWindow

        // 小爱的对话窗是**覆盖层**,这轮对话没结束它就一直压在微信上面。
        // 而对话没结束正是因为我们还在处理 —— 干等等不出来(实测干等 8 秒照样是它)。
        // 真机上是靠轮次之间的模型往返时间它才让开,那纯属运气。主动按 HOME 请它走,
        // 再把微信拉到前面。
        // 用 **BACK** 而不是 HOME:微信在这之前已经由 Tools 那边(小爱进程,有前台
        // 权限)拉起来了,就压在覆盖层底下,退一步就露出来。
        // HOME 试过,是错的 —— 它回的是桌面,而服务自己再 startActivity 会被
        // Android 的后台启动限制挡掉,结果停在 com.miui.home 干等 8 秒,
        // 整体从 18 秒退化到 29 秒。
        // **只按一次**。按完必须轮询等结果,不能睡个固定时长就重新判断:
        // 覆盖层消失要时间,400ms 后读到的往往还是旧状态,于是又补一下 BACK ——
        // 而那时微信已经露出来了,第二下就把微信退回了桌面(真机实测正是如此,
        // 结果停在 com.miui.home)。和 waitIdle 那个 bug 是同一个病根:
        // 用固定短延迟去判断一个需要时间的状态变化。
        if (root0?.packageName == VOICE_ASSIST) {
            Log.i(TAG, "assistant overlay on top, pressing BACK once")
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        root0 = rootInActiveWindow

        val deadline = SystemClock.uptimeMillis() + 8000
        while (root0?.packageName != WECHAT && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(200)
            root0 = rootInActiveWindow
        }
        if (root0 == null) return "error: 拿不到当前窗口，无障碍服务可能没连上"
        if (root0.packageName != WECHAT) {
            return "error: 等了 8 秒微信仍未到前台(当前 pkg=${root0.packageName})"
        }

        // 1. 进搜索。微信首页右上角那个放大镜 desc 是"搜索"。
        //
        // 关键:**不能假设微信停在首页**。启动微信只是把它 resume 到上次停留的页面 ——
        // 真机上就撞到过:上一轮把会话开着,再来一次时当前页是聊天窗口,里面没有
        // 搜索按钮,直接报"没找到搜索按钮"。所以找不到就按返回键往回退,
        // 每退一步重新找,直到回到首页。
        var searchBtn = findSearchButton()
        var back = 0
        while (searchBtn == null && back < 5) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            back++
            searchBtn = waitForNode(2000) {
                it.contentDescription?.toString() == "搜索" && it.isVisibleToUser
            }
        }
        if (searchBtn == null) {
            return "error: 退回 $back 步仍没找到搜索按钮，当前可能不在微信主界面"
        }
        if (back > 0) Log.i(TAG, "backed out $back screens to reach WeChat home")
        if (!click(searchBtn)) return "error: 点搜索按钮失败"

        // 2+3. 往搜索框输入 → 在结果里找**完全同名**的那条。
        //
        // 先试拼音首字母(文件传输助手 → wjcszs),搜不到再用全名兜底。
        // 为什么不直接换成首字母:多音字会算错(长 cháng/zhǎng、重 chóng/zhòng、
        // 曾 zēng/céng…),ICU 只会给一个读音,和微信的索引对不上就搜不到人。
        // 兜底那一步保证最差也就退回原来的行为。
        //
        // **匹配规则一个字没改**:仍然要求结果里有一条 text 和 contact 完全相同。
        // 搜索框里输什么只影响"能不能搜出来",点谁始终由全名精确匹配决定 ——
        // 发错人没法撤回,这条底线不能因为换了搜法就松。
        val queries = listOf(pinyinInitials(contact), contact)
            .filter { it.isNotBlank() }.distinct()
        Log.i(TAG, "searching \"$contact\" via ${queries.joinToString(" → ")}")

        var hit: AccessibilityNodeInfo? = null
        for ((i, q) in queries.withIndex()) {
            val searchInput = waitForNode { it.isEditable && it.isVisibleToUser }
                ?: return "error: 没找到搜索输入框"
            if (!setText(searchInput, q)) return "error: 无法写入搜索框"

            // 注意排除搜索框自己:输入的内容等于 contact 时(全名那次)它的 text
            // 也等于 contact,不排掉的话会"点中"输入框,永远进不了会话。
            //
            // 还要等的时间:最后一次给足 5 秒(和改动前一致);前面的尝试给 2.5 秒,
            // 失败了还有下一手,不值得每次都干等满。
            hit = waitForNode(if (i == queries.lastIndex) 5000 else 2500) {
                it.text?.toString() == contact && it.isVisibleToUser && !it.isEditable
            }
            if (hit != null) {
                Log.i(TAG, "matched \"$contact\" by query \"$q\"")
                break
            }
            Log.i(TAG, "query \"$q\" got no exact match for \"$contact\"")
        }
        if (hit == null) {
            // 只列**像联系人名**的短文本(≤16字)。搜索结果页同时也会显示聊天记录摘要,
            // 无差别列出来等于把聊天内容抄进日志/模型上下文。
            val seen = findAll(rootInActiveWindow) {
                val t = it.text?.toString()
                !t.isNullOrBlank() && t.length <= 16 && it.isVisibleToUser && !it.isEditable
            }.take(10).joinToString(" | ") { it.text.toString() }
            return "error: 搜索结果里没有与「$contact」完全同名的联系人。候选: $seen"
        }
        if (!click(hit)) return "error: 点开会话失败"

        // 4. 会话页的输入框。等搜索页那个"搜索"按钮消失,确认真的换页了,
        //    否则可能又抓到搜索框。
        waitUntilGone { it.contentDescription?.toString() == "搜索" && it.isVisibleToUser }
        val msgInput = waitForNode { it.isEditable && it.isVisibleToUser }
            ?: return "error: 会话页没找到输入框"
        if (!setText(msgInput, text)) return "error: 无法写入消息内容"

        if (!send) {
            return "已打开与「$contact」的会话，正文「$text」已填好，等你按发送。"
        }

        // 5. 发送键。输入框非空时微信才把「+」换成「发送」,这个切换要时间。
        //    给 3 秒不够 —— 一轮跑通(14 秒)时正文填完 3 秒内就来找,按钮还没换出来,
        //    报"没找到发送按钮"。之前几次能找到,是因为中间隔着好几轮模型往返、
        //    界面早稳了,那是慢出来的运气,不是逻辑对。
        val sendBtn = waitForNode(8000) {
            (it.text?.toString() == "发送" || it.contentDescription?.toString() == "发送") &&
                    it.isVisibleToUser && it.isEnabled
        } ?: return "error: 等 8 秒仍没出现发送按钮(正文已填好，可手动发送)"
        if (!click(sendBtn)) return "error: 点发送失败(正文已填好，可手动发送)"
        toast("已发给「$contact」：$text")
        return "已发送给「$contact」:$text"
    }
}
