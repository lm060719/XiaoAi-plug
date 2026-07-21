package io.mo.xiaoaiplug.config

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 工具注册表。
 *
 * 设计要点(都是被真机延迟教出来的):
 *
 * 1. **组合式工具优先于通用 shell**。"当前 WiFi 密码是多少"用 run_shell 要跑四轮
 *    (列网卡 → 找配置文件 → 读文件 → 解析),每轮一次模型往返,实测 14 秒。
 *    `wifi_info` 一轮出结果。凡是能预见的问法都应该有专用工具。
 * 2. **一个工具内部只起一次 shell**。多条命令用 `;` 拼进同一次 `su -c`,
 *    进程创建在真机上单次约 80~150ms,起五次就是半秒白给。
 * 3. **描述面向模型而非人**。description 里要写清"什么时候该用我",
 *    否则模型会退回 run_shell 自己拼命令,又变成多轮。
 */
object Tools {

    private const val TAG = "XiaoAiProbe"
    private const val SHELL_TIMEOUT_SEC = 15L
    private const val MAX_TOOL_OUTPUT = 6000

    /** 工具参数声明,用于生成 JSON Schema。 */
    data class Param(
        val name: String,
        val type: String,          // string / integer / boolean
        val description: String,
        val required: Boolean = false,
        val enum: List<String>? = null
    )

    /**
     * 一个工具:名字 + 给模型看的说明 + 参数 + 实现。
     *
     * `mutating` = 会改变设备状态(启动应用、改设置、动音量…)。
     * 这类工具**只在本轮确实由我们接管时**才允许执行 —— 真机事故:
     * "打开微信帮我给X发信息"命中放行词、本该由小爱自己处理,我们没接管,
     * 但模型照样跑了一遍并真的 launch_app 打开了微信。小爱那边同时在报
     * "暂不支持微信双开",两边各干各的。读类工具跑了无所谓,动手类不行。
     */
    data class Spec(
        val name: String,
        /** 一句话说明。工具页拿它当开关的 summary,所以要短、是人话。 */
        val description: String,
        /**
         * 只给模型看的补充:适用场景、默认值、坑。不进界面 ——
         * 揉进 [description] 的话工具页那 16 个开关会被撑成一堵墙。
         */
        val modelHint: String = "",
        val params: List<Param> = emptyList(),
        val mutating: Boolean = false,
        val handler: (JSONObject, Context?) -> String
    )

    // ---------------------------------------------------------------- 注册表

    val ALL: List<Spec> by lazy {
        listOf(
            runShell, deviceStatus, wifiInfo, networkInfo, topMemoryApps, topStorageApps,
            listApps, launchApp, sendMessage, readFile,
            getSetting, setSetting,
            mediaControl, setVolume,
            currentTime, recentNotifications,
            saveMemory
        )
    }

    private val byName: Map<String, Spec> by lazy { ALL.associateBy { it.name } }

    /**
     * 「记忆个性化录入」开关背后就是这个工具的启停 —— 记忆页那个开关和工具页里
     * save_memory 那个开关读写的是**同一份状态**,不是两个各自为政的配置项。
     */
    const val SAVE_MEMORY = "save_memory"

    /** 用户把工具全关掉时存这个,以区别于"老存档里压根没有这个字段"(空串 = 全开)。 */
    const val NONE = "none"

    /** 配置里勾选的工具;`csv` 为空表示全开(老存档没这个字段),`none` 表示全关。 */
    fun enabled(csv: String): List<Spec> {
        if (csv.isBlank()) return ALL
        if (csv.trim() == NONE) return emptyList()
        val want = csv.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return ALL.filter { it.name in want }
    }

    fun isEnabled(csv: String, name: String): Boolean = enabled(csv).any { it.name == name }

    /**
     * 开/关单个工具,返回新的 csv。
     *
     * 全勾选时也存显式列表、不存空串:存空串的话以后新增的工具会被自动打开,
     * 用户以为自己关掉的东西又冒出来。(这条语义原先只写在工具页里,提上来共用。)
     */
    fun withEnabled(csv: String, name: String, on: Boolean): String {
        val cur = enabled(csv).map { it.name }.toSet()
        val next = if (on) cur + name else cur - name
        return next.joinToString(",").ifEmpty { NONE }
    }

    /**
     * 给模型看的工具说明。三条出口(OpenAI schema / Anthropic schema / 文本约定)都用它,
     * 免得"动作类"这种信息只在其中一条路上有。
     *
     * [Spec.mutating] 原先只在执行时被 allowMutating 拦,模型那边一无所知 ——
     * 它会以为自己随时能开应用,答"好的已经帮你打开了",实际被拦下什么也没发生。
     */
    private fun modelDescription(s: Spec): String = buildString {
        append(s.description)
        if (s.modelHint.isNotEmpty()) append(' ').append(s.modelHint)
        if (s.mutating) append(" ⚡动作类：会改变设备状态，只在本轮确实由本模块接管时才会真正执行。")
    }

    /** 单个工具的 JSON Schema。OpenAI 叫 `parameters`,Anthropic 叫 `input_schema`,内容一样。 */
    private fun paramsSchema(s: Spec): JSONObject {
        val props = JSONObject()
        val required = JSONArray()
        for (p in s.params) {
            val po = JSONObject().put("type", p.type).put("description", p.description)
            p.enum?.let { po.put("enum", JSONArray(it)) }
            props.put(p.name, po)
            if (p.required) required.put(p.name)
        }
        return JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("required", required)
    }

    /** OpenAI `tools` 数组。原生 function calling 走这个,比文本约定可靠得多。 */
    fun toOpenAiSchema(specs: List<Spec>): JSONArray {
        val arr = JSONArray()
        for (s in specs) {
            arr.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject()
                        .put("name", s.name)
                        .put("description", modelDescription(s))
                        .put("parameters", paramsSchema(s))
                )
            )
        }
        return arr
    }

    /** Anthropic `tools` 数组。比 OpenAI 少一层包装:名字和 schema 直接摊在顶层。 */
    fun toAnthropicSchema(specs: List<Spec>): JSONArray {
        val arr = JSONArray()
        for (s in specs) {
            arr.put(
                JSONObject()
                    .put("name", s.name)
                    .put("description", modelDescription(s))
                    .put("input_schema", paramsSchema(s))
            )
        }
        return arr
    }

    /**
     * 给不支持原生 function calling 的端点用:把工具表写进 system prompt。
     * 顺带**明确指定** JSON 方言 —— 之前模型自由发挥出三种 XML 变体,
     * 解析漏一种就把整段标记念了出去(见 AiClient.stripToolTags 的注释)。
     */
    fun toPromptSpec(specs: List<Spec>): String {
        if (specs.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("你可以调用以下工具获取设备真实信息。需要调用时，输出且仅输出：\n")
        sb.append("<tool_call>{\"name\":\"工具名\",\"arguments\":{...}}</tool_call>\n")
        sb.append("必须使用上面的 JSON 格式，不要用 XML 标签形式。可以一次输出多个 tool_call。\n")
        sb.append("拿到 <tool_response> 后，用简洁口语回答用户，不要复述原始数据。\n\n")
        sb.append("可用工具：\n")
        for (s in specs) {
            sb.append("- ").append(s.name).append(": ").append(modelDescription(s))
            if (s.params.isNotEmpty()) {
                sb.append(" 参数: ")
                sb.append(s.params.joinToString(", ") {
                    "${it.name}(${it.type}${if (it.required) ", 必填" else ""})"
                })
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * 执行一个工具。未知工具名返回错误串而不是抛异常 —— 让模型有机会自己纠正。
     *
     * `allowMutating` 在**执行时**求值,不是调用开始时:接管信号(takeOver / 静音泵)
     * 往往比模型调用晚到,开始时判定会误杀。
     */
    fun execute(name: String, args: JSONObject, ctx: Context?, allowMutating: Boolean = true): String {
        val spec = byName[name]
            ?: return "error: unknown tool \"$name\"; available: ${byName.keys.joinToString(",")}"
        if (spec.mutating && !allowMutating) {
            Log.i(TAG, "blocked mutating tool $name (round not ours)")
            return "error: 本轮对话由小爱自己处理，未被本模块接管，动作类工具已禁用。" +
                    "请只作答，不要尝试执行任何会改变设备状态的操作。"
        }
        return try {
            spec.handler(args, ctx).take(MAX_TOOL_OUTPUT)
        } catch (t: Throwable) {
            Log.w(TAG, "tool $name failed: $t")
            "error: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    // ---------------------------------------------------------------- 通用 shell

    private val runShell = Spec(
        name = "run_shell",
        description = "以 root 执行任意 shell 命令。",
        modelHint = "用于没有专用工具覆盖的操作（安装/卸载应用、查看进程详情、改文件权限等）。" +
                "能用专用工具就用专用工具——那些已经把输出解析好了，run_shell 的原始输出往往很长且要多轮往返。",
        params = listOf(Param("command", "string", "要执行的 shell 命令", required = true)),
        handler = { args, _ ->
            val cmd = args.optString("command", args.optString("cmd", "")).trim()
            if (cmd.isEmpty()) "error: empty command" else sh(cmd)
        }
    )

    // ---------------------------------------------------------------- 设备状态

    private val deviceStatus = Spec(
        name = "device_status",
        description = "一次性返回设备状态：电量与充电状态、剩余存储、内存、运行时长、机型、系统版本。",
        modelHint = "用户问「还有多少电」「内存够不够」「手机是什么型号」时用。",
        handler = { _, _ ->
            // 一次 su 调用跑完所有采集。分五次起进程要多花近半秒。
            val raw = sh(
                "echo '--BATTERY--'; dumpsys battery | grep -E 'level|status|powered|temperature';" +
                // 不能写 `df -h /data` —— 小米的 pass_through 挂载会让它解析到
                // /mnt/pass_through/.../com.xiaomi.market/... 那个 bind mount 上,
                // 报出来的是别人的挂载点。按挂载点精确匹配 " /data" 结尾那行。
                "echo '--STORAGE--'; df -h | grep -E ' /data$';" +
                "echo '--MEM--'; cat /proc/meminfo | grep -E 'MemTotal|MemAvailable';" +
                "echo '--UPTIME--'; uptime;" +
                "echo '--MODEL--'; getprop ro.product.marketname; getprop ro.product.model;" +
                "getprop ro.build.version.release; getprop ro.miui.ui.version.name"
            )
            raw
        }
    )

    // ---------------------------------------------------------------- WiFi

    private val wifiInfo = Spec(
        name = "wifi_info",
        description = "返回当前连接的 WiFi状态信息",
        modelHint = "包含信号强度、协商速率、IP 地址，以及已保存网络的密码。" +
                "能查询任意已保存 WiFi 的密码（root 读 WifiConfigStore.xml），返回里会直接给出【答案】，可据此直接作答、无需再自己找。" +
                "用户问「WiFi 密码是多少」「当前连的什么网」「信号怎么样」时用。",
        params = listOf(
            Param("ssid", "string", "只查这个 SSID 的密码；留空表示当前连接的网络")
        ),
        handler = { args, _ ->
            val want = args.optString("ssid", "").trim()
            // 当前连接信息:小,随便截。
            val raw = sh(
                "echo '--CURRENT--'; dumpsys wifi | grep -m1 -E 'mWifiInfo|current SSID' ;" +
                "echo '--IP--'; ip -f inet addr show wlan0 | grep inet"
            )
            // 已保存网络单独读,而且**只 grep 出需要的三种行**。
            // 真机事故:早先这里 `cat` 整个 184KB 的 XML,被 MAX_TOOL_OUTPUT(6000)截断,
            // 当前连接的网络排在 42 个里的靠后位置,压根没进解析器 —— 工具返回一堆
            // 不相干的网络,模型只好再调一次、再退回 run_shell,一共烧了 3 轮 11 秒。
            // 截断本来只该管**返回给模型**的内容,不该管中间读取。
            // 保留 <Network> 行是为了分块:开放网络没有 PreSharedKey,
            // 只 grep SSID/PSK 两种行会串位。
            val store = sh(
                "grep -E '<Network>|name=\"SSID\"|name=\"PreSharedKey\"' " +
                "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml 2>/dev/null " +
                "|| grep -E '<Network>|name=\"SSID\"|name=\"PreSharedKey\"' " +
                "/data/misc/wifi/WifiConfigStore.xml 2>/dev/null",
                limit = 256 * 1024
            )
            val nets = parseWifiStore(store)
            val sb = StringBuilder()
            // `mWifiInfo SSID: "Xiaomi_XXXX", BSSID: …, RSSI: -68, Link speed: 576Mbps, …`
            // 整行原样给模型 —— 信号强度、协商速率、Supplicant 状态都在里面,
            // 用户问"信号怎么样""网速慢"时用得上,只抠个 SSID 就把它们丢了。
            val currentLine = raw.lineSequence().firstOrNull { it.contains("SSID:") }?.trim()
            val current = currentLine?.let {
                Regex("SSID:\\s*\"?([^\",]+)\"?").find(it)?.groupValues?.get(1)?.trim()
            }
            if (currentLine != null) sb.append("当前连接: ").append(currentLine).append('\n')
            Regex("inet\\s+(\\S+)").find(raw)?.let { sb.append("IP: ").append(it.groupValues[1]).append('\n') }

            // 目标网络:显式指定 > 当前连接。命中就把答案挑明,别让模型自己去列表里找 ——
            // 含糊的返回会让它再调一次工具,一轮就是好几秒。
            val target = want.ifEmpty { current.orEmpty() }
            val hit = if (target.isEmpty()) null
                      else nets.firstOrNull { it.first.equals(target, true) }
                        ?: nets.firstOrNull { it.first.contains(target, true) }

            when {
                nets.isEmpty() ->
                    sb.append("未能读取到已保存的 WiFi 配置(可能没有 root 权限)")
                hit != null ->
                    sb.append("【答案】网络 ").append(hit.first).append(" 的密码是: ")
                        .append(if (hit.second.isBlank()) "(开放网络，无密码)" else hit.second)
                        .append("\n(共保存了 ").append(nets.size).append(" 个网络，此条即用户所问，可直接作答)")
                target.isNotEmpty() -> {
                    sb.append("没有找到 \"").append(target).append("\" 的保存记录。")
                        .append("已保存的 ").append(nets.size).append(" 个网络:\n")
                    for ((ssid, psk) in nets.take(30)) {
                        sb.append("  ").append(ssid).append(" : ")
                            .append(if (psk.isBlank()) "(无密码)" else psk).append('\n')
                    }
                }
                else -> {
                    sb.append("已保存 ").append(nets.size).append(" 个网络:\n")
                    for ((ssid, psk) in nets.take(30)) {
                        sb.append("  ").append(ssid).append(" : ")
                            .append(if (psk.isBlank()) "(无密码)" else psk).append('\n')
                    }
                }
            }
            sb.toString()
        }
    )

    /**
     * 从 WifiConfigStore.xml 里抠出 (SSID, 密码) 对。
     * 实测这台机器上 42 个网络的 SSID **全部**是 `&quot;` 包起来的:
     *   `<string name="SSID">&quot;Xiaomi_8B79&quot;</string>`
     * 所以先无差别取标签内容,再单独剥引号 —— 不要把剥引号写进正则,
     * 那样要靠 `&quot;?` 的贪婪/惰性配合才对,读的人和写的人都容易想错。
     */
    private fun parseWifiStore(xml: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        // 每个 <Network> 块里各有一个 SSID 和(可能)一个 PreSharedKey
        for (block in xml.split("<Network>").drop(1)) {
            val ssid = tagValue(block, "SSID") ?: continue
            val psk = tagValue(block, "PreSharedKey").orEmpty()
            out.add(ssid to psk)
        }
        return out
    }

    private fun tagValue(block: String, name: String): String? {
        val raw = Regex("name=\"$name\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(block)?.groupValues?.get(1)?.trim() ?: return null
        return unquote(raw)
    }

    /** 剥掉 XML 转义的引号或真引号。开放网络会存 `null` 字面量。 */
    private fun unquote(s: String): String {
        var v = s.trim()
        if (v == "null") return ""
        if (v.startsWith("&quot;") && v.endsWith("&quot;") && v.length >= 12) {
            v = v.substring(6, v.length - 6)
        } else if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length - 1)
        }
        return v.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    }

    // ---------------------------------------------------------------- 网络

    private val networkInfo = Spec(
        name = "network_info",
        description = "当前网络连接情况",
        modelHint = "包含连接类型（WiFi/移动数据）、IP 地址、运营商、网络制式、飞行模式状态、连通性测试。" +
                "用户问「现在用的什么网络」「有没有联网」「运营商是谁」时用。",
        handler = { _, _ ->
            sh(
                "echo '--TYPE--'; dumpsys connectivity | grep -m3 -E 'NetworkAgentInfo|Active default';" +
                "echo '--IP--'; ip -f inet addr | grep -E 'inet .*(wlan|rmnet)';" +
                "echo '--CARRIER--'; getprop gsm.operator.alpha; getprop gsm.network.type;" +
                "echo '--AIRPLANE--'; settings get global airplane_mode_on;" +
                "echo '--PING--'; ping -c 1 -W 2 223.5.5.5 | tail -n 2"
            )
        }
    )

    // ---------------------------------------------------------------- 内存占用

    /**
     * "哪些应用最占内存"。没有这个工具时模型只能用 run_shell 去啃 `dumpsys meminfo` ——
     * 那份输出在这台机器上是几万字符,一返回就撞 MAX_TOOL_OUTPUT(6000)被拦腰截断,
     * 排序表的头部(也就是唯一有用的部分)恰好在最前面还好,但 `-a`、`ps` 之类的变体
     * 一律截在半路,模型换着命令重试直到轮数烧光,用户看到的就是
     * "工具调用超过上限,未得到最终答案"。和 wifi_info 那次是同一个坑:
     * **截断只该管返回给模型的内容,不该管工具内部的读取**,所以这里 sh(limit=…) 显式放大,
     * 在 Kotlin 里排完序再把前 N 条交出去。
     */
    private val topMemoryApps = Spec(
        name = "top_memory_apps",
        description = "获取进程内存占用状态",
        modelHint = "用户问「哪些应用最占内存」「内存被谁吃了」「手机卡是不是内存不够」时用。" +
                "默认不含系统进程（system_server/systemui 等），用户要看的话传 include_system=true。",
        params = listOf(
            Param("count", "integer", "返回前几名，默认 8"),
            Param("include_system", "boolean", "是否包含系统进程(system_server/systemui 等)，默认 false")
        ),
        handler = { args, ctx ->
            val count = args.optInt("count", 8).coerceIn(1, 30)
            val includeSystem = args.optBoolean("include_system", false)
            val raw = sh(MEM_CMD, limit = 512 * 1024)
            var procs = parseProcMem(raw)
            // ps 的 -o 选项在别的 ROM 上不一定支持;真解析不出来再退回慢但通用的 dumpsys
            if (procs.size < MIN_PLAUSIBLE_PROCS) {
                Log.i(TAG, "ps/smaps path yielded only ${procs.size} procs, " +
                        "falling back to dumpsys meminfo")
                procs = parseMeminfo(sh(MEM_CMD_FALLBACK, limit = 512 * 1024))
            }
            if (procs.isEmpty()) return@Spec "error: 没能解析出内存占用(可能没有 root 权限)"

            val pm = ctx?.packageManager
            val sb = StringBuilder()
            Regex("MemTotal:\\s+(\\d+) kB").find(raw)?.let { m ->
                sb.append("总内存 ").append(mb(m.groupValues[1].toLong()))
                Regex("MemAvailable:\\s+(\\d+) kB").find(raw)?.let {
                    sb.append("，可用 ").append(mb(it.groupValues[1].toLong()))
                }
                sb.append('\n')
            }
            sb.append("内存占用前 ").append(count).append(" 名:\n")
            var n = 0
            for ((pkg, kb) in procs) {
                // 进程名可能是 com.foo:remote / com.foo:push,归到主包名下看应用名
                val base = pkg.substringBefore(':')
                val info = pm?.let { runCatching { it.getApplicationInfo(base, 0) }.getOrNull() }
                val isSystem = info == null ||
                        (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystem && !includeSystem) continue
                val label = info?.let { pm.getApplicationLabel(it).toString() }
                sb.append("  ").append(++n).append(". ")
                    .append(if (label != null && label != pkg) "$label ($pkg)" else pkg)
                    .append(" — ").append(mb(kb)).append('\n')
                if (n >= count) break
            }
            if (n == 0) {
                sb.append("  (前 ").append(procs.size)
                    .append(" 个进程全是系统进程；想看它们请用 include_system=true)")
            }
            sb.toString()
        }
    )

    /**
     * 两段式取内存占用,一次 su 跑完。
     *
     * 为什么不直接 `dumpsys meminfo`:它挨个进程发 binder、让每个进程自己遍历 smaps 算 PSS,
     * 这台机器 730 个进程实测 **5.42 秒**(工具整体 8 秒,用户能明显感到卡)。
     *
     * 也不能只用 `ps` 的 RSS(0.04 秒但不准):RSS 把共享页在每个进程里重复计,
     * 实测微信 RSS 760MB / PSS 551MB,虚高 27%,排名也会被 system_server 这类
     * 大量共享框架内存的进程带偏。
     *
     * 所以:**先用便宜的 RSS 排个序选出前 40 名候选,再只对这 40 个读
     * `/proc/<pid>/smaps_rollup`**(内核直接给算好的 Pss,不用逐页遍历)。
     * 实测 0.50 秒,PSS 精度不变。落选的进程 RSS 都比第 40 名小,
     * PSS ≤ RSS,所以不可能挤进前几名 —— 这个截断是安全的。
     */
    // `${'$'}` 是 Kotlin 转义:这些 $ 要原样交给 shell,不能被字符串模板吃掉。
    private val MEM_CMD = buildString {
        val d = "$"
        append("echo '--PS--'; PS=$d(ps -A -o PID,RSS,NAME);")
        append(" echo \"${d}PS\" | sort -k2 -rn | head -40;")
        append("echo '--PSS--';")
        append(" grep -s '^Pss:' $d(echo \"${d}PS\" | sort -k2 -rn | head -40 |")
        append(" while read p r n; do echo /proc/${d}p/smaps_rollup; done);")
        append("echo '--MEM--'; grep -E 'MemTotal|MemAvailable' /proc/meminfo")
    }

    /**
     * ps 这条路少于这么多个进程就判定为"没采到",退回 dumpsys。
     *
     * MEM_CMD 里是 `head -40`,任何一台正常开机的机器都必然凑满 40 条,所以这个阈值
     * 不会误伤。它防的是**采到了几条、但全是垃圾**的情况 —— 早先这里只判 `isEmpty()`,
     * 于是真机上翻车:HyperOS 把应用的 /proc 挂成 `hidepid=invisible`(见
     * `/proc/<pid>/mountinfo`),而我们 su 出来的 root **没有 READPROC(gid 3009)
     * 补充组、也没有 CAP_SYS_PTRACE**(KernelSU 按 App Profile 授权,不一定是完整 root),
     * 于是 `ps -A` 只看得见 su 自己拉起来的 ps/sh 两三个进程。
     * 非空 → 不触发 fallback → 工具一本正经地回"内存占用前 10 名:1. ps — 5MB"。
     * 模型不信这个结果,换 include_system 再调、再改用 run_shell 手搓 ps,
     * 六轮 MAX_TOOL_ITERATIONS 烧光,用户看到"工具调用超过上限,未得到最终答案"。
     *
     * dumpsys 走 binder 问 AMS,不读 /proc,所以完全不受 hidepid 影响 —— 这也是为什么
     * 判据要落在"结果像不像真的"上,而不是"命令有没有报错":那条 ps 是**成功返回**的。
     */
    private const val MIN_PLAUSIBLE_PROCS = 20

    /**
     * su 自己这一串管道进程。正常情况下它们 RSS 只有几 MB、排不进前 40,
     * 但 hidepid 挡住别人时它们就是全部,列出来纯属噪音。
     */
    private val SHELL_NOISE = setOf("ps", "sh", "sort", "head", "grep", "wc", "toybox", "awk")

    /** ps -o 不被支持时的退路:慢(5 秒+)但哪儿都有。 */
    private const val MEM_CMD_FALLBACK =
        "dumpsys meminfo | awk '/by process:/{p=1} /by OOM adjustment:/{p=0} p';" +
        "echo '--MEM--'; grep -E 'MemTotal|MemAvailable' /proc/meminfo"

    /**
     * 解析 MEM_CMD 的输出,返回按 KB 降序的 (进程名, KB)。
     *
     * `--PS--` 段:`22538 760084 com.tencent.mm`(pid / RSS / 进程名)
     * `--PSS--` 段:`/proc/22538/smaps_rollup:Pss:   551174 kB`
     *
     * 用 pid 把两段拼起来,**有 PSS 用 PSS,没有才退回 RSS** ——
     * 内核线程和刚退出的进程没有 smaps_rollup(或读不到),不能因此把它们丢了。
     */
    private fun parseProcMem(raw: String): List<Pair<String, Long>> {
        val psLine = Regex("^\\s*(\\d+)\\s+(\\d+)\\s+(\\S+)\\s*$")
        val pssLine = Regex("/proc/(\\d+)/smaps_rollup:Pss:\\s+(\\d+)")
        val names = LinkedHashMap<String, String>()   // pid -> 进程名
        val rss = HashMap<String, Long>()
        val pss = HashMap<String, Long>()
        var section = ""
        for (l in raw.lineSequence()) {
            when {
                l.startsWith("--") -> { section = l; continue }
                section.startsWith("--PS--") -> psLine.find(l)?.let { m ->
                    names[m.groupValues[1]] = m.groupValues[3]
                    rss[m.groupValues[1]] = m.groupValues[2].toLong()
                }
                section.startsWith("--PSS--") -> pssLine.find(l)?.let { m ->
                    pss[m.groupValues[1]] = m.groupValues[2].toLong()
                }
            }
        }
        return names.entries
            .mapNotNull { (pid, name) ->
                if (name.substringAfterLast('/') in SHELL_NOISE) return@mapNotNull null
                val kb = pss[pid] ?: rss[pid] ?: return@mapNotNull null
                name to kb
            }
            .sortedByDescending { it.second }
    }

    /**
     * 从 meminfo 的排序表里抠出 (进程名, KB)。表里的行长这样:
     *   `   350,123K: com.tencent.mm (pid 1234 / activities)`
     * 数字带千位逗号,得先去掉再转 Long。
     *
     * 输出里 PSS 和 RSS 两张表都在,**只能收一张** —— 两段都收会让同一个应用出现两次、
     * 数字还对不上。按表头挑 PSS,理由和 ps 那条路一样:RSS 把共享页在每个进程里重复计,
     * 虚高且会带偏排名。
     *
     * 注意这张 PSS 表的数字会**大于**同一次 dumpsys 里的 RSS(实测微信 RSS 304MB /
     * PSS 617MB)。纯 PSS 不可能超过 RSS,多出来的是换出到 ZRAM 的那部分 —— MIUI 压缩
     * 内存用得凶,这些页不驻留所以不计进 RSS,但确实还是这个应用占着的。问"内存被谁吃了"
     * 的时候算上它更贴题,所以不做修正。
     *
     * 不能按出现顺序挑:老版本 Android 是 PSS 在前,Android 14 起默认不算 PSS,
     * 这台 HyperOS 上是 `Total RSS by process:` 在前、PSS 在后。原先"取第一张表"的写法
     * 在这里恰好取到了 RSS。PSS 表缺席时(有些 ROM 真的只给 RSS)退回第一张有内容的表。
     */
    private fun parseMeminfo(raw: String): List<Pair<String, Long>> {
        val line = Regex("^\\s*([\\d,]+)K:\\s+(\\S+)")
        val tables = LinkedHashMap<String, MutableList<Pair<String, Long>>>()
        var cur: MutableList<Pair<String, Long>>? = null
        for (l in raw.lineSequence()) {
            if (l.contains("by process:")) {
                cur = tables.getOrPut(l.trim()) { ArrayList() }
                continue
            }
            val m = line.find(l)
            if (m == null) {
                // 表和表之间夹着别的段落(OOM adjustment、category…),空行不算结束,
                // 但一碰到不认识的正文就收手,免得把隔壁段的数字吃进来。
                if (l.isNotBlank()) cur = null
                continue
            }
            cur?.add(m.groupValues[2] to m.groupValues[1].replace(",", "").toLong())
        }
        val pick = tables.entries.firstOrNull { it.key.contains("PSS", true) && it.value.isNotEmpty() }
            ?: tables.entries.firstOrNull { it.value.isNotEmpty() }
        return pick?.value?.sortedByDescending { it.second }.orEmpty()
    }

    private fun mb(kb: Long): String =
        if (kb >= 1024L * 1024) String.format(Locale.US, "%.1fGB", kb / 1024.0 / 1024.0)
        else "${kb / 1024}MB"

    // ---------------------------------------------------------------- 存储占用

    /**
     * "哪些应用最占存储"。和 top_memory_apps 是同一个坑的另一半:没有专用工具时
     * 模型只能拿 run_shell 硬凑,而这件事在 shell 里**没有一条便宜的路** ——
     * 拿 `du` 递归 /data/data 要遍历几十万个文件,实测直接撞穿 SHELL_TIMEOUT_SEC(15s)
     * 只回半截;`pm list packages` 只有包名没有体积;`dumpsys package` 几十万字符,
     * 一返回就被 MAX_TOOL_OUTPUT(6000)拦腰截断。模型于是换着命令重试,
     * 六轮 MAX_TOOL_ITERATIONS 烧光,用户看到的就是"工具调用超过上限,未得到最终答案"。
     *
     * 正路是 StorageStatsManager:它读的是**内核 quota 记账**(和"设置-存储"里那个
     * 数字同源),完全不遍历文件,一个包一次 binder 就同时拿到 apk / 数据 / 缓存三个数。
     */
    private val topStorageApps = Spec(
        name = "top_storage_apps",
        description = "获取各应用占用的存储空间",
        modelHint = "统计应用大小 + 数据 + 缓存。" +
                "用户问「哪些应用最占存储」「存储空间被谁用了」「手机空间不够了」时用。" +
                "返回会附带机身存储总量和剩余。默认不含系统应用。",
        params = listOf(
            Param("count", "integer", "返回前几名，默认 8"),
            Param("include_system", "boolean", "是否包含系统应用，默认 false")
        ),
        handler = { args, ctx -> topStorage(args, ctx) }
    )

    /** 单个应用的体积。`data` 含 `cache`(两条数据源都是这个语义),所以 total = app + data。 */
    private data class AppSize(val pkg: String, val app: Long, val data: Long, val cache: Long)

    /** 扫描结果。`partial` = 预算烧完提前收工,排名可能不全,得对用户讲明。 */
    private class StorageScan(val apps: List<AppSize>, val partial: Boolean)

    /** quota 没开时 queryStatsForPackage 会退化成真的遍历文件,几百个包能跑几十秒。 */
    private const val STORAGE_SCAN_BUDGET_MS = 6000L

    private fun topStorage(args: JSONObject, ctx: Context?): String {
        val count = args.optInt("count", 8).coerceIn(1, 30)
        val includeSystem = args.optBoolean("include_system", false)
        val pm = ctx?.packageManager

        var scan = ctx?.let { queryStorageStats(it) } ?: StorageScan(emptyList(), false)
        if (scan.apps.isEmpty()) {
            // 退路:每日任务 DiskStatsLoggingService 落在 /data/system/diskstats_cache.json
            // 里的快照,可能是昨天的,但总比没有强。
            Log.i(TAG, "StorageStatsManager yielded nothing, falling back to dumpsys diskstats")
            scan = StorageScan(parseDiskStats(sh("dumpsys diskstats", limit = 512 * 1024)), false)
        }
        if (scan.apps.isEmpty()) {
            // 这条错误串是写给模型看的:必须明说"别重试",否则它会退回 run_shell
            // 自己拼命令,又把六轮烧光 —— 那正是本工具要解决的问题。
            return "error: 取不到各应用的存储占用（没有 PACKAGE_USAGE_STATS 权限，" +
                    "dumpsys diskstats 也没有缓存数据）。请直接如实告知用户这项查不到，" +
                    "不要改用 run_shell 或其它工具重试。"
        }

        val sb = StringBuilder()
        // 总量/剩余单独取:拿不到就不打印,别让整个工具因此失败
        storageTotals(ctx)?.let { sb.append(it).append('\n') }
        sb.append("占用存储前 ").append(count).append(" 名")
        if (!includeSystem) sb.append("（不含系统应用）")
        sb.append(":\n")

        var n = 0
        for (a in scan.apps.sortedByDescending { it.app + it.data }) {
            val info = pm?.let { runCatching { it.getApplicationInfo(a.pkg, 0) }.getOrNull() }
            val isSystem = when {
                pm == null -> false        // 没有 PackageManager 就没法判断，全给出去
                info == null -> true       // 已卸载 / 属于别的用户，当系统项滤掉
                else -> (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            }
            if (isSystem && !includeSystem) continue
            val label = info?.let { pm.getApplicationLabel(it).toString() }
            sb.append("  ").append(++n).append(". ")
                .append(if (label != null && label != a.pkg) "$label (${a.pkg})" else a.pkg)
                .append(" — ").append(humanBytes(a.app + a.data))
                .append("（应用 ").append(humanBytes(a.app))
                .append("，数据 ").append(humanBytes((a.data - a.cache).coerceAtLeast(0)))
                .append("，缓存 ").append(humanBytes(a.cache)).append("）\n")
            if (n >= count) break
        }
        if (n == 0) {
            sb.append("  （统计到的 ").append(scan.apps.size)
                .append(" 项全是系统应用；想看它们请用 include_system=true）")
        }
        if (scan.partial) {
            sb.append("（本机未启用存储配额，只来得及统计 ").append(scan.apps.size)
                .append(" 个应用，排名可能不完整）\n")
        }
        return sb.toString()
    }

    /**
     * StorageStatsManager 路线。需要 PACKAGE_USAGE_STATS ——
     * 没有的话**第一个包**就抛 SecurityException,整体放弃换下一条路;
     * 单个包失败(正在卸载、属于别的用户)只跳过它自己。
     */
    private fun queryStorageStats(ctx: Context): StorageScan {
        val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE)
                as? android.app.usage.StorageStatsManager ?: return StorageScan(emptyList(), false)
        val uuid = android.os.storage.StorageManager.UUID_DEFAULT
        val user = android.os.Process.myUserHandle()
        val out = ArrayList<AppSize>()
        val deadline = System.currentTimeMillis() + STORAGE_SCAN_BUDGET_MS
        for (app in ctx.packageManager.getInstalledApplications(0)) {
            val st = try {
                ssm.queryStatsForPackage(uuid, app.packageName, user)
            } catch (e: SecurityException) {
                Log.i(TAG, "no PACKAGE_USAGE_STATS in this process: $e")
                return StorageScan(emptyList(), false)
            } catch (t: Throwable) {
                continue
            }
            out.add(AppSize(app.packageName, st.appBytes, st.dataBytes, st.cacheBytes))
            if (System.currentTimeMillis() > deadline) {
                Log.i(TAG, "storage scan budget exceeded after ${out.size} packages")
                return StorageScan(out, true)
            }
        }
        return StorageScan(out, false)
    }

    /**
     * `dumpsys diskstats` 里的四行平行数组(单位是字节):
     *   Package Names: ["com.foo","com.bar"]
     *   App Sizes: [1234,5678]
     *   App Data Sizes: [...]
     *   App Cache Sizes: [...]
     * 按下标对齐。缺哪条就当 0,不能因为少一条把整份数据丢了。
     */
    private fun parseDiskStats(raw: String): List<AppSize> {
        fun arr(label: String): List<String>? =
            Regex("^$label:\\s*\\[(.*)\\]\\s*$", RegexOption.MULTILINE)
                .find(raw)?.groupValues?.get(1)
                ?.split(',')
                ?.map { it.trim().trim('"') }
                ?.filter { it.isNotEmpty() }
        val names = arr("Package Names") ?: return emptyList()
        val app = arr("App Sizes").orEmpty()
        val data = arr("App Data Sizes").orEmpty()
        val cache = arr("App Cache Sizes").orEmpty()
        return names.mapIndexed { i, pkg ->
            AppSize(
                pkg,
                app.getOrNull(i)?.toLongOrNull() ?: 0L,
                data.getOrNull(i)?.toLongOrNull() ?: 0L,
                cache.getOrNull(i)?.toLongOrNull() ?: 0L
            )
        }
    }

    /** 机身存储总量/剩余。StorageStatsManager 的这两个方法不需要任何权限。 */
    private fun storageTotals(ctx: Context?): String? {
        if (ctx == null) return null
        runCatching {
            val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE)
                    as android.app.usage.StorageStatsManager
            val uuid = android.os.storage.StorageManager.UUID_DEFAULT
            val total = ssm.getTotalBytes(uuid)
            val free = ssm.getFreeBytes(uuid)
            return "机身存储共 ${humanBytes(total)}，已用 ${humanBytes(total - free)}，" +
                    "剩余 ${humanBytes(free)}"
        }
        return runCatching {
            val fs = android.os.StatFs("/data")
            "/data 共 ${humanBytes(fs.blockCountLong * fs.blockSizeLong)}，" +
                    "剩余 ${humanBytes(fs.availableBlocksLong * fs.blockSizeLong)}"
        }.getOrNull()
    }

    private fun humanBytes(b: Long): String = when {
        b >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2fGB", b / 1024.0 / 1024 / 1024)
        b >= 1024L * 1024 -> String.format(Locale.US, "%.0fMB", b / 1024.0 / 1024)
        else -> "${b / 1024}KB"
    }

    // ---------------------------------------------------------------- 应用

    private val listApps = Spec(
        name = "list_apps",
        description = "获取已安装的应用信息(包名 + 显示名)",
        modelHint = "用户问「我装了哪些应用」「有没有装 XXX」「找一下 XX 的包名」时用。默认只列第三方应用。",
        params = listOf(
            Param("filter", "string", "按名称/包名过滤的关键词，留空返回全部第三方应用"),
            Param("include_system", "boolean", "是否包含系统应用，默认 false")
        ),
        handler = { args, ctx ->
            val filter = args.optString("filter", "").trim()
            val includeSystem = args.optBoolean("include_system", false)
            val pm = ctx?.packageManager ?: return@Spec "error: no context available"
            val sb = StringBuilder()
            var n = 0
            for (app in pm.getInstalledApplications(0)) {
                val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystem && !includeSystem) continue
                val label = pm.getApplicationLabel(app).toString()
                if (filter.isNotEmpty() &&
                    !label.contains(filter, true) && !app.packageName.contains(filter, true)
                ) continue
                sb.append(label).append(" (").append(app.packageName).append(")\n")
                if (++n >= 200) break
            }
            if (n == 0) "没有匹配的应用" else sb.toString()
        }
    )

    private val launchApp = Spec(
        name = "launch_app",
        description = "启动应用",
        modelHint = "支持按显示名或包名模糊匹配。用户说「打开微信」「帮我启动抖音」时用。",
        params = listOf(Param("name", "string", "应用显示名或包名", required = true)),
        mutating = true,
        handler = { args, ctx ->
            val name = args.optString("name", "").trim()
            if (name.isEmpty()) return@Spec "error: empty name"
            val pm = ctx?.packageManager ?: return@Spec "error: no context available"
            // 先当包名试,不中再按显示名找
            var pkg = pm.getInstalledApplications(0).firstOrNull { it.packageName == name }?.packageName
            if (pkg == null) {
                pkg = pm.getInstalledApplications(0).firstOrNull {
                    pm.getApplicationLabel(it).toString().equals(name, true)
                }?.packageName
            }
            if (pkg == null) {
                pkg = pm.getInstalledApplications(0).firstOrNull {
                    pm.getApplicationLabel(it).toString().contains(name, true)
                }?.packageName
            }
            if (pkg == null) return@Spec "error: 没找到应用 \"$name\""
            val intent = pm.getLaunchIntentForPackage(pkg)
                ?: return@Spec "error: $pkg 没有可启动的入口"
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            "已启动 $pkg"
        }
    )

    /**
     * 给微信联系人发消息。走无障碍服务真的去点微信界面 ——
     * 小爱原生这条路在双开微信上是死的,它只会回"暂不支持微信双开功能"。
     *
     * 跨进程:本工具跑在小爱进程里,服务在模块自己进程,靠 ConfigProvider 过桥。
     * 整个 UI 流程要好几秒,这里同步等,所以调用方必须在后台线程(AiClient 已经是)。
     */
    private val sendMessage = Spec(
        name = "send_message",
        description = "微信指定给某个联系人发消息。联系人名必须和微信里显示的备注/昵称**完全一致**",
        modelHint = "通过无障碍服务发送，解决小爱原生不支持微信双开的问题。" +
                "名字对不上就找不到人。整个流程需要数秒。用户说「给张三发微信说晚上不回家吃饭」时用。",
        params = listOf(
            Param("contact", "string", "联系人在微信里的显示名，必须完全一致", required = true),
            Param("text", "string", "要发送的消息正文", required = true),
            Param("send", "boolean", "true=直接发出；false=只打开会话并填好正文，由用户自己按发送。默认 true")
        ),
        mutating = true,
        handler = { args, ctx ->
            val contact = args.optString("contact", "").trim()
            val text = args.optString("text", "").trim()
            val send = if (args.has("send")) args.optBoolean("send", true) else true
            when {
                ctx == null -> "error: no context available"
                contact.isEmpty() || text.isEmpty() -> "error: contact 或 text 为空"
                else -> {
                    // 微信必须在前台,无障碍服务才拿得到它的控件树
                    // 只管拉起来,不在这里 sleep 等 —— 等多久合适这里判断不了。
                    // 服务那边会轮询等微信真到前台(小爱的窗口常压在上面)。
                    ctx.packageManager.getLaunchIntentForPackage(UI_AUTO_WECHAT)?.let {
                        it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(it)
                    }
                    val extras = android.os.Bundle().apply {
                        putString("contact", contact)
                        putString("text", text)
                        putString("send", send.toString())
                    }
                    val out = ctx.contentResolver.call(
                        android.net.Uri.parse("content://io.mo.xiaoaiplug.config"),
                        "send_message", null, extras
                    )
                    out?.getString("result") ?: "error: 模块进程无响应（无障碍服务可能没开）"
                }
            }
        }
    )

    private const val UI_AUTO_WECHAT = "com.tencent.mm"

    // ---------------------------------------------------------------- 文件

    private val readFile = Spec(
        name = "read_file",
        description = "读取文本文件内容(root)。用于查看日志、配置等。",
        modelHint = "支持 /data、/sdcard、/system 等任意路径。仅适合文本文件，二进制文件读了没有意义。",
        params = listOf(
            Param("path", "string", "绝对路径", required = true),
            Param("max_lines", "integer", "最多读多少行，默认 200")
        ),
        handler = { args, _ ->
            val path = args.optString("path", "").trim()
            if (path.isEmpty()) return@Spec "error: empty path"
            val maxLines = args.optInt("max_lines", 200).coerceIn(1, 2000)
            sh("head -n $maxLines ${shellQuote(path)}")
        }
    )

    // ---------------------------------------------------------------- 系统设置

    private val getSetting = Spec(
        name = "get_setting",
        description = "读取系统设置项的值(Settings.System/Secure/Global)。",
        modelHint = "用户问「屏幕亮度多少」「自动锁屏时间是多少」时用。" +
                "常用 key: screen_brightness(system)、screen_off_timeout(system)。",
        params = listOf(
            Param("namespace", "string", "system / secure / global", required = true,
                enum = listOf("system", "secure", "global")),
            Param("key", "string", "设置项名，如 screen_brightness", required = true)
        ),
        handler = { args, _ ->
            val ns = args.optString("namespace", "system").trim()
            val key = args.optString("key", "").trim()
            if (key.isEmpty()) return@Spec "error: empty key"
            sh("settings get ${shellQuote(ns)} ${shellQuote(key)}")
        }
    )

    private val setSetting = Spec(
        name = "set_setting",
        description = "修改系统设置项",
        modelHint = "用户说「把自动锁屏改成 1 分钟」「打开飞行模式」时用。" +
                "常用 key: screen_off_timeout(毫秒, system)、airplane_mode_on(0/1, global)。",
        params = listOf(
            Param("namespace", "string", "system / secure / global", required = true,
                enum = listOf("system", "secure", "global")),
            Param("key", "string", "设置项名", required = true),
            Param("value", "string", "要设置的值", required = true)
        ),
        mutating = true,
        handler = { args, _ ->
            val ns = args.optString("namespace", "system").trim()
            val key = args.optString("key", "").trim()
            val value = args.optString("value", "").trim()
            if (key.isEmpty()) return@Spec "error: empty key"
            sh("settings put ${shellQuote(ns)} ${shellQuote(key)} ${shellQuote(value)} && " +
                    "settings get ${shellQuote(ns)} ${shellQuote(key)}")
        }
    )

    // ---------------------------------------------------------------- 媒体 / 音量

    private val mediaControl = Spec(
        name = "media_control",
        description = "控制正在播放的媒体",
        modelHint = "用户说「暂停音乐」「下一首」「停止播放」时用。",
        params = listOf(
            Param("action", "string", "动作", required = true,
                enum = listOf("play", "pause", "toggle", "next", "previous", "stop"))
        ),
        mutating = true,
        handler = { args, _ ->
            val key = when (args.optString("action", "toggle").trim().lowercase()) {
                "play" -> 126
                "pause" -> 127
                "next" -> 87
                "previous", "prev" -> 88
                "stop" -> 86
                else -> 85  // toggle
            }
            sh("input keyevent $key")
            "已发送媒体按键 $key"
        }
    )

    private val setVolume = Spec(
        name = "set_volume",
        description = "设置、查询媒体音量",
        modelHint = "用户问「现在音量多大」「把音量调到 50%」「静音」时用。" +
                "不传 percent 则只返回当前音量、不做修改。",
        params = listOf(
            Param("percent", "integer", "目标音量百分比 0-100；不传则只返回当前音量")
        ),
        mutating = true,
        handler = { args, ctx ->
            val am = ctx?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                ?: return@Spec "error: no audio service"
            val stream = android.media.AudioManager.STREAM_MUSIC
            val max = am.getStreamMaxVolume(stream)
            if (!args.has("percent")) {
                val cur = am.getStreamVolume(stream)
                return@Spec "当前媒体音量 ${cur * 100 / max}% ($cur/$max)"
            }
            val pct = args.optInt("percent", 50).coerceIn(0, 100)
            val target = (pct * max + 50) / 100
            am.setStreamVolume(stream, target, 0)
            "已设为 $pct% ($target/$max)"
        }
    )

    // ---------------------------------------------------------------- 杂项

    private val currentTime = Spec(
        name = "current_time",
        description = "获取当前系统时间",
        modelHint = "精确到秒，含星期几。用户问「现在几点了」「今天星期几」「今天几号」时用。",
        handler = { _, _ ->
            val fmt = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm:ss", Locale.CHINA)
            fmt.format(Date())
        }
    )

    private val recentNotifications = Spec(
        name = "recent_notifications",
        description = "获取最近的通知",
        modelHint = "含包名、标题、正文。用户问「刚才来了什么通知」「有没有新消息」时用。",
        handler = { _, _ ->
            sh("dumpsys notification --noredact | grep -E 'pkg=|android.title=|android.text=' | head -n 60")
        }
    )

    /**
     * 注意 `mutating = false`。
     *
     * [Spec.mutating] 的语义是"会改变**设备**状态,本轮不归我们就必须拦下"
     * (见上面那段事故注释)。写一条记忆不碰设备,而且用户提到自己信息的那句话
     * 常常正好是小爱自己接管的轮次 —— 标成 mutating 的话恰恰在最该记的时候记不下来。
     */
    private val saveMemory = Spec(
        name = SAVE_MEMORY,
        description = "记住关于用户的长期信息(口味、家庭成员、习惯、称呼等)",
        modelHint = "用户在闲聊中透露出以后还用得上的个人信息时**主动**调用，不用先征求同意，" +
                "也不用在回答里提起「我记住了」。一次一条，写成自包含的陈述句" +
                "（「用户对花生过敏」而不是「对，是的」）。" +
                "只记稳定的事实，不要记一次性的东西（今天几点开会、刚才问的天气）。" +
                "已经记过的会自动去重，不用担心重复。",
        params = listOf(
            Param("content", "string", "要记住的一句话，自包含的陈述句", required = true)
        ),
        handler = { args, ctx ->
            val content = args.optString("content", "").trim()
            if (content.isEmpty()) "error: content 为空"
            else MemoryClient.save(ctx, content)
        }
    )

    // ---------------------------------------------------------------- shell 实现

    /** 单引号包裹,内部单引号转义,避免路径/值里的空格和元字符把命令拆了。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 优先 root;su 不可用退回普通 shell(很多工具会因此失效,输出里会标注)。
     *
     * `limit` 默认是"直接返回给模型"的上限。工具内部要**先读后解析**时必须显式放大 ——
     * 否则会在解析之前就把数据截掉(见 wifi_info 里那段事故注释)。
     */
    private fun sh(command: String, limit: Int = MAX_TOOL_OUTPUT): String {
        runCatching { return exec(arrayOf("su", "-c", command), limit) }
        return runCatching { "(无 root，结果可能不完整) " + exec(arrayOf("sh", "-c", command), limit) }
            .getOrElse { "shell error: ${it.message}" }
    }

    private fun exec(argv: Array<String>, limit: Int = MAX_TOOL_OUTPUT): String {
        val proc = ProcessBuilder(*argv)
            .redirectErrorStream(true)
            .directory(File("/"))
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(SHELL_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return (output + "\n(命令超时)").take(limit)
        }
        return output.ifBlank { "(无输出, exit=${proc.exitValue()})" }.take(limit)
    }
}
