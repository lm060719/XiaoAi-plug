package io.mo.xiaoaiplug.config

/**
 * 一条长期记忆:关于用户的、值得跨对话记住的事实。
 *
 * 跟 [ChatTurn] 的区别是**寿命**:对话上下文一小时就过期、进程死了就没,
 * 记忆则一直留着,直到用户自己删。
 */
data class MemoryEntry(
    val id: Long = 0L,
    /** 记下来的时刻。 */
    val time: Long,
    /** 记忆正文,一句话一条。写成"用户对花生过敏"这种自包含的陈述,别写"对,是的"。 */
    val content: String,
    /** 来源:模型在对话里自己记的,还是用户在设置页手填的。 */
    val source: String = SOURCE_AUTO
) {
    companion object {
        /** 模型通过 save_memory 工具主动录入。 */
        const val SOURCE_AUTO = "AUTO"

        /** 用户在「记忆」页手动添加或改过。 */
        const val SOURCE_MANUAL = "MANUAL"
    }
}
