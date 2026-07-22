package io.mo.xiaoaiplug.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.mo.xiaoaiplug.ModuleStatus
import io.mo.xiaoaiplug.auto.AccessibilityGuard
import io.mo.xiaoaiplug.config.AiClient
import io.mo.xiaoaiplug.config.AiConfig
import io.mo.xiaoaiplug.config.ConfigRepository
import io.mo.xiaoaiplug.config.LogEntry
import io.mo.xiaoaiplug.config.LogStore
import io.mo.xiaoaiplug.config.Tools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

/** 底部提示条停留多久（不含淡出动画的时长）。 */
private const val TOAST_DURATION_MS = 2000L

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

    /** 底栏上方那条一闪而过的提示。null = 不显示。 */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var toastJob: Job? = null

    /**
     * 弹一条提示，[TOAST_DURATION_MS] 后自己收回（收回时那边会播淡出动画）。
     *
     * 每次都把上一个计时器取消掉：连着弹两条时，不取消的话第一条的计时器会在
     * 第二条刚出来 0.几秒时把它一起清掉。
     */
    private fun showToast(message: String) {
        toastJob?.cancel()
        _toast.value = message
        toastJob = viewModelScope.launch {
            delay(TOAST_DURATION_MS)
            _toast.value = null
        }
    }

    /**
     * 验 root 期间的防重入标记。
     *
     * **故意不是 StateFlow、也不暴露给界面。** 之前它驱动着开关的 enabled 和 summary，
     * 结果没 root 时 su 几毫秒就失败，summary 在两行和一行之间闪一个来回，
     * 卡片高度跟着缩一下再弹回来 —— 整页看着"闪一下"。检查状态不值得用布局变化去表达。
     *
     * **必须同步置位**，不能挪进 launch 里：协程启动有延迟，连点时好几次都会先过掉
     * 这道检查，再各自 fork 一个 su。
     */
    private var autoFixChecking = false

    init {
        refreshStatus()
        autoRepairOnLaunch()
    }

    /**
     * 打开应用时把无障碍修回来。
     *
     * **为什么光有 ConfigProvider 那道自愈不够:** 那道只在小爱真的调 ui_dump /
     * send_message 时才触发。用户清了后台、点开应用一看还是红的 —— 因为在他
     * 说下一句话之前，压根没有任何东西调过 provider。他只能手动点「去开启」，
     * 那自动恢复就名不副实了。
     */
    private fun autoRepairOnLaunch() {
        viewModelScope.launch {
            // 必须等配置真读上来。加载完成前 repo.config 是一份默认值，而
            // autoFixAccessibility 默认为 false —— 不等就会把「已经打开了」的用户
            // 当成没开，启动时这次自愈直接白跳过。
            repo.loaded.first { it }
            if (!config.value.autoFixAccessibility) return@launch
            withContext(Dispatchers.IO) { AccessibilityGuard.ensureRunning(getApplication()) }
            refreshStatus()
        }
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
     * 拨动「自动恢复无障碍」。
     *
     * 打开之前先验一次「能不能写系统设置」——**验不过就不让开**，而不是开了之后
     * 每次要用时才静悄悄失败一遍。这个开关承诺的是「以后不用管了」，开得成却不
     * 干活比一开始就开不了更糟。
     *
     * 关掉不用验：收回权限不需要权限。
     */
    fun setAutoFixAccessibility(on: Boolean) {
        if (!on) {
            update { it.copy(autoFixAccessibility = false) }
            return
        }
        if (autoFixChecking) return
        autoFixChecking = true
        viewModelScope.launch {
            try {
                // fork su，还可能停在 KernelSU 的授权弹窗上等用户点，绝不能在主线程做。
                val ok = withContext(Dispatchers.IO) {
                    AccessibilityGuard.canSelfHeal(getApplication())
                }
                if (!ok) {
                    showToast("没有 root 权限，无法开启")
                    return@launch
                }
                update { it.copy(autoFixAccessibility = true) }
                // 刚开启就顺手修一次，省得用户还要再去主页点一下「去开启」。
                // 不复用 autoRepairOnLaunch()：update{} 是异步落盘的，那边读
                // config.value 会读到还没更新的旧值，白跑一趟。
                withContext(Dispatchers.IO) {
                    AccessibilityGuard.ensureRunning(getApplication())
                }
                refreshStatus()
            } finally {
                // 走 finally：中间任何一步抛了异常，标记也得放开，
                // 否则开关从此再也点不动，而界面上完全看不出为什么。
                autoFixChecking = false
            }
        }
    }

    /**
     * 「去开启」：先自己修，修不好才把用户送去系统设置页。
     *
     * 有 root 或 WRITE_SECURE_SETTINGS 时用户根本不用离开这个页面，
     * 点一下红点就变绿了。[onNeedsManual] 是最后的退路。
     */
    fun repairAccessibility(onNeedsManual: () -> Unit) {
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                AccessibilityGuard.ensureRunning(getApplication())
            }
            refreshStatus()
            if (failure != null) onNeedsManual()
        }
    }

    /**
     * 查系统设置里无障碍服务的开启状态。
     *
     * 比 `UiAutoService.instance != null` 可靠：那个静态引用只有在服务真被系统拉起来
     * 之后才有值，用户刚在设置里打开、服务还没连上的窗口期会误报成未开启。
     */
    private fun isAccessibilityEnabled(): Boolean =
        AccessibilityGuard.isEnabledInSettings(getApplication())

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
