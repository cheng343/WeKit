package dev.ujhhgtg.wekit.features.items.chat.wxai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

object WxAiSessionManager {

    private const val TAG = "WxAiSessionManager"
    private const val DB_NAME = "wxai_memory.db"
    private const val SP_TURNS = "wxai_turns_cache"
    private const val DB_VERSION = 4

    private const val TABLE_SUMMARY = "session_summaries"
    private const val TABLE_MESSAGES = "session_messages"
    private const val TABLE_MEMORIES = "long_term_memories"
    private const val TABLE_SELF_STATE = "self_state"
    private const val TABLE_DOSSIERS = "contact_dossiers"
    private const val TABLE_DIARY = "life_diary"

    @Volatile
    private var dbHelper: Helper? = null

    private fun getDbPath(): String =
        KnownPaths.moduleRoot.resolve(DB_NAME).toFile().absolutePath

    private fun appCtx(): Context = HostInfo.application

    fun getUnreflectedTurns(talker: String): Int {
        val sp = appCtx().getSharedPreferences(SP_TURNS, Context.MODE_PRIVATE)
        return sp.getInt("t_$talker", 0)
    }

    fun incrementUnreflectedTurns(talker: String): Int {
        val sp = appCtx().getSharedPreferences(SP_TURNS, Context.MODE_PRIVATE)
        val next = sp.getInt("t_$talker", 0) + 1
        sp.edit().putInt("t_$talker", next).apply()
        return next
    }

    fun resetReflectionCounter(talker: String) {
        val sp = appCtx().getSharedPreferences(SP_TURNS, Context.MODE_PRIVATE)
        sp.edit().putInt("t_$talker", 0).apply()
    }

    private val windowAnchorId = ConcurrentHashMap<String, Long>()
    private val contextStartId = ConcurrentHashMap<String, Long>()

    fun getContextStartId(talker: String): Long = contextStartId[talker] ?: 0L

    data class SelfState(
        val mood: String = "元气满满",
        val energy: Int = 85,
        val activity: String = defaultActivityForHour(),
        val monologue: String = "今天会遇到什么有趣的人和故事呢？",
        val updatedAt: Long = 0L,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("mood", mood)
            put("energy", energy)
            put("activity", activity)
            put("monologue", monologue)
            put("updatedAt", updatedAt)
        }
    }

    data class ContactDossier(
        val talker: String,
        val displayName: String,
        val relationship: String = "普通朋友",
        val affection: Int = 55,
        val profileSummary: String = "",
        val updatedAt: Long = 0L,
    ) {
        fun affectionLabel(): String = when {
            affection >= 90 -> "挚爱/死忠 ($affection/100)"
            affection >= 75 -> "亲密信赖 ($affection/100)"
            affection >= 60 -> "聊得来 ($affection/100)"
            affection >= 40 -> "熟悉点头 ($affection/100)"
            affection >= 20 -> "冷淡疏远 ($affection/100)"
            else -> "厌恶警惕 ($affection/100)"
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("talker", talker)
            put("displayName", displayName)
            put("relationship", relationship)
            put("affection", affection)
            put("profileSummary", profileSummary)
            put("updatedAt", updatedAt)
        }
    }

    data class DiaryItem(
        val id: Long,
        val mood: String,
        val relatedPerson: String,
        val entry: String,
        val timestamp: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("mood", mood)
            put("relatedPerson", relatedPerson)
            put("entry", entry)
            put("timestamp", timestamp)
        }
    }

    data class MemoryItem(
        val id: Long,
        val scope: String,
        val fact: String,
        val timestamp: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("scope", scope)
            put("fact", fact)
            put("timestamp", timestamp)
        }
    }

    private class Helper(ctx: Context) : SQLiteOpenHelper(
        ctx.applicationContext ?: ctx,
        getDbPath(),
        null,
        DB_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            createAllTables(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            createAllTables(db)
        }

        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            createAllTables(db)
        }

        private fun createAllTables(db: SQLiteDatabase) {
            try {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE_MESSAGES (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        talker TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_msg_talker_time ON $TABLE_MESSAGES(talker, timestamp)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE_MEMORIES (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        scope TEXT NOT NULL,
                        fact TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_mem_scope ON $TABLE_MEMORIES(scope, timestamp)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE_SELF_STATE (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        mood TEXT NOT NULL,
                        energy INTEGER NOT NULL,
                        activity TEXT NOT NULL,
                        monologue TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE_DOSSIERS (
                        talker TEXT PRIMARY KEY,
                        display_name TEXT NOT NULL,
                        relationship TEXT NOT NULL,
                        affection INTEGER NOT NULL,
                        profile_summary TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE_DIARY (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        mood TEXT NOT NULL,
                        related_person TEXT NOT NULL,
                        entry TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_diary_time ON $TABLE_DIARY(timestamp DESC)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE_SUMMARY (
                        talker TEXT PRIMARY KEY,
                        summary TEXT NOT NULL,
                        upto_id INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            } catch (t: Throwable) {
                WeLogger.e(TAG, "createAllTables failed", t)
            }
        }
    }

    @Synchronized
    private fun db(): SQLiteDatabase {
        var h = dbHelper
        if (h == null) {
            h = Helper(appCtx())
            dbHelper = h
        }
        return h.writableDatabase
    }

    private fun defaultActivityForHour(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 0..5 -> "深夜蜷在被窝里熬夜玩手机"
            in 6..8 -> "刚睡醒迷迷糊糊伸懒腰、准备吃早饭"
            in 9..11 -> "上午精神饱满地忙活手头的事"
            in 12..13 -> "刚吃完午饭，正在午休放空刷手机"
            in 14..17 -> "下午一边喝奶茶一边摸鱼聊天"
            in 18..20 -> "傍晚忙完放松下来，吃晚饭听歌"
            else -> "洗完澡舒舒服服窝在沙发上看手机"
        }
    }

    fun getSelfState(): SelfState {
        return try {
            val database = db()
            database.rawQuery(
                "SELECT mood, energy, activity, monologue, updated_at FROM $TABLE_SELF_STATE WHERE id = 1",
                null
            ).use { cur ->
                if (cur.moveToFirst()) {
                    val mood = cur.getString(0).orEmpty().ifEmpty { "愉快放松" }
                    val energy = cur.getInt(1).coerceIn(0, 100)
                    val savedActivity = cur.getString(2).orEmpty()
                    val monologue = cur.getString(3).orEmpty()
                    val updatedAt = cur.getLong(4)
                    val now = System.currentTimeMillis()
                    val activity = if (now - updatedAt > 2 * 3_600_000L || savedActivity.isBlank()) {
                        defaultActivityForHour()
                    } else {
                        savedActivity
                    }
                    SelfState(mood, energy, activity, monologue, updatedAt)
                } else {
                    SelfState()
                }
            }
        } catch (t: Throwable) {
            WeLogger.e(TAG, "getSelfState failed", t)
            SelfState()
        }
    }

    fun updateSelfState(
        mood: String?,
        energyDelta: Int = 0,
        activity: String?,
        monologue: String?,
    ): SelfState {
        val current = getSelfState()
        val newMood = mood?.trim()?.takeIf { it.isNotEmpty() } ?: current.mood
        val newEnergy = (current.energy + energyDelta).coerceIn(10, 100)
        val newActivity = activity?.trim()?.takeIf { it.isNotEmpty() } ?: current.activity
        val newMonologue = monologue?.trim()?.takeIf { it.isNotEmpty() } ?: current.monologue
        val now = System.currentTimeMillis()

        try {
            val database = db()
            val cv = ContentValues().apply {
                put("id", 1)
                put("mood", newMood)
                put("energy", newEnergy)
                put("activity", newActivity)
                put("monologue", newMonologue)
                put("updated_at", now)
            }
            database.insertWithOnConflict(TABLE_SELF_STATE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (t: Throwable) {
            WeLogger.e(TAG, "updateSelfState failed", t)
        }
        return SelfState(newMood, newEnergy, newActivity, newMonologue, now)
    }

    fun getDossier(talker: String, fallbackName: String = talker): ContactDossier {
        val clean = talker.trim()
        if (clean.isEmpty()) return ContactDossier("", fallbackName)
        return try {
            val database = db()
            database.rawQuery(
                "SELECT display_name, relationship, affection, profile_summary, updated_at FROM $TABLE_DOSSIERS WHERE talker = ? LIMIT 1",
                arrayOf(clean)
            ).use { cur ->
                if (cur.moveToFirst()) {
                    ContactDossier(
                        talker = clean,
                        displayName = cur.getString(0).orEmpty().ifEmpty { fallbackName },
                        relationship = cur.getString(1).orEmpty().ifEmpty { "朋友" },
                        affection = cur.getInt(2).coerceIn(0, 100),
                        profileSummary = cur.getString(3).orEmpty(),
                        updatedAt = cur.getLong(4),
                    )
                } else {
                    ContactDossier(
                        talker = clean,
                        displayName = fallbackName,
                        relationship = if (clean.endsWith("@chatroom")) "微信群聊" else "认识的朋友",
                        affection = 55,
                        profileSummary = "",
                        updatedAt = 0L,
                    )
                }
            }
        } catch (t: Throwable) {
            WeLogger.e(TAG, "getDossier failed", t)
            ContactDossier(clean, fallbackName)
        }
    }

    fun updateDossier(
        talker: String,
        displayName: String,
        relationship: String? = null,
        affectionDelta: Int = 0,
        profileNotes: String? = null,
    ): ContactDossier {
        val clean = talker.trim()
        val current = getDossier(clean, displayName)
        val newName = displayName.trim().ifEmpty { current.displayName }
        val newRel = relationship?.trim()?.takeIf { it.isNotEmpty() } ?: current.relationship
        val newAffection = (current.affection + affectionDelta).coerceIn(0, 100)
        val newSummary = when {
            profileNotes.isNullOrBlank() -> current.profileSummary
            current.profileSummary.isBlank() -> profileNotes.trim()
            current.profileSummary.contains(profileNotes.trim()) -> current.profileSummary
            else -> (current.profileSummary + "；" + profileNotes.trim()).takeLast(500)
        }
        val now = System.currentTimeMillis()

        try {
            val database = db()
            val cv = ContentValues().apply {
                put("talker", clean)
                put("display_name", newName)
                put("relationship", newRel)
                put("affection", newAffection)
                put("profile_summary", newSummary)
                put("updated_at", now)
            }
            database.insertWithOnConflict(TABLE_DOSSIERS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (t: Throwable) {
            WeLogger.e(TAG, "updateDossier failed", t)
        }
        return ContactDossier(clean, newName, newRel, newAffection, newSummary, now)
    }

    fun listAllDossiers(limit: Int = 40): List<ContactDossier> {
        val list = ArrayList<ContactDossier>()
        try {
            db().rawQuery(
                "SELECT talker, display_name, relationship, affection, profile_summary, updated_at FROM $TABLE_DOSSIERS ORDER BY updated_at DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { cur ->
                while (cur.moveToNext()) {
                    list.add(
                        ContactDossier(
                            talker = cur.getString(0).orEmpty(),
                            displayName = cur.getString(1).orEmpty(),
                            relationship = cur.getString(2).orEmpty(),
                            affection = cur.getInt(3).coerceIn(0, 100),
                            profileSummary = cur.getString(4).orEmpty(),
                            updatedAt = cur.getLong(5),
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            WeLogger.e(TAG, "listAllDossiers failed", t)
        }
        return list
    }

    fun writeDiary(
        mood: String,
        relatedPerson: String,
        entry: String,
    ): Boolean {
        val cleanEntry = entry.trim()
        if (cleanEntry.isEmpty()) return false
        return try {
            val database = db()
            val cv = ContentValues().apply {
                put("mood", mood.trim().ifEmpty { "平静" })
                put("related_person", relatedPerson.trim())
                put("entry", cleanEntry)
                put("timestamp", System.currentTimeMillis())
            }
            database.insert(TABLE_DIARY, null, cv) > 0
        } catch (t: Throwable) {
            WeLogger.e(TAG, "writeDiary failed", t)
            false
        }
    }

    fun getRecentDiaries(limit: Int = 15): List<DiaryItem> {
        val list = ArrayList<DiaryItem>()
        try {
            db().rawQuery(
                "SELECT id, mood, related_person, entry, timestamp FROM $TABLE_DIARY ORDER BY timestamp DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { cur ->
                while (cur.moveToNext()) {
                    list.add(
                        DiaryItem(
                            id = cur.getLong(0),
                            mood = cur.getString(1).orEmpty(),
                            relatedPerson = cur.getString(2).orEmpty(),
                            entry = cur.getString(3).orEmpty(),
                            timestamp = cur.getLong(4),
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            WeLogger.e(TAG, "getRecentDiaries failed", t)
        }
        return list
    }

    private data class StoredRow(
        val id: Long,
        val msg: WxAiClient.ChatMessage,
        val ts: Long,
    )

    fun getHistory(
        talker: String,
        maxTurns: Int,
        expireMinutes: Int,
        useAnchor: Boolean = false,
    ): List<WxAiClient.ChatMessage> {
        if (maxTurns <= 0) return emptyList()
        val targetCount = (maxTurns * 2).coerceIn(4, 1000)
        val stepSize = (targetCount / 5).coerceIn(10, 80)
        val queryLimit = targetCount + stepSize
        val rowsReversed = ArrayList<StoredRow>(queryLimit)

        try {
            val database = db()
            database.rawQuery(
                """
                SELECT id, role, content, timestamp
                FROM $TABLE_MESSAGES
                WHERE talker = ?
                ORDER BY id DESC
                LIMIT ?
                """.trimIndent(),
                arrayOf(talker, queryLimit.toString())
            ).use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    val role = cur.getString(1) ?: continue
                    val content = cur.getString(2) ?: continue
                    val ts = cur.getLong(3)
                    rowsReversed.add(StoredRow(id, WxAiClient.ChatMessage(role = role, content = content), ts))
                }
            }
        } catch (t: Throwable) {
            WeLogger.e(TAG, "getHistory failed for $talker", t)
            return emptyList()
        }

        if (rowsReversed.isEmpty()) return emptyList()

        val expireMs = if (expireMinutes > 0) expireMinutes * 60_000L else 0L
        val validReversed = ArrayList<StoredRow>(rowsReversed.size)
        var prevTs = System.currentTimeMillis()

        for (row in rowsReversed) {
            if (expireMs > 0L && (prevTs - row.ts) > expireMs) {
                break
            }
            validReversed.add(row)
            prevTs = row.ts
        }

        val chronological = validReversed.asReversed().toMutableList()
        while (chronological.isNotEmpty() && chronological.first().msg.role != "user") {
            chronological.removeAt(0)
        }
        if (chronological.isEmpty()) return emptyList()

        if (!useAnchor) {
            val start = (chronological.size - targetCount).coerceAtLeast(0)
            var s = start
            while (s < chronological.size && chronological[s].msg.role != "user") s++
            return if (s < chronological.size) chronological.subList(s, chronological.size).map { it.msg } else emptyList()
        }

        val currentAnchor = windowAnchorId[talker]
        val anchorIdx = if (currentAnchor != null) {
            chronological.indexOfFirst { it.id >= currentAnchor }
        } else {
            -1
        }
        val anchorOverflow = anchorIdx == 0 && currentAnchor != null &&
            chronological.first().id > currentAnchor && rowsReversed.size >= queryLimit

        val effectiveStartIdx = if (anchorIdx >= 0 && !anchorOverflow) {
            anchorIdx
        } else {
            var newStart = (chronological.size - targetCount).coerceAtLeast(0)
            while (newStart < chronological.size && chronological[newStart].msg.role != "user") {
                newStart++
            }
            if (newStart < chronological.size) {
                windowAnchorId[talker] = chronological[newStart].id
            }
            newStart
        }

        val anchoredSlice = if (effectiveStartIdx in 0 until chronological.size) {
            chronological.subList(effectiveStartIdx, chronological.size)
        } else {
            chronological
        }
        anchoredSlice.firstOrNull()?.let { contextStartId[talker] = it.id }
        return anchoredSlice.map { it.msg }
    }

    fun getSummary(talker: String): Pair<String, Long> {
        return try {
            db().rawQuery(
                "SELECT summary, upto_id FROM $TABLE_SUMMARY WHERE talker = ? LIMIT 1",
                arrayOf(talker)
            ).use { cur ->
                if (cur.moveToFirst()) cur.getString(0).orEmpty() to cur.getLong(1) else "" to 0L
            }
        } catch (t: Throwable) {
            WeLogger.e(TAG, "getSummary failed", t)
            "" to 0L
        }
    }

    fun saveSummary(talker: String, summary: String, uptoId: Long) {
        try {
            val cv = ContentValues().apply {
                put("talker", talker)
                put("summary", summary.trim())
                put("upto_id", uptoId)
                put("updated_at", System.currentTimeMillis())
            }
            db().insertWithOnConflict(TABLE_SUMMARY, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (t: Throwable) {
            WeLogger.e(TAG, "saveSummary failed", t)
        }
    }

    fun getMessagesBetween(
        talker: String,
        afterId: Long,
        beforeId: Long,
        limit: Int = 160,
    ): List<Pair<Long, WxAiClient.ChatMessage>> {
        val list = ArrayList<Pair<Long, WxAiClient.ChatMessage>>()
        try {
            db().rawQuery(
                "SELECT id, role, content FROM $TABLE_MESSAGES WHERE talker = ? AND id > ? AND id < ? ORDER BY id ASC LIMIT ?",
                arrayOf(talker, afterId.toString(), beforeId.toString(), limit.toString())
            ).use { cur ->
                while (cur.moveToNext()) {
                    val role = cur.getString(1) ?: continue
                    val content = cur.getString(2) ?: continue
                    list.add(cur.getLong(0) to WxAiClient.ChatMessage(role = role, content = content))
                }
            }
        } catch (t: Throwable) {
            WeLogger.e(TAG, "getMessagesBetween failed", t)
        }
        return list
    }

    fun appendMessage(
        talker: String,
        role: String,
        content: String,
        timestamp: Long = System.currentTimeMillis(),
    ): Int {
        if (talker.isBlank() || content.isBlank()) return 0
        try {
            val database = db()
            val cv = ContentValues().apply {
                put("talker", talker)
                put("role", role)
                put("content", content)
                put("timestamp", timestamp)
            }
            database.insert(TABLE_MESSAGES, null, cv)

            database.execSQL(
                """
                DELETE FROM $TABLE_MESSAGES
                WHERE talker = ? AND id NOT IN (
                    SELECT id FROM $TABLE_MESSAGES
                    WHERE talker = ?
                    ORDER BY id DESC
                    LIMIT 2000
                )
                """.trimIndent(),
                arrayOf(talker, talker)
            )
        } catch (t: Throwable) {
            WeLogger.e(TAG, "appendMessage failed for $talker", t)
        }
        if (role == "assistant") {
            return incrementUnreflectedTurns(talker)
        }
        return getUnreflectedTurns(talker)
    }

    fun clearSession(talker: String): Int {
        resetReflectionCounter(talker)
        windowAnchorId.remove(talker)
        contextStartId.remove(talker)
        return try {
            runCatching { db().delete(TABLE_SUMMARY, "talker = ?", arrayOf(talker)) }
            db().delete(TABLE_MESSAGES, "talker = ?", arrayOf(talker))
        } catch (t: Throwable) {
            WeLogger.e(TAG, "clearSession failed for $talker", t)
            0
        }
    }

    fun saveMemory(scope: String, fact: String): Boolean {
        val cleanScope = scope.trim().ifEmpty { "global" }
        val cleanFact = fact.trim()
        if (cleanFact.isEmpty()) return false
        return try {
            val database = db()
            database.rawQuery(
                "SELECT id FROM $TABLE_MEMORIES WHERE scope = ? AND fact = ? LIMIT 1",
                arrayOf(cleanScope, cleanFact)
            ).use { cur ->
                if (cur.moveToFirst()) {
                    val existingId = cur.getLong(0)
                    val cv = ContentValues().apply {
                        put("timestamp", System.currentTimeMillis())
                    }
                    database.update(TABLE_MEMORIES, cv, "id = ?", arrayOf(existingId.toString()))
                    return true
                }
            }

            val cv = ContentValues().apply {
                put("scope", cleanScope)
                put("fact", cleanFact)
                put("timestamp", System.currentTimeMillis())
            }
            database.insert(TABLE_MEMORIES, null, cv) > 0
        } catch (t: Throwable) {
            WeLogger.e(TAG, "saveMemory failed", t)
            false
        }
    }

    fun getRelevantMemories(
        talker: String,
        displayName: String?,
        limit: Int = 25,
    ): List<MemoryItem> {
        val result = ArrayList<MemoryItem>()
        try {
            val database = db()
            val hasName = !displayName.isNullOrBlank() && displayName != talker
            val sql = if (hasName) {
                """
                SELECT id, scope, fact, timestamp
                FROM $TABLE_MEMORIES
                WHERE scope = 'global' OR scope = ? OR scope = ? OR fact LIKE ?
                ORDER BY timestamp DESC
                LIMIT ?
                """.trimIndent()
            } else {
                """
                SELECT id, scope, fact, timestamp
                FROM $TABLE_MEMORIES
                WHERE scope = 'global' OR scope = ?
                ORDER BY timestamp DESC
                LIMIT ?
                """.trimIndent()
            }
            val args = if (hasName) {
                arrayOf(talker, displayName!!, "%$displayName%", limit.toString())
            } else {
                arrayOf(talker, limit.toString())
            }
            database.rawQuery(sql, args).use { cur ->
                while (cur.moveToNext()) {
                    result.add(
                        MemoryItem(
                            id = cur.getLong(0),
                            scope = cur.getString(1).orEmpty(),
                            fact = cur.getString(2).orEmpty(),
                            timestamp = cur.getLong(3),
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            WeLogger.e(TAG, "getRelevantMemories failed", t)
        }
        return result
    }

    fun searchMemories(query: String, limit: Int = 30): List<MemoryItem> {
        val result = ArrayList<MemoryItem>()
        try {
            val database = db()
            val q = query.trim()
            val (sql, args) = if (q.isEmpty() || q == "all" || q == "全部") {
                """
                SELECT id, scope, fact, timestamp
                FROM $TABLE_MEMORIES
                ORDER BY timestamp DESC
                LIMIT ?
                """.trimIndent() to arrayOf(limit.toString())
            } else {
                """
                SELECT id, scope, fact, timestamp
                FROM $TABLE_MEMORIES
                WHERE scope LIKE ? OR fact LIKE ?
                ORDER BY timestamp DESC
                LIMIT ?
                """.trimIndent() to arrayOf("%$q%", "%$q%", limit.toString())
            }
            database.rawQuery(sql, args).use { cur ->
                while (cur.moveToNext()) {
                    result.add(
                        MemoryItem(
                            id = cur.getLong(0),
                            scope = cur.getString(1).orEmpty(),
                            fact = cur.getString(2).orEmpty(),
                            timestamp = cur.getLong(3),
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            WeLogger.e(TAG, "searchMemories failed", t)
        }
        return result
    }

    fun searchLocalChatHistory(talker: String, limit: Int = 20): List<WxAiClient.ChatMessage> {
        val list = ArrayList<WxAiClient.ChatMessage>()
        try {
            val database = db()
            database.rawQuery(
                """
                SELECT role, content, timestamp
                FROM $TABLE_MESSAGES
                WHERE talker = ?
                ORDER BY id DESC
                LIMIT ?
                """.trimIndent(),
                arrayOf(talker, limit.coerceIn(1, 50).toString())
            ).use { cur ->
                while (cur.moveToNext()) {
                    val role = cur.getString(0) ?: continue
                    val content = cur.getString(1) ?: continue
                    list.add(WxAiClient.ChatMessage(role = role, content = content))
                }
            }
        } catch (t: Throwable) {
            WeLogger.e(TAG, "searchLocalChatHistory failed", t)
        }
        return list.asReversed()
    }
}
