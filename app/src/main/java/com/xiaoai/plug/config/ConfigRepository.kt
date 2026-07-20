package com.xiaoai.plug.config

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 界面侧的配置读写。
 *
 * 包住已有的 [ConfigClient],不改它的跨进程协议 —— hook 侧还在直接用 [ConfigClient.read]。
 * 这里解决的是界面自己的两个老毛病:
 *  1. `contentResolver.call` 原先在主线程同步跑(onCreate 里一次、保存按钮里一次)
 *  2. 读失败被吞成一份空配置,provider 挂了看起来跟全新安装一模一样
 */
class ConfigRepository private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: ConfigRepository? = null

        fun get(context: Context): ConfigRepository =
            instance ?: synchronized(this) {
                instance ?: ConfigRepository(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 串行化读改写,避免两个开关同时被拨动时后写的那次覆盖掉前一次。 */
    private val writeLock = Mutex()

    private val _config = MutableStateFlow(defaultConfig())
    val config: StateFlow<AiConfig> = _config.asStateFlow()

    /** provider 是否可达。false 时界面要明确报错,而不是显示一份看似正常的空配置。 */
    private val _reachable = MutableStateFlow(true)
    val reachable: StateFlow<Boolean> = _reachable.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val read = ConfigClient.readOrNull(appContext)
        if (read == null) {
            _reachable.value = false
        } else {
            _reachable.value = true
            _config.value = read
        }
        _loaded.value = true
    }

    /**
     * 改一项配置并立刻落盘(界面是改动即存,没有保存按钮)。
     *
     * 先更新内存里的 StateFlow 再写盘 —— 开关的手感不该等一次跨进程调用。
     * 写失败就回滚,并把 [reachable] 置 false 让界面能报出来。
     */
    fun update(transform: (AiConfig) -> AiConfig) {
        scope.launch {
            writeLock.withLock {
                val before = _config.value
                val after = transform(before)
                if (after == before) return@withLock
                _config.value = after
                val ok = ConfigClient.write(appContext, after)
                _reachable.value = ok
                if (!ok) _config.value = before
            }
        }
    }

    /**
     * 默认配置。跟 [ConfigClient.read] 在拿不到任何数据时的结果保持一致 ——
     * 尤其是几个「空 = 开」的字段,不能在这里写成 false,否则界面第一帧会把
     * 拦截开关显示成关闭的,用户以为自己没开。
     */
    private fun defaultConfig() = AiConfig(
        provider = "",
        endpoint = "",
        apiKey = "",
        model = "",
        systemPrompt = "",
        enabled = false,
        blockViewJump = true,
        jumpAllowWords = DEFAULT_JUMP_ALLOW_WORDS,
        blockWebSearch = true,
        webSearchAllowWords = DEFAULT_WEB_SEARCH_ALLOW_WORDS,
        speakAnswer = true,
        enabledTools = "",
        useNativeTools = true
    )
}
