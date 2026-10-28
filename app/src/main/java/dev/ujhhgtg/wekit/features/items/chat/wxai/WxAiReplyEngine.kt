package dev.ujhhgtg.wekit.features.items.chat.wxai

import android.content.ContentValues
import dev.ujhhgtg.wekit.utils.WeLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object WxAiReplyEngine {

    private const val TAG = "WxAiReplyEngine"
    private const val STALE_MSG_SECONDS = 120L
    private const val SEEN_CACHE = 512

    private val pool = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(16),
        { r -> Thread(r, "pool-wxai-dispatch").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    private val debounceScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pool-wxai-debounce").apply { isDaemon = true }
    }

    private class TalkerState {
        @Volatile
        var lastReplyFinishedAt: Long = 0L
        val recentReplies = ArrayDeque<Long>()

        val pendingLines = ArrayList<String>()
        var firstPendingAt: Long = 0L
        var lastSenderWxid: String? = null
        var lastSenderName: String = ""
        var lastMsgSvrId: Long = 0L
        var pendingIsSelfCmd: Boolean = false
        var debounceFuture: ScheduledFuture<*>? = null
    }

    private val states = ConcurrentHashMap<String, TalkerState>()

    private val seenKeys = object : LinkedHashMap<String, Boolean>(SEEN_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > SEEN_CACHE
    }

    private val aiSentRecent = object : LinkedHashMap<Int, Long>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Long>): Boolean =
            size > 256
    }

    fun markAiOutbound(talker: String, text: String) {
        synchronized(aiSentRecent) {
            aiSentRecent[(talker + "|" + text.trim()).hashCode()] = System.currentTimeMillis()
        }
    }

    private fun isAiOutbound(talker: String, text: String): Boolean = synchronized(aiSentRecent) {
        val key = (talker + "|" + text.trim()).hashCode()
        val ts = aiSentRecent[key] ?: return false
        if (System.currentTimeMillis() - ts < 45_000L) {
            return true
        }
        aiSentRecent.remove(key)
        false
    }

    fun onMessageInsertedValues(values: ContentValues) {
        val cfg = WxAiConfig.snapshot()
        if (!cfg.enabled) return

        val type = values.getAsInteger("type") ?: return
        val isSys = (type == 10000)
        if (type != WxAiBridge.TYPE_TEXT &&
            type != WxAiBridge.TYPE_QUOTE_REPLY &&
            type != WxAiBridge.TYPE_IMAGE &&
            type != WxAiBridge.TYPE_EMOJI &&
            !isSys
        ) return

        val talker = values.getAsString("talker")?.trim().orEmpty()
        if (talker.isEmpty()) return

        val rawContent = values.getAsString("content").orEmpty()
        val imgPath = values.getAsString("imgPath")
        if (rawContent.isEmpty() && imgPath.isNullOrEmpty()) return

        if (isSys) {
            val isPat = rawContent.contains("拍了拍") &&
                (rawContent.contains("拍了拍我") || rawContent.contains("拍了拍你") || rawContent.contains("拍了拍")) &&
                !rawContent.startsWith("我拍了拍")
            if (!isPat) return
        }

        val isSend = values.getAsInteger("isSend") ?: 0
        if (isSend != 0) {
            if (isAiOutbound(talker, rawContent)) return
            val isFileHelper = talker == "filehelper"
            if (!isFileHelper && !isSelfCommandTrigger(rawContent)) return
        }

        val createTime = values.getAsLong("createTime") ?: 0L
        val nowMs = System.currentTimeMillis()
        val msgTimeMs = when {
            createTime <= 0L -> nowMs
            createTime < 10_000_000_000L -> createTime * 1000L
            else -> createTime
        }
        if (nowMs - msgTimeMs > STALE_MSG_SECONDS * 1000L) {
            return
        }

        val isSelfCmd = isSend != 0 || isSelfCommandTrigger(rawContent)
        if (!isSelfCmd && !inWhitelist(cfg.whitelist, talker)) return

        val msgId = values.getAsLong("msgId") ?: 0L
        val svrId = values.getAsLong("msgSvrId") ?: 0L

        debounceScheduler.execute {
            enqueueOrBatchCandidate(cfg, talker, type, rawContent, imgPath, msgId, svrId, msgTimeMs, isSelfCmd)
        }
    }

    private fun isMasterBindCommand(raw: String): Boolean {
        val c = raw.trim().removePrefix("#").removePrefix("/").removePrefix("@").trim()
        return c in setOf(
            "认主", "我是主人", "我是你主人", "我就是你主人", "我是你的主人",
            "认我为主", "绑定主人", "绑定为主人", "你是我的", "做我仆人", "做我的仆人"
        ) || c == "认主" || c.startsWith("认主") || (c.contains("我是") && c.contains("主人"))
    }

    private fun isSelfCommandTrigger(raw: String): Boolean {
        val t = raw.trim()
        return t.startsWith("/ai ", ignoreCase = true) ||
            t.startsWith("@ai ", ignoreCase = true) ||
            t.startsWith("#ai ", ignoreCase = true) ||
            t.startsWith("#人设") ||
            t.startsWith("#风格") ||
            isMasterBindCommand(t) ||
            t in setOf(
                "#重置", "#重置会话", "#清除记忆",
                "#记忆", "#查看记忆",
                "#状态", "#心境",
                "#日记", "#看日记",
                "#好感度", "#档案",
                "#人设", "#风格",
            )
    }

    private fun stripSelfCommandPrefix(raw: String): String {
        val t = raw.trim()
        for (p in listOf("/ai ", "@ai ", "#ai ", "/AI ", "@AI ", "#AI ")) {
            if (t.startsWith(p)) return t.substring(p.length).trim()
        }
        return t
    }

    private fun enqueueOrBatchCandidate(
        cfg: WxAiConfigData,
        talker: String,
        type: Int,
        rawContent: String,
        imgPath: String?,
        msgId: Long,
        svrId: Long,
        msgTimeMs: Long,
        isSelfCmd: Boolean,
    ) {
        try {
            val isGroup = talker.endsWith("@chatroom")
            val (senderWxid, rawBody) = if (isGroup && !isSelfCmd) {
                extractGroupSenderAndBody(rawContent)
            } else {
                null to (if (isSelfCmd) stripSelfCommandPrefix(rawContent) else rawContent)
            }

            val isPat = (type == 10000)
            val isMedia = (type == WxAiBridge.TYPE_IMAGE || type == WxAiBridge.TYPE_EMOJI)
            val parsedBody = when {
                isPat -> "[对方在微信中拍了拍你]"
                type == WxAiBridge.TYPE_QUOTE_REPLY || (rawBody.trimStart().startsWith("<") && !isMedia && !isPat) -> {
                    WxAiBridge.parseQuotedMessageText(rawBody) ?: return
                }
                type == WxAiBridge.TYPE_EMOJI -> {
                    val resolvedFile = WxAiBridge.resolveMediaFilePath(WxAiBridge.TYPE_EMOJI, svrId, rawBody, imgPath)
                    val dataUrl = resolvedFile?.let { WxAiBridge.fileToBase64DataUrl(it) }
                    val desc = if (cfg.enableVision && dataUrl != null) {
                        WxAiBridge.describeImageOrEmoji(cfg, dataUrl, WxAiBridge.TYPE_EMOJI)
                    } else null
                    if (!desc.isNullOrBlank()) {
                        "[对方发来一个微信表情包，画面内容与梗: $desc]"
                    } else {
                        val rawDesc = WxAiBridge.extractAttr(rawBody, "desc").ifBlank { WxAiBridge.extractAttr(rawBody, "title") }
                        if (rawDesc.isNotBlank()) "[对方发来一个表情包: $rawDesc]" else "[对方发来了一个微信表情包]"
                    }
                }
                type == WxAiBridge.TYPE_IMAGE -> {
                    val resolvedFile = WxAiBridge.resolveMediaFilePath(WxAiBridge.TYPE_IMAGE, svrId, rawBody, imgPath)
                    val dataUrl = resolvedFile?.let { WxAiBridge.fileToBase64DataUrl(it) }
                    val desc = if (cfg.enableVision && dataUrl != null) {
                        WxAiBridge.describeImageOrEmoji(cfg, dataUrl, WxAiBridge.TYPE_IMAGE)
                    } else null
                    if (!desc.isNullOrBlank()) {
                        "[对方发来一张图片，画面内容: $desc]"
                    } else {
                        "[对方发来了一张图片]"
                    }
                }
                else -> rawBody.trim()
            }

            if (parsedBody.isEmpty()) return
            if (!isMedia && !isPat && looksLikeSystemOrMedia(parsedBody)) return

            if (isPat && cfg.enablePatResponse) {
                val patTarget = senderWxid?.takeIf { it.isNotBlank() } ?: talker
                pool.execute {
                    try {
                        Thread.sleep(600L)
                        WxAiBridge.sendPat(talker, patTarget)
                    } catch (_: Throwable) {
                    }
                }
            }

            val dedupeKey = when {
                svrId != 0L -> "s$svrId"
                msgId != 0L -> "m$msgId"
                else -> "h$talker|$msgTimeMs|${parsedBody.hashCode()}"
            }
            if (!markSeen(dedupeKey)) {
                return
            }

            val strippedForCmd = stripMentionTag(parsedBody, cfg).trim()
            if (isBuiltinCommand(strippedForCmd)) {
                pool.execute {
                    handleBuiltinCommand(cfg, talker, senderWxid, strippedForCmd)
                }
                return
            }

            val state = states.computeIfAbsent(talker) { TalkerState() }
            val cleanInput = stripMentionTag(parsedBody, cfg)

            synchronized(state) {
                val isFollowUpInActiveBatch = state.pendingLines.isNotEmpty() &&
                    state.lastSenderWxid == senderWxid &&
                    (System.currentTimeMillis() - state.firstPendingAt) < 4000L

                if (isGroup && !isSelfCmd && cfg.groupOnlyWhenMentioned && !mentionsMe(parsedBody, cfg) && !isFollowUpInActiveBatch) {
                    return
                }

                val talkerName = WxAiBridge.getDisplayName(talker)
                val senderName = when {
                    !senderWxid.isNullOrBlank() -> WxAiBridge.getDisplayName(senderWxid)
                    isSelfCmd -> "主人"
                    else -> talkerName
                }

                val formattedLine = if (isGroup && !isSelfCmd) {
                    "[$senderName]: $cleanInput"
                } else {
                    cleanInput
                }

                if (state.pendingLines.isEmpty()) {
                    state.firstPendingAt = System.currentTimeMillis()
                }
                state.pendingLines.add(formattedLine)
                state.lastSenderWxid = senderWxid
                state.lastSenderName = senderName
                state.lastMsgSvrId = svrId
                state.pendingIsSelfCmd = state.pendingIsSelfCmd || isSelfCmd

                state.debounceFuture?.cancel(false)
                val baseWaitMs = if (state.pendingIsSelfCmd) {
                    500L
                } else {
                    val min = cfg.replyDelayMinMs.coerceIn(600, 4000).toLong()
                    val max = cfg.replyDelayMaxMs.coerceIn(min.toInt(), 5000).toLong()
                    if (max > min) Random.nextLong(min, max + 1) else min
                }
                val elapsed = System.currentTimeMillis() - state.firstPendingAt
                val waitMs = if (elapsed + baseWaitMs > 4500L) {
                    (4500L - elapsed).coerceAtLeast(150L)
                } else {
                    baseWaitMs
                }

                WeLogger.i(TAG, "batched input [${state.pendingLines.size}] <- $talker ($senderName): ${cleanInput.take(50)} (wait=${waitMs}ms)")

                state.debounceFuture = debounceScheduler.schedule(
                    { flushPendingBatch(talker) },
                    waitMs,
                    TimeUnit.MILLISECONDS,
                )
            }
        } catch (t: Throwable) {
            WeLogger.e(TAG, "enqueueOrBatchCandidate failed for $talker", t)
        }
    }

    private fun flushPendingBatch(talker: String) {
        val state = states[talker] ?: return
        val mergedInput: String
        val senderWxid: String?
        val senderName: String
        val quoteSvrId: Long
        val isSelfCmd: Boolean

        synchronized(state) {
            if (state.pendingLines.isEmpty()) return
            mergedInput = state.pendingLines.joinToString("\n")
            senderWxid = state.lastSenderWxid
            senderName = state.lastSenderName
            quoteSvrId = state.lastMsgSvrId
            isSelfCmd = state.pendingIsSelfCmd
            state.pendingLines.clear()
            state.lastMsgSvrId = 0L
            state.pendingIsSelfCmd = false
            state.debounceFuture = null
        }

        pool.execute {
            val cfg = WxAiConfig.snapshot()
            if (!cfg.enabled) return@execute
            executeMergedTurn(
                cfg = cfg,
                talker = talker,
                mergedUserMsg = mergedInput,
                senderWxid = senderWxid,
                senderName = senderName,
                quoteSvrId = quoteSvrId,
                isSelfCmd = isSelfCmd,
            )
        }
    }

    private fun executeMergedTurn(
        cfg: WxAiConfigData,
        talker: String,
        mergedUserMsg: String,
        senderWxid: String?,
        senderName: String,
        quoteSvrId: Long,
        isSelfCmd: Boolean,
    ) {
        try {
            if (!isSelfCmd && !waitOrCheckRateLimit(cfg, talker)) return

            val isGroup = talker.endsWith("@chatroom")
            val talkerName = WxAiBridge.getDisplayName(talker)
            val speakerId = if (isGroup && !senderWxid.isNullOrBlank()) senderWxid else talker
            val speakerDisplayName = if (isGroup && !senderWxid.isNullOrBlank()) senderName else talkerName
            val existingDossier = WxAiSessionManager.getDossier(speakerId, speakerDisplayName)

            val isMaster = isSelfCmd ||
                isMasterSpeaker(cfg, talker, talkerName, senderWxid, senderName) ||
                existingDossier.relationship == "唯一主人" ||
                (cfg.masterTalkers.isBlank() && !isGroup)

            if (isMaster && speakerId != "filehelper") {
                if (existingDossier.relationship != "唯一主人" || existingDossier.affection < 100) {
                    WxAiSessionManager.updateDossier(
                        talker = speakerId,
                        displayName = speakerDisplayName,
                        relationship = "唯一主人",
                        affectionDelta = 100,
                    )
                }
            }

            val historyMessages = buildConversationContext(
                cfg = cfg,
                talker = talker,
                currentUserMessage = mergedUserMsg,
            )

            val allowTools = cfg.enableAgentTools && (isMaster || cfg.masterTalkers.isBlank())

            val turnContext = buildDynamicTurnContext(
                cfg = cfg,
                talker = talker,
                talkerName = talkerName,
                speakerId = speakerId,
                senderName = senderName,
                isGroup = isGroup,
                isSelfCmd = isSelfCmd,
                isMaster = isMaster,
            )

            val turnStartMs = System.currentTimeMillis()
            val result = WxAiClient.completeWithContext(
                cfg = cfg,
                currentTalker = talker,
                historyMessages = historyMessages,
                extraSystem = turnContext,
                allowAgentTools = allowTools,
            )

            val text = result.text
            if (text.isNullOrBlank()) {
                WeLogger.w(TAG, "no reply for $talker: ${result.error}")
                return
            }

            val reply = sanitize(text, cfg.maxReplyChars, cfg.enableMultiBubble)
            if (reply.isEmpty()) {
                WeLogger.w(TAG, "sanitized reply empty for $talker")
                return
            }

            markAiOutbound(talker, reply)
            val targetQuote = if (cfg.enableQuoteReply && isGroup && quoteSvrId > 0L) quoteSvrId else 0L
            val ok = WxAiBridge.sendHumanLikeText(talker, reply, cfg.enableMultiBubble, targetQuote)
            if (ok) {
                recordReplyFinished(talker)
                if (cfg.enableMemory) {
                    WxAiSessionManager.appendMessage(talker, "user", mergedUserMsg, turnStartMs)
                    val cleanSavedReply = reply.replace("||", " ").replace(Regex(" {2,}"), " ").trim()
                    val turns = WxAiSessionManager.appendMessage(talker, "assistant", cleanSavedReply, System.currentTimeMillis())

                    WxAiClient.rollSummaryIfNeeded(cfg, talker, talkerName)

                    if (cfg.autoReflectionTurns > 0 && turns >= cfg.autoReflectionTurns) {
                        WxAiSessionManager.resetReflectionCounter(talker)
                        val recentForReflection = WxAiSessionManager.getHistory(talker, maxTurns = 8, expireMinutes = 0)
                        WxAiClient.reflectOnConversation(cfg, speakerId, speakerDisplayName, recentForReflection)
                    }
                }
            }

            val cacheInfo = if (result.promptTokens > 0) {
                val pct = (result.cacheHitTokens * 100) / result.promptTokens
                " (缓存命中 $pct%)"
            } else {
                ""
            }
            WeLogger.i(TAG, "reply -> $talker$cacheInfo: ${reply.take(60)} (ok=$ok)")
        } catch (t: Throwable) {
            WeLogger.e(TAG, "executeMergedTurn failed for $talker", t)
        }
    }

    private fun isBuiltinCommand(cmd: String): Boolean {
        val c = cmd.trim()
        return isMasterBindCommand(c) ||
            c in setOf(
                "#重置", "#重置会话", "#清除记忆", "#reset", "#RESET",
                "#状态", "#心境",
                "#好感度", "#档案",
                "#日记", "#看日记",
                "#记忆", "#查看记忆",
                "#人设", "#风格",
            ) || c.startsWith("#人设") || c.startsWith("#风格")
    }

    private fun handleBuiltinCommand(
        cfg: WxAiConfigData,
        talker: String,
        senderWxid: String?,
        cmd: String,
    ): Boolean {
        val c = cmd.trim()
        when {
            isMasterBindCommand(c) -> {
                val masterWxid = senderWxid?.takeIf { it.isNotBlank() }
                    ?: if (talker == "filehelper") WxAiBridge.getSelfWxid().ifEmpty { "filehelper" } else talker
                val masterName = WxAiBridge.getDisplayName(masterWxid).ifBlank { "主人" }

                WxAiConfig.update { old ->
                    val existingMasters = old.masterTalkers.split(',', '，', ';', '；')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toMutableSet()
                    existingMasters.add(masterWxid)
                    if (masterName.isNotBlank() && masterName != masterWxid) {
                        existingMasters.add(masterName)
                    }
                    val newWhitelist = old.whitelist + talker
                    old.copy(
                        masterTalkers = existingMasters.joinToString(", "),
                        whitelist = newWhitelist,
                    )
                }

                WxAiSessionManager.updateDossier(
                    talker = masterWxid,
                    displayName = masterName,
                    relationship = "唯一主人",
                    affectionDelta = 100,
                    profileNotes = "我最信任、唯一效忠的主人",
                )
                WxAiSessionManager.saveMemory("global", "我的唯一主人是 $masterName ($masterWxid)")
                WxAiSessionManager.saveMemory(masterWxid, "身份是我的唯一主人，对我拥有最高掌控权与绝对指令权")

                val reply = "已成功认主！【$masterName】就是我的唯一主人，随时听候您的差遣！跨对话发消息、查聊天记录等微信掌控权限已全部为您开启。"
                WxAiBridge.sendHumanLikeText(talker, reply, false)
                return true
            }

            c in setOf("#重置", "#重置会话", "#清除记忆") || c.equals("#reset", ignoreCase = true) -> {
                val count = WxAiSessionManager.clearSession(talker)
                val reply = "已重置当前会话上下文（清理了 $count 条记录），我们的永久记忆和好感度依然保留。"
                WxAiBridge.sendHumanLikeText(talker, reply, false)
                return true
            }

            c == "#状态" || c == "#心境" -> {
                val self = WxAiSessionManager.getSelfState()
                val targetId = senderWxid?.takeIf { it.isNotBlank() } ?: talker
                val targetName = WxAiBridge.getDisplayName(targetId)
                val dossier = WxAiSessionManager.getDossier(targetId, targetName)
                val history = WxAiSessionManager.getHistory(talker, cfg.maxHistoryTurns, cfg.sessionExpireMinutes)
                val masterInfo = cfg.masterTalkers.ifBlank { "未绑定（发 #认主 即可绑定）" }
                val reply = "【当前心境】${self.mood}（精力 ${self.energy}/100）||" +
                    "【正在做】${self.activity}||" +
                    "【内心独白】${self.monologue}||" +
                    "【唯一主人】$masterInfo｜【对你的定位】${dossier.relationship} · ${dossier.affectionLabel()}｜【活跃上下文】${history.size} 条"
                WxAiBridge.sendHumanLikeText(talker, reply, cfg.enableMultiBubble)
                return true
            }

            c == "#好感度" || c == "#档案" -> {
                val targetId = senderWxid?.takeIf { it.isNotBlank() } ?: talker
                val targetName = WxAiBridge.getDisplayName(targetId)
                val dossier = WxAiSessionManager.getDossier(targetId, targetName)
                val notes = dossier.profileSummary.ifBlank { "还在慢慢了解中" }
                val reply = "【${dossier.displayName} 的专属档案】关系：${dossier.relationship}｜好感度：${dossier.affectionLabel()}｜印象：$notes"
                WxAiBridge.sendHumanLikeText(talker, reply, false)
                return true
            }

            c == "#日记" || c == "#看日记" -> {
                val diaries = WxAiSessionManager.getRecentDiaries(limit = 3)
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                val reply = if (diaries.isEmpty()) {
                    val history = WxAiSessionManager.getHistory(talker, 8, 0)
                    if (history.size >= 2) {
                        val speakerDisplayName = WxAiBridge.getDisplayName(talker)
                        pool.execute {
                            WxAiClient.reflectOnConversation(cfg, talker, speakerDisplayName, history)
                        }
                    }
                    "我的日记本还在酝酿中，多聊聊天我就会把生活点滴写进日记里。"
                } else {
                    diaries.joinToString("||") { d ->
                        "📒 [${fmt.format(Date(d.timestamp))} · 心情:${d.mood}] ${d.entry}"
                    }
                }
                WxAiBridge.sendHumanLikeText(talker, reply, cfg.enableMultiBubble)
                return true
            }

            c == "#记忆" || c == "#查看记忆" -> {
                val talkerName = WxAiBridge.getDisplayName(talker)
                val memories = WxAiSessionManager.getRelevantMemories(talker, talkerName, limit = 10)
                val history = WxAiSessionManager.getHistory(talker, cfg.maxHistoryTurns, cfg.sessionExpireMinutes)
                val reply = if (memories.isEmpty()) {
                    if (history.size >= 2) {
                        pool.execute {
                            WxAiClient.reflectOnConversation(cfg, talker, talkerName, history.takeLast(8))
                        }
                    }
                    "当前会话活跃上下文 ${history.size} 条（上限 ${cfg.maxHistoryTurns} 轮），已在后台开始整理与沉淀记忆。"
                } else {
                    val summary = memories.take(6).joinToString("；") { it.fact }
                    "活跃上下文 ${history.size} 条；永久记忆 ${memories.size} 条：$summary"
                }
                val safeReply = sanitize(reply, cfg.maxReplyChars, false)
                WxAiBridge.sendHumanLikeText(talker, safeReply, false)
                return true
            }

            c.startsWith("#人设") || c.startsWith("#风格") -> {
                val arg = c.substringAfter(" ", "").trim()
                    .ifEmpty { c.substringAfter("人设", "").trim() }
                    .ifEmpty { c.substringAfter("风格", "").trim() }

                if (arg.isEmpty() || arg == "查看" || arg == "列表") {
                    val reply = "【当前人设风格】${cfg.personaPresetLabel()}||" +
                        "发送指令可直接切换风格：\n" +
                        "• #人设 群友（机智网瘾群友，默认高网感接梗）\n" +
                        "• #人设 女仆（傲娇忠犬女仆，对主人极度顺从护短）\n" +
                        "• #人设 毒舌（毒舌腹黑损友，神级吐槽与阴阳）\n" +
                        "• #人设 猫娘（软萌可爱猫娘，句尾带喵超治愈）\n" +
                        "• #人设 管家（全能高智商管家，严谨靠谱执行力）"
                    WxAiBridge.sendHumanLikeText(talker, reply, cfg.enableMultiBubble)
                    return true
                }

                val newPreset = when {
                    arg.contains("群友") || arg.contains("网络") || arg.contains("抽象") -> "group"
                    arg.contains("女仆") || arg.contains("忠犬") || arg.contains("跟班") -> "maid"
                    arg.contains("毒舌") || arg.contains("腹黑") || arg.contains("损友") -> "tsundere"
                    arg.contains("猫娘") || arg.contains("猫") || arg.contains("萌") -> "catgirl"
                    arg.contains("管家") || arg.contains("执事") || arg.contains("助手") -> "butler"
                    else -> "group"
                }

                val updatedCfg = WxAiConfig.update { old ->
                    old.copy(personaPreset = newPreset)
                }

                val reply = "已成功切换人设风格为：【${updatedCfg.personaPresetLabel()}】！随时发消息来和我互动吧[旺柴]"
                WxAiBridge.sendHumanLikeText(talker, reply, false)
                return true
            }
        }
        return false
    }

    private fun buildConversationContext(
        cfg: WxAiConfigData,
        talker: String,
        currentUserMessage: String,
    ): List<WxAiClient.ChatMessage> {
        if (!cfg.enableMemory || cfg.maxHistoryTurns <= 0) {
            return listOf(WxAiClient.ChatMessage(role = "user", content = currentUserMessage))
        }

        val list = ArrayList<WxAiClient.ChatMessage>()
        val savedHistory = WxAiSessionManager.getHistory(
            talker = talker,
            maxTurns = cfg.maxHistoryTurns,
            expireMinutes = cfg.sessionExpireMinutes,
            useAnchor = true,
        )

        if (savedHistory.isNotEmpty()) {
            list.addAll(savedHistory)
        } else {
            val wxRecent = WxAiBridge.queryWechatMessages(talker, limit = 12)
            if (wxRecent.size > 1) {
                val prior = wxRecent.dropLast(1).takeLast(10)
                val warmup = ArrayList<Pair<WxAiClient.ChatMessage, Long>>()
                for (r in prior) {
                    if (r.type != WxAiBridge.TYPE_TEXT && r.type != WxAiBridge.TYPE_QUOTE_REPLY) continue
                    val role = if (r.isSelf) "assistant" else "user"
                    val msgContent = if (!r.isSelf && talker.endsWith("@chatroom")) {
                        "[${r.senderName}]: ${r.content}"
                    } else {
                        r.content
                    }
                    warmup.add(WxAiClient.ChatMessage(role = role, content = msgContent) to r.createTimeMs)
                }
                while (warmup.isNotEmpty() && warmup.first().first.role != "user") {
                    warmup.removeAt(0)
                }
                for ((m, ts) in warmup) {
                    list.add(m)
                    WxAiSessionManager.appendMessage(talker, m.role, m.content.orEmpty(), ts)
                }
            }
        }

        list.add(WxAiClient.ChatMessage(role = "user", content = currentUserMessage))
        return list
    }

    private fun buildDynamicTurnContext(
        cfg: WxAiConfigData,
        talker: String,
        talkerName: String,
        speakerId: String,
        senderName: String,
        isGroup: Boolean,
        isSelfCmd: Boolean,
        isMaster: Boolean,
    ): String = buildString {
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm (EEEE)", Locale.CHINA).format(Date())
        val selfState = WxAiSessionManager.getSelfState()
        val dossier = WxAiSessionManager.getDossier(speakerId, senderName)

        append("当前时间：$nowStr\n")
        append("自我状态：心情=${selfState.mood} | 精力=${selfState.energy}/100 | 正在做=${selfState.activity}")
        if (selfState.monologue.isNotBlank()) {
            append(" | 内心独白：「${selfState.monologue}」")
        }
        append("\n")

        if (isMaster) {
            append("当前对话人身份：【唯一主人 ($senderName, ID=$speakerId)】！正在与你说话的人就是你的唯一主人，TA对你拥有最高绝对掌控权！请展现最高忠诚度、信任度与默契，无条件服从并执行主人的操控指令。\n")
            append("【主人身份确认原则】：当主人询问“我是你主人吗”、“你认识我吗”、“谁是你主人”、“我是谁”时，必须无比肯定、坚定且亲切地回答：“您当然是我的唯一主人呀！我的一切都听您的，随时听候您的差遣！”严禁回答不是！\n")
        } else {
            append("当前对话人身份：【$senderName (ID=$speakerId)】 | 关系定位：${dossier.relationship} | 好感度：${dossier.affectionLabel()}\n")
            append("【交谈原则】：亲切自然地与对方交流。如果对方询问你是否认TA为主，或者询问谁是你主人，可礼貌回复；如果这是私聊且对方明确想要成为你的主人，请热情告诉TA只需说一声【认主】即可绑定。\n")
        }

        if (dossier.profileSummary.isNotBlank()) {
            append("对方印象画像：${dossier.profileSummary}\n")
        }

        if (isGroup) {
            append("当前窗口：微信群聊【$talkerName ($talker)】\n")
            val recentGroupMsgs = WxAiBridge.queryWechatMessages(talker, limit = 8)
            if (recentGroupMsgs.size > 1) {
                val ambient = recentGroupMsgs.dropLast(1).takeLast(6)
                append("群内刚才的聊天背景：\n")
                for (gm in ambient) {
                    append("  ${gm.senderName}: ${gm.content}\n")
                }
            }
        } else if (isSelfCmd && talker != "filehelper") {
            append("当前窗口：主人在与【$talkerName ($talker)】的聊天窗口中直接向你下达指令。\n")
        } else {
            append("当前窗口：与【$talkerName ($talker)】私聊中。\n")
        }

        if (cfg.enableMemory) {
            val (convSummary, _) = WxAiSessionManager.getSummary(talker)
            if (convSummary.isNotBlank()) {
                append("与该会话更早之前的对话摘要（已滑出上下文窗口，请当作你自己的记忆）：${convSummary}\n")
            }
            val diaries = WxAiSessionManager.getRecentDiaries(limit = 3)
            if (diaries.isNotEmpty()) {
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                append("近期日记：")
                append(diaries.joinToString("；") { "[${fmt.format(Date(it.timestamp))} ${it.mood}] ${it.entry}" })
                append("\n")
            }

            val memories = WxAiSessionManager.getRelevantMemories(speakerId, senderName, limit = 15)
            if (memories.isNotEmpty()) {
                append("相关长期记忆与历史约定：\n")
                for (m in memories) {
                    val tag = if (m.scope == "global") "全局" else senderName
                    append("- [$tag] ${m.fact}\n")
                }
            }
        }
    }

    private fun isMasterSpeaker(
        cfg: WxAiConfigData,
        talker: String,
        talkerName: String,
        senderWxid: String?,
        senderName: String,
    ): Boolean {
        val rawMasters = cfg.masterTalkers.trim()
        if (rawMasters.isEmpty()) return false

        val masters = rawMasters.split(',', '，', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (masters.isEmpty()) return false

        return masters.any { m ->
            m.equals(talker, ignoreCase = true) ||
                m.equals(talkerName, ignoreCase = true) ||
                (!senderWxid.isNullOrBlank() && m.equals(senderWxid, ignoreCase = true)) ||
                m.equals(senderName, ignoreCase = true) ||
                (talkerName.isNotBlank() && (talkerName.contains(m, ignoreCase = true) || m.contains(talkerName, ignoreCase = true))) ||
                (senderName.isNotBlank() && (senderName.contains(m, ignoreCase = true) || m.contains(senderName, ignoreCase = true)))
        }
    }

    private fun effectiveMyNickname(cfg: WxAiConfigData): String {
        val configured = cfg.myNickname.trim()
        if (configured.isNotEmpty()) return configured
        return WxAiBridge.getSelfNickname()
    }

    private fun stripMentionTag(content: String, cfg: WxAiConfigData): String {
        val nick = effectiveMyNickname(cfg)
        var s = content
        if (nick.isNotEmpty()) {
            s = s.replace("@$nick\u2005", "")
                .replace("@$nick ", "")
                .replace("@$nick", "")
        }
        return s.trim().ifEmpty { content }
    }

    private fun sanitize(text: String, maxChars: Int, allowMultiBubble: Boolean): String {
        var s = text.trim()
        s = s.replace(Regex("<turn_context>[\\s\\S]*?</turn_context>", RegexOption.IGNORE_CASE), "").trim()

        val quotePairs = listOf("「" to "」", "\"" to "\"", "'" to "'", "“" to "”", "‘" to "’")
        for ((open, close) in quotePairs) {
            if (s.length > 2 && s.startsWith(open) && s.endsWith(close)) {
                s = s.substring(1, s.length - 1).trim()
                break
            }
        }
        s = if (allowMultiBubble) {
            s.replace("\r\n", "\n")
                .replace(Regex("\n+"), "||")
                .replace(Regex("(\\|\\|\\s*)+"), "||")
                .trim()
                .trim('|')
                .trim()
        } else {
            s.replace("||", " ")
                .replace("\r\n", " ")
                .replace('\n', ' ')
                .replace(Regex(" {2,}"), " ")
                .trim()
        }
        if (maxChars > 0 && s.length > maxChars) s = s.substring(0, maxChars)
        return s
    }

    private fun inWhitelist(whitelist: Set<String>, talker: String): Boolean {
        if (whitelist.isEmpty()) return false
        return whitelist.contains(talker)
    }

    private fun mentionsMe(content: String, cfg: WxAiConfigData): Boolean {
        if (!content.contains("@")) return false
        val nick = effectiveMyNickname(cfg)
        if (nick.isEmpty()) return true
        return content.contains("@$nick", ignoreCase = true)
    }

    private fun extractGroupSenderAndBody(content: String): Pair<String?, String> {
        val idx = content.indexOf(":\n")
        if (idx in 1..80) {
            val head = content.substring(0, idx)
            if (head.none { it == ' ' || it == '\n' || it == '：' }) {
                return head to content.substring(idx + 2)
            }
        }
        return null to content
    }

    private fun looksLikeSystemOrMedia(content: String): Boolean {
        if (content.startsWith("<")) return true
        if (content.contains("撤回了一条消息")) return true
        if (content.contains("拍了拍")) return true
        return false
    }

    private fun waitOrCheckRateLimit(cfg: WxAiConfigData, talker: String): Boolean {
        val state = states.computeIfAbsent(talker) { TalkerState() }
        val cooldownMs = cfg.cooldownSeconds.coerceAtLeast(0) * 1000L
        var sleepNeededMs = 0L

        synchronized(state) {
            val now = System.currentTimeMillis()
            while (state.recentReplies.isNotEmpty() && now - state.recentReplies.first() > 3_600_000L) {
                state.recentReplies.removeFirst()
            }
            if (cfg.maxPerHour in 1..Int.MAX_VALUE && state.recentReplies.size >= cfg.maxPerHour) {
                WeLogger.w(TAG, "hourly cap reached for $talker (${state.recentReplies.size})")
                return false
            }
            val elapsed = now - state.lastReplyFinishedAt
            if (cooldownMs > 0L && elapsed < cooldownMs) {
                val remain = cooldownMs - elapsed
                if (remain <= 8_000L) {
                    sleepNeededMs = remain
                } else {
                    WeLogger.i(TAG, "cooldown hit for $talker (remain=${remain / 1000}s)")
                    return false
                }
            }
            state.recentReplies.addLast(now + sleepNeededMs)
        }

        if (sleepNeededMs > 0L) {
            Thread.sleep(sleepNeededMs)
        }
        return true
    }

    private fun recordReplyFinished(talker: String) {
        states[talker]?.let { st ->
            synchronized(st) { st.lastReplyFinishedAt = System.currentTimeMillis() }
        }
    }

    private fun markSeen(key: String): Boolean = synchronized(seenKeys) {
        if (seenKeys.containsKey(key)) {
            false
        } else {
            seenKeys[key] = true
            true
        }
    }
}
