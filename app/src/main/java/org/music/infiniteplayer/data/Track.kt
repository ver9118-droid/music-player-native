package org.music.infiniteplayer.data

import org.json.JSONArray
import org.json.JSONObject

/** 재생목록 한 곡. */
data class Track(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val rank: Int = 0,
    val source: String = "",
    val pageUrl: String = "",
    val videoId: String = "",
    val thumbUrl: String = "",
    val durationMs: Long = 0L,
) {
    val display: String
        get() = if (artist.isBlank()) title else "$artist - $title"

    /**
     * 모음/플레이리스트 영상인지.
     *
     * 차트 곡은 3~4분짜리 뮤직비디오/음원이지만, 유튜브 검색으로 가져온
     * "명곡 모음" 같은 항목은 20분~10시간짜리 한 영상이다.
     * 이런 항목은 영상 화면 대신 앨범아트만 보여준다.
     */
    val isCompilation: Boolean
        get() {
            if (durationMs >= 20 * 60_000L) return true
            val low = title.lowercase()
            for (w in COMPILATION_WORDS) if (low.contains(w)) return true
            return false
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("rank", rank)
        put("source", source)
        put("pageUrl", pageUrl)
        put("videoId", videoId)
        put("thumbUrl", thumbUrl)
        put("durationMs", durationMs)
    }

    companion object {
        private val COMPILATION_WORDS = listOf(
            "모음", "playlist", "플레이리스트", "연속재생", "연속 듣기", "메들리", "medley",
            "nonstop", "논스톱", "1시간", "2시간", "3시간", "4시간", "5시간", "10시간",
            "greatest hits", "best songs", "full album", "full playlist", "compilation",
            "인기차트", "실시간 인기", "차트", "선곡", "bgm", "카페음악", "작업용", "수면",
        )

        fun fromJson(o: JSONObject): Track = Track(
            id = o.optString("id"),
            title = o.optString("title"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            rank = o.optInt("rank"),
            source = o.optString("source"),
            pageUrl = o.optString("pageUrl"),
            videoId = o.optString("videoId"),
            thumbUrl = o.optString("thumbUrl"),
            durationMs = o.optLong("durationMs"),
        )
    }
}

/** 목록 → JSON (캐시 저장용) */
fun encodeTracks(list: List<Track>): String {
    val arr = JSONArray()
    list.forEach { arr.put(it.toJson()) }
    return arr.toString()
}

/** JSON → 목록 */
fun decodeTracks(json: String): List<Track> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { Track.fromJson(it) } }
    } catch (_: Exception) {
        emptyList()
    }
}
