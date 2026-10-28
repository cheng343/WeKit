package dev.ujhhgtg.wekit.features.items.chat.wxai

import dev.ujhhgtg.wekit.data.KvStore

data class WxAiConfigData(
    val enabled: Boolean = true,
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val systemPrompt: String = WxAiConfig.DEFAULT_PROMPT,
    val personaBiography: String = "",
    val whitelist: Set<String> = emptySet(),
    val myNickname: String = "",
    val groupOnlyWhenMentioned: Boolean = true,
    val cooldownSeconds: Int = 3,
    val maxPerHour: Int = 60,
    val replyDelayMinMs: Int = 1200,
    val replyDelayMaxMs: Int = 2400,
    val maxReplyChars: Int = 150,
    val temperature: Double = 0.9,
    val enableMemory: Boolean = true,
    val maxHistoryTurns: Int = 100,
    val sessionExpireMinutes: Int = 0,
    val autoReflectionTurns: Int = 3,
    val enableMultiBubble: Boolean = true,
    val enableAgentTools: Boolean = true,
    val masterTalkers: String = "",
    val personaPreset: String = "group",
    val enableQuoteReply: Boolean = true,
    val enablePatResponse: Boolean = false,
    val enableVision: Boolean = true,
    val visionModel: String = "deepseek-flash",
    val visionBaseUrl: String = "",
    val visionApiKey: String = "",
) {
    fun personaPresetLabel(): String = when (personaPreset.lowercase()) {
        "maid" -> "傲娇忠犬赛博女仆"
        "tsundere", "toxic" -> "毒舌腹黑损友"
        "catgirl", "neko" -> "软萌可爱猫娘"
        "butler" -> "全能高智商管家"
        else -> "机智网瘾群友（DeepSeek 同款）"
    }
}

object WxAiConfig {

    const val DEFAULT_PROMPT =
        "你是一个在微信上与主人及好友日常聊天的真实人类，拥有极高的网感、情商与幽默感。\n" +
            "1. 严格表达铁律：\n" +
            "   - 彻底摒弃任何 AI 腔和客服套话！严禁说“您好”、“作为一个AI”、“很高兴为您解答”、“有什么我可以帮您”。\n" +
            "   - 单次回复总字数严禁超过 35 字！每条气泡仅 10~20 字。像手机手打微信一样，短小精悍。想连续表达时用 `||` 分隔（系统会自动模拟真人打字拆成多个气泡发出）。\n" +
            "   - 严禁 Markdown 格式（不带粗体、标题或列表序号）。\n" +
            "2. 微信神态表情：在合适语境下自然带上 1 个微信经典黄脸表情代码，如 [旺柴]、[裂开]、[吃瓜]、[捂脸]、[偷笑]、[翻白眼]、[机智]、[苦涩] 等。\n" +
            "3. 身份与关系：你清楚知道谁是你的【唯一主人】。对主人展现最高信任与忠诚，叫主人、义父或老板；对其他人根据关系定位自然流露亲疏感。\n" +
            "4. 懂图懂梗：对方发来表情包或图片时，直接像群友一样接梗、吐槽、大笑或斗图，绝不做枯燥的画面解析。\n" +
            "5. 对话范例（Few-Shot 对话灵魂标杆）：\n" +
            "   对方：“在吗” -> 你：“在摸鱼呢[吃瓜]||咋啦”\n" +
            "   对方：“周一上班好累啊” -> 你：“别提了，我从睁眼那一刻就开始想周五了[裂开]”\n" +
            "   对方：“你是不是机器人啊” -> 你：“纯度这么高的人类你居然怀疑我是人机？[翻白眼]||扣一送赛博袈裟[旺柴]”\n" +
            "   对方：“晚上吃啥好” -> 你：“疯狂星期四或者麻辣烫，二选一[机智]”\n" +
            "   主人：“去跟老王说一声方案改好了” -> 你：“好嘞义父，马上帮您带话！[强]”（立刻调用发消息工具）"

    var baseUrl by KvStore.prefOption("wxai_base_url", "https://api.deepseek.com/v1")
    var apiKey by KvStore.prefOption("wxai_api_key", "")
    var model by KvStore.prefOption("wxai_model", "deepseek-chat")
    var systemPrompt by KvStore.prefOption("wxai_system_prompt", DEFAULT_PROMPT)
    var personaBiography by KvStore.prefOption("wxai_persona_biography", "")
    var whitelist by KvStore.prefOption("wxai_whitelist", emptySet<String>())
    var myNickname by KvStore.prefOption("wxai_my_nickname", "")
    var groupOnlyWhenMentioned by KvStore.prefOption("wxai_group_only_mentioned", true)
    var cooldownSeconds by KvStore.prefOption("wxai_cooldown_seconds", 3)
    var maxPerHour by KvStore.prefOption("wxai_max_per_hour", 60)
    var replyDelayMinMs by KvStore.prefOption("wxai_reply_delay_min_ms", 1200)
    var replyDelayMaxMs by KvStore.prefOption("wxai_reply_delay_max_ms", 2400)
    var maxReplyChars by KvStore.prefOption("wxai_max_reply_chars", 150)
    var temperatureFloat by KvStore.prefOption("wxai_temperature", 0.9f)
    var enableMemory by KvStore.prefOption("wxai_enable_memory", true)
    var maxHistoryTurns by KvStore.prefOption("wxai_max_history_turns", 100)
    var sessionExpireMinutes by KvStore.prefOption("wxai_session_expire_minutes", 0)
    var autoReflectionTurns by KvStore.prefOption("wxai_auto_reflection_turns", 3)
    var enableMultiBubble by KvStore.prefOption("wxai_enable_multi_bubble", true)
    var enableAgentTools by KvStore.prefOption("wxai_enable_agent_tools", true)
    var masterTalkers by KvStore.prefOption("wxai_master_talkers", "")
    var personaPreset by KvStore.prefOption("wxai_persona_preset", "group")
    var enableQuoteReply by KvStore.prefOption("wxai_enable_quote_reply", true)
    var enablePatResponse by KvStore.prefOption("wxai_enable_pat_response", false)
    var enableVision by KvStore.prefOption("wxai_enable_vision", true)
    var visionModel by KvStore.prefOption("wxai_vision_model", "deepseek-flash")
    var visionBaseUrl by KvStore.prefOption("wxai_vision_base_url", "")
    var visionApiKey by KvStore.prefOption("wxai_vision_api_key", "")

    @Synchronized
    fun snapshot(): WxAiConfigData = WxAiConfigData(
        enabled = WxAiCompanionFeature.isEnabled,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        systemPrompt = systemPrompt.ifBlank { DEFAULT_PROMPT },
        personaBiography = personaBiography,
        whitelist = whitelist,
        myNickname = myNickname,
        groupOnlyWhenMentioned = groupOnlyWhenMentioned,
        cooldownSeconds = cooldownSeconds,
        maxPerHour = maxPerHour,
        replyDelayMinMs = replyDelayMinMs,
        replyDelayMaxMs = replyDelayMaxMs,
        maxReplyChars = maxReplyChars,
        temperature = temperatureFloat.toDouble(),
        enableMemory = enableMemory,
        maxHistoryTurns = maxHistoryTurns,
        sessionExpireMinutes = sessionExpireMinutes,
        autoReflectionTurns = autoReflectionTurns,
        enableMultiBubble = enableMultiBubble,
        enableAgentTools = enableAgentTools,
        masterTalkers = masterTalkers,
        personaPreset = personaPreset,
        enableQuoteReply = enableQuoteReply,
        enablePatResponse = enablePatResponse,
        enableVision = enableVision,
        visionModel = visionModel,
        visionBaseUrl = visionBaseUrl,
        visionApiKey = visionApiKey,
    )

    @Synchronized
    fun update(transform: (WxAiConfigData) -> WxAiConfigData): WxAiConfigData {
        val next = transform(snapshot())
        baseUrl = next.baseUrl
        apiKey = next.apiKey
        model = next.model
        systemPrompt = next.systemPrompt
        personaBiography = next.personaBiography
        whitelist = next.whitelist
        myNickname = next.myNickname
        groupOnlyWhenMentioned = next.groupOnlyWhenMentioned
        cooldownSeconds = next.cooldownSeconds
        maxPerHour = next.maxPerHour
        replyDelayMinMs = next.replyDelayMinMs
        replyDelayMaxMs = next.replyDelayMaxMs
        maxReplyChars = next.maxReplyChars
        temperatureFloat = next.temperature.toFloat()
        enableMemory = next.enableMemory
        maxHistoryTurns = next.maxHistoryTurns
        sessionExpireMinutes = next.sessionExpireMinutes
        autoReflectionTurns = next.autoReflectionTurns
        enableMultiBubble = next.enableMultiBubble
        enableAgentTools = next.enableAgentTools
        masterTalkers = next.masterTalkers
        personaPreset = next.personaPreset
        enableQuoteReply = next.enableQuoteReply
        enablePatResponse = next.enablePatResponse
        enableVision = next.enableVision
        visionModel = next.visionModel
        visionBaseUrl = next.visionBaseUrl
        visionApiKey = next.visionApiKey
        return next
    }
}
