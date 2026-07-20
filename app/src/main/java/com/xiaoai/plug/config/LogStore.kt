package com.xiaoai.plug.config

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 运行记录的落盘存储。
 *
 * 只在**本模块自己的进程**里存在 —— 数据库文件在模块私有目录下,被 hook 的小爱进程
 * 根本访问不到,所以写入必须绕 [ConfigProvider] 过桥(见 [LogClient])。
 * 而「记录」页跟 ContentProvider 同进程(manifest 里没给 provider 单独的 android:process),
 * 读取直接走这个类就行,不用过 Binder —— 顺带绕开了 Binder 事务约 1MB 的上限。
 */
class LogStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "xiaoai_plug_logs.db"
        private const val DB_VERSION = 1
        private const val TABLE = "logs"

        /** 滚动上限:只保留最近这么多条。 */
        const val MAX_ROWS = 500

        /**
         * 单条 detail 的截断长度。工具输出上限是 Tools.MAX_TOOL_OUTPUT(6000),
         * 留点余量够放参数和返回。500 条封顶算下来数据库最大几 MB,可以接受。
         */
        private const val MAX_DETAIL = 8000

        @Volatile
        private var instance: LogStore? = null

        /**
         * 单例。同进程里 UI 和 ContentProvider 都要用,必须共用一个 helper —— 两个
         * SQLiteOpenHelper 实例指向同一个文件会各自持锁,写入偶发 SQLITE_BUSY。
         */
        fun get(context: Context): LogStore =
            instance ?: synchronized(this) {
                instance ?: LogStore(context).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                time INTEGER NOT NULL,
                type TEXT NOT NULL,
                title TEXT NOT NULL,
                detail TEXT NOT NULL,
                duration INTEGER NOT NULL,
                ok INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // 记录页默认按时间倒序翻页,没索引的话满 500 条时每次翻页都是全表扫
        db.execSQL("CREATE INDEX idx_${TABLE}_time ON $TABLE(time DESC)")
        db.execSQL("CREATE INDEX idx_${TABLE}_type ON $TABLE(type)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 记录是纯诊断数据,不值得为它写迁移。表结构变了直接重建。
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun append(entry: LogEntry) {
        val values = ContentValues().apply {
            put("time", entry.time)
            put("type", entry.type)
            put("title", entry.title.take(MAX_DETAIL))
            put("detail", entry.detail.take(MAX_DETAIL))
            put("duration", entry.durationMs)
            put("ok", if (entry.ok) 1 else 0)
        }
        val db = writableDatabase
        db.insert(TABLE, null, values)
        // id 是自增单调的,所以「保留最近 N 条」等价于按 id 砍掉尾巴 —— 走主键的范围删除,
        // 通常一行都删不到。比 `id NOT IN (SELECT ... ORDER BY id DESC LIMIT N)` 便宜得多,
        // 可以每次插入都跑,不用攒批。
        db.execSQL(
            "DELETE FROM $TABLE WHERE id <= (SELECT MAX(id) FROM $TABLE) - ?",
            arrayOf(MAX_ROWS)
        )
    }

    /**
     * 按时间倒序查询。
     *
     * @param type 为 null 时不限类型
     * @param keyword 在 title 和 detail 里模糊匹配,为空时不过滤
     */
    fun query(
        type: String? = null,
        keyword: String? = null,
        limit: Int = 100,
        offset: Int = 0
    ): List<LogEntry> {
        val where = StringBuilder()
        val args = ArrayList<String>()
        if (!type.isNullOrBlank()) {
            where.append("type = ?")
            args.add(type)
        }
        if (!keyword.isNullOrBlank()) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("(title LIKE ? OR detail LIKE ?)")
            val like = "%${keyword.replace("%", "\\%").replace("_", "\\_")}%"
            args.add(like)
            args.add(like)
        }

        val sql = buildString {
            append("SELECT id, time, type, title, detail, duration, ok FROM $TABLE")
            if (where.isNotEmpty()) append(" WHERE ").append(where)
            append(" ORDER BY id DESC LIMIT ? OFFSET ?")
        }
        args.add(limit.toString())
        args.add(offset.toString())

        val out = ArrayList<LogEntry>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(
                    LogEntry(
                        id = c.getLong(0),
                        time = c.getLong(1),
                        type = c.getString(2),
                        title = c.getString(3),
                        detail = c.getString(4),
                        durationMs = c.getLong(5),
                        ok = c.getInt(6) != 0
                    )
                )
            }
        }
        return out
    }

    /** 从 [sinceMillis] 起各类型的条数,用于主页的「今日统计」。 */
    fun countsSince(sinceMillis: Long): Map<String, Int> {
        val out = HashMap<String, Int>()
        readableDatabase.rawQuery(
            "SELECT type, COUNT(*) FROM $TABLE WHERE time >= ? GROUP BY type",
            arrayOf(sinceMillis.toString())
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
        }
        return out
    }

    /** 从 [sinceMillis] 起失败的条数(ok = 0)。 */
    fun failureCountSince(sinceMillis: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE time >= ? AND ok = 0",
            arrayOf(sinceMillis.toString())
        ).use { c -> if (c.moveToNext()) c.getInt(0) else 0 }

    /** 删掉单条(记录页左滑删除)。id 是主键，走索引,不用管表里有多少条。 */
    fun delete(id: Long) {
        writableDatabase.delete(TABLE, "id = ?", arrayOf(id.toString()))
    }

    fun clear() {
        writableDatabase.delete(TABLE, null, null)
    }
}
