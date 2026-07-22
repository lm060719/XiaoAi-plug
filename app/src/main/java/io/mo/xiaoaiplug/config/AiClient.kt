package io.mo.xiaoaiplug.config

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * AI 客户端 + 工具执行循环。支持 OpenAI 兼容(含 xAI、硅基流动)和 Anthropic 两种报文。
 *
 * 内部一律用 OpenAI 的消息形状,Anthropic 的差异全压在 [postAnthropic] 里翻译 ——
 * 下面这套工具循环因此不用关心当前接的是谁。
 *
 * 两种工具调用方式都支持:
 *  - **原生 function calling**(首选):请求带 `tools` 数组,响应里读 `message.tool_calls`。
 *    结构化,不用猜方言。
 *  - **文本约定**(兜底):模型在正文里写 `<tool_call>{...}</tool_call>`。
 *    端点不认 `tools` 参数(400)时自动切到这条,并把工具表写进 system prompt。
 *
 * 同步阻塞,调用方须放到后台线程。
 */
object AiClient {

    private const val TAG = "XiaoAiProbe"
    private const val MAX_TOOL_ITERATIONS = 6

    private const val ANTHROPIC_VERSION = "2023-06-01"
    // Anthropic 必填。语音助手的答案都很短,4096 够用还留了工具循环的余量。
    private const val ANTHROPIC_MAX_TOKENS = 4096

    // 一轮里模型可能要求调好几个工具。串行跑的话 3 个工具 × 各自 shell 开销就是好几秒,
    // 而它们之间没有依赖关系 —— 并行跑,一轮的耗时就是最慢那个工具的耗时。
    private val toolPool = Executors.newCachedThreadPool { r ->
        Thread(r, "xiaoai-tool").apply { isDaemon = true }
    }

    // 任何 <tool_call>…</tool_call>,不管里面是 JSON 还是 XML。
    // 早先这里写死了 `\{.*?\}`(只认 JSON),结果模型改用 XML 方言时匹配不上 →
    // parseToolCalls 返回空 → 被当成"最终答案" → 整段 XML 标记被念了出来
    // (真机上就是那句"一堆英文":<tool_call><parameter name="command">get_device_info…)。
    // 现在先无差别捞出整块,再按内容判断方言。
    private val TOOL_CALL_BLOCK =
        Regex("<tool_call>(.*?)</tool_call>", RegexOption.DOT_MATCHES_ALL)

    // XML 方言:<name>run_shell</name><arguments><command>id</command></arguments>
    private val XML_NAME = Regex("<name>\\s*(.*?)\\s*</name>", RegexOption.DOT_MATCHES_ALL)
    private val XML_ARGS_BLOCK = Regex("<arguments>(.*?)</arguments>", RegexOption.DOT_MATCHES_ALL)
    private val XML_TAG_PAIR = Regex("<(\\w+)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)

    // 另一种 XML 方言:<parameter name="command">id</parameter>
    private val XML_PARAM =
        Regex("<parameter\\s+name=\"(\\w+)\"\\s*>(.*?)</parameter>", RegexOption.DOT_MATCHES_ALL)

    /** 端点是否支持原生 tools。第一次被 400 拒绝后置为 false,本进程内不再重试。 */
    @Volatile
    private var nativeToolsSupported = true

    /** 一次工具调用:名字 + 参数 + 原生模式下的 id(回传时要对上)。 */
    private data class Call(val name: String, val args: JSONObject, val id: String?)

    /**
     * 「这轮没答上来」的软失败答案。
     *
     * 硬失败([chat] 抛异常)调用方本来就会当失败处理,但这一种是**正常返回**的字符串,
     * 光看返回值和真答案没有区别。调用方必须能认出它,否则会把失败当答案缓存/记历史 ——
     * 真机事故:天气问话遇到一次端点抖动返回本串,被 15 秒窗口缓存住;用户觉得不对再问
     * 一遍(同一句话 → 同一个 key),直接命中缓存原样重播,**连重试都没发生**,
     * 一次偶发抖动看起来就成了稳定故障。
     */
    const val FAILED_ANSWER = "(模型返回了无法解析的工具调用，没有得到答案)"

    /**
     * @param history 之前几轮的问答(由旧到新),拼在本轮提问前面当上下文。见 [ChatHistory]。
     * @param allowMutating 每次工具执行时求值:本轮是否确实由我们接管。
     *   false 时动作类工具(launch_app / set_setting / …)被拒绝执行。
     *   见 Tools.Spec.mutating 上的事故说明。
     */
    fun chat(
        config: AiConfig,
        userText: String,
        ctx: Context? = null,
        history: List<ChatTurn> = emptyList(),
        allowMutating: () -> Boolean = { true }
    ): String {
        // 包一层只为埋点:chatInternal 有三个 return 点,逐个改容易漏。
        // 失败原样往上抛,不改变调用方(HookEntry)看到的行为。
        val started = System.currentTimeMillis()
        // 各阶段耗时。用户看到"这轮花了 7 秒"时,得能一眼分清是模型慢还是工具慢 ——
        // 早先只有一个总时长,只能拿 CHAT 和 TOOL 两条记录的时间戳倒推。
        val steps = ArrayList<Pair<String, Long>>()
        return try {
            val answer = chatInternal(config, userText, ctx, history, allowMutating, steps)
            LogClient.chat(
                ctx, userText, answer, config.effectiveModel,
                System.currentTimeMillis() - started, formatSteps(steps)
            )
            answer
        } catch (t: Throwable) {
            LogClient.error(
                ctx,
                "对话失败: ${t.javaClass.simpleName}",
                "问: $userText\n\n${t.stackTraceToString()}",
                System.currentTimeMillis() - started
            )
            throw t
        }
    }

    /** "模型 2.9s → 工具 0.8s → 模型 3.4s"。 */
    private fun formatSteps(steps: List<Pair<String, Long>>): String =
        steps.joinToString(" → ") { (name, ms) ->
            name + " " + String.format(Locale.US, "%.1f", ms / 1000.0) + "s"
        }

    private fun chatInternal(
        config: AiConfig,
        userText: String,
        ctx: Context?,
        history: List<ChatTurn>,
        allowMutating: () -> Boolean,
        steps: MutableList<Pair<String, Long>>
    ): String {
        val specs = Tools.enabled(config.enabledTools)
        val useNative = config.useNativeTools && nativeToolsSupported && specs.isNotEmpty()
        // 模型说"我没有这个能力"时,必须能一眼分清是**没给它工具**还是**给了它没用** ——
        // 光看答案分不出来,只能靠猜。
        Log.i(TAG, "offering ${specs.size} tools (native=$useNative): " +
                specs.joinToString(",") { it.name })

        val messages = JSONArray()
        val sysParts = ArrayList<String>()
        sysParts.add(config.effectiveSystemPrompt)
        // 文本约定模式下必须把工具表告诉模型;原生模式下 tools 参数已经带了,再塞一遍是浪费 token。
        if (!useNative && specs.isNotEmpty()) sysParts.add(Tools.toPromptSpec(specs))
        // 已有记忆无条件注入,**跟「记忆个性化录入」开关无关** —— 那个开关管的是模型能不能
        // 自己往里写(即 save_memory 工具的启停)。用户手填的记忆不该因为关了自动录入就失效。
        val memories = MemoryClient.list(ctx)
        if (memories.isNotEmpty()) {
            sysParts.add(
                "以下是你记住的关于用户的信息，回答时自然地用上，不要主动复述或提起「我记得」：\n" +
                    memories.joinToString("\n") { "- $it" }
            )
        }
        if (sysParts.isNotEmpty()) {
            messages.put(JSONObject().put("role", "system").put("content", sysParts.joinToString("\n\n")))
        }
        // 历史一律走 user/assistant 交替的普通消息,**不能**用 role=system ——
        // postAnthropic 会把所有 system 消息折进顶层 system 字段(见那边的注释),
        // 上下文就变成系统提示词的一部分了。
        for (turn in history) {
            messages.put(JSONObject().put("role", "user").put("content", turn.question))
            messages.put(JSONObject().put("role", "assistant").put("content", turn.answer))
        }
        if (history.isNotEmpty()) Log.i(TAG, "carrying ${history.size} turns of context")

        messages.put(JSONObject().put("role", "user").put("content", userText))

        var lastContent = ""
        for (iter in 0 until MAX_TOOL_ITERATIONS) {
            val modelAt = System.currentTimeMillis()
            val reply = callModel(config, messages, if (useNative) specs else emptyList(), ctx)
            steps.add("模型" to System.currentTimeMillis() - modelAt)
            val content = reply.optString("content", "")
            lastContent = content

            val calls = extractCalls(reply, content)
            if (calls.isEmpty()) {
                // 没有工具调用 = 最终答案。但要防一种情况:内容里**确实有**工具标记,
                // 只是方言我们还不认识 —— 那 stripToolTags 之后会所剩无几,
                // 与其把残渣(或空串)当答案念出去,不如明说这轮没答上来。
                val clean = stripToolTags(content)
                if (clean.isBlank()) {
                    Log.w(TAG, "empty answer after stripping tool markup, raw=${content.take(200)}")
                    return FAILED_ANSWER
                }
                return clean
            }

            // 原样回填 assistant 这一轮(原生模式要带上 tool_calls,否则模型对不上号)
            messages.put(reply)

            val toolAt = System.currentTimeMillis()
            val results = runCallsParallel(calls, ctx, iter, allowMutating)
            steps.add(
                (if (calls.size == 1) "工具" else "工具×${calls.size}")
                        to System.currentTimeMillis() - toolAt
            )

            if (calls.any { it.id != null }) {
                // 原生协议:每个调用一条 role=tool 消息
                for ((call, result) in calls.zip(results)) {
                    messages.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", call.id ?: call.name)
                            .put("name", call.name)
                            .put("content", result)
                    )
                }
            } else {
                // 文本约定:拼成一条 user 消息回传
                val sb = StringBuilder()
                for ((call, result) in calls.zip(results)) {
                    sb.append("<tool_response>\n")
                        .append(JSONObject().put("name", call.name).put("content", result))
                        .append("\n</tool_response>\n")
                }
                messages.put(JSONObject().put("role", "user").put("content", sb.toString()))
            }
        }
        // 超过循环上限:把最后内容去掉标记后返回,避免卡死
        return stripToolTags(lastContent).ifBlank { "(工具调用超过上限，未得到最终答案)" }
    }

    /** 并行执行本轮所有工具,返回与 calls 同序的结果。 */
    private fun runCallsParallel(
        calls: List<Call>, ctx: Context?, iter: Int, allowMutating: () -> Boolean
    ): List<String> {
        if (calls.size == 1) {
            val c = calls[0]
            val t0 = System.currentTimeMillis()
            val r = Tools.execute(c.name, c.args, ctx, allowMutating())
            logCall(iter, c, t0, r, ctx)
            return listOf(r)
        }
        val futures = calls.map { c ->
            val t0 = System.currentTimeMillis()
            toolPool.submit<String> {
                val r = Tools.execute(c.name, c.args, ctx, allowMutating())
                logCall(iter, c, t0, r, ctx)
                r
            }
        }
        return futures.mapIndexed { i, f ->
            try {
                f.get(40, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                val msg = "error: 工具 ${calls[i].name} 执行失败: $t"
                LogClient.error(ctx, "工具超时/失败: ${calls[i].name}", msg)
                msg
            }
        }
    }

    // 这个循环最多能烧 6 轮 × (模型往返 + 工具耗时),真机上见过整整 30 秒
    // 最后还是"未得到最终答案"。不打日志的话它就是个黑盒,只能干瞪眼。
    private fun logCall(iter: Int, c: Call, startedAt: Long, result: String, ctx: Context?) {
        val took = System.currentTimeMillis() - startedAt
        Log.i(TAG, "tool iter=$iter name=${c.name} args=${c.args} " +
                "took=${took}ms result=${result.take(120)}")
        // 落盘一份,好让手机不在电脑边上时也能事后翻。写入是 fire-and-forget 的。
        LogClient.tool(ctx, c.name, c.args.toString(), result, took)
    }

    /** 先看原生 tool_calls,没有再回退去正文里捞文本约定。 */
    private fun extractCalls(reply: JSONObject, content: String): List<Call> {
        val native = reply.optJSONArray("tool_calls")
        if (native != null && native.length() > 0) {
            val out = ArrayList<Call>()
            for (i in 0 until native.length()) {
                val tc = native.optJSONObject(i) ?: continue
                val fn = tc.optJSONObject("function") ?: continue
                val name = fn.optString("name", "")
                if (name.isBlank()) continue
                // arguments 按规范是**字符串化**的 JSON,但有的端点直接给对象
                val args = when (val a = fn.opt("arguments")) {
                    is JSONObject -> a
                    is String -> try { JSONObject(a) } catch (t: Throwable) { JSONObject() }
                    else -> JSONObject()
                }
                out.add(Call(name, args, tc.optString("id", "call_$i")))
            }
            if (out.isNotEmpty()) return out
        }
        return parseToolCalls(content)
    }

    /**
     * 发一次请求,返回 assistant 那条 message(含可能的 tool_calls)。
     * 带 tools 时若端点回 400,认为它不支持原生 function calling,降级重来一次。
     */
    private fun callModel(
        config: AiConfig,
        messages: JSONArray,
        specs: List<Tools.Spec>,
        ctx: Context?
    ): JSONObject {
        return try {
            request(config, messages, specs)
        } catch (t: HttpError) {
            if (specs.isNotEmpty() && t.code == 400) {
                Log.w(TAG, "endpoint rejected native tools (400), falling back to text convention: ${t.body.take(200)}")
                LogClient.error(
                    ctx,
                    "端点不支持原生 function calling，已降级",
                    "HTTP 400。之后本进程都改走文本约定。\n\n${t.body.take(2000)}"
                )
                nativeToolsSupported = false
                // 这一轮先按无工具重发;下一轮 chat() 会走文本约定并把工具表写进 prompt。
                // 注意本轮模型看不到工具表,可能直接给个"我不知道"——可以接受,
                // 因为降级只会发生一次,之后整个进程都走文本路。
                request(config, messages, emptyList())
            } else throw t
        }
    }

    /**
     * 按服务商选报文格式。
     *
     * 不管走哪条路,**进出这个函数的都是 OpenAI 形状**:入参 `messages` 是 OpenAI 的
     * 消息数组,返回值是 OpenAI 的 assistant message。Anthropic 那条路在函数内部
     * 完成来回翻译,这样上面的工具循环一份代码通吃两种协议。
     */
    private fun request(
        config: AiConfig,
        messages: JSONArray,
        specs: List<Tools.Spec>
    ): JSONObject = when (config.aiProvider.wire) {
        AiProvider.Wire.OPENAI -> postOpenAi(config, messages, specs)
        AiProvider.Wire.ANTHROPIC -> postAnthropic(config, messages, specs)
    }

    private class HttpError(val code: Int, val body: String) : RuntimeException("HTTP $code: $body")

    private fun postOpenAi(
        config: AiConfig,
        messages: JSONArray,
        specs: List<Tools.Spec>
    ): JSONObject {
        val body = JSONObject()
            .put("model", config.effectiveModel)
            .put("messages", messages)
        if (specs.isNotEmpty()) {
            body.put("tools", Tools.toOpenAiSchema(specs))
            body.put("tool_choice", "auto")
        }
        val base = config.effectiveEndpoint.trimEnd('/')
        val url = URL(if (base.endsWith("/chat/completions")) base else "$base/chat/completions")
        val headers = HashMap<String, String>()
        if (config.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${config.apiKey}"
        return postJson(url, headers, body)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
    }

    /**
     * Anthropic Messages API。和 OpenAI 有三处对不上,都在这儿抹平:
     *  1. system 不在 messages 里,是顶层字段
     *  2. 工具结果不是 role=tool,是塞进 user 消息的 tool_result 块 ——
     *     而且同一轮的多个结果必须**并在同一条** user 消息里,分开发会被判为漏答
     *  3. max_tokens 必填
     *
     * 没开 thinking:这是语音助手,工具循环本来就能跑到几十秒,再加思考链只会更慢。
     */
    private fun postAnthropic(
        config: AiConfig,
        messages: JSONArray,
        specs: List<Tools.Spec>
    ): JSONObject {
        val system = StringBuilder()
        val converted = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "system" -> {
                    if (system.isNotEmpty()) system.append("\n\n")
                    system.append(m.optString("content"))
                }
                "tool" -> {
                    val block = JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", m.optString("tool_call_id", m.optString("name")))
                        .put("content", m.optString("content"))
                    // 紧邻的上一条已经是"装工具结果的 user 消息"就并进去,否则新开一条
                    val last = converted.optJSONObject(converted.length() - 1)
                    if (last != null && last.optString("role") == "user" && last.opt("content") is JSONArray) {
                        last.getJSONArray("content").put(block)
                    } else {
                        converted.put(
                            JSONObject().put("role", "user").put("content", JSONArray().put(block))
                        )
                    }
                }
                "assistant" -> converted.put(assistantToAnthropic(m))
                else -> converted.put(
                    JSONObject().put("role", "user").put("content", m.optString("content"))
                )
            }
        }

        val body = JSONObject()
            .put("model", config.effectiveModel)
            .put("max_tokens", ANTHROPIC_MAX_TOKENS)
            .put("messages", converted)
        if (system.isNotEmpty()) body.put("system", system.toString())
        if (specs.isNotEmpty()) {
            body.put("tools", Tools.toAnthropicSchema(specs))
            body.put("tool_choice", JSONObject().put("type", "auto"))
        }

        val base = config.effectiveEndpoint.trimEnd('/')
        val url = URL(if (base.endsWith("/messages")) base else "$base/messages")
        val headers = HashMap<String, String>()
        headers["anthropic-version"] = ANTHROPIC_VERSION
        if (config.apiKey.isNotBlank()) headers["x-api-key"] = config.apiKey
        return anthropicToOpenAi(postJson(url, headers, body))
    }

    /** OpenAI 的 assistant message → Anthropic 的 assistant 消息(tool_calls 变 tool_use 块)。 */
    private fun assistantToAnthropic(m: JSONObject): JSONObject {
        val calls = m.optJSONArray("tool_calls")
        val text = m.optString("content", "")
        if (calls == null || calls.length() == 0) {
            return JSONObject().put("role", "assistant").put("content", text)
        }
        val blocks = JSONArray()
        if (text.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", text))
        for (i in 0 until calls.length()) {
            val c = calls.optJSONObject(i) ?: continue
            val fn = c.optJSONObject("function") ?: continue
            // arguments 按 OpenAI 规范是字符串化的 JSON,Anthropic 的 input 要的是对象
            val args = when (val a = fn.opt("arguments")) {
                is JSONObject -> a
                is String -> try { JSONObject(a) } catch (t: Throwable) { JSONObject() }
                else -> JSONObject()
            }
            blocks.put(
                JSONObject()
                    .put("type", "tool_use")
                    .put("id", c.optString("id", "call_$i"))
                    .put("name", fn.optString("name"))
                    .put("input", args)
            )
        }
        return JSONObject().put("role", "assistant").put("content", blocks)
    }

    /** Anthropic 响应 → OpenAI 形状的 assistant message,好让上面的循环原样处理。 */
    private fun anthropicToOpenAi(resp: JSONObject): JSONObject {
        val content = resp.optJSONArray("content") ?: JSONArray()
        val text = StringBuilder()
        val calls = JSONArray()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "text" -> text.append(b.optString("text"))
                "tool_use" -> calls.put(
                    JSONObject()
                        .put("id", b.optString("id", "call_$i"))
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", b.optString("name"))
                                // extractCalls 认对象也认字符串,这里直接给对象
                                .put("arguments", b.optJSONObject("input") ?: JSONObject())
                        )
                )
            }
        }
        val out = JSONObject().put("role", "assistant").put("content", text.toString())
        if (calls.length() > 0) out.put("tool_calls", calls)
        return out
    }

    /**
     * 一次 POST。
     *
     * **不要在正常路径上 disconnect()** —— 它会关掉底层 socket 并把它踢出 keep-alive 池,
     * 而一轮对话至少两次请求(决定调工具 + 组织答案),打向同一个 host。
     * 实测这个端点新建连接要 133ms 握手(TTFB 186ms),复用连接 TTFB 只要 56ms。
     * 把响应体**读完**(下面两个 `use` 都读到 EOF,包括 4xx 的 errorStream)就够了,
     * HttpURLConnection 自己会把连接还回池子。
     *
     * 出异常时才 disconnect:那种连接状态不明(可能还剩没读完的字节),
     * 留在池子里会毒害下一次请求。
     */
    private fun postJson(url: URL, headers: Map<String, String>, body: JSONObject): JSONObject {
        val conn = url.openConnection() as HttpURLConnection
        var bodyDrained = false
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.doOutput = true
            conn.connectTimeout = 30000
            conn.readTimeout = 60000

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            // HttpError 也是读完整个 body 之后才抛的,连接同样是干净的、可复用
            bodyDrained = true
            if (code !in 200..299) throw HttpError(code, text)
            return JSONObject(text)
        } finally {
            if (!bodyDrained) conn.disconnect()
        }
    }

    // 解析所有 <tool_call>,返回调用列表。JSON 和 XML 两种方言都认。
    private fun parseToolCalls(content: String): List<Call> {
        val out = ArrayList<Call>()
        for (mr in TOOL_CALL_BLOCK.findAll(content)) {
            val body = mr.groupValues[1].trim()
            val parsed = if (body.startsWith("{")) parseJsonCall(body) else parseXmlCall(body)
            if (parsed != null) out.add(parsed)
        }
        return out
    }

    private fun parseJsonCall(body: String): Call? {
        return try {
            val obj = JSONObject(body)
            val name = obj.optString("name", "")
            if (name.isBlank()) return null
            // arguments 可能是对象,也可能是被转义的字符串
            val args = when (val a = obj.opt("arguments")) {
                is JSONObject -> a
                is String -> try { JSONObject(a) } catch (t: Throwable) { JSONObject() }
                else -> JSONObject()
            }
            Call(name, args, null)
        } catch (t: Throwable) {
            null
        }
    }

    // XML 方言有两种写法,都见过:
    //   <name>run_shell</name><arguments><command>id</command></arguments>
    //   <parameter name="command">id</parameter>          (没有 <name>,工具名靠猜)
    private fun parseXmlCall(body: String): Call? {
        val args = JSONObject()
        var name = XML_NAME.find(body)?.groupValues?.get(1)?.trim().orEmpty()

        val argsBlock = XML_ARGS_BLOCK.find(body)?.groupValues?.get(1)
        if (argsBlock != null) {
            for (m in XML_TAG_PAIR.findAll(argsBlock)) {
                args.put(m.groupValues[1], m.groupValues[2].trim())
            }
        }
        for (m in XML_PARAM.findAll(body)) {
            args.put(m.groupValues[1], m.groupValues[2].trim())
        }
        // 没给工具名但给了 command,按 shell 处理 —— 这是实测最常见的残缺形态
        if (name.isBlank() && args.has("command")) name = "run_shell"
        if (name.isBlank()) return null
        return Call(name, args, null)
    }

    // 去掉最终答案里残留的工具标记。这是最后一道闸:不管模型用哪种方言、
    // 也不管解析有没有成功,这些标记都绝不能流到卡片和 TTS 上。
    // (真机事故:XML 方言没被识别,整段 <tool_call><parameter name=…> 被当答案念了出来。)
    private fun stripToolTags(content: String): String {
        var s = content
            .replace(TOOL_CALL_BLOCK, "")
            .replace(Regex("<tool_response>.*?</tool_response>", RegexOption.DOT_MATCHES_ALL), "")
        // 闭合标签缺失(截断/流式没收尾)时上面的成对匹配会漏,这里把残留的开标签及其后内容也切掉
        s = s.replace(Regex("<tool_call>.*", RegexOption.DOT_MATCHES_ALL), "")
        s = s.replace(Regex("</?(tool_call|tool_response|arguments|parameter|name)[^>]*>"), "")
        return s.trim()
    }
}
