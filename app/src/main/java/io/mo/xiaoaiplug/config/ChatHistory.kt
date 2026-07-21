package io.mo.xiaoaiplug.config

/** 一轮已经答完的问答。[time] 是答案落地的时刻,过期判断以它为准。 */
data class ChatTurn(val question: String, val answer: String, val time: Long)

/**
 * 对话上下文:让模型看得见前几轮说过什么,而不是每句话都从零开始。
 *
 * **只活在小爱进程的内存里**,不落盘。com.miui.voiceassist 被 MIUI 后台清理杀掉后
 * 历史就没了 —— 这是有意的取舍:进程被杀通常意味着隔了很久没用,那时一小时的窗口
 * 大概率本来也过期了,为此加一套跨进程读写(ConfigProvider / SQLite)不划算。
 *
 * 窗口是**滑动**的:每轮各自从自己的时间点起算一小时,所以聊得连贯就一直有上下文,
 * 停下来超过一小时再开口就是全新的一段对话。
 */
object ChatHistory {

    /** 单轮的存活时长。超过这个时间的旧问答不再发给模型。 */
    private const val WINDOW_MS = 60 * 60 * 1000L

    /** 最多带几轮。轮数直接乘进每次请求的 token 和首字延迟,不能不设上限。 */
    private const val MAX_TURNS = 5

    // 按时间先后排列,队首最旧。锁住整个对象:写在模型线程、读也在模型线程,
    // 但一次问话可能有多个 dialogId 并发进来,不锁会读到半截的队列。
    private val turns = ArrayDeque<ChatTurn>()

    /** 当前窗口内的历史,由旧到新。顺带把过期的清掉。 */
    fun recent(now: Long = System.currentTimeMillis()): List<ChatTurn> = synchronized(turns) {
        prune(now)
        turns.toList()
    }

    /** 记一轮。问或答为空就不记 —— 空消息发给模型只会污染上下文。 */
    fun record(question: String, answer: String, now: Long = System.currentTimeMillis()) {
        if (question.isBlank() || answer.isBlank()) return
        synchronized(turns) {
            turns.addLast(ChatTurn(question, answer, now))
            prune(now)
        }
    }

    /** 手动断开上下文(设置里关掉开关时调,免得再打开时接上一小时前的话茬)。 */
    fun clear() = synchronized(turns) { turns.clear() }

    // 读写两头都剪,保证队列既不超时也不超长。
    private fun prune(now: Long) {
        while (turns.isNotEmpty() && now - turns.first().time >= WINDOW_MS) turns.removeFirst()
        while (turns.size > MAX_TURNS) turns.removeFirst()
    }
}
