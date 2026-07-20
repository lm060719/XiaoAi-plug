package com.xiaoai.plug.config

/**
 * 支持接入的 AI 服务商。
 *
 * 只有 [wire] 决定请求怎么发 —— xAI 和硅基流动都是 OpenAI 兼容协议,
 * 拿它们单独立项只是为了给用户一个能直接用的默认地址,省得自己去翻文档。
 * 真正需要另一套报文的只有 Anthropic(见 [Wire.ANTHROPIC])。
 *
 * [defaultEndpoint] / [defaultModel] 是**占位默认值**:用户不填时按它走,
 * 界面上以灰字提示。所以新增服务商只要在这儿加一行,界面和客户端都不用动。
 */
enum class AiProvider(
    val key: String,
    val label: String,
    val wire: Wire,
    val defaultEndpoint: String,
    val defaultModel: String
) {
    OPENAI("openai", "OpenAI 兼容", Wire.OPENAI, "https://api.openai.com/v1", "gpt-4o-mini"),
    ANTHROPIC("anthropic", "Anthropic", Wire.ANTHROPIC, "https://api.anthropic.com/v1", "claude-opus-4-8"),
    XAI("xai", "xAI (Grok)", Wire.OPENAI, "https://api.x.ai/v1", "grok-4"),
    SILICONFLOW("siliconflow", "硅基流动", Wire.OPENAI, "https://api.siliconflow.cn/v1", "Qwen/Qwen3-8B");

    /** 报文协议。同一种协议下换服务商只是换地址,不用改代码。 */
    enum class Wire { OPENAI, ANTHROPIC }

    companion object {
        val DEFAULT = OPENAI

        /** 旧存档里没有 provider 字段,读出来是空串 —— 一律当 OpenAI 兼容,行为和升级前一致。 */
        fun fromKey(key: String?): AiProvider =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
