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
