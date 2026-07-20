package com.xiaoai.plug.ui

import android.app.Application
import android.content.ComponentName
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoai.plug.ModuleStatus
import com.xiaoai.plug.auto.UiAutoService
import com.xiaoai.plug.config.AiClient
import com.xiaoai.plug.config.AiConfig
import com.xiaoai.plug.config.ConfigRepository
import com.xiaoai.plug.config.LogEntry
import com.xiaoai.plug.config.LogStore
import com.xiaoai.plug.config.Tools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/** 「测试连接」的状态机。 */
sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val reply: String) : TestState
    data class Failed(val message: String) : TestState
}

/** 主页要显示的运行状况。 */
data class StatusSnapshot(
    val moduleActive: Boolean = false,
    val accessibilityOn: Boolean = false,
    val todayChats: Int = 0,
    val todayTools: Int = 0,
    val todayFailures: Int = 0
)

class ConfigViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ConfigRepository.get(app)

    val config: StateFlow<AiConfig> = repo.config
    val reachable: StateFlow<Boolean> = repo.reachable

    private val _status = MutableStateFlow(StatusSnapshot())
    val status: StateFlow<StatusSnapshot> = _status.asStateFlow()

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    init {
        refreshStatus()
    }

    fun update(transform: (AiConfig) -> AiConfig) = repo.update(transform)

    fun refreshStatus() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val store = LogStore.get(getApplication())
                val since = startOfToday()
                val counts = store.countsSince(since)
                StatusSnapshot(
                    // isActive() 常量返回 false，被 hook 改写后才是 true —— 见 ModuleStatus
                    moduleActive = ModuleStatus.isActive(),
                    accessibilityOn = isAccessibilityEnabled(),
                    todayChats = counts[LogEntry.TYPE_CHAT] ?: 0,
                    todayTools = counts[LogEntry.TYPE_TOOL] ?: 0,
                    todayFailures = store.failureCountSince(since)
                )
            }
            _status.value = snapshot
        }
    }

    /**
     * 拿当前配置真发一次请求。
     *
     * 复用 [AiClient.chat]，但**把工具全关掉**（`Tools.NONE`）：测试只想知道端点、
     * 密钥、模型名对不对，不该在这里真去执行 launch_app 之类会动设备的东西。
     * `ctx` 传 null，顺带让 LogClient 直接跳过，测试不会污染记录页。
     */
    fun testConnection() {
        if (_testState.value == TestState.Running) return
        viewModelScope.launch {
            _testState.value = TestState.Running
            val cfg = config.value
            if (!cfg.isUsable) {
                _testState.value = TestState.Failed("请先填写 API Key")
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AiClient.chat(
                        config = cfg.copy(enabledTools = Tools.NONE, useNativeTools = false),
                        userText = "回复两个字：收到",
                        ctx = null
                    )
                }
            }
            _testState.value = result.fold(
                onSuccess = { TestState.Ok(it.trim().take(200)) },
                onFailure = { TestState.Failed("${it.javaClass.simpleName}: ${it.message}") }
            )
        }
    }

    fun clearTestState() {
        _testState.value = TestState.Idle
    }

    /**
     * 查系统设置里无障碍服务的开启状态。
     *
     * 比 `UiAutoService.instance != null` 可靠：那个静态引用只有在服务真被系统拉起来
     * 之后才有值，用户刚在设置里打开、服务还没连上的窗口期会误报成未开启。
     */
    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(getApplication(), UiAutoService::class.java)
        val enabled = Settings.Secure.getString(
            getApplication<Application>().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == expected
        }
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
