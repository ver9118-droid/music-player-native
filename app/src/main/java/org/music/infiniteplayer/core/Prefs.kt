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
    const val KEY_LAST_ERROR = "last_error"       // 최근 오류 (진단용, 지워지지 않음)
    const val KEY_LAST_ERROR_AT = "last_error_at" // 최근 오류 시각
    const val KEY_DIAGNOSTICS = "diagnostics"     // 진단 모드 (오류 표시·기록)

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

    /** 진단용 : 마지막으로 발생한 재생 오류 (앱을 다시 열어도 남는다) */
    var lastError: String
        get() = sp.getString(KEY_LAST_ERROR, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_ERROR, v).apply()

    var lastErrorAt: String
        get() = sp.getString(KEY_LAST_ERROR_AT, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_ERROR_AT, v).apply()

    /**
     * 진단 모드.
     *
     *  - 꺼짐(기본) : 오류 메시지를 화면에 띄우지 않고, 오류/스택 기록도 남기지 않는다
     *                (평소 사용에는 조용하고 가볍게)
     *  - 켜짐      : 오류 배너·진단 화면·로그 기록을 모두 사용
     *                (문제가 생겼을 때 사용자가 직접 켠다)
     */
    var diagnostics: Boolean
        get() = sp.getBoolean(KEY_DIAGNOSTICS, false)
        set(v) = sp.edit().putBoolean(KEY_DIAGNOSTICS, v).apply()

    /** 유튜브 방문자 ID (봇 판정 회피용) */
    var visitorData: String
        get() = sp.getString("visitor_data", "") ?: ""
        set(v) = sp.edit().putString("visitor_data", v).apply()

    // ── 재생목록 복원용 (서비스가 죽어도 곡이 사라지지 않게) ──
    /** 재생목록 전체 (JSON) */
    var playbackQueue: String
        get() = sp.getString("playback_queue", "") ?: ""
        set(v) = sp.edit().putString("playback_queue", v).apply()

    /** 재생목록에서 현재 곡의 인덱스 */
    var playbackIndex: Int
        get() = sp.getInt("playback_index", 0)
        set(v) = sp.edit().putInt("playback_index", v).apply()

    /** 현재 재생 위치 (ms) */
    var playbackPosition: Long
        get() = sp.getLong("playback_position", 0L)
        set(v) = sp.edit().putLong("playback_position", v).apply()

    /** 마지막으로 재생 중이었는지 */
    var playbackPlaying: Boolean
        get() = sp.getBoolean("playback_playing", false)
        set(v) = sp.edit().putBoolean("playback_playing", v).apply()

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
