package dev.ujhhgtg.wekit.features.items.chat.wxai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

object WxAiBridge {

    private const val TAG = "WxAiBridge"
    const val TYPE_TEXT = 1
    const val TYPE_IMAGE = 3
    const val TYPE_EMOJI = 47
    const val TYPE_QUOTE_REPLY = 822083633

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val displayNameCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var cachedSelfNickname: String? = null

    data class ConvEntry(
        val username: String,
        val name: String,
        val isGroup: Boolean,
        val unread: Int = 0,
        val lastTime: Long = 0L,
    )

    data class WechatMessageRecord(
        val msgId: Long = 0L,
        val msgSvrId: Long = 0L,
        val senderWxid: String = "",
        val senderName: String,
        val isSelf: Boolean,
        val content: String,
        val type: Int,
        val createTimeMs: Long,
    )

    data class GlobalMessageHit(
        val talker: String,
        val talkerName: String,
        val senderName: String,
        val content: String,
        val createTimeMs: Long,
    )

    private fun sqlEscape(s: String): String = s.replace("'", "''")

    fun getSelfWxid(): String {
        return runCatching { WeApi.selfWxId }.getOrDefault("").ifBlank {
            if (!WeDatabaseApi.isReady) return@ifBlank ""
            runCatching {
                val rows = WeDatabaseApi.executeQuery("SELECT value FROM userinfo WHERE id = 2 LIMIT 1")
                rows.firstOrNull()?.get("value")?.toString().orEmpty().trim()
            }.getOrDefault("")
        }
    }

    fun getSelfNickname(): String {
        cachedSelfNickname?.let { if (it.isNotEmpty()) return it }
        if (!WeDatabaseApi.isReady) return ""
        return runCatching {
            val rows = WeDatabaseApi.executeQuery("SELECT value FROM userinfo WHERE id = 4 LIMIT 1")
            val v = rows.firstOrNull()?.get("value")?.toString().orEmpty().trim()
            if (v.isNotEmpty()) {
                cachedSelfNickname = v
                v
            } else {
                ""
            }
        }.getOrDefault("")
    }

    fun getDisplayName(talker: String): String {
        val clean = talker.trim()
        if (clean.isEmpty()) return ""
        displayNameCache[clean]?.let { return it }
        if (!WeDatabaseApi.isReady) return clean
        return runCatching {
            val name = WeDatabaseApi.getDisplayName(clean).ifBlank { clean }
            displayNameCache[clean] = name
            name
        }.getOrDefault(clean)
    }

    fun findContacts(query: String, limit: Int = 15): List<ConvEntry> {
        if (!WeDatabaseApi.isReady) return emptyList()
        val q = query.trim()
        val safeLimit = limit.coerceIn(1, 50)
        val sql = if (q.isEmpty()) {
            """
            SELECT c.username AS username, c.unReadCount AS unread, r.nickname AS nickname, r.conRemark AS conRemark
            FROM rconversation c
            LEFT JOIN rcontact r ON r.username = c.username
            ORDER BY c.conversationTime DESC
            LIMIT $safeLimit
            """.trimIndent()
        } else {
            val esc = sqlEscape(q)
            """
            SELECT r.username AS username, 0 AS unread, r.nickname AS nickname, r.conRemark AS conRemark
            FROM rcontact r
            WHERE (r.conRemark LIKE '%$esc%' OR r.nickname LIKE '%$esc%' OR r.alias = '$esc' OR r.username = '$esc')
              AND r.username NOT LIKE 'gh_%'
              AND r.username NOT LIKE 'fake_%'
            LIMIT $safeLimit
            """.trimIndent()
        }

        val list = ArrayList<ConvEntry>()
        try {
            val rows = WeDatabaseApi.executeQuery(sql)
            for (row in rows) {
                val username = row["username"]?.toString()?.trim().orEmpty()
                if (username.isEmpty()) continue
                val unread = (row["unread"] as? Number)?.toInt() ?: 0
                val nick = row["nickname"]?.toString().orEmpty()
                val remark = row["conRemark"]?.toString().orEmpty()
                val name = remark.ifBlank { nick.ifBlank { username } }
                displayNameCache[username] = name
                list.add(
                    ConvEntry(
                        username = username,
                        name = name,
                        isGroup = username.endsWith("@chatroom"),
                        unread = unread.coerceAtLeast(0),
                    )
                )
            }
        } catch (t: Throwable) {
            WeLogger.e(TAG, "findContacts($q) failed", t)
        }
        return list
    }

    fun resolveTargetTalker(target: String): ConvEntry? {
        val clean = target.trim()
        if (clean.isEmpty()) return null

        if (clean.startsWith("wxid_") || clean.endsWith("@chatroom") || clean == "filehelper") {
            val display = getDisplayName(clean)
            return ConvEntry(
                username = clean,
                name = display,
                isGroup = clean.endsWith("@chatroom"),
            )
        }

        val candidates = findContacts(clean, limit = 20)
        if (candidates.isEmpty()) return null

        candidates.firstOrNull { it.username.equals(clean, ignoreCase = true) }?.let { return it }
        candidates.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it }
        return candidates.first()
    }

    fun queryWechatMessages(talker: String, limit: Int = 20): List<WechatMessageRecord> {
        if (!WeDatabaseApi.isReady) return emptyList()
        val cleanTalker = talker.trim()
        if (cleanTalker.isEmpty()) return emptyList()

        val safeLimit = limit.coerceIn(1, 50)
        val esc = sqlEscape(cleanTalker)
        val isGroup = cleanTalker.endsWith("@chatroom")
        val sql = """
            SELECT msgId, msgSvrId, isSend, type, content, createTime
            FROM message
            WHERE talker = '$esc'
            ORDER BY createTime DESC
            LIMIT $safeLimit
        """.trimIndent()

        val rawReversed = ArrayList<WechatMessageRecord>()
        try {
            val rows = WeDatabaseApi.executeQuery(sql)
            for (row in rows) {
                val msgId = (row["msgId"] as? Number)?.toLong() ?: 0L
                val msgSvrId = (row["msgSvrId"] as? Number)?.toLong() ?: 0L
                val isSend = (row["isSend"] as? Number)?.toInt() ?: 0
                val type = (row["type"] as? Number)?.toInt() ?: 0
                val rawContent = row["content"]?.toString().orEmpty()
                val createTime = (row["createTime"] as? Number)?.toLong() ?: 0L
                val timeMs = if (createTime in 1L..9_999_999_999L) createTime * 1000L else createTime

                val isSelf = isSend != 0
                var senderWxid = if (isSelf) "self" else cleanTalker
                var sender = if (isSelf) "我" else getDisplayName(cleanTalker)
                var body = rawContent

                if (isGroup && !isSelf) {
                    val idx = rawContent.indexOf(":\n")
                    if (idx in 1..80) {
                        senderWxid = rawContent.substring(0, idx).trim()
                        sender = getDisplayName(senderWxid)
                        body = rawContent.substring(idx + 2).trim()
                    }
                }

                val readableBody = when (type) {
                    TYPE_TEXT -> body.trim()
                    TYPE_QUOTE_REPLY -> parseQuotedMessageText(body) ?: "[引用消息]"
                    TYPE_IMAGE -> "[图片]"
                    34 -> "[语音]"
                    43 -> "[视频]"
                    TYPE_EMOJI -> "[表情包]"
                    10000 -> "[系统提示: ${body.take(40)}]"
                    else -> parseQuotedMessageText(body) ?: if (body.startsWith("<")) "[富媒体/卡片消息]" else body.take(120)
                }

                if (readableBody.isNotEmpty()) {
                    rawReversed.add(
                        WechatMessageRecord(
                            msgId = msgId,
                            msgSvrId = msgSvrId,
                            senderWxid = senderWxid,
                            senderName = sender,
                            isSelf = isSelf,
                            content = readableBody,
                            type = type,
                            createTimeMs = timeMs,
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            WeLogger.e(TAG, "queryWechatMessages($cleanTalker) failed", t)
        }
        return rawReversed.asReversed()
    }

    fun parseQuotedMessageText(rawXml: String): String? {
        val s = rawXml.trim()
        if (!s.contains("<refermsg>") && !s.contains("<title>")) return null
        val title = extractXmlTag(s, "title").trim()
        if (title.isEmpty()) return null
        val referBlock = extractXmlTag(s, "refermsg")
        if (referBlock.isEmpty()) return title
        val refAuthor = extractXmlTag(referBlock, "displayname").trim().ifEmpty { "对方" }
        val refSvrId = extractXmlTag(referBlock, "svrid").trim()
        val refContentRaw = extractXmlTag(referBlock, "content").trim()
        val refContent = if (refContentRaw.startsWith("<")) "[富媒体消息]" else refContentRaw.take(80)
        val svrInfo = if (refSvrId.isNotEmpty()) "(svrId=$refSvrId)" else ""
        return "「引用 $refAuthor$svrInfo: $refContent」$title"
    }

    private fun extractXmlTag(xml: String, tag: String): String {
        val open = "<$tag>"
        val close = "</$tag>"
        val i = xml.indexOf(open)
        if (i < 0) return ""
        val j = xml.indexOf(close, i + open.length)
        if (j < 0) return ""
        return xml.substring(i + open.length, j)
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
    }

    fun getGroupMembers(groupTalker: String, limit: Int = 80): List<ConvEntry> {
        if (!WeDatabaseApi.isReady) return emptyList()
        return runCatching {
            WeDatabaseApi.getGroupMembers(groupTalker.trim())
                .take(limit.coerceIn(1, 150))
                .map { m ->
                    val name = m.remarkName.ifBlank { m.nickname.ifBlank { m.wxId } }
                    ConvEntry(
                        username = m.wxId,
                        name = name,
                        isGroup = false,
                    )
                }
        }.getOrDefault(emptyList())
    }

    fun searchGlobalMessages(keyword: String, limit: Int = 20): List<GlobalMessageHit> {
        if (!WeDatabaseApi.isReady) return emptyList()
        val q = keyword.trim()
        if (q.isEmpty()) return emptyList()

        val safeLimit = limit.coerceIn(1, 40)
        val esc = sqlEscape(q)
        val sql = """
            SELECT talker, isSend, content, createTime
            FROM message
            WHERE type = $TYPE_TEXT
              AND content LIKE '%$esc%'
            ORDER BY createTime DESC
            LIMIT $safeLimit
        """.trimIndent()

        val hits = ArrayList<GlobalMessageHit>()
        try {
            val rows = WeDatabaseApi.executeQuery(sql)
            for (row in rows) {
                val talker = row["talker"]?.toString().orEmpty()
                if (talker.isEmpty()) continue
                val isSend = (row["isSend"] as? Number)?.toInt() ?: 0
                val rawContent = row["content"]?.toString().orEmpty()
                val createTime = (row["createTime"] as? Number)?.toLong() ?: 0L
                val timeMs = if (createTime in 1L..9_999_999_999L) createTime * 1000L else createTime

                val talkerName = getDisplayName(talker)
                val isGroup = talker.endsWith("@chatroom")
                val isSelf = isSend != 0
                var sender = if (isSelf) "我" else talkerName
                var body = rawContent

                if (isGroup && !isSelf) {
                    val idx = rawContent.indexOf(":\n")
                    if (idx in 1..80) {
                        val senderWxid = rawContent.substring(0, idx).trim()
                        sender = getDisplayName(senderWxid)
                        body = rawContent.substring(idx + 2).trim()
                    }
                }

                hits.add(
                    GlobalMessageHit(
                        talker = talker,
                        talkerName = talkerName,
                        senderName = sender,
                        content = body.trim().take(160),
                        createTimeMs = timeMs,
                    )
                )
            }
        } catch (t: Throwable) {
            WeLogger.e(TAG, "searchGlobalMessages($q) failed", t)
        }
        return hits
    }

    // ---------------------------------------------------------------- 消息与动作发送（直连 WeMessageApi / WeMomentsApi）

    private fun runOnMainBlocking(action: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching { action() }.getOrElse {
                WeLogger.e(TAG, "runOnMainBlocking failed on main", it)
                false
            }
        }
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        mainHandler.post {
            try {
                ok.set(action())
            } catch (t: Throwable) {
                WeLogger.e(TAG, "runOnMainBlocking failed", t)
            } finally {
                latch.countDown()
            }
        }
        return try {
            latch.await(6, TimeUnit.SECONDS) && ok.get()
        } catch (_: InterruptedException) {
            false
        }
    }

    fun sendHumanLikeText(
        talker: String,
        rawReply: String,
        enableMultiBubble: Boolean,
        quoteSvrId: Long = 0L,
    ): Boolean {
        val segments = if (enableMultiBubble) {
            splitIntoBubbles(rawReply)
        } else {
            listOf(rawReply.replace("||", " ").trim())
        }

        if (segments.isEmpty()) return false
        var anySuccess = false
        for ((idx, seg) in segments.withIndex()) {
            if (seg.isBlank()) continue
            if (idx > 0) {
                val typingDelay = (seg.length * 60L).coerceIn(450L, 1600L) + Random.nextLong(100L, 350L)
                try {
                    Thread.sleep(typingDelay)
                } catch (_: InterruptedException) {
                }
            }
            WxAiReplyEngine.markAiOutbound(talker, seg)
            val ok = if (idx == 0 && quoteSvrId > 0L) {
                val quoted = sendQuoteText(talker, quoteSvrId, seg)
                if (!quoted) sendText(talker, seg) else true
            } else {
                sendText(talker, seg)
            }
            if (ok) anySuccess = true
        }
        return anySuccess
    }

    private fun splitIntoBubbles(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.contains("||")) {
            return trimmed.split("||")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(4)
        }
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size in 2..3 && trimmed.length <= 180) {
            return lines
        }
        val sentenceSplit = trimmed.split(Regex("(?<=[！？!?~～])\\s+"))
        if (sentenceSplit.size in 2..3 && trimmed.length <= 100) {
            return sentenceSplit.map { it.trim() }.filter { it.isNotEmpty() }
        }
        return listOf(trimmed)
    }

    fun sendText(talker: String, text: String): Boolean = runOnMainBlocking {
        WxAiReplyEngine.markAiOutbound(talker, text)
        WeMessageApi.sendText(talker, text)
    }

    fun sendQuoteText(talker: String, quotedMsgSvrId: Long, content: String): Boolean = runOnMainBlocking {
        WxAiReplyEngine.markAiOutbound(talker, content)
        if (quotedMsgSvrId <= 0L) {
            WeMessageApi.sendText(talker, content)
        } else {
            val ok = runCatching { WeMessageApi.sendQuoteText(talker, quotedMsgSvrId, content) }.getOrDefault(false)
            if (ok) true else WeMessageApi.sendText(talker, content)
        }
    }

    fun sendPat(talker: String, patTargetWxId: String): Boolean = runOnMainBlocking {
        WeMessageApi.sendPat(talker, patTargetWxId)
    }

    fun sendShareCard(talker: String, cardWxId: String): Boolean = runOnMainBlocking {
        WeMessageApi.sendShareCard(talker, cardWxId)
    }

    fun revokeMessage(msgId: Long): Boolean = runOnMainBlocking {
        if (msgId <= 0L) false else WeMessageApi.revokeMsgByMsgId(msgId)
    }

    fun postMomentText(content: String): Boolean = runOnMainBlocking {
        if (content.isBlank()) false else WeMomentsApi.postText(content, null, null)
    }

    // ---------------------------------------------------------------- 多模态图片与表情包解析

    fun resolveMediaFilePath(
        type: Int,
        msgSvrId: Long,
        rawContent: String,
        imgPath: String?,
    ): String? {
        val ctx = HostInfo.application
        if (type == TYPE_EMOJI) {
            if (msgSvrId > 0L) {
                runCatching {
                    val res = WeMessageApi.cacheAndSaveSticker(msgSvrId)
                    if (!res.isNullOrBlank() && File(res).exists()) return res
                }
            }
            val md5 = extractAttr(rawContent, "md5").ifEmpty {
                imgPath?.trim().orEmpty().substringBeforeLast('.').takeIf { it.length == 32 }.orEmpty()
            }
            if (md5.isNotEmpty()) {
                val candidateDirs = listOf(
                    File(ctx.filesDir, "emoji"),
                    File(ctx.dataDir, "files/emoji"),
                    File("/data/data/com.tencent.mm/files/emoji"),
                )
                for (dir in candidateDirs) {
                    val f = File(dir, md5)
                    if (f.exists() && f.length() > 0) return f.absolutePath
                    val fGif = File(dir, "$md5.gif")
                    if (fGif.exists() && fGif.length() > 0) return fGif.absolutePath
                }
            }
        }

        if (type == TYPE_IMAGE) {
            if (msgSvrId > 0L) {
                runCatching {
                    val res = WeMessageApi.downloadImage(msgSvrId)
                    if (!res.isNullOrBlank() && File(res).exists()) return res
                }
            }
            if (!imgPath.isNullOrBlank()) {
                val f = File(imgPath.trim())
                if (f.exists() && f.length() > 0) return f.absolutePath
            }
        }
        return null
    }

    fun fileToBase64DataUrl(filePath: String, maxDim: Int = 1024): String? {
        return try {
            val file = File(filePath)
            if (!file.exists() || file.length() <= 0) return null

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, opts)
            val w = opts.outWidth
            val h = opts.outHeight
            if (w <= 0 || h <= 0) return null

            var inSample = 1
            var maxSide = Math.max(w, h)
            while (maxSide > maxDim * 1.5) {
                inSample *= 2
                maxSide /= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeFile(filePath, decodeOpts) ?: return null

            val scaledBmp = if (bmp.width > maxDim || bmp.height > maxDim) {
                val scale = maxDim.toFloat() / Math.max(bmp.width, bmp.height).toFloat()
                val targetW = (bmp.width * scale).toInt().coerceAtLeast(1)
                val targetH = (bmp.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, targetW, targetH, true).also {
                    if (it != bmp) bmp.recycle()
                }
            } else {
                bmp
            }

            val baos = ByteArrayOutputStream()
            scaledBmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            scaledBmp.recycle()
            val bytes = baos.toByteArray()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:image/jpeg;base64,$b64"
        } catch (t: Throwable) {
            WeLogger.e(TAG, "fileToBase64DataUrl failed for $filePath", t)
            null
        }
    }

    fun describeImageOrEmoji(
        cfg: WxAiConfigData,
        dataUrl: String,
        type: Int,
    ): String? {
        val endpoint = (if (cfg.visionBaseUrl.isNotBlank()) cfg.visionBaseUrl else cfg.baseUrl).trimEnd('/') + "/chat/completions"
        val apiKey = if (cfg.visionApiKey.isNotBlank()) cfg.visionApiKey else cfg.apiKey
        val model = when {
            cfg.visionModel.isNotBlank() -> cfg.visionModel.trim()
            cfg.model.isNotBlank() -> cfg.model.trim()
            else -> "deepseek-flash"
        }
        if (apiKey.isBlank()) return null

        val promptText = if (type == TYPE_EMOJI) {
            "这是微信聊天中的一个表情包。请用简炼、风趣的一句话说明这个表情包的画面内容、人物神态、配字以及想要表达的梗或情绪（35字以内，不要寒暄）："
        } else {
            "这是微信聊天中对方发送的一张图片。请简明扼要概括图中的核心画面、主体对象或关键文字（40字以内）："
        }

        return try {
            val messages = JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "content",
                            JSONArray().apply {
                                put(JSONObject().put("type", "text").put("text", promptText))
                                put(
                                    JSONObject().put("type", "image_url").put(
                                        "image_url",
                                        JSONObject().put("url", dataUrl)
                                    )
                                )
                            }
                        )
                    }
                )
            }

            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("temperature", 0.3)
                put("max_tokens", 100)
            }

            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 40_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.let { s -> BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() } }.orEmpty()
            conn.disconnect()

            if (code in 200..299) {
                val choices = JSONObject(raw).optJSONArray("choices")
                val desc = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")?.trim()
                if (!desc.isNullOrBlank()) return desc
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    fun extractAttr(xml: String, attr: String): String {
        val pattern = "$attr=\""
        val i = xml.indexOf(pattern)
        if (i < 0) return ""
        val j = xml.indexOf("\"", i + pattern.length)
        if (j < 0) return ""
        return xml.substring(i + pattern.length, j).trim()
    }
}
