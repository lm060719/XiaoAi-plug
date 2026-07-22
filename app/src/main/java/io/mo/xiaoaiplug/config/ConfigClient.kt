package io.mo.xiaoaiplug.config

import android.content.Context
import android.net.Uri
import android.os.Bundle

data class AiConfig(
    // 服务商 key(见 AiProvider.key)。空串 = 旧存档,按 OpenAI 兼容处理。
    val provider: String,
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
    val enabled: Boolean,
    // 「查看类」语音不跳转设置页开关(默认开)
    val blockViewJump: Boolean,
    // 放行词:命中这些词才允许跳转(如"打开"),多个用逗号/空格分隔
    val jumpAllowWords: String,
    // 小爱答不上来时跳全局搜索的兜底,拦掉改由 AI 回答(默认开)
    val blockWebSearch: Boolean,
    // 放行词:用户明确要求"搜索"时不拦,让它照常跳搜索
    val webSearchAllowWords: String,
    // 用小爱自己的 TTS 念出我们的答案(默认开)。关掉的话就只有卡片、不出声。
    val speakAnswer: Boolean,
    // 允许模型调用的工具,逗号分隔;空串 = 全开
    val enabledTools: String = "",
    // 原生 function calling(默认开,端点不支持会自动降级)
    val useNativeTools: Boolean = true,
    // 多轮上下文:带上最近一小时内的问答(默认开)。见 ChatHistory。
    val contextEnabled: Boolean = true,
    // 白名单直通(默认开):问话命中 [skipTakeoverPattern] 就完全不接管,原生行为照旧。
    // 用来处理"这句话小爱自己处理得又快又好,我们没有对应工具,或者两边会各干各的"的场景 ——
    // 点播歌曲我们没有对应工具,硬接管只会白白调用一次注定失败的模型;设闹钟/提醒更糟,
    // 小爱自己会真的设一个,我们接管后模型又用 run_shell 真的设了一个,变成两个闹钟。
    val skipTakeoverEnabled: Boolean = true,
    // 正则表达式,命中即跳过接管。留空即使开关开着也不生效(避免空正则误判成"全部匹配"),
    // 且留空时按"用默认"处理,见 [DEFAULT_SKIP_TAKEOVER_PATTERN]。
    val skipTakeoverPattern: String = DEFAULT_SKIP_TAKEOVER_PATTERN,
    // 无障碍被 MIUI 清后台摘掉后自动写回。见 AccessibilityGuard。
    // **默认关**:它要动系统设置、要 root,不该在用户没点头之前就自己开着。
    val autoFixAccessibility: Boolean = false
) {
    /** 当前服务商。旧存档(provider 为空)落到 [AiProvider.DEFAULT]。 */
    val aiProvider: AiProvider get() = AiProvider.fromKey(provider)

    /**
     * 实际发请求用的地址/模型:用户没填就用服务商的默认值。
     *
     * 调用方一律用这两个,别直接读 [endpoint] / [model] —— 那两个是**用户输入的原文**,
     * 空串是合法状态(表示"用默认"),拿去发请求会得到一个空 URL。
     */
    val effectiveEndpoint: String get() = endpoint.ifBlank { aiProvider.defaultEndpoint }
    val effectiveModel: String get() = model.ifBlank { aiProvider.defaultModel }

    /**
     * 实际发给模型的系统提示词:用户留空就用内置的 [DEFAULT_SYSTEM_PROMPT]。
     *
     * 同样别直接读 [systemPrompt] —— 留空是"用默认",不是"不要系统提示词"。
     */
    val effectiveSystemPrompt: String get() = systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }

    /**
     * 配置是否齐全到能真发一次请求。
     *
     * 地址和模型都有默认值兜底了,所以唯一还可能缺的就是密钥 ——
     * 各处"是否已配置"的判断都该问这个,而不是再去看 endpoint 空不空。
     */
    val isUsable: Boolean get() = apiKey.isNotBlank()
}

// 放行词默认值:只有说"打开/开启/进入/去..."才跳转
const val DEFAULT_JUMP_ALLOW_WORDS = "打开,开启,进入,去,跳转,启动"

// 用户明确要搜索时放行,不然"搜一下明天天气"也会被我们截走
const val DEFAULT_WEB_SEARCH_ALLOW_WORDS = "搜索,搜一下,搜下,搜搜,百度,上网搜,网上搜"

// 白名单直通默认正则:小爱自己处理得又快又好(或者本模块压根没有对应工具)的设备指令类问话。
// 闹钟/提醒尤其不能接管——小爱自己会真的设一个,我们接管后模型八成也会用 run_shell 真设一个,
// 变成两个闹钟;播放/上一首/下一首/暂停同理,本模块没有"点播指定歌曲"的工具,硬接管只会
// 白调一次注定失败的模型。
const val DEFAULT_SKIP_TAKEOVER_PATTERN = "闹钟|提醒|播放|上一首|下一首|暂停"

object ConfigClient {

    private val uri: Uri = Uri.parse("content://${ConfigProvider.AUTHORITY}")

    /**
     * 读配置;provider 不可达时返回 null。
     *
     * 跟 [read] 的区别只在于**能不能区分「读不到」和「读到一份空配置」** ——
     * 界面需要这个区分(provider 挂了要显式报错,而不是装成全新安装的样子),
     * hook 侧不需要,继续用宽容的 [read]。
     */
    fun readOrNull(context: Context): AiConfig? {
        val result: Bundle = try {
            context.contentResolver.call(uri, ConfigProvider.METHOD_GET, null, null)
        } catch (t: Throwable) {
            null
        } ?: return null
        return fromBundle(result)
    }

    fun read(context: Context): AiConfig = readOrNull(context) ?: fromBundle(null)

    private fun fromBundle(result: Bundle?): AiConfig {
        // 新增字段在旧存档里可能不存在:blockViewJump 默认 true,放行词默认 DEFAULT
        val blockRaw = result?.getString(ConfigKeys.BLOCK_VIEW_JUMP)
        val allowRaw = result?.getString(ConfigKeys.JUMP_ALLOW_WORDS)
        val searchRaw = result?.getString(ConfigKeys.BLOCK_WEB_SEARCH)
        val searchAllowRaw = result?.getString(ConfigKeys.WEB_SEARCH_ALLOW_WORDS)
        val speakRaw = result?.getString(ConfigKeys.SPEAK_ANSWER)
        val nativeToolsRaw = result?.getString(ConfigKeys.USE_NATIVE_TOOLS)
        val contextRaw = result?.getString(ConfigKeys.CONTEXT_ENABLED)
        val skipTakeoverRaw = result?.getString(ConfigKeys.SKIP_TAKEOVER_ENABLED)
        val autoFixRaw = result?.getString(ConfigKeys.AUTO_FIX_ACCESSIBILITY)
        return AiConfig(
            provider = result?.getString(ConfigKeys.PROVIDER).orEmpty(),
            endpoint = result?.getString(ConfigKeys.ENDPOINT).orEmpty(),
            apiKey = result?.getString(ConfigKeys.API_KEY).orEmpty(),
            model = result?.getString(ConfigKeys.MODEL).orEmpty(),
            systemPrompt = result?.getString(ConfigKeys.SYSTEM_PROMPT).orEmpty(),
            enabled = result?.getString(ConfigKeys.ENABLED) == "true",
            blockViewJump = blockRaw.isNullOrEmpty() || blockRaw == "true",
            jumpAllowWords = if (allowRaw.isNullOrEmpty()) DEFAULT_JUMP_ALLOW_WORDS else allowRaw,
            blockWebSearch = searchRaw.isNullOrEmpty() || searchRaw == "true",
            webSearchAllowWords = if (searchAllowRaw.isNullOrEmpty()) DEFAULT_WEB_SEARCH_ALLOW_WORDS else searchAllowRaw,
            speakAnswer = speakRaw.isNullOrEmpty() || speakRaw == "true",
            enabledTools = result?.getString(ConfigKeys.ENABLED_TOOLS).orEmpty(),
            useNativeTools = nativeToolsRaw.isNullOrEmpty() || nativeToolsRaw == "true",
            contextEnabled = contextRaw.isNullOrEmpty() || contextRaw == "true",
            skipTakeoverEnabled = skipTakeoverRaw.isNullOrEmpty() || skipTakeoverRaw == "true",
            // 「从没配置过给默认值、用户手动清空就是空」的区分已经在 ConfigProvider 里做完了
            // (SharedPreferences.getString 的 defValue 只在 key 不存在时生效),这里不用再猜。
            skipTakeoverPattern = result?.getString(ConfigKeys.SKIP_TAKEOVER_PATTERN).orEmpty(),
            // 注意这里不是「空即开」—— 默认关，只有显式存过 "true" 才算开。
            autoFixAccessibility = autoFixRaw == "true"
        )
    }

    /** @return 写入是否成功。旧代码忽略了这个返回值,界面上「已保存」的提示因此可能是假的。 */
    fun write(context: Context, config: AiConfig): Boolean {
        val extras = Bundle().apply {
            putString(ConfigKeys.PROVIDER, config.provider)
            putString(ConfigKeys.ENDPOINT, config.endpoint)
            putString(ConfigKeys.API_KEY, config.apiKey)
            putString(ConfigKeys.MODEL, config.model)
            putString(ConfigKeys.SYSTEM_PROMPT, config.systemPrompt)
            putString(ConfigKeys.ENABLED, config.enabled.toString())
            putString(ConfigKeys.BLOCK_VIEW_JUMP, config.blockViewJump.toString())
            putString(ConfigKeys.JUMP_ALLOW_WORDS, config.jumpAllowWords)
            putString(ConfigKeys.BLOCK_WEB_SEARCH, config.blockWebSearch.toString())
            putString(ConfigKeys.WEB_SEARCH_ALLOW_WORDS, config.webSearchAllowWords)
            putString(ConfigKeys.SPEAK_ANSWER, config.speakAnswer.toString())
            putString(ConfigKeys.ENABLED_TOOLS, config.enabledTools)
            putString(ConfigKeys.USE_NATIVE_TOOLS, config.useNativeTools.toString())
            putString(ConfigKeys.CONTEXT_ENABLED, config.contextEnabled.toString())
            putString(ConfigKeys.SKIP_TAKEOVER_ENABLED, config.skipTakeoverEnabled.toString())
            putString(ConfigKeys.SKIP_TAKEOVER_PATTERN, config.skipTakeoverPattern)
            putString(ConfigKeys.AUTO_FIX_ACCESSIBILITY, config.autoFixAccessibility.toString())
        }
        return try {
            val out = context.contentResolver.call(uri, ConfigProvider.METHOD_SET, null, extras)
            out?.getBoolean("ok") == true
        } catch (t: Throwable) {
            false
        }
    }
}
