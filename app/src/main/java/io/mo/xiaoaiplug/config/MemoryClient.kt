package io.mo.xiaoaiplug.config

import android.content.Context
import android.net.Uri
import android.os.Bundle

/**
 * Hook 侧(跑在 com.miui.voiceassist 进程里)的记忆读写端。
 *
 * 跟 [LogClient] 的 fire-and-forget 相反,这里两个方法都是**同步**的:
 * 存记忆是工具调用,模型在等回执;读记忆要拼进这一轮的系统提示词,更等不得。
 * 所以调用方必须已经在后台线程上 —— AiClient 和 Tools.execute 都满足。
 */
object MemoryClient {

    private val uri: Uri = Uri.parse("content://${ConfigProvider.AUTHORITY}")

    /** @return 给模型看的结果字符串,失败以 "error:" 开头(Tools 的约定)。 */
    fun save(context: Context?, content: String): String {
        val app = context?.applicationContext ?: context ?: return "error: no context available"
        return try {
            val out = app.contentResolver.call(
                uri,
                ConfigProvider.METHOD_MEMORY_SAVE,
                null,
                Bundle().apply { putString(ConfigProvider.MEM_CONTENT, content) }
            )
            out?.getString("result") ?: "error: 模块进程无响应"
        } catch (t: Throwable) {
            "error: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** 全部记忆正文,新的在前。读不到就返回空表 —— 记忆读失败不该把整轮对话搞挂。 */
    fun list(context: Context?): List<String> {
        val app = context?.applicationContext ?: context ?: return emptyList()
        return try {
            val out = app.contentResolver.call(uri, ConfigProvider.METHOD_MEMORY_LIST, null, null)
            out?.getStringArrayList(ConfigProvider.MEM_CONTENT).orEmpty()
        } catch (t: Throwable) {
            emptyList()
        }
    }
}
