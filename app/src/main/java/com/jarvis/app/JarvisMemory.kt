package com.jarvis.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class MemoryEntry(
    var id: String = "",
    var key: String = "",
    var value: String = "",
    var category: String = "other",
    var timestamp: Long = 0L,
    var source: String = "phone",
    var lastAccessed: Long = 0L,
    var accessCount: Int = 0,
    var keywords: String = ""
)

object JarvisMemory {
    private const val GLOBAL_PATH = "/jarvis/memory/global"
    private const val VERSION_PATH = "/jarvis/memory/version"
    private const val SYNC_LOG_PATH = "/jarvis/memory/sync_log"
    private const val PREFS_NAME = "jarvis_memory_cache"
    private const val PREFS_KEY = "memories_json"
    private const val SOURCE = "phone"
    private const val DUP_WINDOW_MS = 5_000L

    private val cache = mutableListOf<MemoryEntry>()
    private var prefs: SharedPreferences? = null
    private var listenerAttached = false
    private var globalRef: DatabaseReference? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val stopwords = setOf(
        "the","a","an","and","or","but","if","is","are","was","were","be","been","being",
        "have","has","had","do","does","did","will","would","could","should","may","might",
        "must","can","i","you","he","she","it","we","they","me","him","her","us","them",
        "my","your","his","its","our","their","this","that","these","those","what","which",
        "who","when","where","why","how","of","in","on","at","to","for","with","by","about",
        "from","as","into","like","after","over","between","out","against","during","without",
        "before","under","around","among","through","up","down","off","also","very","really",
        "just","so","than","then","there","here","yes","no","not","know","think","want","need",
        "get","got","sir","jarvis","please","remember","forget","tell"
    )

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
        attachFirebaseListener()
        Log.e("JARVIS_CMD", "JarvisMemory initialized — ${cache.size} cached entries")
    }

    fun cacheSize(): Int = synchronized(cache) { cache.size }

    // ─── Persistence: local JSON cache so offline phone still has data ────────

    private fun loadFromPrefs() {
        try {
            val json = prefs?.getString(PREFS_KEY, null) ?: return
            val arr = JSONArray(json)
            synchronized(cache) {
                cache.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    cache.add(
                        MemoryEntry(
                            id = o.optString("id"),
                            key = o.optString("key"),
                            value = o.optString("value"),
                            category = o.optString("category", "other"),
                            timestamp = o.optLong("timestamp"),
                            source = o.optString("source", "phone"),
                            lastAccessed = o.optLong("lastAccessed"),
                            accessCount = o.optInt("accessCount"),
                            keywords = o.optString("keywords")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Memory cache load failed: ${e.message}")
        }
    }

    private fun saveToPrefs() {
        try {
            val arr = JSONArray()
            synchronized(cache) {
                cache.forEach { e ->
                    arr.put(JSONObject().apply {
                        put("id", e.id); put("key", e.key); put("value", e.value)
                        put("category", e.category); put("timestamp", e.timestamp)
                        put("source", e.source); put("lastAccessed", e.lastAccessed)
                        put("accessCount", e.accessCount); put("keywords", e.keywords)
                    })
                }
            }
            prefs?.edit()?.putString(PREFS_KEY, arr.toString())?.apply()
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Memory cache save failed: ${e.message}")
        }
    }

    // ─── Firebase real-time listener ──────────────────────────────────────────

    private fun attachFirebaseListener() {
        if (listenerAttached) return
        try {
            globalRef = FirebaseDatabase.getInstance().getReference(GLOBAL_PATH)
            globalRef?.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    synchronized(cache) {
                        cache.clear()
                        for (child in snapshot.children) {
                            parseSnapshot(child)?.let { cache.add(it) }
                        }
                        cache.sortByDescending { it.timestamp }
                    }
                    saveToPrefs()
                    Log.e("JARVIS_CMD", "Memory synced from Firebase — ${cacheSize()} entries")
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("JARVIS_CMD", "Memory listener cancelled: ${error.message}")
                }
            })
            listenerAttached = true
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "Memory listener attach failed: ${e.message}")
        }
    }

    private fun parseSnapshot(snap: DataSnapshot): MemoryEntry? {
        return try {
            val map = snap.value as? Map<*, *> ?: return null
            MemoryEntry(
                id = snap.key ?: "",
                key = (map["key"] as? String) ?: "",
                value = (map["value"] as? String) ?: "",
                category = (map["category"] as? String) ?: "other",
                timestamp = (map["timestamp"] as? Number)?.toLong() ?: 0L,
                source = (map["source"] as? String) ?: "unknown",
                lastAccessed = (map["lastAccessed"] as? Number)?.toLong() ?: 0L,
                accessCount = (map["accessCount"] as? Number)?.toInt() ?: 0,
                keywords = (map["keywords"] as? String) ?: ""
            )
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "parseSnapshot failed: ${e.message}")
            null
        }
    }

    fun forceSync(onDone: (Int) -> Unit = {}) {
        try {
            FirebaseDatabase.getInstance().getReference(GLOBAL_PATH)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        synchronized(cache) {
                            cache.clear()
                            for (child in snapshot.children) {
                                parseSnapshot(child)?.let { cache.add(it) }
                            }
                            cache.sortByDescending { it.timestamp }
                        }
                        saveToPrefs()
                        Log.e("JARVIS_CMD", "Memory force-synced — ${cacheSize()} entries")
                        onDone(cacheSize())
                    }
                    override fun onCancelled(error: DatabaseError) { onDone(cacheSize()) }
                })
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "forceSync failed: ${e.message}")
            onDone(cacheSize())
        }
    }

    // ─── Write: remember(text) ────────────────────────────────────────────────

    fun remember(rawText: String, onDone: (MemoryEntry?) -> Unit = {}) {
        if (rawText.isBlank()) { onDone(null); return }
        val trimmed = rawText.trim()
        val now = System.currentTimeMillis()
        val provisionalKey = deriveKey(trimmed)

        scope.launch {
            // Conflict-resolution: if a recent entry (<5s) already has the same key,
            // overwrite it (latest-timestamp-wins). Avoids PC+Phone duplicate writes.
            val collision = synchronized(cache) {
                cache.firstOrNull { it.key == provisionalKey && now - it.timestamp < DUP_WINDOW_MS }
            }
            val (category, keywords) = categorizeWithClaude(trimmed)
            try {
                if (collision != null && collision.id.isNotBlank()) {
                    val updates = mapOf(
                        "value" to trimmed,
                        "category" to category,
                        "timestamp" to now,
                        "source" to SOURCE,
                        "lastAccessed" to now,
                        "keywords" to keywords
                    )
                    FirebaseDatabase.getInstance().getReference("$GLOBAL_PATH/${collision.id}")
                        .updateChildren(updates)
                    bumpVersion()
                    logSync("merge", collision.id, provisionalKey)
                    Log.e("JARVIS_CMD", "Merged into existing memory '${collision.id}' (latest timestamp wins)")
                    val merged = collision.copy(
                        value = trimmed, category = category, timestamp = now,
                        source = SOURCE, lastAccessed = now, keywords = keywords
                    )
                    onDone(merged)
                    return@launch
                }
                val ref = FirebaseDatabase.getInstance().getReference(GLOBAL_PATH).push()
                val id = ref.key ?: ""
                val map = mapOf(
                    "key" to provisionalKey,
                    "value" to trimmed,
                    "category" to category,
                    "timestamp" to now,
                    "source" to SOURCE,
                    "lastAccessed" to now,
                    "accessCount" to 0,
                    "keywords" to keywords
                )
                ref.setValue(map)
                bumpVersion()
                logSync("write", id, provisionalKey)
                Log.e("JARVIS_CMD", "Remembered [$category]: '$provisionalKey' = '$trimmed'")
                onDone(
                    MemoryEntry(
                        id = id, key = provisionalKey, value = trimmed,
                        category = category, timestamp = now, source = SOURCE,
                        lastAccessed = now, accessCount = 0, keywords = keywords
                    )
                )
            } catch (e: Exception) {
                Log.e("JARVIS_CMD", "remember() Firebase write failed: ${e.message}")
                onDone(null)
            }
        }
    }

    private fun deriveKey(text: String): String {
        val cleaned = text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val key = cleaned.split(" ")
            .filter { it.length >= 3 && it !in stopwords }
            .take(4)
            .joinToString("_")
        return if (key.isEmpty()) "memory_${System.currentTimeMillis()}" else key
    }

    private suspend fun categorizeWithClaude(text: String): Pair<String, String> = withContext(Dispatchers.IO) {
        try {
            val prompt = """Categorize this memory into ONE of: location, preference, person, event, task, fact, relationship, project, other. Also extract 3-5 keywords. Return JSON only: {"category": "...", "keywords": "kw1, kw2, kw3"}. Memory: $text"""
            val body = JSONObject().apply {
                put("model", "claude-sonnet-4-6")
                put("max_tokens", 150)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user"); put("content", prompt)
                }))
            }
            val resp = OkHttpClient().newCall(
                Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("x-api-key", ANTHROPIC_API_KEY)
                    .addHeader("anthropic-version", "2023-06-01")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            val out = resp.body?.string() ?: return@withContext fallbackCategory(text)
            val rawText = JSONObject(out).getJSONArray("content").getJSONObject(0)
                .getString("text").trim()
            val jsonStr = rawText.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
            val obj = JSONObject(jsonStr)
            val cat = obj.optString("category", "other").lowercase().trim()
            val kws = obj.optString("keywords", "").lowercase().trim()
            val validCats = setOf(
                "location","preference","person","event","task","fact",
                "relationship","project","other"
            )
            val finalCat = if (cat in validCats) cat else "other"
            val finalKws = if (kws.isNotEmpty()) kws else fallbackCategory(text).second
            Pair(finalCat, finalKws)
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "categorizeWithClaude failed: ${e.message}")
            fallbackCategory(text)
        }
    }

    private fun fallbackCategory(text: String): Pair<String, String> {
        val kws = text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 4 && it !in stopwords }
            .distinct().take(5).joinToString(", ")
        return Pair("other", kws)
    }

    // ─── Search: keyword (fast) ───────────────────────────────────────────────

    fun searchByKeywords(query: String, limit: Int = 5): List<MemoryEntry> {
        val terms = query.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 && it !in stopwords }
        val now = System.currentTimeMillis()
        val snapshot = synchronized(cache) { cache.toList() }
        if (terms.isEmpty()) {
            return snapshot.sortedByDescending { it.timestamp }.take(limit)
                .also { it.forEach(::touch) }
        }
        val scored = snapshot.map { e ->
            var score = 0.0
            val hay = (e.key + " " + e.value + " " + e.keywords + " " + e.category).lowercase()
            for (t in terms) if (hay.contains(t)) score += 3.0
            val ageDays = ((now - e.timestamp) / 86_400_000.0).coerceAtLeast(0.1)
            score += 1.0 / ageDays
            score += e.accessCount * 0.05
            e to score
        }
        return scored.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .also { it.forEach(::touch) }
    }

    private fun touch(e: MemoryEntry) {
        if (e.id.isBlank()) return
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                FirebaseDatabase.getInstance().getReference("$GLOBAL_PATH/${e.id}")
                    .updateChildren(
                        mapOf(
                            "lastAccessed" to now,
                            "accessCount" to (e.accessCount + 1)
                        )
                    )
            } catch (_: Exception) {}
        }
    }

    // ─── Search: semantic (Claude reads top 20 recent + answers) ──────────────

    fun semanticSearch(query: String, onResult: (String) -> Unit) {
        val recent = synchronized(cache) {
            cache.sortedByDescending { it.timestamp }.take(20).toList()
        }
        if (recent.isEmpty()) { onResult("I have no memories on that, sir."); return }
        scope.launch {
            try {
                val sb = StringBuilder()
                recent.forEachIndexed { i, e ->
                    sb.append("${i + 1}. [${e.category}] ${e.value}\n")
                }
                val prompt = """User query: "$query"

Memories I have stored:
$sb
Pick the SINGLE most relevant memory and answer the user's query in ONE concise sentence as Jarvis (British wit, address user as 'sir', no markdown). If nothing fits, say "I have no recollection of that, sir."""".trimIndent()
                val body = JSONObject().apply {
                    put("model", "claude-sonnet-4-6")
                    put("max_tokens", 200)
                    put("messages", JSONArray().put(JSONObject().apply {
                        put("role", "user"); put("content", prompt)
                    }))
                }
                val resp = OkHttpClient().newCall(
                    Request.Builder()
                        .url("https://api.anthropic.com/v1/messages")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("x-api-key", ANTHROPIC_API_KEY)
                        .addHeader("anthropic-version", "2023-06-01")
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute()
                val out = resp.body?.string()
                val text = if (out != null) {
                    JSONObject(out).getJSONArray("content").getJSONObject(0)
                        .getString("text").replace("**", "").replace("*", "").trim()
                } else "I have no recollection of that, sir."
                onResult(text)
            } catch (e: Exception) {
                Log.e("JARVIS_CMD", "semanticSearch failed: ${e.message}")
                onResult("I encountered a difficulty recalling that, sir.")
            }
        }
    }

    // ─── Auto-recall: tiny matcher to inject memories into Claude prompt ──────

    fun findRelevantForPrompt(userText: String, limit: Int = 4): List<MemoryEntry> {
        val terms = userText.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 4 && it !in stopwords }
        if (terms.isEmpty()) return emptyList()
        val snapshot = synchronized(cache) { cache.toList() }
        val scored = snapshot.map { e ->
            val hay = (e.key + " " + e.value + " " + e.keywords).lowercase()
            var score = 0
            for (t in terms) if (hay.contains(t)) score++
            e to score
        }
        return scored.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    // ─── Forget ───────────────────────────────────────────────────────────────

    fun forget(topic: String, onDone: (Int) -> Unit = {}) {
        val matches = searchByKeywords(topic, limit = 20)
        if (matches.isEmpty()) { onDone(0); return }
        scope.launch {
            var deleted = 0
            for (m in matches) {
                if (m.id.isBlank()) continue
                try {
                    FirebaseDatabase.getInstance().getReference("$GLOBAL_PATH/${m.id}")
                        .removeValue()
                    logSync("delete", m.id, m.key)
                    deleted++
                } catch (e: Exception) {
                    Log.e("JARVIS_CMD", "forget delete failed: ${e.message}")
                }
            }
            bumpVersion()
            Log.e("JARVIS_CMD", "Forgot $deleted memor${if (deleted == 1) "y" else "ies"} matching '$topic'")
            onDone(deleted)
        }
    }

    // ─── Grouped recent (for "what do you remember about me") ─────────────────

    fun listGroupedByCategory(limit: Int = 10): Map<String, List<MemoryEntry>> {
        val snapshot = synchronized(cache) { cache.toList() }
        return snapshot.sortedByDescending { it.timestamp }
            .take(limit)
            .groupBy { it.category }
    }

    // ─── Version + sync log ───────────────────────────────────────────────────

    private fun bumpVersion() {
        try {
            FirebaseDatabase.getInstance().getReference(VERSION_PATH)
                .runTransaction(object : Transaction.Handler {
                    override fun doTransaction(currentData: MutableData): Transaction.Result {
                        val v = (currentData.value as? Number)?.toLong() ?: 0L
                        currentData.value = v + 1
                        return Transaction.success(currentData)
                    }
                    override fun onComplete(
                        error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?
                    ) {}
                })
        } catch (e: Exception) {
            Log.e("JARVIS_CMD", "bumpVersion failed: ${e.message}")
        }
    }

    private fun logSync(action: String, id: String, key: String) {
        try {
            FirebaseDatabase.getInstance().getReference(SYNC_LOG_PATH).push().setValue(
                mapOf(
                    "action" to action,
                    "id" to id,
                    "key" to key,
                    "source" to SOURCE,
                    "timestamp" to System.currentTimeMillis()
                )
            )
        } catch (_: Exception) {}
    }
}
