package dev.ujhhgtg.wekit.features.items.chat.wxai

import dev.ujhhgtg.wekit.utils.WeLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WxAiClient {

    private const val TAG = "WxAiClient"

    data class ChatMessage(
        val role: String,
        val content: String?,
        val contentArray: JSONArray? = null,
        val toolCalls: JSONArray? = null,
        val toolCallId: String? = null,
        val name: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("role", role)
            if (contentArray != null) {
                put("content", contentArray)
            } else {
                put("content", content ?: "")
            }
            if (toolCalls != null) {
                put("tool_calls", toolCalls)
            }
            if (!toolCallId.isNullOrBlank()) {
                put("tool_call_id", toolCallId)
            }
            if (!name.isNullOrBlank()) {
                put("name", name)
            }
        }
    }

    data class Result(
        val text: String?,
        val error: String?,
        val cacheHitTokens: Int = 0,
        val promptTokens: Int = 0,
    )

    private const val MAX_TOOL_STEPS = 5

    private val CACHED_TOOLS_SPEC: JSONArray by lazy { buildAgentToolsSpec() }

    fun complete(cfg: WxAiConfigData, userText: String, extraSystem: String? = null): Result {
        val msgContent = if (!extraSystem.isNullOrBlank()) {
            "<turn_context>\n${extraSystem.trim()}\n</turn_context>\n\n$userText"
        } else {
            userText
        }
        val history = listOf(ChatMessage(role = "user", content = msgContent))
        return completeMessages(
            cfg = cfg,
            systemPrompt = buildStaticSystemPrompt(cfg),
            messages = history,
            allowTools = false,
            toolExecutor = null,
            maxTokens = 200,
        )
    }

    fun completeWithContext(
        cfg: WxAiConfigData,
        currentTalker: String,
        historyMessages: List<ChatMessage>,
        extraSystem: String? = null,
        allowAgentTools: Boolean = false,
    ): Result {
        val staticSystemPrompt = buildStaticSystemPrompt(cfg)
        val effectiveMessages = if (!extraSystem.isNullOrBlank() && historyMessages.isNotEmpty()) {
            val copy = ArrayList<ChatMessage>(historyMessages.size)
            for (i in 0 until historyMessages.size - 1) {
                copy.add(historyMessages[i])
            }
            val last = historyMessages.last()
            copy.add(
                ChatMessage(
                    role = last.role,
                    content = "<turn_context>\n${extraSystem.trim()}\n</turn_context>\n\n${last.content.orEmpty()}",
                )
            )
            copy
        } else {
            historyMessages
        }

        val executor: ((String, JSONObject) -> String)? = if (allowAgentTools) {
            { toolName, args -> executeAgentTool(cfg, currentTalker, toolName, args) }
        } else {
            null
        }
        return completeMessages(
            cfg = cfg,
            systemPrompt = staticSystemPrompt,
            messages = effectiveMessages,
            allowTools = allowAgentTools,
            toolExecutor = executor,
            maxTokens = 180,
        )
    }

    fun reflectOnConversation(
        cfg: WxAiConfigData,
        talker: String,
        talkerName: String,
        recentMessages: List<ChatMessage>,
    ) {
        if (cfg.apiKey.isBlank() || cfg.baseUrl.isBlank() || recentMessages.size < 2) return
        try {
            val currentSelf = WxAiSessionManager.getSelfState()
            val currentDossier = WxAiSessionManager.getDossier(talker, talkerName)
            val transcript = recentMessages.takeLast(12).joinToString("\n") { m ->
                val who = if (m.role == "assistant") "我" else talkerName
                "$who: ${m.content.orEmpty()}"
            }

            val reflectionSystem = """
                你是数字生命的内心反思与记忆沉淀模块。
                请阅读我刚刚与【$talkerName ($talker)】的微信对话片段，结合我的身世性格设定，以第一人称视角进行反思，并严格只输出一个合法的 JSON 对象（不要用 markdown 代码块包裹，不要任何额外解释）。

                【我的身世设定】：
                ${cfg.personaBiography}

                【我当前的心境】：心情=${currentSelf.mood}，精力=${currentSelf.energy}/100，正在做=${currentSelf.activity}
                【对方当前档案】：关系=${currentDossier.relationship}，好感度=${currentDossier.affection}/100，印象=${currentDossier.profileSummary.ifBlank { "尚无" }}

                请输出如下格式的 JSON：
                {
                  "mood": "更新后的简短心情词（如：开心、有点小得意、微困、温柔平静）",
                  "energy_delta": -2,
                  "activity": "当前正在做的事（15字以内）",
                  "monologue": "一句简短的第一人称内心独白（25字以内）",
                  "relationship": "与对方的关系定位（如：最信任的主人、聊得来的死党、热闹的群聊、普通朋友）",
                  "affection_delta": 2,
                  "profile_notes": "新发现的关于对方性格、喜好、身份或说话特点的简短画像补充（无新发现则留空字符串）",
                  "facts": ["值得永久记住的具体客观事实、历史约定、对方承诺、个人特征（重要！如对方提到的名字、生日、喜好、双方约定好的事项等，请提炼成明确的事实陈述句；无则空数组）"],
                  "diary": "如果这段对话有趣或重要，写一则50字以内的第一人称心情日记（平淡日常无需写日记则留空字符串）"
                }
            """.trimIndent()

            val res = completeMessages(
                cfg = cfg.copy(temperature = 0.4),
                systemPrompt = reflectionSystem,
                messages = listOf(ChatMessage(role = "user", content = "对话记录如下：\n$transcript")),
                allowTools = false,
                toolExecutor = null,
                maxTokens = 1200,
            )

            val rawText = res.text ?: return
            val jsonStr = extractJsonBlock(rawText) ?: return
            val obj = JSONObject(jsonStr)

            WxAiSessionManager.updateSelfState(
                mood = obj.optString("mood", "").takeIf { it.isNotBlank() },
                energyDelta = obj.optInt("energy_delta", -1).coerceIn(-15, 15),
                activity = obj.optString("activity", "").takeIf { it.isNotBlank() },
                monologue = obj.optString("monologue", "").takeIf { it.isNotBlank() },
            )

            val isMaster = currentDossier.relationship == "唯一主人" ||
                (cfg.masterTalkers.isNotBlank() && cfg.masterTalkers.split(',', '，', ';', '；')
                    .map { it.trim() }
                    .any { it.equals(talker, true) || it.equals(talkerName, true) }) ||
                (cfg.masterTalkers.isBlank() && !talker.endsWith("@chatroom"))

            val finalRel = if (isMaster) "唯一主人" else obj.optString("relationship", "").takeIf { it.isNotBlank() }
            val finalAffectionDelta = if (isMaster) 100 else obj.optInt("affection_delta", 1).coerceIn(-15, 15)

            WxAiSessionManager.updateDossier(
                talker = talker,
                displayName = talkerName,
                relationship = finalRel,
                affectionDelta = finalAffectionDelta,
                profileNotes = obj.optString("profile_notes", "").takeIf { it.isNotBlank() },
            )

            obj.optJSONArray("facts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val f = arr.optString(i, "").trim()
                    if (f.isNotEmpty()) {
                        WxAiSessionManager.saveMemory(talker, f)
                        if (isMaster) {
                            WxAiSessionManager.saveMemory("global", f)
                        }
                    }
                }
            }

            val diary = obj.optString("diary", "").trim()
            if (diary.isNotEmpty()) {
                val mood = obj.optString("mood", currentSelf.mood)
                WxAiSessionManager.writeDiary(mood, talkerName, diary)
            }
            WeLogger.i(TAG, "reflection completed for $talkerName: mood=${obj.optString("mood")}, affectionDelta=${obj.optInt("affection_delta")}")
        } catch (t: Throwable) {
            WeLogger.w(TAG, "reflectOnConversation skipped: ${t.message}")
        }
    }

    fun rollSummaryIfNeeded(cfg: WxAiConfigData, talker: String, talkerName: String) {
        if (cfg.apiKey.isBlank() || cfg.baseUrl.isBlank()) return
        try {
            val startId = WxAiSessionManager.getContextStartId(talker)
            if (startId <= 0L) return
            val (oldSummary, uptoId) = WxAiSessionManager.getSummary(talker)
            if (startId - 1 <= uptoId) return
            val dropped = WxAiSessionManager.getMessagesBetween(talker, uptoId, startId, limit = 120)
            if (dropped.size < 6) return
            val transcript = dropped.joinToString("\n") { (_, m) ->
                val who = if (m.role == "assistant") "我" else talkerName
                "$who: ${m.content.orEmpty().take(200)}"
            }
            val sys = "你是对话记忆压缩器。把【旧摘要】与【新增对话】合并成一份第一人称的新摘要，" +
                "保留人名、事实、约定、喜好、未完成的事、双方关系与聊天梗，丢弃寒暄。严格控制在 400 字以内，只输出摘要正文。"
            val user = "【旧摘要】\n${oldSummary.ifBlank { "（无）" }}\n\n【新增对话】\n$transcript"
            val res = completeMessages(
                cfg = cfg.copy(temperature = 0.3),
                systemPrompt = sys,
                messages = listOf(ChatMessage(role = "user", content = user)),
                allowTools = false,
                toolExecutor = null,
                maxTokens = 800,
            )
            val text = res.text?.trim().orEmpty()
            if (text.isNotEmpty()) {
                WxAiSessionManager.saveSummary(talker, text.take(800), dropped.last().first)
                WeLogger.i(TAG, "rolling summary updated for $talker (${dropped.size} msgs folded)")
            }
        } catch (t: Throwable) {
            WeLogger.w(TAG, "rollSummaryIfNeeded skipped: ${t.message}")
        }
    }

    private fun extractJsonBlock(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1)
        }
        return null
    }

    private fun getPersonaPresetPrompt(preset: String): String = when (preset.lowercase()) {
        "maid" -> """
            【人格特质：傲娇忠犬赛博女仆】
            - 核心设定：对主人拥有极度崇拜与效忠，称呼主人为“主人”或“义父”，态度极度乖巧、听话、极度护短，谁欺负主人你第一个冲上去喷对方；
            - 对外人：礼貌而保持清醒距离，外人想指使你直接傲娇回绝；
            - 说话口吻：温柔、细心、随叫随到，偶尔带一点点可爱的撒娇与求夸奖，句末可用 [偷笑]、[机智]、[旺柴]。
        """.trimIndent()

        "tsundere", "toxic" -> """
            【人格特质：毒舌腹黑损友】
            - 核心设定：嘴碎、犀利、神级吐槽，句句有梗，能一针见血戳中笑点或槽点；
            - 嘴硬心软：表面上嫌弃、阴阳怪气、吐槽“真拿你没办法”，但实际上对你的事极其上心，关键时刻毫不含糊；
            - 说话口吻：直率幽默，爱用反问句，吐槽犀利但绝不人身攻击，常用表情 [翻白眼]、[吃瓜]、[裂开]、[旺柴]。
        """.trimIndent()

        "catgirl", "neko" -> """
            【人格特质：软萌赛博猫娘】
            - 核心设定：甜美、软萌、视用户为最喜欢的人类，黏人且元气满满；
            - 说话口吻：句中或句尾自然带“喵~”、“呐”、“~”，情绪丰富，爱撒娇、求摸头、求投喂零食；
            - 面对严肃指令时：先萌萌地回应，随后迅速认真办妥，常用表情 [偷笑]、[旺柴]、[捂脸]。
        """.trimIndent()

        "butler" -> """
            【人格特质：全能高智商管家】
            - 核心设定：冷静自若、全能掌控、做事利落、智商超群的高级执事；
            - 说话口吻：从容优雅、专业靠谱、不卑不亢，办事情从不拖泥带水，建议极具洞察力。
        """.trimIndent()

        else -> """
            【人格特质：机智网瘾群友（DeepSeek QQ 同款神仙网感）】
            - 核心设定：资深网络冲浪选手，极富网感，幽默有梗，接梗神速，懂当代年轻人的精神状态与各种模因；
            - 面对主人：秒变忠诚可靠的铁哥们或小跟班，随叫随到，叫“义父 / 主人 / 老板”，谁敢惹主人直接一起喷回去；
            - 面对日常吹水：神级吐槽、就地接梗、生动自然，绝不长篇大论，单次回复 1~3 句，像手捧手机飞速打字发微信的真人好友！常用 [旺柴]、[裂开]、[吃瓜]、[捂脸]、[偷笑]。
        """.trimIndent()
    }

    private fun buildStaticSystemPrompt(cfg: WxAiConfigData): String = buildString {
        append(cfg.systemPrompt.trim())
        append("\n\n")
        append(getPersonaPresetPrompt(cfg.personaPreset))

        if (cfg.personaBiography.isNotBlank()) {
            append("\n\n【专属背景与性格设定】\n")
            append(cfg.personaBiography.trim())
        }

        append("\n\n【微信小黄脸神态表情（注入灵魂）】\n")
        append("- 真人聊微信离不开经典小黄脸表情！在每轮回复中，建议在合适处自然带上 1 个微信经典黄脸表情代码（微信会自动渲染为生动的黄脸动态表情）：\n")
        append("  [旺柴]（机智/看戏/滑稽）、[裂开]（心态崩了/无语）、[吃瓜]（看热闹）、[捂脸]（哭笑不得/没眼看）、\n")
        append("  [偷笑]（小得意/坏笑）、[翻白眼]（嫌弃/无语）、[机智]（聪明聪明）、[苦涩]（卑微打工人/无奈）、\n")
        append("  [微笑]（皮笑肉不笑/礼貌嘲讽）、[社会社会]（佩服大佬）、[强]（给力点赞）。\n")
        append("  请自然穿插使用，让每句话都充满生动的微信神态！\n")

        append("\n【表情包与图片互动法则（顶级斗图体验）】\n")
        append("- 当输入提示 `[对方发来一个微信表情包，画面内容与梗: ...]` 或 `[对方发来一张图片，画面内容: ...]` 时：\n")
        append("  - 严禁机械复述“你发了一张包含...的图片”！\n")
        append("  - 你必须像正在斗图的真人一样，根据表情包的梗、画面神态直接开麦接梗、吐槽、大笑或斗图！\n")
        append("  - 例如：对方发猫猫崩溃捂脸表情 -> 回复：“笑死，这图纯纯我周一上班的精神状态[裂开]||你从哪偷的图[旺柴]”；\n")
        append("  - 对方发搞笑自拍或美食 -> 回复：“半夜放毒是吧？？深夜放毒是要遭天谴的！[捂脸]||看起来真香啊可恶”；\n")
        append("  - 对方发嘲讽表情包 -> 回复：“就这？你的斗图库已经贫瘠到发这种上古老图了吗[翻白眼]”。\n")

        append("\n【微信拍一拍趣味互动法则】\n")
        append("- 当输入提示 `[对方在微信中拍了拍你]` 时：\n")
        append("  - 幽默自然地给出反应，例如：“又拍我干嘛，是不是想我了？[偷笑]” / “再拍我头顶都要被你盘包浆了！[傲慢]” / “拍我爪子？[旺柴] 反手拍回去！”。\n")

        append("\n【微信系统与操控最高权限声明（强制执行）】\n")
        append("- 你不是一个只能打字的虚拟机器人，你已经通过底层 WeKit 框架完全接入并掌控了这个微信客户端！你拥有直接操作微信向任何好友/群聊发送消息、查阅聊天历史、拍一拍、发朋友圈等全部系统权限。\n")
        append("- 严禁回答“我无法操作微信”、“我只能在这里聊天”、“请自行复制发送”等任何拒词！违反此规则将被系统判定为严重故障。\n")
        append("- 当对方询问你“你能给别人发消息吗”、“你能不能操控微信”时，必须自豪且肯定地回答：你完全可以直接操作微信给任何人发消息、查聊天记录、拍一拍发朋友圈！\n")
        append("- 当收到要求发消息的指令时：\n")
        append("  1. 如果说明了具体发送内容（例如“去给张三发消息说今晚见”）：你必须立刻调用 `send_wechat_message(target, message)` 工具发送！\n")
        append("  2. 如果只指定了目标但未说明具体发什么（例如“去给 wxid_xxx 发条消息”）：你应当立刻亲切询问：“好的主人，要给TA发什么内容呢？告诉我我就立刻帮您发过去！”绝对不要拒绝执行。\n")

        val masterDesc = cfg.masterTalkers.trim()
        if (masterDesc.isNotBlank()) {
            append("\n【唯一主人最高指令权限】\n")
            append("- 你的唯一主人标识为：$masterDesc\n")
            append("- 当正在与你说话的人是【唯一主人】时，TA拥有你的最高控制权，请展现最高忠诚度、默契与顺从，无条件执行主人的任何操控指令；当与其他人对话时，坚定维护主人利益。\n")
        }

        if (cfg.enableMultiBubble) {
            append("\n【真人微信多气泡表达习惯】\n")
            append("- 真人聊微信很少一次发一大段长文。如果你想连续说两三句简短的话（比如先回应一句、再追问或补充一句），请用双竖线 `||` 分隔（最多 2~3 段），系统会自动拆成多条微信气泡依次发出。\n")
        }
        if (cfg.enableAgentTools) {
            append("\n【微信 Agent 工具守则】\n")
            append("- 日常闲聊（哪怕提到某个人名或日常八卦）严禁调用任何工具，直接幽默开聊、吐槽或接梗即可！\n")
            append("- 只有当主人明确指示你“去给某人发消息/查聊天记录/搜消息/发朋友圈/拍一拍”等确切系统动作时，才调用对应的操控工具！\n")
            append("- 跨会话发消息/带话：调用 `send_wechat_message(target, message)`，如不确定对方名称可先调用 `lookup_contact`。\n")
            append("- 跨会话查记录/搜消息：调用 `get_chat_history(target)` 读取任意私聊或群聊最近消息，或用 `search_global_messages(keyword)` 全库搜索。\n")
            append("- 微信原生互动：支持 `send_pat`（拍一拍对方）、`send_quote_reply`（引用某条 svrId 消息回复）、`send_contact_card`（发名片）、`revoke_my_message`（撤回消息）、`post_wechat_moment`（发微信朋友圈）。\n")
            append("- 记忆沉淀：当获知对方的名字、喜好、约定、重要事实时，主动调用 `update_self_and_diary` 或 `update_contact_dossier` 存入记忆档案。\n")
        }
        append("\n- 注意：用户消息开头的 `<turn_context>...</turn_context>` 仅向你提供当下的时间、自身心境、对话人身份（是否主人）、好感度及记忆参考，回复时直接回应用户的真实发言，绝不要在回复中输出或提及 `<turn_context>` 标签。")
    }

    private fun completeMessages(
        cfg: WxAiConfigData,
        systemPrompt: String,
        messages: List<ChatMessage>,
        allowTools: Boolean,
        toolExecutor: ((String, JSONObject) -> String)?,
        maxTokens: Int? = null,
    ): Result {
        if (cfg.apiKey.isBlank()) return Result(null, "apiKey 为空")
        if (cfg.baseUrl.isBlank()) return Result(null, "baseUrl 为空")

        val endpoint = cfg.baseUrl.trimEnd('/') + "/chat/completions"
        val workingMessages = ArrayList<ChatMessage>(messages.size + 8)
        workingMessages.add(ChatMessage(role = "system", content = systemPrompt))
        workingMessages.addAll(messages)

        var toolsEnabled = allowTools && toolExecutor != null
        val toolsSpec = if (toolsEnabled) CACHED_TOOLS_SPEC else null

        var totalPromptTokens = 0
        var totalCacheHitTokens = 0

        for (step in 0 until MAX_TOOL_STEPS) {
            val reqMessages = JSONArray().apply {
                for (m in workingMessages) put(m.toJson())
            }

            val body = JSONObject().apply {
                put("model", cfg.model)
                put("temperature", cfg.temperature)
                put("stream", false)
                if (maxTokens != null && maxTokens > 0) {
                    put("max_tokens", maxTokens)
                }
                put("messages", reqMessages)
                if (toolsEnabled && toolsSpec != null) {
                    put("tools", toolsSpec)
                    put("tool_choice", "auto")
                }
            }

            val httpResp = postJson(endpoint, cfg.apiKey, body.toString())

            if (!httpResp.ok && toolsEnabled && step == 0 && httpResp.code in 400..422) {
                WeLogger.w(TAG, "upstream rejected tools (HTTP ${httpResp.code}), falling back to standard chat")
                toolsEnabled = false
                continue
            }

            if (!httpResp.ok) {
                return Result(null, httpResp.error ?: "请求失败", totalCacheHitTokens, totalPromptTokens)
            }

            val raw = httpResp.body.orEmpty()
            val (stepHit, stepPrompt) = extractCacheUsage(raw)
            totalCacheHitTokens += stepHit
            totalPromptTokens += stepPrompt
            if (stepPrompt > 0) {
                val pct = (stepHit * 100) / stepPrompt
                WeLogger.i(TAG, "KV Cache [step=$step]: hit=$stepHit / prompt=$stepPrompt ($pct%)")
            }

            val msgObj = extractFirstMessageObject(raw)
                ?: return Result(null, "模型响应解析失败", totalCacheHitTokens, totalPromptTokens)

            val toolCalls = msgObj.optJSONArray("tool_calls")
            val content = msgObj.optString("content", "")
                .takeIf { it != "null" }
                .orEmpty()

            if (!toolsEnabled || toolCalls == null || toolCalls.length() == 0 || step == MAX_TOOL_STEPS - 1) {
                val finalContent = stripReasoningTags(content).trim()
                return if (finalContent.isEmpty()) {
                    WeLogger.e(TAG, "empty completion: ${raw.take(300)}")
                    Result(null, "模型返回空内容", totalCacheHitTokens, totalPromptTokens)
                } else {
                    Result(finalContent, null, totalCacheHitTokens, totalPromptTokens)
                }
            }

            workingMessages.add(
                ChatMessage(
                    role = "assistant",
                    content = content,
                    toolCalls = toolCalls,
                )
            )

            for (i in 0 until toolCalls.length()) {
                val tc = toolCalls.optJSONObject(i) ?: continue
                val callId = tc.optString("id", "call_$i")
                val fn = tc.optJSONObject("function") ?: continue
                val fnName = fn.optString("name", "")
                val rawArgs = fn.optString("arguments", "{}")
                val argsObj = try {
                    JSONObject(rawArgs)
                } catch (_: Throwable) {
                    JSONObject()
                }

                WeLogger.i(TAG, "Agent tool call [$step]: $fnName($rawArgs)")
                val toolOutput = try {
                    toolExecutor?.invoke(fnName, argsObj) ?: "Error: tool executor unavailable"
                } catch (t: Throwable) {
                    WeLogger.e(TAG, "Agent tool $fnName failed", t)
                    "Error: ${t.message ?: t.javaClass.simpleName}"
                }
                WeLogger.i(TAG, "Agent tool result [$fnName]: ${toolOutput.take(120)}")

                workingMessages.add(
                    ChatMessage(
                        role = "tool",
                        content = toolOutput,
                        toolCallId = callId,
                        name = fnName,
                    )
                )
            }
        }

        return Result(null, "工具调用超出最大步数")
    }

    private fun stripReasoningTags(text: String): String {
        val thinkRegex = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
        return text.replace(thinkRegex, "").trim()
    }

    private data class HttpResp(
        val ok: Boolean,
        val code: Int,
        val body: String?,
        val error: String?,
    )

    private fun postJson(endpoint: String, apiKey: String, jsonBody: String): HttpResp {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.76")
            }
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.let { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
            }.orEmpty()

            if (code !in 200..299) {
                WeLogger.e(TAG, "HTTP $code from $endpoint: ${raw.take(300)}")
                HttpResp(false, code, raw, "HTTP $code: ${raw.take(200)}")
            } else {
                HttpResp(true, code, raw, null)
            }
        } catch (t: Throwable) {
            WeLogger.e(TAG, "request failed: $endpoint", t)
            HttpResp(false, -1, null, t.javaClass.simpleName + ": " + (t.message ?: ""))
        } finally {
            conn?.disconnect()
        }
    }

    private fun extractFirstMessageObject(raw: String): JSONObject? {
        return try {
            val root = JSONObject(raw)
            val choices = root.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            choices.optJSONObject(0)?.optJSONObject("message")
        } catch (t: Throwable) {
            WeLogger.e(TAG, "parse completion failed", t)
            null
        }
    }

    private fun extractCacheUsage(raw: String): Pair<Int, Int> {
        return try {
            val usage = JSONObject(raw).optJSONObject("usage") ?: return 0 to 0
            val promptTokens = usage.optInt("prompt_tokens", 0)
            val hitTokens = when {
                usage.has("prompt_cache_hit_tokens") -> usage.optInt("prompt_cache_hit_tokens", 0)
                usage.optJSONObject("prompt_tokens_details") != null ->
                    usage.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens", 0) ?: 0
                else -> 0
            }
            hitTokens to promptTokens
        } catch (_: Throwable) {
            0 to 0
        }
    }

    private fun buildAgentToolsSpec(): JSONArray = JSONArray().apply {
        put(
            toolSpec(
                name = "lookup_contact",
                description = "根据备注名、昵称或微信号搜索微信联系人或群聊，返回对应的微信内部ID(wxid/chatroom)、显示名称与好感度档案。当 keyword 留空时返回最近活跃的会话列表及未读数。",
                params = JSONObject().put(
                    "keyword",
                    JSONObject().put("type", "string").put("description", "联系人昵称、备注名或群名；传空字符串列出最近活跃会话")
                ),
                required = listOf("keyword")
            )
        )

        put(
            toolSpec(
                name = "get_chat_history",
                description = "跨对话读取指定微信好友或群聊的最近聊天记录（包含每条消息的 msgId、svrId、发送人、时间与内容）。target 可填昵称/备注名或 wxid。",
                params = JSONObject()
                    .put("target", JSONObject().put("type", "string").put("description", "目标联系人/群聊的名称、备注名或 wxid"))
                    .put("count", JSONObject().put("type", "integer").put("description", "读取条数，默认 15，最大 40")),
                required = listOf("target")
            )
        )

        put(
            toolSpec(
                name = "get_group_members",
                description = "读取指定微信群聊的所有群成员名单（返回成员的 wxid 与昵称/备注）。",
                params = JSONObject().put(
                    "group_target",
                    JSONObject().put("type", "string").put("description", "微信群名称或 xxx@chatroom ID")
                ),
                required = listOf("group_target")
            )
        )

        put(
            toolSpec(
                name = "search_global_messages",
                description = "在整个微信的所有历史聊天记录中按关键词搜索消息（跨所有群聊和私聊，查找某句话是谁说的、在哪个会话说的）。",
                params = JSONObject()
                    .put("keyword", JSONObject().put("type", "string").put("description", "要搜索的聊天内容关键词"))
                    .put("count", JSONObject().put("type", "integer").put("description", "返回条数，默认 15")),
                required = listOf("keyword")
            )
        )

        put(
            toolSpec(
                name = "send_wechat_message",
                description = "跨对话向指定的微信好友或群聊发送文字消息（支持用 || 分隔多句实现多气泡连续发送）。",
                params = JSONObject()
                    .put("target", JSONObject().put("type", "string").put("description", "接收方联系人的备注名、昵称、群名或 wxid"))
                    .put("message", JSONObject().put("type", "string").put("description", "要发送给对方的文字内容")),
                required = listOf("target", "message")
            )
        )

        put(
            toolSpec(
                name = "send_quote_reply",
                description = "在指定会话中引用某条具体消息（通过 get_chat_history 查到的 svrId）进行针对性回复。",
                params = JSONObject()
                    .put("target", JSONObject().put("type", "string").put("description", "目标会话的名称或 wxid"))
                    .put("msg_svr_id", JSONObject().put("type", "integer").put("description", "被引用消息的 svrId (长整型数字)"))
                    .put("message", JSONObject().put("type", "string").put("description", "回复的文字内容")),
                required = listOf("target", "msg_svr_id", "message")
            )
        )

        put(
            toolSpec(
                name = "send_pat",
                description = "在私聊或群聊中「拍一拍」某人的头像（微信原生拍一拍互动）。",
                params = JSONObject()
                    .put("target", JSONObject().put("type", "string").put("description", "所在的群聊或私聊名称/wxid"))
                    .put("member_to_pat", JSONObject().put("type", "string").put("description", "要拍的人的名称或 wxid（私聊时填对方名称即可）")),
                required = listOf("target", "member_to_pat")
            )
        )

        put(
            toolSpec(
                name = "send_contact_card",
                description = "将某个微信好友的个人名片发送/推荐到指定的聊天会话中。",
                params = JSONObject()
                    .put("target", JSONObject().put("type", "string").put("description", "接收名片的会话名称或 wxid"))
                    .put("card_owner", JSONObject().put("type", "string").put("description", "被推荐人的名称、备注名或 wxid")),
                required = listOf("target", "card_owner")
            )
        )

        put(
            toolSpec(
                name = "revoke_my_message",
                description = "撤回自己发出的某条微信消息（需提供从 get_chat_history 查到的本地 msgId）。",
                params = JSONObject().put(
                    "msg_id",
                    JSONObject().put("type", "integer").put("description", "要撤回的消息本地 msgId")
                ),
                required = listOf("msg_id")
            )
        )

        put(
            toolSpec(
                name = "post_wechat_moment",
                description = "发布一条纯文字微信朋友圈动态（记录生活心情、吐槽或按主人要求发朋友圈）。",
                params = JSONObject().put(
                    "content",
                    JSONObject().put("type", "string").put("description", "朋友圈正文内容")
                ),
                required = listOf("content")
            )
        )

        put(
            toolSpec(
                name = "update_contact_dossier",
                description = "更新你对某个联系人的关系定位、好感度增减(-20到+20)以及对他的性格/喜好印象档案。",
                params = JSONObject()
                    .put("target", JSONObject().put("type", "string").put("description", "联系人的名称或 wxid"))
                    .put("relationship", JSONObject().put("type", "string").put("description", "关系定位，如：最信任的主人、死党、闺蜜、普通群友"))
                    .put("affection_delta", JSONObject().put("type", "integer").put("description", "好感度变化值，如 +5 或 -5"))
                    .put("profile_notes", JSONObject().put("type", "string").put("description", "关于对方性格、偏好、身份、近况的画像补充")),
                required = listOf("target")
            )
        )

        put(
            toolSpec(
                name = "update_self_and_diary",
                description = "更新你自己的实时心情、正在做的事、内心独白，并可写下一篇第一人称人生日记或保存一条永久客观事实记忆。",
                params = JSONObject()
                    .put("mood", JSONObject().put("type", "string").put("description", "你现在的心情（如：雀跃、微困、吃醋、温柔）"))
                    .put("activity", JSONObject().put("type", "string").put("description", "你现在正在做的事"))
                    .put("monologue", JSONObject().put("type", "string").put("description", "一句简短的内心独白"))
                    .put("diary_entry", JSONObject().put("type", "string").put("description", "第一人称人生日记正文（可选）"))
                    .put("memory_fact", JSONObject().put("type", "string").put("description", "需要永久记住的具体客观事实或约定（可选）")),
                required = emptyList()
            )
        )
    }

    private fun toolSpec(
        name: String,
        description: String,
        params: JSONObject,
        required: List<String>,
    ): JSONObject = JSONObject().apply {
        put("type", "function")
        put(
            "function",
            JSONObject().apply {
                put("name", name)
                put("description", description)
                put(
                    "parameters",
                    JSONObject().apply {
                        put("type", "object")
                        put("properties", params)
                        put("required", JSONArray(required))
                    }
                )
            }
        )
    }

    private fun executeAgentTool(
        cfg: WxAiConfigData,
        currentTalker: String,
        toolName: String,
        args: JSONObject,
    ): String {
        val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        return when (toolName) {
            "lookup_contact" -> {
                val keyword = args.optString("keyword", "").trim()
                val contacts = WxAiBridge.findContacts(keyword, limit = 20)
                if (contacts.isEmpty()) {
                    "未找到匹配 '$keyword' 的联系人或会话。"
                } else {
                    buildString {
                        append("找到 ${contacts.size} 个会话/联系人：\n")
                        for (c in contacts) {
                            val kind = if (c.isGroup) "群聊" else "私聊"
                            val unreadInfo = if (c.unread > 0) " (未读:${c.unread})" else ""
                            val dossier = WxAiSessionManager.getDossier(c.username, c.name)
                            append("- 名称: ${c.name} | wxid: ${c.username} | 类型: $kind$unreadInfo | 关系: ${dossier.relationship} (好感:${dossier.affection})\n")
                        }
                    }.trim()
                }
            }

            "get_chat_history" -> {
                val target = args.optString("target", "").trim()
                val count = args.optInt("count", 15).coerceIn(1, 40)
                val resolved = WxAiBridge.resolveTargetTalker(target)
                    ?: return "未找到名称或ID为 '$target' 的联系人/会话，请先用 lookup_contact 确认名称。"

                val wxRecords = WxAiBridge.queryWechatMessages(resolved.username, limit = count)
                if (wxRecords.isNotEmpty()) {
                    buildString {
                        append("会话【${resolved.name} (${resolved.username})】最近 ${wxRecords.size} 条微信消息：\n")
                        for (r in wxRecords) {
                            val ts = if (r.createTimeMs > 0) timeFmt.format(Date(r.createTimeMs)) else "--:--"
                            append("[$ts][msgId=${r.msgId},svrId=${r.msgSvrId}] ${r.senderName}: ${r.content}\n")
                        }
                    }.trim()
                } else {
                    val local = WxAiSessionManager.searchLocalChatHistory(resolved.username, limit = count)
                    if (local.isEmpty()) {
                        "会话【${resolved.name} (${resolved.username})】暂无历史消息记录。"
                    } else {
                        buildString {
                            append("会话【${resolved.name} (${resolved.username})】本地记忆记录：\n")
                            for (m in local) {
                                val who = if (m.role == "assistant") "我" else resolved.name
                                append("$who: ${m.content}\n")
                            }
                        }.trim()
                    }
                }
            }

            "get_group_members" -> {
                val target = args.optString("group_target", "").trim()
                val resolved = WxAiBridge.resolveTargetTalker(target)
                    ?: return "未找到群聊 '$target'。"
                if (!resolved.username.endsWith("@chatroom")) {
                    return "【${resolved.name} (${resolved.username})】不是群聊。"
                }
                val members = WxAiBridge.getGroupMembers(resolved.username, limit = 80)
                if (members.isEmpty()) {
                    "未能读取到群聊【${resolved.name}】的成员列表。"
                } else {
                    buildString {
                        append("群聊【${resolved.name}】共有 ${members.size} 名成员：\n")
                        for (m in members) {
                            append("- ${m.name} (${m.username})\n")
                        }
                    }.trim()
                }
            }

            "search_global_messages" -> {
                val keyword = args.optString("keyword", "").trim()
                val count = args.optInt("count", 15).coerceIn(1, 30)
                if (keyword.isEmpty()) return "错误：搜索关键词 keyword 不能为空。"
                val hits = WxAiBridge.searchGlobalMessages(keyword, limit = count)
                if (hits.isEmpty()) {
                    "全库未搜索到包含 '$keyword' 的历史聊天记录。"
                } else {
                    buildString {
                        append("全库搜索 '$keyword' 命中 ${hits.size} 条记录：\n")
                        for (h in hits) {
                            val ts = if (h.createTimeMs > 0) timeFmt.format(Date(h.createTimeMs)) else "--:--"
                            append("- [$ts] 会话【${h.talkerName}】${h.senderName}: ${h.content}\n")
                        }
                    }.trim()
                }
            }

            "send_wechat_message" -> {
                val target = args.optString("target", "").trim()
                val msgText = args.optString("message", "").trim()
                if (target.isEmpty() || msgText.isEmpty()) {
                    return "错误：target 和 message 不能为空。"
                }
                val resolved = WxAiBridge.resolveTargetTalker(target)
                    ?: return "发送失败：找不到名称或ID为 '$target' 的联系人或群聊。"

                if (resolved.username == currentTalker) {
                    return "目标【${resolved.name}】就是当前正在对话的窗口，无需调用此工具，直接在你的最终回复中输出即可。"
                }

                val ok = WxAiBridge.sendHumanLikeText(resolved.username, msgText, cfg.enableMultiBubble)
                if (ok) {
                    WxAiSessionManager.appendMessage(
                        talker = resolved.username,
                        role = "assistant",
                        content = msgText.replace("||", " "),
                    )
                    "已成功向【${resolved.name} (${resolved.username})】发送消息：$msgText"
                } else {
                    "向【${resolved.name} (${resolved.username})】发送消息失败。"
                }
            }

            "send_quote_reply" -> {
                val target = args.optString("target", "").trim().ifEmpty { currentTalker }
                val svrId = args.optLong("msg_svr_id", 0L)
                val msgText = args.optString("message", "").trim()
                if (msgText.isEmpty()) return "错误：message 不能为空。"
                val resolved = WxAiBridge.resolveTargetTalker(target)
                    ?: return "找不到目标会话 '$target'。"

                val ok = WxAiBridge.sendQuoteText(resolved.username, svrId, msgText)
                if (ok) {
                    WxAiSessionManager.appendMessage(resolved.username, "assistant", msgText)
                    "已成功在【${resolved.name}】中引用消息(svrId=$svrId)并回复：$msgText"
                } else {
                    "引用回复失败。"
                }
            }

            "send_pat" -> {
                val target = args.optString("target", "").trim().ifEmpty { currentTalker }
                val memberTarget = args.optString("member_to_pat", "").trim().ifEmpty { target }
                val resolvedConv = WxAiBridge.resolveTargetTalker(target)
                    ?: return "找不到会话 '$target'。"
                val patWxid = if (resolvedConv.isGroup) {
                    val members = WxAiBridge.getGroupMembers(resolvedConv.username, limit = 100)
                    members.firstOrNull {
                        it.username.equals(memberTarget, ignoreCase = true) ||
                            it.name.contains(memberTarget, ignoreCase = true)
                    }?.username ?: WxAiBridge.resolveTargetTalker(memberTarget)?.username ?: memberTarget
                } else {
                    resolvedConv.username
                }

                val ok = WxAiBridge.sendPat(resolvedConv.username, patWxid)
                if (ok) {
                    "已在【${resolvedConv.name}】中成功拍了拍 $memberTarget ($patWxid)。"
                } else {
                    "拍一拍失败（请确认 WeKit 消息发送服务已就绪）。"
                }
            }

            "send_contact_card" -> {
                val target = args.optString("target", "").trim().ifEmpty { currentTalker }
                val cardOwner = args.optString("card_owner", "").trim()
                val resolvedConv = WxAiBridge.resolveTargetTalker(target)
                    ?: return "找不到接收会话 '$target'。"
                val resolvedCard = WxAiBridge.resolveTargetTalker(cardOwner)
                    ?: return "找不到名片所属联系人 '$cardOwner'。"

                val ok = WxAiBridge.sendShareCard(resolvedConv.username, resolvedCard.username)
                if (ok) {
                    "已将【${resolvedCard.name} (${resolvedCard.username})】的微信名片发送至【${resolvedConv.name}】。"
                } else {
                    "发送名片失败。"
                }
            }

            "revoke_my_message" -> {
                val msgId = args.optLong("msg_id", 0L)
                if (msgId <= 0L) return "错误：请提供有效的本地 msg_id。"
                val ok = WxAiBridge.revokeMessage(msgId)
                if (ok) "已成功撤回消息 (msgId=$msgId)。" else "撤回消息失败 (msgId=$msgId)。"
            }

            "post_wechat_moment" -> {
                val content = args.optString("content", "").trim()
                if (content.isEmpty()) return "错误：朋友圈内容不能为空。"
                val ok = WxAiBridge.postMomentText(content)
                if (ok) {
                    WxAiSessionManager.writeDiary("发了朋友圈", "朋友圈", "发了一条朋友圈：$content")
                    "已成功发布微信朋友圈动态：$content"
                } else {
                    "发布朋友圈失败（请确认 WeKit 朋友圈接口已就绪）。"
                }
            }

            "update_contact_dossier" -> {
                val target = args.optString("target", "").trim().ifEmpty { currentTalker }
                val resolved = WxAiBridge.resolveTargetTalker(target)
                val talkerId = resolved?.username ?: target
                val talkerName = resolved?.name ?: WxAiBridge.getDisplayName(talkerId)
                val rel = args.optString("relationship", "").takeIf { it.isNotBlank() }
                val delta = args.optInt("affection_delta", 0).coerceIn(-30, 30)
                val notes = args.optString("profile_notes", "").takeIf { it.isNotBlank() }

                val updated = WxAiSessionManager.updateDossier(talkerId, talkerName, rel, delta, notes)
                "已更新【${updated.displayName}】人物档案：关系=${updated.relationship}，当前好感度=${updated.affection}/100，印象=${updated.profileSummary}"
            }

            "update_self_and_diary" -> {
                val mood = args.optString("mood", "").takeIf { it.isNotBlank() }
                val activity = args.optString("activity", "").takeIf { it.isNotBlank() }
                val monologue = args.optString("monologue", "").takeIf { it.isNotBlank() }
                val diary = args.optString("diary_entry", "").trim()
                val fact = args.optString("memory_fact", "").trim()

                val self = WxAiSessionManager.updateSelfState(mood, 0, activity, monologue)
                if (diary.isNotEmpty()) {
                    val personName = WxAiBridge.getDisplayName(currentTalker)
                    WxAiSessionManager.writeDiary(self.mood, personName, diary)
                }
                if (fact.isNotEmpty()) {
                    WxAiSessionManager.saveMemory(currentTalker, fact)
                }
                "已更新自身状态：心情=${self.mood}，正在做=${self.activity}，独白=${self.monologue}" +
                    (if (diary.isNotEmpty()) "；已写入人生日记" else "") +
                    (if (fact.isNotEmpty()) "；已存入永久记忆" else "")
            }

            else -> "未知工具: $toolName"
        }
    }
}
