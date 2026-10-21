package org.music.infiniteplayer.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 앱 설정 저장소.
 *
 * SharedPreferences 를 그대로 쓰면
 *  - 첫 실행 지연이 없고
 *  - 별도 의존성( DataStore )이 필요 없어 앱 시작이 빠르다.
 */
object Prefs {

    private const val FILE = "infinite_player_prefs"
    private lateinit var sp: SharedPreferences

    // ── 설정 항목 ───────────────────────────────────────────────
    const val KEY_VOLUME = "volume"
    const val KEY_REPEAT = "repeat"          // all | one | off
    const val KEY_SHUFFLE = "shuffle"
    const val KEY_INFINITE = "infinite"
    const val KEY_QUALITY = "quality"        // high | medium | low
    const val KEY_AUTOPLAY = "autoplay"
    const val KEY_SOURCE = "last_source"
    const val KEY_CHART_PREFIX = "chart_"

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    // ── 기본값 ─────────────────────────────────────────────────
    var volume: Float
        get() = sp.getFloat(KEY_VOLUME, 0.85f)
        set(v) = sp.edit().putFloat(KEY_VOLUME, v.coerceIn(0f, 1f)).apply()

    var repeat: String
        get() = sp.getString(KEY_REPEAT, "all") ?: "all"
        set(v) = sp.edit().putString(KEY_REPEAT, v).apply()

    var shuffle: Boolean
        get() = sp.getBoolean(KEY_SHUFFLE, false)
        set(v) = sp.edit().putBoolean(KEY_SHUFFLE, v).apply()

    var infinite: Boolean
        get() = sp.getBoolean(KEY_INFINITE, true)
        set(v) = sp.edit().putBoolean(KEY_INFINITE, v).apply()

    var quality: String
        get() = sp.getString(KEY_QUALITY, "high") ?: "high"
        set(v) = sp.edit().putString(KEY_QUALITY, v).apply()

    var autoplay: Boolean
        get() = sp.getBoolean(KEY_AUTOPLAY, true)
        set(v) = sp.edit().putBoolean(KEY_AUTOPLAY, v).apply()

    var lastSource: String
        get() = sp.getString(KEY_SOURCE, "") ?: ""
        set(v) = sp.edit().putString(KEY_SOURCE, v).apply()

    /** 유튜브 방문자 ID (봇 판정 회피용) */
    var visitorData: String
        get() = sp.getString("visitor_data", "") ?: ""
        set(v) = sp.edit().putString("visitor_data", v).apply()

    // ── 차트 캐시 (재실행 시 즉시 표시) ─────────────────────────
    fun cachedChart(sourceKey: String): String =
        sp.getString(KEY_CHART_PREFIX + sourceKey, "") ?: ""

    fun saveChart(sourceKey: String, json: String) {
        sp.edit().putString(KEY_CHART_PREFIX + sourceKey, json).apply()
    }

    // ── 즐겨찾기 ───────────────────────────────────────────────
    fun favorites(): List<String> {
        val raw = sp.getString("favorites", "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun toggleFavorite(key: String): Boolean {
        val list = favorites().toMutableList()
        val added = if (list.contains(key)) {
            list.remove(key); false
        } else {
            list.add(key); true
        }
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        sp.edit().putString("favorites", arr.toString()).apply()
        return added
    }

    fun isFavorite(key: String): Boolean = favorites().contains(key)

    // ── 즐겨찾기 상세 정보 저장 ─────────────────────────────────
    fun favoriteTracks(): List<JSONObject> {
        val raw = sp.getString("favorite_tracks", "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun toggleFavoriteTrack(obj: JSONObject): Boolean {
        val key = obj.optString("id")
        val list = favoriteTracks().toMutableList()
        val idx = list.indexOfFirst { it.optString("id") == key }
        val added: Boolean
        if (idx >= 0) {
            list.removeAt(idx); added = false
        } else {
            list.add(obj); added = true
        }
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        sp.edit().putString("favorite_tracks", arr.toString()).apply()
        toggleFavorite(key)
        return added
    }
}
