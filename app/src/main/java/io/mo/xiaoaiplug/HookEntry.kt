package io.mo.xiaoaiplug

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.mo.xiaoaiplug.config.AiClient
import io.mo.xiaoaiplug.config.AiConfig
import io.mo.xiaoaiplug.config.ChatHistory
import io.mo.xiaoaiplug.config.ConfigClient
import io.mo.xiaoaiplug.hook.SettingsHook
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "XiaoAiProbe"
private const val TARGET_PKG = "com.miui.voiceassist"

/** 本模块自己的包名。作用域里勾上自己,就能靠 hook 自身来检测「模块是否已激活」。 */
private const val SELF_PKG = "io.mo.xiaoaiplug"

/** 系统设置。只为了在「小米澎湃 AI」页里挂一个入口,跟语音那条链路无关。 */
private const val SETTINGS_PKG = "com.android.settings"

private const val OPERATION_MANAGER_CLASS = "com.xiaomi.voiceassistant.instruction.base.OperationManager"
private const val RN_CARD_CLASS = "com.xiaomi.voiceassistant.instruction.card.TemplateReactNativeCard"
private const val BRIDGE_CLASS = "r70.a"
private const val AUDIO_TRACK_MANAGER_CLASS = "v20.e"

// 我们自己的答案播在这条音轨上(n1.speakTts 用的),静音时必须跳过它。
// 其余的(main / tts / getAuthorization / getNoAccountAuthorization)都是小爱在说话。
private const val OUR_AUDIO_TRACK = "toastStreamTts"

// 卡片自己的 TTS 播放器(ToastStreamPlayer),喇叭按钮点下去走的就是它:
//   n1.f95882a.speakTts(String) -> speakDialogId
// 优先用它而不是 u1.speak:我们的答案是**延迟几秒**才到的,那时小爱的会话常常已经拆了,
// 而 u1.speak(String) 内部有 `if (this.f56104f != null)` 守卫(f56104f 是 y00.h 引擎),
// 引擎为 null 时它静默丢弃、一声不吭。n1 设计上就是给"会话早结束、用户回头点重播"用的。
private const val TOAST_STREAM_PLAYER_CLASS = "la0.n1"

// 小爱自己的 TTS 入口:u1.getInstance().speak(String) 内部会造 t20.e(SelectedVendorTtsRequest,
// 构造时 updateSettings() 自动带上当前音色/音源设置)交给 y00.h → f10.j1 合成播报。
// 这是小爱念本地答案(如"当前电量 80%")走的路,音色/打断/音频焦点都是原生行为。
// 这里只作 n1 失败时的兜底。
private const val TTS_BRIDGE_CLASS = "com.xiaomi.voiceassistant.u1"

// ASR 指令处理器。小爱把语音识别结果(SpeechRecognizer.RecognizeResult)交给它,
// 终态结果(isFinal=true)带着 dialogId 和问话原文。
// 为什么要它:真机日志显示"跳设置"那条 Agent Action 在 setQueryInfo **之前** 334ms 就发出去了,
// 那时 lastQueryText 还停在上一句,按问话判定必然失效。而终态 ASR 比 Agent Action 早 266ms、
// 比 setQueryInfo 早 600ms,在这里记问话才来得及拦。
private const val ASR_PROCESSOR_CLASS = "z10.a"
private const val ASR_RECOGNIZE_RESULT = "SpeechRecognizer.RecognizeResult"

// AgentActionManager —— 「查看X→跳设置」的**第三条**路,和另外两条完全无关。
// 小爱不在自己进程里构造 Intent,而是把一条 AIoT action spec
//   urn:aiot-spec-v3:com.mi.phones:action:[com.miui.securitycenter/powercenter/go_power_model_setting]:0:1.0
// 经 binder 交给 AiCr 引擎,最终由 com.miui.securitycenter 自己 startActivity 打开自己的页面。
// 所以 IntentUtilsWrapper / m2 那两个 hook 结构上就抓不到它,只能在小爱侧这个收口拦。
private const val AGENT_ACTION_CLASS = "kh0.s0"

// TemplateToastOperation —— 小爱那些固定话术卡片的生成处(「应用已经不支持这个功能啦」之类)。
// 卡片和语音是两条独立的路:语音被静音泵掐掉了,但卡片照样显示 —— 所以还得单独拦这里。
// g0/i0 都返回 com.xiaomi.voiceassistant.card.a,且它们自己就有 return null 的分支
// (isScenesOp / isSimulatingClick),说明返回 null 是小爱支持的正常结果,拦起来安全。
private const val TOAST_OPERATION_CLASS = "jb0.vd"

// SpeakContentManager —— 小爱"这一轮该念什么"的权威文本源。
// jb0.hb 把 SpeakStream 分片 addFragment() 累积进去;卡片上那个喇叭按钮
// (TTSPlayView.k())在 dialogId 匹配时**优先读 b2.getText() 而不是卡片文本**:
//     if (dialogId == b2.getCurDialogId()) str = b2.getText() else str = card.getTotalText()
// 所以只改卡片文字不改这里的话,点喇叭放出来的还是小爱的原始答案 —— 声音和字对不上。
private const val SPEAK_CONTENT_CLASS = "com.xiaomi.voiceassistant.instruction.utils.b2"

// 念出来的答案上限。模型答案可能很长(还可能夹着工具输出),整段念完既吵又没法打断。
// 超出部分只截断不摘要 —— 卡片上是全文,想看细节看屏幕。
private const val MAX_SPEAK_CHARS = 220

// 同一句问话在这个时间窗内出现的多个 dialogId,算作同一次交互
private const val UTTERANCE_WINDOW_MS = 15_000L

// 「查看类」不跳转:所有跳转系统设置页的语音指令都经过这个 wrapper
// (现场 trace 确认:startActivitySafely(Intent, boolean),上游是混淆 Operation jb0.g5.Q0)
private const val INTENT_UTILS_WRAPPER_CLASS = "com.xiaomi.voiceassistant.instruction.utils.IntentUtilsWrapper"

// 小爱答不上来时的兜底(播报"只能帮你到这儿啦"+跳全局搜索)走的是另一条路:
//   jb0.g5.v1 → N0 → N1 → IntentUtilsWrapper.sendIntent → m2.sendIntent → m2.h → m2.o
//                                                       → m2.startActivitySafely(Intent, String)
// 注意它经过的是 IntentUtilsWrapper.**sendIntent** 而不是 startActivitySafely,
// 所以上面那个 wrapper hook 一次都拦不到(现场 trace 确认)。m2.startActivitySafely 是更靠上的
// 统一收口 —— 跳设置页那条路最后也汇到它,拦这一处两类跳转都覆盖。
private const val INTENT_UTILS_CLASS = "com.xiaomi.voiceassistant.utils.m2"

// 兜底跳转的目标:小米全局搜索(act=android.intent.action.SEARCH, dat=qsb://query/...)
private const val QUICK_SEARCH_PKG = "com.android.quicksearchbox"

// App 内「历史对话」是另一份本地持久化,和卡片渲染完全独立:
// 库 /data/data/com.miui.voiceassist/databases/VoiceAssistant.db 表 CHAT_MESSAGE_BEAN,
// 小爱要说的每句话都经 ChatDbManager.recordToSpeak(String) 落库(isSend=0)。
// 只改卡片不改这里的话,回 App 看历史依旧是"小爱只能帮你到这啦"。
private const val CHAT_DB_MANAGER_CLASS = "com.xiaomi.voiceassistant.skills.model.chat.a"

// 兜底话术的特征词。实测这句话分三批落库:拦截**之前**就写了两行、我们写完答案之后
// 又补写了十来行,所以只按"拦截时刻"开时间窗是挡不住的,得按内容认。
private val FALLBACK_MARKERS = listOf("只能帮你到这", "请手动操作")

// 当前 HyperOS 流式结果里的原生文本答案卡(查看类查询小爱会为它建这张卡)
private const val FLOW_TOAST_CARD_CLASS = "com.xiaomi.voiceassistant.instruction.card.stream.FlowTemplateToastCard"

// 两套结果渲染 sink,都用 addCard(card.a) 把卡片接进各自会渲染的列表:
//  - App 内对话用 flowableresult.d;  - 电源键悬浮窗(FloatBoard)用 widget.d(FloatManager)。
// 谁最近被调用谁就是当前活跃 sink。
private const val FLOW_CONTROLLER_CLASS = "com.xiaomi.voiceassistant.mainui.flowableresult.d"
private const val FLOAT_MANAGER_CLASS = "com.xiaomi.voiceassistant.widget.d"

// 「查看类」触发词:命中其一 + 未命中放行词 + 跳转目标是系统设置页 → 拦掉跳转
private val VIEW_WORDS = listOf(
    "查看", "查询", "看看", "看下", "看一下", "查一下", "是多少", "多少", "怎么样", "什么情况", "是什么"
)

// 「发消息类」指令。这类**必须由我们接管**,理由和查看类不同:
// 小爱原生是支持语音发微信的,但这台机器装了微信双开,它只会回
// "暂不支持微信双开功能";打字输入时还会回"仅支持语音对话方式"。
// 两条都是死路,而我们有无障碍服务能真的发出去。
//
// 注意这类话必然命中放行词("打开微信…"里的"打开"),所以走不了
// isViewBlockCandidate,得单独判。
private val SEND_MESSAGE_APPS = listOf("微信", "wechat")
private val SEND_MESSAGE_VERBS = listOf("发消息", "发信息", "发条", "发个", "告诉", "说一声", "带句话")

// 「<锚点> + 对象 + 发 + 内容」的句式。
//
// 词表补不完 —— 「打开微信给文件传输助手**发你好**」一个动词都匹配不上
// (表里最接近的是"发个"),整句被当成"打开微信"处理,发消息彻底失效。
//
// 锚点必须有**两个**。第一版只写了「给」,结果实测语音把它吞了:
//   说的:  打开微信给万峰吻春眠发今晚出去吃饭
//   ASR:   打开微信万峰吻春眠发今晚出去吃饭     ← 「给」没了
// 于是认不出来、不接管、动作类工具被闸拦下,现象就是"被系统小爱接管了"。
// 「给」是口语里最容易被吞的虚词之一,不能单靠它。「微信」是实词,ASR 稳得多。
//
// 后面的否定预查是为了挡掉**问句**:「微信怎么发朋友圈」结构上也是
// 微信…发…,但那是在问怎么做,不是让我们发。
private val SEND_MESSAGE_PATTERN = Regex("(?:给|微信).{1,15}?发(?!朋友圈|现|视频号|布)")

// 判定"跳转目标确实是系统设置/系统应用",避免误伤导航/打开第三方应用之类的正常跳转
class HookEntry : IXposedHookLoadPackage {

    /** 把本模块进程里的 ModuleStatus.isActive() 替换成返回 true(见 ModuleStatus 的注释)。 */
    private fun hookSelfProbe(cl: ClassLoader) {
        try {
            val clazz = cl.loadClass("io.mo.xiaoaiplug.ModuleStatus")
            XposedBridge.hookAllMethods(
                clazz, "isActive",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                }
            )
            Log.i(TAG, "self probe installed, module is active")
        } catch (t: Throwable) {
            Log.w(TAG, "self probe failed: $t")
        }
    }

    // dialogId -> 用户提问原文
    private val queryTexts = ConcurrentHashMap<String, String>()

    // dialogId -> 我们自己 AI 的回复(拿到之后才能注入)
    private val aiAnswers = ConcurrentHashMap<String, String>()

    // dialogId -> RN 桥接对象(r70.a 子类实例),后面拿它来发我们自己的内容
    private val bridgeRefs = ConcurrentHashMap<String, WeakReference<Any>>()

    // dialogId -> TemplateReactNativeCard 实例,用来强制显示卡片
    private val cardRefs = ConcurrentHashMap<String, WeakReference<Any>>()

    // 已经接管、要拦截真实内容的 dialogId
    private val takeOver = ConcurrentHashMap.newKeySet<String>()

    // 已经完成注入的 dialogId,防止重复
    private val injected = ConcurrentHashMap.newKeySet<String>()

    // 标记"这次 sendStreamData 调用是我们自己发起的",避免被自己的 hook 再拦一次
    private val injectingNow = ThreadLocal.withInitial { false }

    private var sendStreamDataMethod: java.lang.reflect.Method? = null

    // 「查看类」不跳转用:记录最近一次用户问话 + 当时读到的配置,供跳转拦截判定
    @Volatile private var lastQueryText: String = ""
    @Volatile private var lastQueryTime: Long = 0L
    @Volatile private var lastDialogId: String = ""
    @Volatile private var lastConfig: AiConfig? = null

    /**
     * **终态 ASR 原文**,和 [lastQueryText] 分开存。
     *
     * 小爱会改写问话再交给 setQueryInfo,实测:
     *   "打开系统更新" → "系统更新"      (放行词"打开"没了)
     *   "查看系统版本" → "查看版本"      ("系统"没了)
     * 而 [lastQueryText] 会被后到的 setQueryInfo 覆盖,于是判定看到的是**残缺版**。
     * 后果是双向的:放行词被吃掉 → 该放行的跳转被拦;查看词被吃掉 → 该拦的没拦。
     * 前者更糟 —— 用户明确说了"打开",我们却把它挡了。
     *
     * 所以两个文本都留着,判定时取并集(见 [viewBlockCandidateNow])。
     */
    @Volatile private var lastAsrText: String = ""
    @Volatile private var lastAsrTime: Long = 0L

    @Volatile private var targetClassLoader: ClassLoader? = null

    // 第二层:被判定为"查看类、要拦跳转并让 AI 回答"的对话
    private val pendingViewAnswer = ConcurrentHashMap.newKeySet<String>()

    // 当前活跃的结果渲染 sink(flowableresult.d 或 widget.d,取决于是 App 还是悬浮窗),我们用它 addCard
    @Volatile private var activeCardSink: WeakReference<Any>? = null

    // dialogId -> 我们造并加进去的答案卡(先占位,答案到再更新它)
    private val answerCards = ConcurrentHashMap<String, WeakReference<Any>>()

    /**
     * 哪些卡是"认领"来的(小爱自己造的话术卡),而不是我们自建的。identity hash。
     *
     * 用来区分"还没轮到 bind"和"永远不会 bind":自建卡是我们刚 addCard 进去的,
     * 一时没绑定很正常,不能丢;认领的卡若到答案就绪时仍未绑定,那就是**永远不会绑**
     * (2026-07-20 探针:全程 30 秒 vh=null),必须丢掉改走自建,否则答案无处显示。
     */
    private val commandeeredCards = ConcurrentHashMap.newKeySet<Int>()

    // dialogId -> 当初把卡加进哪个 sink 了。用来判断"我们加的那个"是不是后来真正在渲染的那个:
    // 小爱刚起来时 activeCardSink 还没被观察到,只能靠 UiManager.getFloatManager() 兜底,
    // 而那个实例可能并不是稍后真正接收 addCard、真正渲染的那个 —— 现象就是
    // "第一句没弹卡片但语音正常"(卡加进了一个不渲染的列表里)。
    private val answerCardSinks = ConcurrentHashMap<String, WeakReference<Any>>()

    // 被小爱摘掉的卡片(identity hash)。小爱开新一轮对话会重置卡片列表,把我们加的卡也摘掉;
    // 答案回来时若发现自己的卡在这里面,就得重新造一张加回去,否则答案无处显示。
    private val detachedCards = ConcurrentHashMap.newKeySet<Int>()

    // 我们自己造的卡(identity hash -> 该显示的文本)。
    // FlowTemplateToastCard.bindView 绑定视图时,我们要在那一刻把 toastTv 强制填上并设为可见 ——
    // 光靠构造函数传文本不够,实测占位卡 bindView 跑了但屏幕上全程空白。
    private val ourCardTexts = ConcurrentHashMap<Int, String>()

    private val THINKING_PLACEHOLDER = "🤖 正在思考…"

    // 历史库改写:拦下小爱自己那句兜底文案,等我们的答案到了再写进去。
    // 实测一次交互里 recordToSpeak 会被连调十来次(同一句插了 11 行),所以压制按对话维度、
    // 写入用 historyWritten 保证只写一次。
    private val historyPending = ConcurrentHashMap.newKeySet<String>()
    private val historyWritten = ConcurrentHashMap.newKeySet<String>()

    // 标记"这次 recordToSpeak 是我们自己写的",别被自己的 hook 压掉
    private val writingHistory = ThreadLocal.withInitial { false }

    // ——— 按「一次问话」而不是按 dialogId 归拢 ———
    // 实测同一句"查看电量"小爱会在 388ms 内派发**两个不同的 dialogId**,各自走一遍 setQueryInfo。
    // 老的去重是 queryTexts.containsKey(dialogId),两个 id 全放行 → 调两次模型 → 得到两个
    // 措辞不同的答案 → 先后念两遍(n1.speakTts 每次先 stopPlay,第一句念一半被打断)。
    // 听感就是"抢答"。这里改成按 问话原文+时间窗 归拢:同一句话只调一次模型、只念一次,
    // 但所有 dialogId 都登记进来,好让它们各自的卡片都能拿到同一个答案。
    //
    // 问话原文 -> (归拢用的 key, 最近一次出现时间)
    private val utteranceLastSeen = ConcurrentHashMap<String, Pair<String, Long>>()

    // key -> 属于这次问话的所有 dialogId
    private val utteranceDialogs = ConcurrentHashMap<String, MutableSet<String>>()

    // key -> 这次问话的答案(只调一次模型)
    private val utteranceAnswers = ConcurrentHashMap<String, String>()

    // 已经发起过模型调用的 key,防止并发重复发起
    private val utteranceCalling = ConcurrentHashMap.newKeySet<String>()

    // 已经念过的 key,保证一次问话只出一次声
    private val spokenUtterances = ConcurrentHashMap.newKeySet<String>()

    // 静音泵:持续钳制主音轨到这个时刻为止(0 = 不钳制)。
    // 小爱的答案是云端流式回来的,什么时候开始播不确定 —— 打几发定时补刀必然漏。
    // 关键前提:小爱的 TTS 在**主音轨**(v20.e.getMainAudioTrack()),我们的答案在
    // **另一条**音轨(getAudioTrackByName(TOAST_STREAM_TTS),n1 用的那条),
    // 两个是不同的 v20.e 实例,所以死命掐主音轨永远不会掐到我们自己。
    @Volatile private var mutePumpUntil = 0L

    // 静音泵作用于哪个对话。新提问换了 dialogId 就停泵,免得把下一轮的正常播报也吃掉。
    @Volatile private var mutePumpDialogId = ""

    // 泵是否已在跑,避免重复投递形成多条自我递归的 Runnable 链
    @Volatile private var mutePumpRunning = false

    override fun handleLoadPackage(lpparam: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam) {
        // 加载进模块自己的进程时,只做一件事:把 ModuleStatus.isActive() 改成返回 true,
        // 好让设置界面能判断 LSPosed 到底有没有真的激活本模块。
        if (lpparam.packageName == SELF_PKG) {
            hookSelfProbe(lpparam.classLoader)
            return
        }
        // 系统设置:只往「小米澎湃 AI」页插一个进本模块的入口,不碰小爱那套逻辑。
        if (lpparam.packageName == SETTINGS_PKG) {
            SettingsHook.install(lpparam)
            return
        }
        if (lpparam.packageName != TARGET_PKG) return
        Log.i(TAG, "loaded into $TARGET_PKG process=${lpparam.processName}")
        targetClassLoader = lpparam.classLoader

        hookOperationManager(lpparam.classLoader)
        hookRnCard(lpparam.classLoader)
        hookRnCardStop(lpparam.classLoader)
        hookRnJsReady(lpparam.classLoader)
        hookBridge(lpparam.classLoader)
        hookCardBaseDiagnostic(lpparam.classLoader)
        hookSettingsJump(lpparam.classLoader)
        hookWebSearchFallback(lpparam.classLoader)
        hookChatHistory(lpparam.classLoader)
        hookCardSinks(lpparam.classLoader)
        hookAsrResult(lpparam.classLoader)
        hookAgentAction(lpparam.classLoader)
        hookToastCard(lpparam.classLoader)
        hookIntentLaunch(lpparam.classLoader)
        hookToastCardBind(lpparam.classLoader)
    }

    // 「查看类」不跳转:拦截 IntentUtilsWrapper.startActivitySafely(Intent, boolean)。
    // 当最近一次问话是"查看/查询…"类、且没有放行词、且跳转目标确实是系统设置页时,
    // 直接跳过原方法(装作启动成功),这样小爱就不会跳进设置。
    private fun hookSettingsJump(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(INTENT_UTILS_WRAPPER_CLASS)
        } catch (e: Throwable) {
            Log.i(TAG, "$INTENT_UTILS_WRAPPER_CLASS not found: $e")
            return
        }
        for (m in clazz.declaredMethods) {
            if (m.name != "startActivitySafely") continue
            val types = m.parameterTypes
            if (types.isEmpty() || types[0] != android.content.Intent::class.java) continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val intent = param.args[0] as? android.content.Intent ?: return
                            if (!ownsCurrentTurn()) return
                            Log.i(TAG, "block view-jump: query=\"$lastQueryText\" intent=$intent")
                            // 返回类型可能是 boolean/int,给个"成功"值,避免上层误判失败
                            param.result = when (m.returnType) {
                                java.lang.Boolean.TYPE -> true
                                Integer.TYPE -> 0
                                else -> null
                            }
                            // 第二层:静音那句"好的",并尝试自建卡片让 AI 来回答
                            onViewJumpBlocked(lastDialogId)
                        } catch (t: Throwable) {
                            Log.i(TAG, "hookSettingsJump error: $t")
                        }
                    }
                })
                Log.i(TAG, "hooked IntentUtilsWrapper.startActivitySafely(${types.joinToString { it.simpleName }})")
            } catch (t: Throwable) {
                Log.i(TAG, "hook startActivitySafely fail: $t")
            }
        }
    }

    // 在**终态 ASR** 就把问话记下来。比 setQueryInfo 早 600ms,比"跳设置"的 Agent Action 早 266ms ——
    // 这 266ms 正是能不能拦住那条跳转的全部余量。
    private fun hookAsrResult(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(ASR_PROCESSOR_CLASS)
        } catch (e: Throwable) {
            Log.i(TAG, "$ASR_PROCESSOR_CLASS not found: $e")
            return
        }
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "processed" && it.parameterTypes.size == 1
        }
        if (method == null) {
            Log.i(TAG, "z10.a.processed(Instruction) not found")
            return
        }
        try {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val instruction = param.args[0] ?: return
                        val fullName = instruction.javaClass.getMethod("getFullName")
                            .invoke(instruction) as? String ?: return
                        if (fullName != ASR_RECOGNIZE_RESULT) return
                        val payload = instruction.javaClass.getMethod("getPayload")
                            .invoke(instruction) ?: return
                        // 只认终态,中间的 partial 结果会随着用户说话不断变
                        val isFinal = payload.javaClass.getMethod("isFinal")
                            .invoke(payload) as? Boolean ?: false
                        if (!isFinal) return
                        val results = payload.javaClass.getMethod("getResults")
                            .invoke(payload) as? List<*> ?: return
                        val text = results.filterNotNull().joinToString("") {
                            (it.javaClass.getMethod("getText").invoke(it) as? String).orEmpty()
                        }
                        if (text.isBlank()) return
                        val dialogId = optionalString(
                            instruction.javaClass.getMethod("getDialogId").invoke(instruction)
                        ).orEmpty()

                        val ctx = currentApplicationContext()
                        val config = if (ctx != null) ConfigClient.read(ctx) else null
                        if (config != null) lastConfig = config

                        // ASR 会把一句话切成两段:实测"现在的WIFI WIFI密码是多少"之后 1.8 秒
                        // 又来了一次 asr final text="呜呜"(说话的尾音)。它带着新 dialogId 走完整流程,
                        // 于是停了我们的静音泵、改写了 lastQueryText,连带小爱自己也重置了卡片列表,
                        // 把我们 1.5 秒前加的占位卡冲掉 —— 现象就是"有语音没卡片"。
                        // 这里挡掉这种碎片:模型调用还在飞、时间很近、内容又短又不像提问,就当没听见。
                        val elapsed = System.currentTimeMillis() - lastQueryTime
                        val junk = text.length <= 3 &&
                            (config == null || !isViewBlockCandidate(text, config))
                        if (junk && elapsed < 5_000L && utteranceCalling.isNotEmpty()) {
                            Log.i(TAG, "ignore ASR fragment \"$text\" (answer still pending, ${elapsed}ms after last query)")
                            return
                        }

                        if (text != lastQueryText) stopMutePump()
                        lastQueryText = text
                        lastQueryTime = System.currentTimeMillis()
                        // 原文单独留一份 —— 稍后 setQueryInfo 会用改写过的版本覆盖 lastQueryText
                        lastAsrText = text
                        lastAsrTime = lastQueryTime
                        if (dialogId.isNotBlank()) lastDialogId = dialogId
                        Log.i(TAG, "asr final: dialogId=$dialogId text=$text")

                        // 这一刻就能判定"这句我们自己答" → 立刻起泵,比小爱起播早得多。
                        //
                        // 发消息类**必须**和查看类一样在这里起泵,不能只靠 setQueryInfo 那处。
                        // 实测「打开微信给文件传输助手发你好」:
                        //   41.625 asr final → 41.924 SpeechSynthesizer.Speak(小爱 299ms 就开口)
                        //   → 42.008 mute pump started(setQueryInfo 路径,383ms)
                        // 晚了 84ms,那句"暂不支持微信双开功能"就漏出去了 —— 现象是先播报
                        // 失败话术、然后消息才被我们发出去。查看类当初就是因为这个把泵前移到
                        // 这里的(见 setQueryInfo 那处注释),发消息这条路当时漏打了。
                        if (config != null && config.enabled && config.speakAnswer &&
                            dialogId.isNotBlank() &&
                            (isViewBlockCandidate(text, config) || isSendMessageCommand(text))
                        ) {
                            pendingViewAnswer.add(dialogId)
                            startMutePump(dialogId)
                        }
                    } catch (t: Throwable) {
                        Log.i(TAG, "hookAsrResult error: $t")
                    }
                }
            })
            Log.i(TAG, "hooked z10.a.processed (asr)")
        } catch (t: Throwable) {
            Log.i(TAG, "hook asr fail: $t")
        }
    }

    // 接管(而不是拦掉)小爱那张固定话术卡。
    //
    // 为什么改成接管:自建卡片走了十几轮都渲染不出来 —— 真机日志显示我们的卡
    // createViewHolder/bindView/onCardAttached 全跑过,TextView 也确实 vis=VISIBLE、h=98、
    // 父容器可见,但屏幕上就是没有,说明它被插进了一个不上屏的容器。
    // 而小爱**自己**那张话术卡("应用已经不支持这个功能啦")是能正常显示的 —— 同一时刻、
    // 同一场景,它的卡看得见。既然如此就别再往里塞自己的卡:让小爱照常创建、布局、显示,
    // 我们只把内容换成自己的。容器、时机、显示模式全部沿用它的机制。
    private fun hookToastCard(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(TOAST_OPERATION_CLASS)
        } catch (e: Throwable) {
            Log.i(TAG, "$TOAST_OPERATION_CLASS not found: $e")
            return
        }
        val targets = clazz.declaredMethods.filter {
            (it.name == "g0" || it.name == "i0") &&
                it.parameterTypes.size == 1 && it.parameterTypes[0] == Integer.TYPE
        }
        if (targets.isEmpty()) {
            Log.i(TAG, "vd.g0/i0(int) not found")
            return
        }
        for (m in targets) {
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val card = param.result ?: return
                            val op = param.thisObject
                            val opDialogId = try {
                                op.javaClass.getMethod("getDialogId").invoke(op) as? String
                            } catch (t: Throwable) { null }.orEmpty()
                            // 注意:这类固定话术 Operation 的 dialogId 是合成字面量
                            // ("fakeDialogId"/"fakeErrorDialogId"),**不是**真实对话 id,
                            // 匹配不上很正常。用"静音泵是否在为当前交互运行"来判定这轮是否归我们。
                            claimToastCard(card, opDialogId, via = "vd.${m.name}")
                        } catch (t: Throwable) {
                            Log.i(TAG, "hookToastCard error: $t")
                        }
                    }
                })
                Log.i(TAG, "hooked vd.${m.name}(int)")
            } catch (t: Throwable) {
                Log.i(TAG, "hook vd.${m.name} fail: $t")
            }
        }
    }

    // 读 com.xiaomi.ai.api.common 那套 Optional 风格的包装(isPresent/get)
    private fun optionalString(opt: Any?): String? {
        if (opt == null) return null
        return try {
            val present = opt.javaClass.getMethod("isPresent").invoke(opt) as? Boolean ?: false
            if (!present) null else opt.javaClass.getMethod("get").invoke(opt)?.toString()
        } catch (t: Throwable) {
            null
        }
    }

    // 拦第三条跳转路:AgentActionManager。两个 executeActionsAsync 重载都挂上,
    // 顺带把它们汇入的 execute 也挂上,免得有调用方绕过重载直接进去。
    private fun hookAgentAction(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(AGENT_ACTION_CLASS)
        } catch (e: Throwable) {
            Log.i(TAG, "$AGENT_ACTION_CLASS not found: $e")
            return
        }
        val targets = clazz.declaredMethods.filter {
            it.name == "executeActionsAsync" || it.name == "execute"
        }
        if (targets.isEmpty()) {
            Log.i(TAG, "s0.executeActionsAsync not found")
            return
        }
        for (m in targets) {
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            // 第一个 Agent.Action 类型的参数就是动作本体
                            // (execute 的静态桥接版第一个参数是 s0 自己,所以按类型找而不是按位置)
                            val action = param.args.firstOrNull {
                                it != null && it.javaClass.name.endsWith("Agent\$Action")
                            } ?: return
                            @Suppress("UNCHECKED_CAST")
                            val specs = (action.javaClass.getMethod("getAction").invoke(action)
                                as? List<String>).orEmpty()
                            if (!shouldBlockAgentAction(specs)) return
                            Log.i(TAG, "block agent action: query=\"$lastQueryText\" specs=$specs")
                            param.result = if (m.returnType == java.lang.Boolean.TYPE) true else null
                            onViewJumpBlocked(lastDialogId)
                        } catch (t: Throwable) {
                            Log.i(TAG, "hookAgentAction error: $t")
                        }
                    }
                })
                Log.i(TAG, "hooked s0.${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
            } catch (t: Throwable) {
                Log.i(TAG, "hook s0.${m.name} fail: $t")
            }
        }
    }

    // 这条 Agent Action 该不该拦。判据同 shouldBlockJump:只看这一轮归不归我们,
    // 不看动作打向哪个包。specs 只用来确认"这确实是条动作指令"。
    private fun shouldBlockAgentAction(specs: List<String>): Boolean {
        if (specs.isEmpty()) return false
        return ownsCurrentTurn()
    }

    // 白名单直通命中的正则,编译失败(用户手写的正则语法有误)就当不生效处理,别把整个接管功能拖垮
    @Volatile private var skipTakeoverRegexCache: Pair<String, Regex?>? = null

    // 判定这句问话是不是命中了"白名单直通"正则:命中就完全不接管,原生行为照旧
    private fun isAiTakeoverSkip(q: String, cfg: AiConfig): Boolean {
        if (!cfg.skipTakeoverEnabled) return false
        val pattern = cfg.skipTakeoverPattern
        if (pattern.isBlank()) return false // 空正则不当"匹配所有"处理,避免误配置吞掉全部问话
        val cached = skipTakeoverRegexCache
        val regex = if (cached != null && cached.first == pattern) {
            cached.second
        } else {
            val compiled = runCatching { Regex(pattern) }
                .onFailure { Log.w(TAG, "skip-takeover pattern invalid, ignored: $pattern", it) }
                .getOrNull()
            skipTakeoverRegexCache = pattern to compiled
            compiled
        }
        return regex?.containsMatchIn(q) == true
    }

    // 命中放行词 = 用户明确要"打开/启动"点什么,而不是在问问题
    private fun hitsJumpAllowWord(q: String, cfg: AiConfig): Boolean {
        val allowWords = cfg.jumpAllowWords.split(',', ' ', '，', '、')
            .map { it.trim() }.filter { it.isNotEmpty() }
        return allowWords.any { q.contains(it) }
    }

    // 判定一句话是不是"查看类、应拦跳转"的候选(不含跳转目标判定,那个只在 startActivitySafely 时才知道)
    private fun isViewBlockCandidate(q: String, cfg: AiConfig): Boolean {
        if (!cfg.blockViewJump) return false
        if (q.isBlank()) return false
        if (hitsJumpAllowWord(q, cfg)) return false
        // 必须是"查看类"问话
        return VIEW_WORDS.any { q.contains(it) }
    }

    // 这里曾经有个 isNativeLaunchCommand():想让"打开X"这类纯指令跳过模型调用。
    // 已撤销,两个理由都是实测打脸的:
    //  1) 它要解决的问题不存在 —— 以为"打开系统更新"末尾会白播一句"没有得到答案",
    //     实际日志里紧跟着就是 "not taken over, skip speaking",那句话从来没被念出来。
    //     真实收益只是省一次模型调用,用户感知不到。
    //  2) 代价是真的 —— "打开微信给文件传输助手发你好"里"发你好"不在 SEND_MESSAGE_VERBS,
    //     isSendMessageCommand 认不出来,整句被当成"打开微信"直接早退,发消息彻底失效。
    // 顺带记一笔:就算不撤,它在这条路上也基本失灵 —— setQueryInfo 拿到的文本是
    // 小爱改写过的("打开系统更新"→"系统更新"),放行词"打开"到这一步已经没了。

    /**
     * 是不是"让某个应用给某人发消息"。命中即由我们接管:起静音泵按住小爱那句
     * 固定话术,并允许模型调用动作类工具(send_message)。
     */
    private fun isSendMessageCommand(q: String): Boolean {
        if (q.isBlank()) return false
        if (SEND_MESSAGE_APPS.none { q.contains(it, ignoreCase = true) }) return false
        // 先按句式认(覆盖"发<任意内容>"),认不出再退回动词表(覆盖"告诉X…""带句话"这种没有"发"的)
        return SEND_MESSAGE_PATTERN.containsMatchIn(q) || SEND_MESSAGE_VERBS.any { q.contains(it) }
    }

    /**
     * 这一轮交互归不归我们 —— 所有"要不要拦"的判定都问这一个问题。
     *
     * **刻意不看跳转目标是哪个 App。** 早先的做法是"问话是查看类 **且** 目标在
     * SETTINGS_PACKAGES 白名单里"才拦,那个白名单是个填不完的坑:实测"查看系统版本"
     * 跳的是 com.android.updater(「系统更新」是独立 app,不在设置里),不在名单里就漏了;
     * 而且它的失败是静默的 —— 跳转照常发生,日志里没有任何异常,只能靠真机复现才发现。
     *
     * 真正的判据在更上游:setQueryInfo 那一刻(比跳转早约 300ms)如果判定这句是查看类,
     * 就已经 pendingViewAnswer.add + 起静音泵 + 调模型了,"这轮我们自己答"是既成事实。
     * 此时小爱还要起 Activity,错不在"目标像不像设置",而在这轮已经不归它了 ——
     * 跳 App 是它回答问题的方式,而它的答案已经被我们抢走。
     *
     * 所以目标是什么包根本不重要,判据只剩:用户是在**问问题**(而不是让"打开"什么),
     * 且这次跳转和那句问话属于同一次交互。
     */
    private fun ownsCurrentTurn(): Boolean {
        val cfg = lastConfig ?: return false
        // 问话要足够新(和这次跳转是同一次交互);太旧就不管,避免误伤后续手动/其它跳转
        if (System.currentTimeMillis() - lastQueryTime > 12_000L) return false
        return viewBlockCandidateNow(cfg)
    }

    /**
     * 「这轮是不是查看类」的**当下**判定:ASR 原文和 setQueryInfo 改写版一起看。
     *
     * 单看 [lastQueryText] 会被小爱的改写坑到(见 [lastAsrText] 的注释)。取并集,且:
     *  - **放行词任一命中就放行** —— "打开系统更新"改写成"系统更新"后"打开"没了,
     *    只看改写版会把用户明确要求的跳转拦下来。放行是安全侧。
     *  - 查看词任一命中就算候选 —— 改写吃掉查看词时靠原文兜住。
     */
    private fun viewBlockCandidateNow(cfg: AiConfig): Boolean {
        if (!cfg.blockViewJump) return false
        val texts = currentQueryTexts()
        if (texts.isEmpty()) return false
        if (texts.any { hitsJumpAllowWord(it, cfg) }) return false
        return texts.any { t -> VIEW_WORDS.any { t.contains(it) } }
    }

    /**
     * 本轮所有可用的问话文本(改写版 + ASR 原文,去重)。
     *
     * ASR 原文带时效:它是上一次说话留下的,如果这轮压根没走语音(打字输入)就不该再算数,
     * 否则会拿上一句的内容判定这一句。用和跳转拦截同样的 12 秒窗口。
     */
    /** 「这轮是不是发消息类」的当下判定。同样两个文本都看,理由见 [viewBlockCandidateNow]。 */
    private fun sendMessageCommandNow(): Boolean =
        currentQueryTexts().any { isSendMessageCommand(it) }

    private fun currentQueryTexts(): List<String> {
        val out = ArrayList<String>(2)
        if (lastQueryText.isNotBlank()) out.add(lastQueryText)
        if (lastAsrText.isNotBlank() && lastAsrText != lastQueryText &&
            System.currentTimeMillis() - lastAsrTime <= 12_000L
        ) out.add(lastAsrText)
        return out
    }

    // 小爱答不上来的兜底:拦截 m2.startActivitySafely(Intent, String)。
    // 这个方法返回 int 错误码,0=成功、非 0 上层会当成启动失败(又触发一轮兜底播报),
    // 所以拦下来必须回 0 冒充成功。
    private fun hookWebSearchFallback(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(INTENT_UTILS_CLASS)
        } catch (e: Throwable) {
            Log.i(TAG, "$INTENT_UTILS_CLASS not found: $e")
            return
        }
        val method = try {
            clazz.getDeclaredMethod("startActivitySafely", android.content.Intent::class.java, String::class.java)
        } catch (e: Throwable) {
            Log.i(TAG, "m2.startActivitySafely(Intent,String) not found: $e")
            return
        }
        try {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val intent = param.args[0] as? android.content.Intent ?: return
                        val srcUri = (param.args[1] as? String).orEmpty()
                        if (!shouldBlockWebSearch(intent, srcUri)) return
                        Log.i(TAG, "block web-search fallback: query=\"$lastQueryText\" intent=$intent srcUri=$srcUri")
                        param.result = 0   // 0 = 启动成功,别让上层判定失败
                        onViewJumpBlocked(lastDialogId)
                    } catch (t: Throwable) {
                        Log.i(TAG, "hookWebSearchFallback error: $t")
                    }
                }
            })
            Log.i(TAG, "hooked m2.startActivitySafely(Intent, String)")
        } catch (t: Throwable) {
            Log.i(TAG, "hook m2.startActivitySafely fail: $t")
        }
    }

    // 第四条跳转路:Launcher.LaunchApp 指令 → LaunchAppOperation → m2 直接起 Activity
    //   (实测 "现在连接的WiFi密码是多少" → action=android.settings.WIFI_SETTINGS)
    // 它不经过 startActivitySafely,所以前面几个 hook 都拦不到。
    // m2.k(Context, Intent) 是 m2 里最底层那个真正启动 Activity 的方法 —— 早先的调用栈里
    //   m2.k ← m2.startIntentWithSplitSetting ← m2.n ← m2.l ← m2.startActivitySafely
    // 说明它在所有路径的最下游,拦这一层四条路都覆盖。
    // 方法名 k 是混淆产物,所以按 (Context, Intent) 签名找,不写死名字。
    private fun hookIntentLaunch(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(INTENT_UTILS_CLASS)
        } catch (e: Throwable) {
            Log.i(TAG, "$INTENT_UTILS_CLASS not found: $e")
            return
        }
        val targets = clazz.declaredMethods.filter {
            val p = it.parameterTypes
            p.size == 2 && p[0] == Context::class.java && p[1] == android.content.Intent::class.java
        }
        if (targets.isEmpty()) {
            Log.i(TAG, "m2.(Context,Intent) launcher not found")
            return
        }
        for (m in targets) {
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val intent = param.args[1] as? android.content.Intent ?: return
                            if (!ownsCurrentTurn()) return
                            Log.i(TAG, "block intent launch: query=\"$lastQueryText\" intent=$intent")
                            param.result = when (m.returnType) {
                                java.lang.Boolean.TYPE -> true
                                Integer.TYPE -> 0
                                else -> null
                            }
                            onViewJumpBlocked(lastDialogId)
                        } catch (t: Throwable) {
                            Log.i(TAG, "hookIntentLaunch error: $t")
                        }
                    }
                })
                Log.i(TAG, "hooked m2.${m.name}(Context, Intent)")
            } catch (t: Throwable) {
                Log.i(TAG, "hook m2.${m.name} fail: $t")
            }
        }
    }

    // 压制小爱写进历史库的兜底文案(只在我们拦了跳转、且自己的答案还没写进去的这段时间内)
    private fun hookChatHistory(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(CHAT_DB_MANAGER_CLASS)
        } catch (e: Throwable) {
            Log.i(TAG, "$CHAT_DB_MANAGER_CLASS not found: $e")
            return
        }
        // 拦 insert(bean) 而不是 recordToSpeak:那句兜底文案实测走的是 m(str, dialogId)
        // (即 recordToSpeakInternal),recordToSpeak(String) 根本没被调到。
        // 这几个入口最后都汇进 insert,拦这一处才不用赌混淆方法名。
        // 注意:a extends vl0.a<e, Long>,insert(e) 是泛型重写,编译器会另外生成 bridge 方法
        // insert(Object)。只挂其中一个会漏 —— m() 里是 getInstance().insert(eVar),静态类型
        // 就是 e,直接调真方法绕开 bridge。所以两个重载全挂上。
        val methods = clazz.declaredMethods.filter {
            it.name == "insert" && it.parameterTypes.size == 1
        }
        if (methods.isEmpty()) {
            Log.i(TAG, "ChatDbManager.insert(bean) not found")
            return
        }
        for (method in methods) {
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (writingHistory.get() == true) return   // 我们自己写的,放行
                        val bean = param.args[0] ?: return
                        // 只管小爱说的话(isSend=0),用户自己的提问要留在历史里
                        val isSend = try {
                            bean.javaClass.getMethod("getIsSend").invoke(bean) as? Int ?: 0
                        } catch (t: Throwable) { 0 }
                        if (isSend > 0) return
                        val text = try {
                            bean.javaClass.getMethod("getUserContent").invoke(bean) as? String
                        } catch (t: Throwable) { null } ?: return
                        if (!shouldSuppressHistory(text)) return
                        Log.i(TAG, "suppress history insert: ${text.take(40)}")
                        param.result = false
                    } catch (t: Throwable) {
                        Log.i(TAG, "hookChatHistory error: $t")
                    }
                }
                })
                Log.i(TAG, "hooked ChatDbManager.insert(${method.parameterTypes[0].simpleName})")
            } catch (t: Throwable) {
                Log.i(TAG, "hook insert fail: $t")
            }
        }
    }

    // 要不要把这条历史写入压掉。两种情况都压:
    //  1) 内容就是那句兜底话术 —— 不管它写在拦截之前还是之后(实测两头都有)
    //  2) 这次对话被我们拦了、自己的答案还没写进去 —— 期间小爱说的都不该留在历史里
    // 都限制在最近一次问话的 60 秒内,免得把之后的正常对话也吃掉。
    private fun shouldSuppressHistory(text: String): Boolean {
        val cfg = lastConfig ?: return false
        if (!cfg.blockWebSearch || !cfg.enabled) return false
        if (System.currentTimeMillis() - lastQueryTime > 60_000L) return false
        if (FALLBACK_MARKERS.any { text.contains(it) }) return true
        return historyPending.isNotEmpty()
    }

    // 把我们自己的答案写进历史库,让 App 里的「历史对话」也显示替换后的内容
    private fun writeAnswerToHistory(dialogId: String, answer: String) {
        if (dialogId !in historyPending) return
        if (!historyWritten.add(dialogId)) return
        val cl = targetClassLoader ?: return
        try {
            writingHistory.set(true)
            val clazz = cl.loadClass(CHAT_DB_MANAGER_CLASS)
            val instance = clazz.getMethod("getInstance").invoke(null) ?: return
            clazz.getDeclaredMethod("recordToSpeak", String::class.java).invoke(instance, answer)
            Log.i(TAG, "answer written to history dialogId=$dialogId")
        } catch (t: Throwable) {
            Log.i(TAG, "writeAnswerToHistory failed dialogId=$dialogId: $t")
            historyWritten.remove(dialogId)
        } finally {
            writingHistory.set(false)
            historyPending.remove(dialogId)
        }
    }

    // 这次跳转是不是"小爱答不上来 → 甩给全局搜索"的兜底
    private fun shouldBlockWebSearch(intent: android.content.Intent, srcUri: String): Boolean {
        val cfg = lastConfig ?: return false
        if (!cfg.blockWebSearch) return false
        // 只有开了 AI 接管才拦 —— 否则拦掉又没人回答,用户就什么都得不到了
        if (!cfg.enabled || !cfg.isUsable) return false
        // 问话要足够新,和这次跳转属于同一次交互
        if (System.currentTimeMillis() - lastQueryTime > 12_000L) return false
        if (lastQueryText.isBlank()) return false
        // 用户明确说了"搜索/百度…" → 他要的就是搜索,放行
        val allowWords = cfg.webSearchAllowWords.split(',', ' ', '，', '、')
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (allowWords.any { lastQueryText.contains(it) }) return false
        return isWebSearchIntent(intent, srcUri)
    }

    private fun isWebSearchIntent(intent: android.content.Intent, srcUri: String): Boolean {
        val action = intent.action.orEmpty()
        if (action == android.content.Intent.ACTION_WEB_SEARCH) return true
        if (action == android.content.Intent.ACTION_SEARCH) return true
        val pkg = intent.component?.packageName ?: intent.`package`.orEmpty()
        if (pkg == QUICK_SEARCH_PKG) return true
        if (intent.data?.scheme == "qsb") return true
        // 兜底 URI 形如 intent://query?words=后台程序&web_search=false#Intent;...
        return srcUri.contains("web_search=") || srcUri.contains("query?words=")
    }

    // 拦掉查看类跳转之后:掐掉那句"好的",并把这个对话标记为接管(答案就绪后塞进小爱自己的 toast 卡)
    private fun onViewJumpBlocked(dialogId: String) {
        try { startMutePump(dialogId) } catch (t: Throwable) { }
        val cfg = lastConfig ?: return
        if (!cfg.enabled) return                // 没开 AI 接管:只拦跳转 + 静音
        if (dialogId.isBlank()) return
        // 注意:不要 takeOver.add(dialogId) —— 那是 RN bridge 文本接管用的,
        // 会触发"拦截控制信令"逻辑导致小爱误判失败并播报"只能帮你到这了"。
        pendingViewAnswer.add(dialogId)
        // 从现在起压制小爱写进历史库的兜底文案,直到我们的答案写进去
        historyPending.add(dialogId)
        // 关键:趁界面还活着,立刻加一张占位卡(答案 6 秒后才到,那时界面已收起就晚了)
        ensureAnswerCard(dialogId)
    }

    // 捕获当前活跃的结果渲染 sink:App(flowableresult.d)和悬浮窗(widget.d)各用一套,
    // 两套的 addCard 都 hook,谁最近被调用(当前会话的 query/loading 卡就是它加的)谁就是活跃 sink。
    private fun hookCardSinks(cl: ClassLoader) {
        for (className in listOf(FLOW_CONTROLLER_CLASS, FLOAT_MANAGER_CLASS)) {
            val clazz = try {
                cl.loadClass(className)
            } catch (e: Throwable) {
                Log.i(TAG, "$className not found: $e")
                continue
            }
            for (m in clazz.declaredMethods) {
                if (m.name != "addCard") continue
                if (m.parameterTypes.size != 1) continue
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // 别把我们自己加的卡也当成"活跃 sink 信号"——不影响,直接更新即可
                            activeCardSink = WeakReference(param.thisObject)
                            // 排查用(2026-07-20):我们认领的 toast 卡 30 秒都没绑过视图,
                            // 怀疑它压根没被加进渲染列表。这个 hook 原来是静默的,
                            // "addCard 没被调用"和"调用了但卡没上屏"看不出区别 —— 一轮就几条,打得起。
                            val c = param.args.getOrNull(0)
                            Log.i(TAG, "[sink] ${param.thisObject.javaClass.simpleName}" +
                                    "@${System.identityHashCode(param.thisObject)} addCard " +
                                    "${c?.javaClass?.name}@${System.identityHashCode(c)}")
                        }
                    })
                    Log.i(TAG, "hooked card sink $className.addCard")
                } catch (t: Throwable) {
                    Log.i(TAG, "hook card sink $className fail: $t")
                }
            }
        }
    }

    // hookCardSinks 是"守株待兔"式的:要先观察到有人调过 addCard 才知道 sink 是谁。
    // 但小爱刚重启后的第一次交互,拦截发生在唤醒后几十毫秒内,那时一次 addCard 都还没发生
    // (真机日志:block 与 query captured 只差 24ms,紧接着就是 no active card sink yet),
    // 占位卡就加不上了。这里主动去要 FloatManager(悬浮窗那套 sink,类型正是 widget.d)兜底。
    private fun resolveFloatManager(): Any? {
        return try {
            val ctx = currentApplicationContext() ?: return null
            val uiManagerClass = ctx.classLoader.loadClass("com.xiaomi.voiceassistant.UiManager")
            val instance = uiManagerClass.getMethod("getInstance", Context::class.java).invoke(null, ctx)
                ?: return null
            val fm = instance.javaClass.getMethod("getFloatManager").invoke(instance)
            if (fm != null) {
                Log.i(TAG, "resolved float manager fallback: ${fm.javaClass.name}@${System.identityHashCode(fm)}")
            }
            fm
        } catch (t: Throwable) {
            Log.i(TAG, "resolveFloatManager failed: $t")
            null
        }
    }

    // 保证有一张答案卡:第一次(拦截时)造卡并加进当前活着的流式列表,内容用答案或占位;
    // 之后(答案就绪)再调一次,把已有卡片的文本更新为答案。
    private fun ensureAnswerCard(dialogId: String) {
        if (dialogId !in pendingViewAnswer) return
        val cl = targetClassLoader ?: return
        val answer = aiAnswers[dialogId]

        // 卡还在但已经被小爱摘掉了(它开新一轮对话会重置列表)→ 丢掉重造,否则更新了也没人显示
        val known = answerCards[dialogId]?.get()
        if (known != null && System.identityHashCode(known) in detachedCards) {
            Log.i(TAG, "answer card was detached, rebuilding dialogId=$dialogId")
            detachedCards.remove(System.identityHashCode(known))
            answerCards.remove(dialogId)
            answerCardSinks.remove(dialogId)
        }

        // 认领来的卡到这会儿还没绑定视图 → 它永远不会绑,丢掉改走自建卡。
        // 2026-07-20 真机:认领的 FlowTemplateToastCard 从 +0ms 到 +30s 全程 viewHolder=null,
        // 而认领动作又占着 answerCards[dialogId],把本来能工作的自建卡路径挡在门外 ——
        // 现象就是"有语音、没卡片"。只对认领的卡这么判:自建卡刚 addCard 完还没 bind 是正常的。
        answerCards[dialogId]?.get()?.let { c ->
            val id = System.identityHashCode(c)
            if (id in commandeeredCards && viewHolderOf(c) == null) {
                Log.i(TAG, "commandeered card@$id never bound, falling back to own card dialogId=$dialogId")
                commandeeredCards.remove(id)
                answerCards.remove(dialogId)
                answerCardSinks.remove(dialogId)
            }
        }

        // 已经有卡了 → 有答案就更新文本
        val existing = answerCards[dialogId]?.get()
        if (existing != null) {
            if (answer != null) {
                Handler(Looper.getMainLooper()).post {
                    try {
                        ourCardTexts[System.identityHashCode(existing)] = answer
                        existing.javaClass.getMethod("updateCardText", String::class.java).invoke(existing, answer)
                        forceShowToastViewHolder(existing, answer)
                        Log.i(TAG, "answer card updated dialogId=$dialogId")
                    } catch (t: Throwable) {
                        Log.i(TAG, "update answer card failed dialogId=$dialogId: $t")
                    }
                    // 当初可能是把卡加进了兜底拿到的 FloatManager,而真正在渲染的是另一个实例
                    // (现象:有语音没卡片)。这时观察到的 activeCardSink 才是对的,补加一次。
                    reattachIfWrongSink(cl, dialogId, existing)
                }
            }
            return
        }

        // 还没造卡 → 造一张并加进当前活跃 sink(内容:有答案用答案,没有就占位)
        val sink = activeCardSink?.get() ?: resolveFloatManager()
        if (sink == null) {
            Log.i(TAG, "no active card sink yet dialogId=$dialogId")
            return
        }
        Handler(Looper.getMainLooper()).post {
            try {
                val cardClass = cl.loadClass(FLOW_TOAST_CARD_CLASS)
                val ctor = cardClass.getConstructor(Integer.TYPE, String::class.java)
                val card = ctor.newInstance(0, answer ?: THINKING_PLACEHOLDER)
                try {
                    cardClass.getMethod("setDialogId", String::class.java).invoke(card, dialogId)
                } catch (t: Throwable) { }
                answerCards[dialogId] = WeakReference(card)
                answerCardSinks[dialogId] = WeakReference(sink)
                // 登记这张卡该显示什么,bindView 时由 hookToastCardBind 强制填进 TextView
                ourCardTexts[System.identityHashCode(card)] = answer ?: THINKING_PLACEHOLDER
                val baseCardClass = cl.loadClass("com.xiaomi.voiceassistant.card.a")
                sink.javaClass.getMethod("addCard", baseCardClass).invoke(sink, card)
                Log.i(TAG, "answer card added via ${sink.javaClass.name}@${System.identityHashCode(sink)}" +
                        " card@${System.identityHashCode(card)} dialogId=$dialogId (answer=${answer != null})")
            } catch (t: Throwable) {
                Log.i(TAG, "ensureAnswerCard add failed dialogId=$dialogId: $t")
            }
        }
    }

    // 答案就绪时复查:当初加卡用的 sink,和现在真正在收 addCard 的 sink 是不是同一个对象。
    // 不是的话说明卡加进了一个不渲染的列表(小爱刚起来、activeCardSink 还没观察到时,
    // 只能用 UiManager.getFloatManager() 兜底,拿到的可能不是当前真正在渲染的那个实例),
    // 补加到对的那个上。相同则什么都不做,避免重复加卡。
    private fun reattachIfWrongSink(cl: ClassLoader, dialogId: String, card: Any) {
        try {
            val used = answerCardSinks[dialogId]?.get() ?: return
            val current = activeCardSink?.get() ?: return
            if (used === current) return
            Log.i(TAG, "card sink mismatch dialogId=$dialogId used@${System.identityHashCode(used)}" +
                    " current@${System.identityHashCode(current)} -> reattaching")
            val baseCardClass = cl.loadClass("com.xiaomi.voiceassistant.card.a")
            current.javaClass.getMethod("addCard", baseCardClass).invoke(current, card)
            answerCardSinks[dialogId] = WeakReference(current)
            Log.i(TAG, "card reattached dialogId=$dialogId")
        } catch (t: Throwable) {
            Log.i(TAG, "reattachIfWrongSink failed dialogId=$dialogId: $t")
        }
    }

    // 在我们自己那张卡真正绑定视图的瞬间,把文本强制填进去并设为可见。
    // 只靠构造函数传文本是不够的:实测占位卡 createViewHolder/bindView/onCardAttached 全跑了,
    // 屏幕上却始终空白 —— bindView 内部会因为某些状态没满足而不给 toastTv 上文本。
    // 这里在 bindView 之后补一刀,并把 TextView 的实际状态打出来,便于确认到底有没有生效。
    private fun hookToastCardBind(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(FLOW_TOAST_CARD_CLASS)
        } catch (e: Throwable) {
            Log.i(TAG, "$FLOW_TOAST_CARD_CLASS not found: $e")
            return
        }
        for (m in clazz.declaredMethods) {
            if (m.name != "bindView") continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val card = param.thisObject
                            // 还没被认领、但这轮确实归我们 → 就地认领。
                            // 有些话术卡不经过 jb0.vd(微信双开那句就是),只能在这兜底,
                            // 否则语音被静音泵按住了、文字却照样显示出来。
                            if (ourCardTexts[System.identityHashCode(card)] == null) {
                                claimToastCard(card, "", via = "bindView", onlyIfUnclaimed = true)
                            }
                            val text = ourCardTexts[System.identityHashCode(card)]
                            // 排查用(2026-07-20):无差别记一笔谁被 bind 了。
                            // 只在"是我们的卡"时才做事,于是"没有任何 toast 卡被 bind"
                            // 和"别人的卡被 bind 了、我们的没有"在日志里长得一模一样。
                            Log.i(TAG, "[bind] card@${System.identityHashCode(card)}" +
                                    " ours=${text != null}")
                            if (text == null) return
                            forceShowToastViewHolder(card, text)
                        } catch (t: Throwable) {
                            Log.i(TAG, "hookToastCardBind error: $t")
                        }
                    }
                })
                Log.i(TAG, "hooked FlowTemplateToastCard.bindView(${m.parameterTypes.size} args)")
            } catch (t: Throwable) {
                Log.i(TAG, "hook FlowTemplateToastCard.bindView fail: $t")
            }
        }
    }

    /**
     * 认领一张 toast 卡:归到我们当前服务的 dialogId 名下,并把它的文字换成我们的答案(或占位)。
     *
     * 抽出来是因为有**两个**入口:
     *  - `jb0.vd.g0/i0` —— 通用话术卡的产地,查看类走这条。
     *  - `FlowTemplateToastCard.bindView` —— 兜底。实测微信双开那句
     *    ("暂不支持微信双开功能")由 ControlWxAudioSdkOperation 产出,压根不经过 vd,
     *    于是卡片从没被认领,语音虽然被静音泵按住了,**文字还是原样留在屏幕上**。
     *    与其再去挂一个 Operation 类(混淆名每版都变,而且下次换个话术又是新的类),
     *    不如挂在所有 toast 卡都必经的渲染入口上。
     *
     * @param onlyIfUnclaimed 兜底入口用:这轮已经有认领好的卡就别抢,避免把正主顶掉。
     */
    private fun claimToastCard(
        card: Any,
        opDialogId: String,
        via: String,
        onlyIfUnclaimed: Boolean = false
    ): Boolean {
        // 注意:这类固定话术 Operation 的 dialogId 是合成字面量
        // ("fakeDialogId"/"fakeErrorDialogId"),**不是**真实对话 id,匹配不上很正常。
        // 用"静音泵是否在为当前交互运行"来判定这轮是否归我们。
        val ours = opDialogId in pendingViewAnswer || opDialogId in takeOver
        if (!ours && !isMutePumpActive()) return false

        val did = mutePumpDialogId.ifBlank { lastDialogId }
        if (did.isBlank()) return false

        val hash = System.identityHashCode(card)
        if (hash in commandeeredCards) return true          // already ours
        if (onlyIfUnclaimed && answerCards[did]?.get() != null) return false

        // 一轮里 vd 那个 hook 会命中**两个**对象:先是 card.e(没有 updateCardText),
        // 再是 FlowTemplateToastCard(有)。后登记的覆盖先登记的,所以实测一直是能更新的
        // 那张赢 —— 但那是顺序运气。顺序一旦反过来,我们就攥着一张改不了字的卡,
        // 答案永远不上屏,现象就是"这轮没有卡片"。只认真正能改字的。
        val updater = try {
            card.javaClass.getMethod("updateCardText", String::class.java)
        } catch (t: Throwable) {
            Log.i(TAG, "skip non-updatable card@$hash class=${card.javaClass.name}")
            return false
        }
        val text = aiAnswers[did] ?: THINKING_PLACEHOLDER
        answerCards[did] = WeakReference(card)
        ourCardTexts[hash] = text
        try {
            updater.invoke(card, text)
        } catch (t: Throwable) {
            Log.i(TAG, "commandeer: updateCardText failed: $t")
        }
        commandeeredCards.add(hash)
        Log.i(TAG, "commandeered toast card@$hash via=$via opDialogId=$opDialogId -> dialogId=$did")
        return true
    }

    /**
     * 读卡片当前绑定的 ViewHolder;没绑定返回 null。
     *
     * **不要按名字找**。原来写的是 `card.javaClass.getDeclaredField("X3")`,有两个坑叠在一起:
     * `getDeclaredField` 只查类自己不查父类,而 `X3` 是混淆产物、换个版本就变名字 ——
     * 两种情况都抛 NoSuchFieldException,被 catch 成 null,于是日志打出
     * "view holder is null",**和"字段确实是 null"完全分不开**。
     * 2026-07-20 就是栽在这:探针显示认领后 2ms X3 就是 null 且 15 秒不变,
     * 差点据此断定"卡片压根没绑过视图"并去改渲染逻辑 —— 但那个 null 有可能只是找错了字段。
     *
     * 现在按**行为**认:遍历整条继承链上的字段,谁的值身上有 `getToastTv()` 谁就是 ViewHolder。
     * 找到后记住那个 Field 直接复用(每次全扫太贵,这个方法在探针里 200ms 一次)。
     */
    private fun viewHolderOf(card: Any): Any? {
        cachedVhField?.let { f ->
            if (f.declaringClass.isInstance(card)) {
                return runCatching { f.get(card) }.getOrNull()
            }
        }
        var c: Class<*>? = card.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                val v = runCatching { f.isAccessible = true; f.get(card) }.getOrNull() ?: continue
                if (runCatching { v.javaClass.getMethod("getToastTv") }.isSuccess) {
                    if (cachedVhField == null) {
                        Log.i(TAG, "[vh] view holder field = ${c!!.simpleName}.${f.name}" +
                                " type=${v.javaClass.name}")
                    }
                    cachedVhField = f
                    return v
                }
            }
            c = c.superclass
        }
        return null
    }

    /** viewHolderOf 找到过的那个字段。null = 还没找到过(可能是没绑定,也可能是这个类真没有)。 */
    @Volatile
    private var cachedVhField: java.lang.reflect.Field? = null

    // 直接把已绑定 ViewHolder(字段 X3)里的 toastTv 设为可见并填文本
    private fun forceShowToastViewHolder(card: Any, text: String) {
        try {
            val vh = viewHolderOf(card)
            if (vh == null) {
                Log.i(TAG, "forceShow: no view holder bound, card@${System.identityHashCode(card)}" +
                        " class=${card.javaClass.name} cachedField=${cachedVhField?.name ?: "(none yet)"}")
                return
            }
            val tv = vh.javaClass.getMethod("getToastTv").invoke(vh) as? android.widget.TextView
            if (tv == null) {
                Log.i(TAG, "forceShow: toastTv is null, card@${System.identityHashCode(card)}")
                return
            }
            tv.visibility = android.view.View.VISIBLE
            tv.text = text
            Log.i(TAG, "forceShow: card@${System.identityHashCode(card)} tv set," +
                    " vis=${tv.visibility} h=${tv.height} parentVis=${(tv.parent as? android.view.View)?.visibility}")
        } catch (t: Throwable) {
            Log.i(TAG, "forceShowToastViewHolder failed: $t")
        }
    }

    // RN 的 JS 侧准备好接收数据时,会回调 native 的 rnStartReceiveInstruction(),
    // 此时卡片才把 Z3 置 true;在这之前 sendStreamDataToFront() 只会把 instruction 塞进队列,
    // 根本不会真正发给 JS。我们之前是绕过这个检查直接调 bridge.sendStreamData,
    // 所以如果 JS 还没 ready,数据就发到了空气里 —— 这正是"简单问题不显示"的原因。
    // 这里等 JS ready 之后再补一次注入。
    private fun hookRnJsReady(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(RN_CARD_CLASS)
        } catch (e: Throwable) {
            return
        }
        for (m in clazz.declaredMethods) {
            if (m.name == "rnStartReceiveInstruction") {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val card = param.thisObject
                                val dialogId = card.javaClass.getMethod("getDialogId")
                                    .invoke(card) as? String ?: return
                                Log.i(TAG, "[rn] js ready dialogId=$dialogId hash=${System.identityHashCode(card)}")
                                if (dialogId.isNotBlank()) {
                                    cardRefs[dialogId] = WeakReference(card)
                                    maybeInject(dialogId)
                                }
                            } catch (t: Throwable) {
                                Log.i(TAG, "[rn] js ready hook error: $t")
                            }
                        }
                    })
                    Log.i(TAG, "hooked rnStartReceiveInstruction")
                } catch (t: Throwable) {
                    Log.i(TAG, "hook rnStartReceiveInstruction fail: $t")
                }
            }
        }
    }

    // 读取卡片的 Z3 字段:RN JS 侧是否已经准备好接收数据
    private fun isRnFrontReady(dialogId: String): Boolean? {
        val card = cardRefs[dialogId]?.get() ?: return null
        return try {
            val f = card.javaClass.getDeclaredField("Z3")
            f.isAccessible = true
            f.getBoolean(card)
        } catch (t: Throwable) {
            Log.i(TAG, "read Z3 failed dialogId=$dialogId: $t")
            null
        }
    }

    // TemplateReactNativeCard.onStop() 里,如果判定要清内存(getRNStatus)就会调用
    // RN delegate(kj0.n).onPause(),把底层 RN/JS 实例暂停掉。
    // 小爱自己的"简单问答"经常几百毫秒内就认为对话结束、走到这个 onStop,
    // 而我们调用第三方 AI 还要等几秒钟——RN 实例一旦被暂停,
    // 之后再怎么 sendStreamData 画面都不会更新。
    // 所以:在还没等到我们自己答案注入完成之前,直接跳过这次 onStop,保持 RN 存活。
    private fun hookRnCardStop(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(RN_CARD_CLASS)
        } catch (e: Throwable) {
            Log.i(TAG, "$RN_CARD_CLASS not found: $e")
            return
        }
        for (m in clazz.declaredMethods) {
            if (m.name == "onStop") {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val dialogId = param.thisObject.javaClass.getMethod("getDialogId")
                                    .invoke(param.thisObject) as? String ?: return
                                if (dialogId in takeOver && dialogId !in injected) {
                                    Log.i(TAG, "suppress onStop (keep RN alive) dialogId=$dialogId")
                                    param.result = null
                                }
                            } catch (t: Throwable) {
                                // getDialogId 拿不到就不管,按原逻辑走
                            }
                        }
                    })
                } catch (t: Throwable) {
                    Log.i(TAG, "hook onStop fail: $t")
                }
            }
        }
    }

    // 诊断用:记录每次真实创建的卡片子类,对比"写诗"和"你是谁"两种场景下卡片创建序列的差异
    private fun hookCardBaseDiagnostic(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass("com.xiaomi.voiceassistant.card.a")
        } catch (e: Throwable) {
            Log.i(TAG, "com.xiaomi.voiceassistant.card.a not found: $e")
            return
        }
        XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val dialogId = try {
                    param.thisObject.javaClass.getMethod("getDialogId").invoke(param.thisObject)
                } catch (t: Throwable) { null }
                Log.i(TAG, "[card] new ${param.thisObject.javaClass.name} dialogId=$dialogId hash=${System.identityHashCode(param.thisObject)}")
            }
        })
        for (name in listOf("onCardVisible", "onCardInvisible", "onCardDetached", "removeCard")) {
            for (m in clazz.declaredMethods) {
                if (m.name == name) {
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val hash = System.identityHashCode(param.thisObject)
                                if (name == "onCardDetached") detachedCards.add(hash)
                                Log.i(TAG, "[card] $name hash=$hash class=${param.thisObject.javaClass.name}")
                            }
                        })
                    } catch (t: Throwable) {
                        // 某些方法可能不存在于基类,忽略
                    }
                }
            }
        }
    }

    // 拦截"用户问了什么" —— OperationManager.setQueryInfo(dialogId, query, extraInfo),比具体某个卡片的构造函数更早更通用
    private fun hookOperationManager(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(OPERATION_MANAGER_CLASS)
        } catch (e: Throwable) {
            Log.i(TAG, "$OPERATION_MANAGER_CLASS not found: $e")
            return
        }
        val method = try {
            clazz.getDeclaredMethod("setQueryInfo", String::class.java, String::class.java, JSONObject::class.java)
        } catch (e: Throwable) {
            Log.i(TAG, "setQueryInfo method not found: $e")
            return
        }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val dialogId = param.args[0] as? String ?: return
                    val queryText = param.args[1] as? String ?: return
                    if (dialogId.isBlank() || queryText.isBlank()) return

                    val ctx = currentApplicationContext()
                    val config = if (ctx != null) ConfigClient.read(ctx) else null

                    // 换了一句话才停泵。不能按 dialogId 判 —— 同一句话小爱会派发多个 dialogId,
                    // 那样会把 383ms 前刚为这句话起的泵又停掉。
                    if (queryText != lastQueryText) stopMutePump()

                    // 先记录最近问话 + 配置 —— 跳转拦截独立于 AI 接管开关,始终要有这份信息
                    lastQueryText = queryText
                    lastQueryTime = System.currentTimeMillis()
                    lastDialogId = dialogId
                    if (config != null) lastConfig = config

                    // 以下是 AI 文本接管,需要功能启用
                    if (config == null || !config.enabled || !config.isUsable) {
                        return // 未启用 AI 接管,保持原生行为(但跳转拦截仍可生效)
                    }
                    // 白名单直通:这句命中用户自己配的正则 → 整句都不接管,原生行为原样放行。
                    // 用在"小爱自己处理得又快又好、我们没有对应工具"的场景(比如点播歌曲) ——
                    // 硬接管只会白起一次泵、白调一次注定失败的模型,日志里还多一条误导性的"工具调用失败"。
                    if (isAiTakeoverSkip(queryText, config)) {
                        Log.i(TAG, "skip takeover (whitelist pattern matched): $queryText")
                        return
                    }
                    // 是"查看类"候选 → 预标记,以便它的 FlowTemplateToastCard 一 bindView 就被我们占位撑开。
                    // 用 ...Now() 而不是 isViewBlockCandidate(queryText):此刻 lastQueryText 已经是
                    // 改写版,而 ASR 原文还在 lastAsrText 里,两个都要看(见 lastAsrText 注释)。
                    if (viewBlockCandidateNow(config)) {
                        pendingViewAnswer.add(dialogId)
                        // 这里**不**预先自建占位卡。优先接管小爱自己那张话术卡
                        // (hookToastCard):它的容器和显示时机都是对的,自建的插进去不上屏。
                        // 小爱这轮如果压根不出话术卡,答案到达时 ensureAnswerCard 再兜底自建。
                        // 这一刻就已经确定"这句我们自己答",立刻起泵按住小爱的嘴。
                        // 真机日志显示不这么做的后果:小爱 450ms 后就 onRealStart,完整念完
                        // 4.9 秒(onPlayFinish isInterrupted=false),9 秒后我们才开口 —— 两个答案
                        // 一前一后全放出来。以前泵接在 takeOver 上,而这条路径根本不经过 takeOver。
                        if (config.speakAnswer) startMutePump(dialogId)
                    }
                    // 发消息类:小爱这轮注定失败(双开/仅语音),我们接管。
                    // 同样要起泵 —— 否则它那句"暂不支持微信双开功能"会照常念出来。
                    if (sendMessageCommandNow()) {
                        pendingViewAnswer.add(dialogId)
                        if (config.speakAnswer) startMutePump(dialogId)
                        Log.i(TAG, "send-message command, taking over: $queryText")
                    }
                    if (queryTexts.containsKey(dialogId)) return // 同一个 dialogId 只处理一次
                    queryTexts[dialogId] = queryText

                    // 按「一次问话」归拢:同一句话小爱可能派发多个 dialogId,不能各调各的模型
                    val key = utteranceKeyFor(queryText)
                    utteranceDialogs.getOrPut(key) { ConcurrentHashMap.newKeySet() }.add(dialogId)
                    Log.i(TAG, "query captured: dialogId=$dialogId text=$queryText key=$key")

                    // 这次问话的答案已经拿到了 → 直接复用,别再调一次模型
                    val ready = utteranceAnswers[key]
                    if (ready != null) {
                        Log.i(TAG, "reuse answer for new dialogId=$dialogId key=$key")
                        applyAnswer(key, dialogId, ready)
                        return
                    }
                    // 已经有一次调用在飞了 → 等它回来统一分发
                    if (!utteranceCalling.add(key)) {
                        Log.i(TAG, "AI call already in flight, dialogId=$dialogId joins key=$key")
                        return
                    }
                    startAiCall(key, queryText, config)
                } catch (t: Throwable) {
                    Log.i(TAG, "hookOperationManager error: $t")
                }
            }
        })
    }

    // 记录 TemplateReactNativeCard 实例(按 dialogId),以便接管时能强制显示卡片
    private fun hookRnCard(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(RN_CARD_CLASS)
        } catch (e: Throwable) {
            Log.i(TAG, "$RN_CARD_CLASS not found: $e")
            return
        }
        for (name in listOf("bindView", "onCardAttached")) {
            for (m in clazz.declaredMethods) {
                if (m.name == name) {
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    val card = param.thisObject
                                    val getDialogId = card.javaClass.getMethod("getDialogId")
                                    val dialogId = getDialogId.invoke(card) as? String ?: return
                                    if (dialogId.isNotBlank()) {
                                        cardRefs[dialogId] = WeakReference(card)
                                        maybeForceShow(dialogId)
                                    }
                                } catch (t: Throwable) {
                                    // getDialogId 可能暂时拿不到,忽略
                                }
                            }
                        })
                    } catch (t: Throwable) {
                        Log.i(TAG, "hook $name fail: $t")
                    }
                }
            }
        }

    }

    // 拦截 RN 桥接的 sendStreamData(type, content):
    //  - 对已接管的 dialogId,拦下真实内容(不让原方法执行,即不显示小爱自己的回答)
    //  - 我们自己发起的调用(injectingNow=true)直接放行
    private fun hookBridge(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(BRIDGE_CLASS)
        } catch (e: Throwable) {
            Log.i(TAG, "$BRIDGE_CLASS not found: $e")
            return
        }
        val method = try {
            clazz.getDeclaredMethod("sendStreamData", String::class.java, String::class.java)
        } catch (e: Throwable) {
            Log.i(TAG, "sendStreamData method not found: $e")
            return
        }
        sendStreamDataMethod = method
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    if (injectingNow.get() == true) return
                    val type = param.args[0] as? String ?: return
                    val content = (param.args[1] as? String).orEmpty()
                    val dialogId = extractDialogId(content)

                    // "Finish" / "cancel" 这类控制信令的 content 是空的,里面没有 dialog_id。
                    // 小爱回答简单问题时不到一秒就结束,会抢在我们的答案注入之前把 Finish 发给 JS,
                    // 前端收到后就把这张卡的流关掉了 —— 之后我们再 sendStreamData 前端直接忽略,
                    // Java 层却不会报任何错(这就是"简单问题不显示文字"的真正原因)。
                    // 所以只要还有接管中、尚未注入完成的对话,就先把这些控制信令拦下来。
                    if (dialogId == null) {
                        if (takeOver.any { it !in injected }) {
                            Log.i(TAG, "suppress control signal type=$type (takeover pending)")
                            param.result = null
                        }
                        return
                    }

                    if (queryTexts.containsKey(dialogId) && dialogId !in takeOver) {
                        takeOver.add(dialogId)
                        Log.i(TAG, "take over dialogId=$dialogId")
                        // 这里原本只打一发 muteAudio()。真机日志显示那是在赌运气:
                        //   05.960 小爱 startPlay_url: main / 06.182 onRealStart(真出声)
                        //   06.183 我们 stopPlayAndClearQueue / 06.184 onPlayFinish isInterrupted=true
                        // 只差 2ms 掐住。小爱的 TTS 是云端流式回来的,起播晚个几百毫秒
                        // 这一发就打空了 —— 现象就是"替换过的和没替换过的一起放"。
                        // 换成持续钳制主音轨,不管它什么时候起播都能在 250ms 内按掉。
                        startMutePump(dialogId)
                        maybeForceShow(dialogId)
                    }
                    if (dialogId in takeOver && type == "instruction") {
                        bridgeRefs[dialogId] = WeakReference(param.thisObject)
                        val filtered = filterOutToastStream(content)
                        if (filtered == null) {
                            Log.i(TAG, "suppress whole content dialogId=$dialogId (nothing left after filtering)")
                            param.result = null
                        } else if (filtered != content) {
                            Log.i(TAG, "filtered ToastStream out, keep rest dialogId=$dialogId: $filtered")
                            param.args[1] = filtered
                        } else {
                            Log.i(TAG, "no ToastStream found, pass through dialogId=$dialogId")
                        }
                        maybeInject(dialogId)
                    } else if (dialogId in takeOver) {
                        // 非 instruction 类型(比如语音相关的其它 type)一律拦掉
                        Log.i(TAG, "suppress non-instruction type=$type dialogId=$dialogId")
                        param.result = null
                    }
                } catch (t: Throwable) {
                    Log.i(TAG, "hookBridge error: $t")
                }
            }
        })
    }

    // content 可能是单个 instruction 的 JSON 对象,也可能是打包了多条 instruction 的 JSON 数组。
    // 把里面 header.name == "ToastStream" 的条目去掉(那是小爱真实的大模型文字内容),
    // 保留其余的(比如 UpdateStreamProperties,前端用它来初始化"这是一个大模型卡片"的渲染状态)。
    // 返回 null 表示过滤完什么都不剩,应该整个拦掉。
    private fun filterOutToastStream(content: String): String? {
        val trimmed = content.trim()
        try {
            if (trimmed.startsWith("[")) {
                val arr = org.json.JSONArray(trimmed)
                val kept = org.json.JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optJSONObject("header")?.optString("name")
                    if (name != "ToastStream") kept.put(obj)
                }
                return if (kept.length() == 0) null else kept.toString()
            } else if (trimmed.startsWith("{")) {
                val obj = org.json.JSONObject(trimmed)
                val name = obj.optJSONObject("header")?.optString("name")
                return if (name == "ToastStream") null else content
            }
        } catch (t: Throwable) {
            Log.i(TAG, "filterOutToastStream parse error: $t")
        }
        return content
    }

    // 强制把卡片显示出来,不管小爱自己内部判不判定要展示(应对 simply-speak 类快速回答不出卡片的情况)。
    // onCardVisible 是通用基类方法,RN 卡片实际渲染是否存活看的是 onResume/onStop
    // (内部会调用 RN delegate 的 onResume/onPause),两个都调一下,确保 RN 侧没被暂停。
    private fun maybeForceShow(dialogId: String) {
        if (dialogId !in takeOver) return
        val card = cardRefs[dialogId]?.get() ?: return
        Handler(Looper.getMainLooper()).post {
            try {
                val m = card.javaClass.getMethod("onCardVisible")
                m.invoke(card)
                Log.i(TAG, "forced onCardVisible dialogId=$dialogId")
            } catch (t: Throwable) {
                Log.i(TAG, "forceShow failed dialogId=$dialogId: $t")
            }
            try {
                val m = card.javaClass.getMethod("onResume")
                m.invoke(card)
                Log.i(TAG, "forced onResume dialogId=$dialogId")
            } catch (t: Throwable) {
                Log.i(TAG, "forceResume failed dialogId=$dialogId: $t")
            }
        }
    }

    // 开启静音泵:在 windowMs 内每 250ms 掐一次主音轨。
    // 早先的做法是拦截当下打 0/350/900/1600ms 四发定时补刀,但小爱的答案是云端流式回来的,
    // 起播时刻不确定,超过 1.6s 才开口就完全漏掉 —— 现象就是"替换过的和没替换过的一起放"。
    // 改成持续钳制。只掐主音轨,我们自己的答案在 TOAST_STREAM_TTS 那条音轨上,不受影响。
    private fun startMutePump(dialogId: String, windowMs: Long = 15_000L) {
        mutePumpDialogId = dialogId
        mutePumpUntil = System.currentTimeMillis() + windowMs
        if (mutePumpRunning) return   // 已有泵在跑,上面刷新了截止时间就够了
        mutePumpRunning = true
        val startedAt = System.currentTimeMillis()
        val h = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() >= mutePumpUntil) {
                    mutePumpRunning = false
                    Log.i(TAG, "mute pump stopped dialogId=$mutePumpDialogId")
                    return
                }
                try { muteAudio() } catch (t: Throwable) { }
                // 开头两秒密集敲(小爱基本都在这个窗口起播),之后放缓省开销。
                // 250ms 的间隔会漏出一个字的音("应用已经…"能听到"应"),60ms 基本听不出来。
                val elapsed = System.currentTimeMillis() - startedAt
                h.postDelayed(this, if (elapsed < 2_000L) 60L else 250L)
            }
        }
        h.post(tick)
        Log.i(TAG, "mute pump started dialogId=$dialogId window=${windowMs}ms")
    }

    // 泵是否正在为当前这次交互运行。等价于"这轮我们接管了,小爱该闭嘴"。
    private fun isMutePumpActive(): Boolean =
        mutePumpUntil > System.currentTimeMillis()

    // 停泵:新一轮提问时调用,别把下一次交互的正常播报也掐了
    private fun stopMutePump() {
        if (mutePumpUntil != 0L) Log.i(TAG, "mute pump cancelled (new query)")
        mutePumpUntil = 0L
        mutePumpDialogId = ""
    }

    // 用小爱自己的 TTS 念出我们的答案。
    // 主路径 n1.speakTts 播在 TOAST_STREAM_TTS 音轨上,和静音泵掐的主音轨互不干扰,
    // 所以泵可以一直跑着,不用为了让我们开口而放开对小爱的钳制。
    private fun speakAnswer(key: String, answer: String) {
        val cfg = lastConfig ?: return
        if (!cfg.enabled || !cfg.speakAnswer) return
        // 只念我们真正接管了的对话 —— 没拦下小爱的话,它自己已经在说了,再念就是两个人抢话。
        // 这次问话名下任何一个 dialogId 被接管,就算接管。
        val dialogIds = utteranceDialogs[key].orEmpty()
        if (dialogIds.none { it in pendingViewAnswer || it in takeOver }) {
            Log.i(TAG, "not taken over, skip speaking key=$key")
            return
        }
        // 一次问话只出一次声。同一句话的多个 dialogId 走到这里只有第一个能过。
        if (!spokenUtterances.add(key)) {
            Log.i(TAG, "already spoken, skip key=$key")
            return
        }
        val dialogId = dialogIds.firstOrNull { it in pendingViewAnswer || it in takeOver } ?: return

        val speakable = toSpeakable(answer)
        if (speakable.isBlank()) {
            Log.i(TAG, "nothing speakable after cleanup key=$key")
            spokenUtterances.remove(key)
            return
        }
        val cl = targetClassLoader ?: return
        Handler(Looper.getMainLooper()).post {
            // 先掐掉小爱可能还在念的兜底话术,再开口,避免两句话叠在一起
            muteAudio()
            // 不管念得成不成,都要把 b2 换成我们的文本:否则用户点卡片右下角喇叭
            // 重播时放的仍是小爱的原始答案,声音和屏幕上的字对不上。
            syncSpeakContent(cl, dialogId, speakable)
            if (speakViaToastPlayer(cl, speakable)) {
                Log.i(TAG, "spoke answer via n1 dialogId=$dialogId len=${speakable.length}")
                return@post
            }
            // 兜底路径 u1.speak 播的是**主音轨** —— 正是静音泵一直在掐的那条。
            // 不先停泵的话我们自己刚开口就被自己掐掉。代价是小爱要是这时也在说话就盖不住了,
            // 但总比我们完全不出声强。
            stopMutePump()
            if (speakViaEngine(cl, speakable)) {
                Log.i(TAG, "spoke answer via u1 (mute pump released) dialogId=$dialogId len=${speakable.length}")
                return@post
            }
            Log.i(TAG, "both TTS paths failed key=$key dialogId=$dialogId")
            spokenUtterances.remove(key)
        }
    }

    // 把 b2(SpeakContentManager)的内容替换成我们的答案。
    // 必须先 clean():addFragment 只在 dialogId **变化**时才重置缓冲区,同一个 dialogId
    // 直接 append 会把我们的答案接在小爱原文后面。clean() 把 curDialogId 置 null,
    // 之后 addFragment 就会重建缓冲区。
    private fun syncSpeakContent(cl: ClassLoader, dialogId: String, text: String) {
        try {
            val clazz = cl.loadClass(SPEAK_CONTENT_CLASS)
            val instance = kotlinObjectInstance(clazz) ?: return
            clazz.getMethod("clean").invoke(instance)
            clazz.getMethod("addFragment", String::class.java, String::class.java)
                .invoke(instance, dialogId, text)
            Log.i(TAG, "speak content synced dialogId=$dialogId")
        } catch (t: Throwable) {
            Log.i(TAG, "syncSpeakContent failed dialogId=$dialogId: $t")
        }
    }

    // 走卡片自己的 ToastStreamPlayer。会话已经拆掉也能播,而且播放状态监听会驱动喇叭图标动画。
    private fun speakViaToastPlayer(cl: ClassLoader, text: String): Boolean {
        return try {
            val clazz = cl.loadClass(TOAST_STREAM_PLAYER_CLASS)
            val instance = kotlinObjectInstance(clazz) ?: return false
            clazz.getMethod("stopPlay").invoke(instance)
            // speakTts 成功返回合成事件 id,文本为空时返回 null
            val speakId = clazz.getMethod("speakTts", String::class.java).invoke(instance, text)
            speakId != null
        } catch (t: Throwable) {
            Log.i(TAG, "speakViaToastPlayer failed: $t")
            false
        }
    }

    // 取 Kotlin object 单例。字段名(b2.f48589a / n1.f95882a)是混淆产物,版本一变就失效,
    // 所以按"类型等于类自身的静态字段"来找,不写死名字。
    private fun kotlinObjectInstance(clazz: Class<*>): Any? {
        try {
            val f = clazz.getDeclaredField("INSTANCE")
            f.isAccessible = true
            f.get(null)?.let { return it }
        } catch (t: Throwable) { }
        for (f in clazz.declaredFields) {
            if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            if (f.type != clazz) continue
            try {
                f.isAccessible = true
                f.get(null)?.let { return it }
            } catch (t: Throwable) { }
        }
        Log.i(TAG, "no singleton instance found for ${clazz.name}")
        return null
    }

    // 兜底:小爱念本地答案那条路。引擎(u1.f56104f)为 null 时它会静默丢弃,所以只当备选。
    private fun speakViaEngine(cl: ClassLoader, text: String): Boolean {
        return try {
            val clazz = cl.loadClass(TTS_BRIDGE_CLASS)
            val instance = clazz.getMethod("getInstance").invoke(null) ?: return false
            clazz.getMethod("speak", String::class.java).invoke(instance, text)
            true
        } catch (t: Throwable) {
            Log.i(TAG, "speakViaEngine failed: $t")
            false
        }
    }

    // 把 markdown 答案压成适合朗读的纯文本。
    // 代码块整段丢掉(念代码毫无意义),链接只留锚文本,各种标记符去掉,最后限长。
    private fun toSpeakable(raw: String): String {
        var s = raw
        s = s.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " ")   // 围栏代码块
        s = s.replace(Regex("`([^`]*)`"), "$1")                               // 行内代码
        s = s.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), " ")                // 图片
        s = s.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")              // 链接留锚文本
        s = s.replace(Regex("https?://\\S+"), " ")                            // 裸链接
        s = s.replace(Regex("^\\s{0,3}#{1,6}\\s*", RegexOption.MULTILINE), "")// 标题
        s = s.replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")     // 无序列表
        s = s.replace(Regex("^\\s*>\\s?", RegexOption.MULTILINE), "")         // 引用
        s = s.replace(Regex("\\*{1,3}([^*]+)\\*{1,3}"), "$1")                 // 粗体/斜体
        s = s.replace(Regex("~~([^~]*)~~"), "$1")                             // 删除线
        s = s.replace(Regex("^\\s*[-*_]{3,}\\s*$", RegexOption.MULTILINE), " ")// 分隔线
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex("\\s*\\n\\s*"), "，")   // 换行读成停顿,别让 TTS 一口气连读
        s = s.replace(Regex("，{2,}"), "，").trim().trim('，')
        return if (s.length <= MAX_SPEAK_CHARS) s else s.take(MAX_SPEAK_CHARS).trimEnd('，') + "……详细内容请看屏幕"
    }

    // 静音:停掉**除我们自己那条之外**的所有音轨。
    // v20.e 内部有个静态注册表 ConcurrentHashMap<String, e>,实测同时存在五条:
    //   main / tts / toastStreamTts / getAuthorization / getNoAccountAuthorization
    // 早先这里只掐 getMainAudioTrack()(即 "main" 一条),所以走别的音轨的话术照样播得出来
    // —— 比如"应用已经不支持这个功能了"这类固定话术。
    // 我们自己的答案在 OUR_AUDIO_TRACK 上(n1.speakTts 用的那条),按名字跳过它。
    private fun muteAudio() {
        try {
            val ctx = currentApplicationContext() ?: return
            val clazz = ctx.classLoader.loadClass(AUDIO_TRACK_MANAGER_CLASS)
            val tracks = allAudioTracks(clazz)
            if (tracks.isEmpty()) {
                muteMainTrackOnly(clazz)   // 注册表拿不到就退回老做法,至少掐住主音轨
                return
            }
            var stopped = 0
            for ((name, track) in tracks) {
                if (name == OUR_AUDIO_TRACK) continue   // 那是我们自己在说话
                if (stopTrack(track)) stopped++
            }
            Log.i(TAG, "muted $stopped/${tracks.size} audio tracks (kept $OUR_AUDIO_TRACK)")
        } catch (t: Throwable) {
            Log.i(TAG, "muteAudio failed: $t")
        }
    }

    // 取 v20.e 的静态音轨注册表。字段名是混淆产物,按"静态 Map 字段"找,不写死名字。
    @Suppress("UNCHECKED_CAST")
    private fun allAudioTracks(clazz: Class<*>): Map<String, Any> {
        for (f in clazz.declaredFields) {
            if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            if (!Map::class.java.isAssignableFrom(f.type)) continue
            try {
                f.isAccessible = true
                val map = f.get(null) as? Map<*, *> ?: continue
                // 注册表的键是音轨名(String),值是 v20.e 实例
                val out = HashMap<String, Any>()
                for ((k, v) in map) {
                    if (k is String && v != null && clazz.isInstance(v)) out[k] = v
                }
                if (out.isNotEmpty()) return out
            } catch (t: Throwable) { }
        }
        return emptyMap()
    }

    private fun stopTrack(track: Any): Boolean {
        var ok = false
        for (name in listOf("stopPlayAndClearQueue", "stop")) {
            try {
                track.javaClass.getMethod(name).invoke(track)
                ok = true
            } catch (t: Throwable) {
                // 忽略,尝试下一个方法名
            }
        }
        return ok
    }

    private fun muteMainTrackOnly(clazz: Class<*>) {
        try {
            val track = clazz.getMethod("getMainAudioTrack").invoke(null) ?: return
            stopTrack(track)
            Log.i(TAG, "muted main audio track (registry unavailable)")
        } catch (t: Throwable) {
            Log.i(TAG, "muteMainTrackOnly failed: $t")
        }
    }

    private fun extractDialogId(content: String): String? {
        val m = Regex("\"dialog_id\"\\s*:\\s*\"([a-zA-Z0-9]+)\"").find(content)
        return m?.groupValues?.get(1)
    }

    // 同一句问话在时间窗内复用同一个 key;超窗就算新的一次交互。
    private fun utteranceKeyFor(text: String): String {
        val now = System.currentTimeMillis()
        val prev = utteranceLastSeen[text]
        if (prev != null && now - prev.second < UTTERANCE_WINDOW_MS) {
            utteranceLastSeen[text] = prev.first to now
            return prev.first
        }
        val key = "$text#$now"
        utteranceLastSeen[text] = key to now
        return key
    }

    // 一次问话只调一次模型;答案回来后分发给这次问话名下的所有 dialogId。
    private fun startAiCall(key: String, queryText: String, config: AiConfig) {
        Thread {
            try {
                Log.i(TAG, "calling AI for key=$key ...")
                // Context 给工具用(装了什么应用、启动应用、调音量都要它)。
                // 拿不到也能跑,只是那几个工具会返回 "no context available"。
                //
                // allowMutating:这轮到底归不归我们。**在工具执行的那一刻**才求值 ——
                // 接管信号(takeOver / 静音泵)常常比模型调用晚到,一开始就判会误杀。
                // 不这么关的话,像"打开微信帮我给X发信息"这种命中放行词、本该由小爱
                // 自己处理的话,我们的模型也会并行跑一遍并真的去 launch_app。
                // 上下文在**发请求前**取一次:一小时窗口内的旧问答,不含本轮。
                // 开关关着时顺手清空:设置页在**另一个进程**,那边调 clear() 清不到这份内存,
                // 不在这清的话关了再开会接上一小时前的话茬。
                val history = if (config.contextEnabled) {
                    ChatHistory.recent()
                } else {
                    ChatHistory.clear()
                    emptyList()
                }
                val answer = AiClient.chat(config, queryText, currentApplicationContext(), history) {
                    val ours = utteranceDialogs[key].orEmpty().any {
                        it in takeOver || it in pendingViewAnswer
                    }
                    ours || isMutePumpActive()
                }
                Log.i(TAG, "AI answer ready key=$key: $answer")
                // 软失败([AiClient.FAILED_ANSWER])要当失败处理,不能缓存也不能记历史。
                // 它是**正常返回**的字符串,不像硬失败会抛异常走下面的 catch,所以得显式认。
                // 缓存了的后果见 FAILED_ANSWER 的注释:用户重问命中 15 秒缓存,重播失败、
                // 不重试。记历史的后果是这句废话会跟着进后续几轮的上下文。
                val failed = answer == AiClient.FAILED_ANSWER
                if (failed) Log.w(TAG, "soft failure, not cached/recorded key=$key")
                if (!failed) {
                    utteranceAnswers[key] = answer
                    // 答成了才记进历史 —— 失败的轮次不该污染后面的上下文。
                    // 一次问话只走到这里一次(utteranceCalling 挡住了并发,答案命中
                    // utteranceAnswers 缓存的重复提问根本不会再调 startAiCall),所以不会重复记。
                    if (config.contextEnabled) ChatHistory.record(queryText, answer)
                }
                // 先把卡片/注入/历史都落好,再开口,让画面和声音基本同时到
                for (id in utteranceDialogs[key].orEmpty()) {
                    applyAnswer(key, id, answer)
                }
                // 播报按 key 走,保证一次问话只出一次声(哪怕有好几个 dialogId)
                speakAnswer(key, answer)
            } catch (t: Throwable) {
                Log.i(TAG, "AI call failed key=$key: $t")
            } finally {
                utteranceCalling.remove(key)
            }
        }.start()
    }

    // 把答案落到某个具体 dialogId 上:注入 RN、更新卡片、写历史。
    // 播报不在这里 —— 那是按 key 的,否则同一句话有几个 dialogId 就念几遍。
    private fun applyAnswer(key: String, dialogId: String, answer: String) {
        try {
            aiAnswers[dialogId] = answer
            maybeInject(dialogId)
            // 查看类被拦的对话:答案就绪后更新(或补建)答案卡
            if (dialogId in pendingViewAnswer) ensureAnswerCard(dialogId)
            // 历史库同步换成我们的答案,否则回 App 看历史还是那句兜底话术
            writeAnswerToHistory(dialogId, answer)
        } catch (t: Throwable) {
            Log.i(TAG, "applyAnswer failed dialogId=$dialogId: $t")
        }
    }

    // AI 回复 + bridge 实例都齐了才注入,只注入一次
    private fun maybeInject(dialogId: String) {
        if (dialogId in injected) return
        val answer = aiAnswers[dialogId] ?: return
        val bridge = bridgeRefs[dialogId]?.get() ?: return
        val method = sendStreamDataMethod ?: return

        // RN 的 JS 侧还没 ready 的话,现在发过去等于石沉大海。
        // 先不标记 injected,等 rnStartReceiveInstruction 回调时会再调一次这里。
        val ready = isRnFrontReady(dialogId)
        if (ready == false) {
            Log.i(TAG, "RN front not ready yet, defer inject dialogId=$dialogId")
            return
        }
        Log.i(TAG, "RN front ready=$ready, injecting dialogId=$dialogId")

        if (!injected.add(dialogId)) return

        Handler(Looper.getMainLooper()).post {
            // 兜底:万一 RN 实例在这之前已经被 onStop/onPause 掉了,注入前再唤醒一次
            try {
                val card = cardRefs[dialogId]?.get()
                if (card != null) {
                    try { card.javaClass.getMethod("onResume").invoke(card) } catch (t: Throwable) { }
                }
            } catch (t: Throwable) { }

            try {
                injectingNow.set(true)
                val transactionId = UUID.randomUUID().toString().replace("-", "")
                val instrId = UUID.randomUUID().toString().replace("-", "")
                val contentPayload = JSONObject().apply {
                    put("header", JSONObject().apply {
                        put("name", "ToastStream")
                        put("namespace", "Template")
                        put("dialog_id", dialogId)
                        put("id", instrId)
                        put("transaction_id", transactionId)
                    })
                    put("payload", JSONObject().apply { put("markdown_text", answer) })
                }
                method.invoke(bridge, "instruction", contentPayload.toString())

                val finalPayload = JSONObject().apply {
                    put("header", JSONObject().apply {
                        put("id", UUID.randomUUID().toString())
                        put("dialog_id", dialogId)
                    })
                    put("payload", JSONObject().apply { put("markdown_text", "<FINAL>") })
                }
                method.invoke(bridge, "instruction", finalPayload.toString())
                method.invoke(bridge, "Finish", "")
                Log.i(TAG, "injected AI answer via bridge, dialogId=$dialogId")
            } catch (t: Throwable) {
                Log.i(TAG, "inject failed dialogId=$dialogId: $t")
                injected.remove(dialogId)
            } finally {
                injectingNow.set(false)
            }

            // 备用渲染通道:直接调用 TemplateReactNativeCard.p1(JSONObject),
            // 直接设置卡片内部的 totalText,应对"简单问答"这类不走 ToastStream 流式通道的场景
            try {
                val card = cardRefs[dialogId]?.get()
                if (card != null) {
                    val p1 = card.javaClass.getDeclaredMethod("p1", JSONObject::class.java)
                    p1.isAccessible = true
                    val obj = JSONObject().apply {
                        put("totalText", answer)
                        put("isLlmContentDisplayComplete", true)
                        put("isIllegalContent", false)
                    }
                    p1.invoke(card, obj)
                    Log.i(TAG, "fallback p1() render dialogId=$dialogId")
                }
            } catch (t: Throwable) {
                Log.i(TAG, "fallback p1() render failed dialogId=$dialogId: $t")
            }
        }
    }

    private fun currentApplicationContext(): Context? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThreadClass.getMethod("currentApplication")
            currentApplication.invoke(null) as? Context
        } catch (t: Throwable) {
            Log.i(TAG, "currentApplicationContext failed: $t")
            null
        }
    }
}
