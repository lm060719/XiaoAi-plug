package io.mo.xiaoaiplug.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import io.mo.xiaoaiplug.auto.AccessibilityGuard
import io.mo.xiaoaiplug.auto.UiAutoService

object ConfigKeys {
    // 服务商:openai / anthropic / xai / siliconflow(见 AiProvider)
    const val PROVIDER = "provider"
    const val ENDPOINT = "endpoint"
    const val API_KEY = "api_key"
    const val MODEL = "model"
    const val SYSTEM_PROMPT = "system_prompt"
    const val ENABLED = "enabled"

    // 「查看类」语音不跳转设置页 —— 独立开关 + 放行词白名单
    const val BLOCK_VIEW_JUMP = "block_view_jump"
    const val JUMP_ALLOW_WORDS = "jump_allow_words"

    // 小爱答不上来时的兜底(播报"只能帮你到这儿啦"+跳全局搜索)—— 独立开关 + 放行词
    const val BLOCK_WEB_SEARCH = "block_web_search"
    const val WEB_SEARCH_ALLOW_WORDS = "web_search_allow_words"

    // 用小爱自己的 TTS 把我们的答案念出来(否则它只出卡片、全程沉默)
    const val SPEAK_ANSWER = "speak_answer"

    // 允许模型调用的工具名,逗号分隔。空串 = 全开。
    const val ENABLED_TOOLS = "enabled_tools"
    // 用 OpenAI 原生 function calling(带 tools 参数)。端点不支持时会自动降级到文本约定。
    const val USE_NATIVE_TOOLS = "use_native_tools"

    // 多轮上下文:把最近一小时内的问答一起发给模型(见 ChatHistory)
    const val CONTEXT_ENABLED = "context_enabled"

    // 白名单直通:问话命中这条正则就完全不接管(不起泵、不调模型、不替换),原生行为照旧
    const val SKIP_TAKEOVER_ENABLED = "skip_takeover_enabled"
    const val SKIP_TAKEOVER_PATTERN = "skip_takeover_pattern"

    // 无障碍被 MIUI 摘掉后自动写回(见 AccessibilityGuard)。默认开。
    const val AUTO_FIX_ACCESSIBILITY = "auto_fix_accessibility"

    val ALL = listOf(
        PROVIDER, ENDPOINT, API_KEY, MODEL, SYSTEM_PROMPT, ENABLED,
        BLOCK_VIEW_JUMP, JUMP_ALLOW_WORDS,
        BLOCK_WEB_SEARCH, WEB_SEARCH_ALLOW_WORDS,
        SPEAK_ANSWER,
        ENABLED_TOOLS, USE_NATIVE_TOOLS, CONTEXT_ENABLED,
        SKIP_TAKEOVER_ENABLED, SKIP_TAKEOVER_PATTERN,
        AUTO_FIX_ACCESSIBILITY
    )
}

/**
 * 跨进程配置存取：MainActivity(本模块自己的进程)写配置，
 * Hook 注入到超级小爱进程后通过 ContentResolver.call() 读配置。
 */
class ConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "io.mo.xiaoaiplug.config"
        const val METHOD_GET = "get"
        const val METHOD_SET = "set"

        // 界面自动化:Hook 在小爱进程,无障碍服务在本模块进程,靠这两个方法过桥。
        // 侦察用,adb 也能直接调:
        //   adb shell content call --uri content://io.mo.xiaoaiplug.config --method ui_dump
        const val METHOD_UI_DUMP = "ui_dump"
        const val METHOD_SEND_MESSAGE = "send_message"

        // 运行记录:hook 在小爱进程里产生记录,数据库在本模块私有目录,同样得过桥。
        // 只有写入需要过桥 —— 「记录」页跟本 provider 同进程,读直接走 LogStore。
        const val METHOD_LOG_APPEND = "log_append"

        const val LOG_TIME = "log_time"
        const val LOG_TYPE = "log_type"
        const val LOG_TITLE = "log_title"
        const val LOG_DETAIL = "log_detail"
        const val LOG_DURATION = "log_duration"
        const val LOG_OK = "log_ok"

        // 长期记忆:同样是「数据库在模块进程、工具跑在小爱进程」,过桥。
        // 跟记录不同的是**读也要过桥** —— 每轮对话都要把已有记忆拼进系统提示词。
        const val METHOD_MEMORY_SAVE = "memory_save"
        const val METHOD_MEMORY_LIST = "memory_list"

        const val MEM_CONTENT = "mem_content"

        private const val PREFS_NAME = "xiaoai_plug_config"

        /**
         * 允许调用本 provider 的包名。
         *
         * 这个 provider 必须 exported —— hook 跑在超级小爱进程里,不导出就过不了桥。
         * 但它存着 **API Key**,现在还存着对话记录,不设防的话设备上任意一个 App 一条
         *   adb shell content call --uri content://io.mo.xiaoaiplug.config --method get
         * 就能全读走。callingPackage 由 Binder 在系统侧填,调用方伪造不了,拿来做白名单足够。
         */
        private val ALLOWED_CALLERS = setOf(
            "io.mo.xiaoaiplug",
            "com.miui.voiceassist"
        )

        /**
         * adb shell 的包名。侦察工作流(见上面 ui_dump 的注释)靠 `adb shell content call`
         * 直接调这个 provider,一刀切封掉会把调试手段一起砍了。所以放行 shell,
         * 但**只放行不涉及密钥的方法** —— get/set 会吐出 API Key,shell 拿不到。
         */
        private const val SHELL_PACKAGE = "com.android.shell"

        private val SHELL_ALLOWED_METHODS = setOf(
            METHOD_UI_DUMP, METHOD_SEND_MESSAGE, METHOD_LOG_APPEND
        )
    }

    private fun prefs(): SharedPreferences =
        context!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun onCreate(): Boolean = true

    /**
     * 自动恢复无障碍的开关。
     *
     * 直接读自己的 SharedPreferences,不走 [ConfigClient] —— 那条路会绕一圈
     * ContentResolver 再回到本 provider,而我们**已经在** provider 里了。
     *
     * **默认关**,和这里其它「空即开」的字段不是一个规矩:它要动系统设置、要 root,
     * 得等用户在设置页明确打开(那时会先验一次 root)才生效。
     */
    private fun autoFixEnabled(): Boolean =
        prefs().getString(ConfigKeys.AUTO_FIX_ACCESSIBILITY, "") == "true"

    /** 调用方是否有权调用 [method]。callingPackage 由系统填,伪造不了。 */
    private fun isCallerAllowed(method: String): Boolean {
        val caller = callingPackage ?: return false
        if (caller in ALLOWED_CALLERS) return true
        return caller == SHELL_PACKAGE && method in SHELL_ALLOWED_METHODS
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!isCallerAllowed(method)) return null
        return when (method) {
            METHOD_GET -> {
                val p = prefs()
                val out = Bundle()
                for (k in ConfigKeys.ALL) {
                    // getString 的第二个参数只在"这个 key 从没存过"时才生效,用户主动存成
                    // 空串之后就再也不会命中它 —— 靠这个区分"从没配置过"(给默认值)
                    // 和"用户手动清空"(就是要它不生效),两者在存进 SharedPreferences 后都是空串,
                    // 只有靠"存过没存过"才分得开。
                    val fallback = if (k == ConfigKeys.SKIP_TAKEOVER_PATTERN) DEFAULT_SKIP_TAKEOVER_PATTERN else ""
                    out.putString(k, p.getString(k, fallback))
                }
                out
            }
            METHOD_SET -> {
                val e = prefs().edit()
                if (extras != null) {
                    for (k in ConfigKeys.ALL) {
                        if (extras.containsKey(k)) {
                            e.putString(k, extras.getString(k, ""))
                        }
                    }
                }
                e.apply()
                Bundle().apply { putBoolean("ok", true) }
            }
            METHOD_UI_DUMP -> {
                // 先自愈。MIUI 清后台会把无障碍权限摘掉,详见 AccessibilityGuard ——
                // 不补这一下,用户每清一次后台就得手动去设置页开一次。
                val failure = AccessibilityGuard.ensureRunning(context!!, autoFixEnabled())
                Bundle().apply {
                    putString("result", failure ?: UiAutoService.instance?.dumpTree()
                        ?: "error: 无障碍服务刚连上又断了")
                }
            }
            METHOD_SEND_MESSAGE -> {
                val failure = AccessibilityGuard.ensureRunning(context!!, autoFixEnabled())
                val svc = UiAutoService.instance
                val contact = extras?.getString("contact").orEmpty()
                val text = extras?.getString("text").orEmpty()
                // send=false 时只把会话开好、正文填好,最后一下留给用户
                val send = extras?.getString("send") != "false"
                Bundle().apply {
                    putString(
                        "result",
                        when {
                            failure != null -> failure
                            svc == null -> "error: 无障碍服务刚连上又断了"
                            contact.isBlank() || text.isBlank() -> "error: 联系人或内容为空"
                            else -> try {
                                svc.sendWeChat(contact, text, send)
                            } catch (t: Throwable) {
                                "error: ${t.javaClass.simpleName}: ${t.message}"
                            }
                        }
                    )
                }
            }
            METHOD_LOG_APPEND -> {
                if (extras == null) return Bundle().apply { putBoolean("ok", false) }
                // 调用方(LogClient)已经在自己那边的后台线程上了,这里直接写。
                try {
                    LogStore.get(context!!).append(
                        LogEntry(
                            time = extras.getLong(LOG_TIME, System.currentTimeMillis()),
                            type = extras.getString(LOG_TYPE).orEmpty(),
                            title = extras.getString(LOG_TITLE).orEmpty(),
                            detail = extras.getString(LOG_DETAIL).orEmpty(),
                            durationMs = extras.getLong(LOG_DURATION, -1L),
                            ok = extras.getBoolean(LOG_OK, true)
                        )
                    )
                    Bundle().apply { putBoolean("ok", true) }
                } catch (t: Throwable) {
                    // 记日志失败绝不能反过来把调用方搞挂
                    Bundle().apply { putBoolean("ok", false) }
                }
            }
            METHOD_MEMORY_SAVE -> {
                // 跟 log_append 的 fire-and-forget 不一样:这是工具调用,模型在等一个
                // 明确的成功/失败回执,所以同步做完再返回。调用方(MemoryClient)已经
                // 在 AiClient 的后台线程上了。
                val content = extras?.getString(MEM_CONTENT).orEmpty()
                Bundle().apply {
                    putString(
                        "result",
                        try {
                            MemoryStore.get(context!!)
                                .append(content, MemoryEntry.SOURCE_AUTO)
                        } catch (t: Throwable) {
                            "error: ${t.javaClass.simpleName}: ${t.message}"
                        }
                    )
                }
            }
            METHOD_MEMORY_LIST -> {
                // 每轮对话都会调一次(拼系统提示词)。条数有 MemoryStore.MAX_ROWS 兜着,
                // 离 Binder 那 1MB 上限还很远。
                Bundle().apply {
                    putStringArrayList(
                        MEM_CONTENT,
                        try {
                            ArrayList(MemoryStore.get(context!!).all().map { it.content })
                        } catch (t: Throwable) {
                            ArrayList()
                        }
                    )
                }
            }
            else -> null
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
