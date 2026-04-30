package com.jarvis.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.json.JSONArray
import org.json.JSONObject

data class Recipe(
    val name: String,
    val trigger: String,
    val steps: List<String>
)

object RecipeManager {
    private const val PREFS_NAME = "jarvis_recipes"
    private const val PREFS_KEY = "recipes_json"
    private const val PREFS_SEEDED_KEY = "recipes_seeded"
    private const val RECIPES_PATH = "/jarvis/recipes"
    private const val TAG = "JARVIS_CMD"

    private var prefs: SharedPreferences? = null
    private var firebaseRef: DatabaseReference? = null
    private val recipes = mutableListOf<Recipe>()
    @Volatile private var initialized = false

    private val defaults = listOf(
        Recipe(
            name = "gym mode",
            trigger = "gym mode",
            steps = listOf(
                "open spotify",
                "play workout playlist",
                "set volume to 80%",
                "do not disturb on"
            )
        ),
        Recipe(
            name = "sleep mode",
            trigger = "sleep mode",
            steps = listOf(
                "alarm 7am",
                "do not disturb on",
                "volume to 20%",
                "close all apps"
            )
        ),
        Recipe(
            name = "study mode",
            trigger = "study mode",
            steps = listOf(
                "do not disturb on",
                "open notion",
                "timer 25 minutes"
            )
        ),
        Recipe(
            name = "morning routine",
            trigger = "morning routine",
            steps = listOf(
                "read weather",
                "read calendar",
                "read news headlines"
            )
        )
    )

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            firebaseRef = try {
                FirebaseDatabase.getInstance().getReference(RECIPES_PATH)
            } catch (e: Exception) {
                Log.e(TAG, "RecipeManager: Firebase init failed: ${e.message}")
                null
            }

            loadFromPrefs()

            val seededBefore = prefs?.getBoolean(PREFS_SEEDED_KEY, false) ?: false
            if (recipes.isEmpty() && !seededBefore) {
                recipes.addAll(defaults)
                prefs?.edit()?.putBoolean(PREFS_SEEDED_KEY, true)?.apply()
                saveAll()
                Log.e(TAG, "RecipeManager: seeded ${defaults.size} default recipes")
            }

            firebaseRef?.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val cloud = parseSnapshot(snapshot)
                    if (cloud.isNotEmpty()) {
                        synchronized(recipes) {
                            recipes.clear()
                            recipes.addAll(cloud)
                        }
                        saveToPrefs()
                        Log.e(TAG, "RecipeManager: loaded ${cloud.size} recipes from Firebase")
                    } else if (recipes.isNotEmpty()) {
                        saveToFirebase()
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "RecipeManager: Firebase load cancelled: ${error.message}")
                }
            })

            initialized = true
        }
    }

    fun all(): List<Recipe> = synchronized(recipes) { recipes.toList() }

    fun findByName(name: String): Recipe? {
        val n = name.lowercase().trim()
        return synchronized(recipes) { recipes.firstOrNull { it.name.lowercase().trim() == n } }
    }

    fun findByTrigger(text: String): Recipe? {
        val t = text.lowercase().trim()
        return synchronized(recipes) {
            recipes.firstOrNull {
                it.trigger.lowercase().trim() == t || it.name.lowercase().trim() == t
            }
        }
    }

    fun add(recipe: Recipe) {
        synchronized(recipes) {
            recipes.removeAll { it.name.lowercase().trim() == recipe.name.lowercase().trim() }
            recipes.add(recipe)
        }
        saveAll()
    }

    fun delete(name: String): Boolean {
        val n = name.lowercase().trim()
        val removed = synchronized(recipes) {
            recipes.removeAll { it.name.lowercase().trim() == n }
        }
        if (removed) saveAll()
        return removed
    }

    private fun saveAll() {
        saveToPrefs()
        saveToFirebase()
    }

    private fun saveToPrefs() {
        try {
            val arr = JSONArray()
            val snapshot = synchronized(recipes) { recipes.toList() }
            for (r in snapshot) {
                val o = JSONObject()
                o.put("name", r.name)
                o.put("trigger", r.trigger)
                val s = JSONArray()
                for (step in r.steps) s.put(step)
                o.put("steps", s)
                arr.put(o)
            }
            prefs?.edit()?.putString(PREFS_KEY, arr.toString())?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "RecipeManager: prefs save failed: ${e.message}")
        }
    }

    private fun loadFromPrefs() {
        synchronized(recipes) { recipes.clear() }
        try {
            val json = prefs?.getString(PREFS_KEY, null) ?: return
            val arr = JSONArray(json)
            val parsed = mutableListOf<Recipe>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val steps = mutableListOf<String>()
                val s = o.optJSONArray("steps") ?: JSONArray()
                for (j in 0 until s.length()) steps.add(s.getString(j))
                val n = o.optString("name", "")
                if (n.isEmpty()) continue
                parsed.add(
                    Recipe(
                        name = n,
                        trigger = o.optString("trigger", n),
                        steps = steps
                    )
                )
            }
            synchronized(recipes) { recipes.addAll(parsed) }
            Log.e(TAG, "RecipeManager: loaded ${parsed.size} recipes from SharedPreferences")
        } catch (e: Exception) {
            Log.e(TAG, "RecipeManager: prefs load failed: ${e.message}")
        }
    }

    private fun saveToFirebase() {
        val ref = firebaseRef ?: return
        try {
            val snapshot = synchronized(recipes) { recipes.toList() }
            val map = LinkedHashMap<String, Any>()
            for (r in snapshot) {
                val key = sanitizeKey(r.name)
                if (key.isEmpty()) continue
                map[key] = mapOf(
                    "name" to r.name,
                    "trigger" to r.trigger,
                    "steps" to r.steps
                )
            }
            ref.setValue(map)
        } catch (e: Exception) {
            Log.e(TAG, "RecipeManager: Firebase save failed: ${e.message}")
        }
    }

    private fun sanitizeKey(name: String): String {
        return name.lowercase().trim()
            .replace(".", "_").replace("#", "_").replace("$", "_")
            .replace("[", "_").replace("]", "_").replace("/", "_")
            .replace(" ", "_")
    }

    private fun parseSnapshot(snapshot: DataSnapshot): List<Recipe> {
        val out = mutableListOf<Recipe>()
        try {
            for (child in snapshot.children) {
                val name = child.child("name").getValue(String::class.java) ?: continue
                val trigger = child.child("trigger").getValue(String::class.java) ?: name
                val stepsList = mutableListOf<String>()
                val steps = child.child("steps")
                for (s in steps.children) {
                    s.getValue(String::class.java)?.let { stepsList.add(it) }
                }
                if (stepsList.isEmpty()) continue
                out.add(Recipe(name, trigger, stepsList))
            }
        } catch (e: Exception) {
            Log.e(TAG, "RecipeManager: snapshot parse failed: ${e.message}")
        }
        return out
    }
}
