package io.mo.xiaoaiplug.config

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 长期记忆的落盘存储。
 *
 * 跟 [LogStore] 一样只在**本模块自己的进程**里存在,数据库在模块私有目录下,
 * 被 hook 的小爱进程访问不到 —— 所以工具侧的读写都得绕 [ConfigProvider] 过桥
 * (见 [MemoryClient]),而「记忆」页跟 provider 同进程,直接用这个类。
 *
 * **故意跟运行记录分库。** LogStore 的 onUpgrade 是 DROP TABLE 重建 —— 那对纯诊断
 * 数据没问题,但记忆是用户资产,不能因为一次版本升级就被抹掉。
 */
class MemoryStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "xiaoai_plug_memory.db"
        private const val DB_VERSION = 1
        private const val TABLE = "memories"

        /** 单条记忆的长度上限。一条记忆是一句事实,超过这个长度基本是模型在写作文。 */
        const val MAX_CONTENT = 500

        /**
         * 条数上限。到顶后 [append] 直接拒绝而**不是**淘汰旧的 ——
         * 记忆是用户资产,悄悄丢掉比明说存不下更糟。
         */
        const val MAX_ROWS = 200

        @Volatile
        private var instance: MemoryStore? = null

        /** 单例。理由同 [LogStore.get]:同进程里 UI 和 provider 共用一个 helper,免得抢锁。 */
        fun get(context: Context): MemoryStore =
            instance ?: synchronized(this) {
                instance ?: MemoryStore(context).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                time INTEGER NOT NULL,
                content TEXT NOT NULL,
                source TEXT NOT NULL
            )
            """.trimIndent()
        )
        // 记忆页按时间倒序列出,注入系统提示词时也按时间取
        db.execSQL("CREATE INDEX idx_${TABLE}_time ON $TABLE(time DESC)")
        // 查重(见 append)靠这个索引,不然每次录入都是全表扫
        db.execSQL("CREATE UNIQUE INDEX idx_${TABLE}_content ON $TABLE(content)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 这里**永远不要**照抄 LogStore 的 DROP TABLE —— 那会把用户攒的记忆全删了。
        // 以后加字段就老老实实 ALTER TABLE。
    }

    /**
     * 记一条。
     *
     * @return 人话结果字符串,直接可以当工具返回值给模型看;失败以 "error:" 开头
     *   (这是 [Tools] 约定的失败标记)。
     */
    fun append(content: String, source: String = MemoryEntry.SOURCE_AUTO): String {
        val text = content.trim().take(MAX_CONTENT)
        if (text.isEmpty()) return "error: 记忆内容为空"

        val db = writableDatabase
        // 同一件事模型很可能反复记(用户每次提到都触发一次)。内容完全相同的直接当成功
        // 返回,别让模型以为失败了去重试 —— 靠的是 content 上的 UNIQUE 索引。
        val values = ContentValues().apply {
            put("time", System.currentTimeMillis())
            put("content", text)
            put("source", source)
        }
        val existing = db.query(
            TABLE, arrayOf("id"), "content = ?", arrayOf(text), null, null, null, "1"
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
        if (existing != null) return "已经记住过了：$text"

        if (count() >= MAX_ROWS) {
            return "error: 记忆已满（上限 $MAX_ROWS 条），请先在设置-记忆里删掉一些"
        }
        val id = db.insert(TABLE, null, values)
        return if (id > 0) "已记住：$text" else "error: 写入失败"
    }

    /** 改一条的正文。用户手改过的一律标成 MANUAL,免得界面上还显示"自动记录"。 */
    fun update(id: Long, content: String) {
        val text = content.trim().take(MAX_CONTENT)
        if (text.isEmpty()) return
        val values = ContentValues().apply {
            put("content", text)
            put("source", MemoryEntry.SOURCE_MANUAL)
        }
        writableDatabase.update(TABLE, values, "id = ?", arrayOf(id.toString()))
    }

    /** 全部记忆,新的在前。条数有 [MAX_ROWS] 兜着,不分页。 */
    fun all(): List<MemoryEntry> {
        val out = ArrayList<MemoryEntry>()
        readableDatabase.rawQuery(
            "SELECT id, time, content, source FROM $TABLE ORDER BY id DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    MemoryEntry(
                        id = c.getLong(0),
                        time = c.getLong(1),
                        content = c.getString(2),
                        source = c.getString(3)
                    )
                )
            }
        }
        return out
    }

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun delete(id: Long) {
        writableDatabase.delete(TABLE, "id = ?", arrayOf(id.toString()))
    }

    fun clear() {
        writableDatabase.delete(TABLE, null, null)
    }
}
