package org.music.infiniteplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.music.infiniteplayer.core.Net
import org.music.infiniteplayer.core.Text

/** resolve() 결과: 실제로 재생할 수 있는 오디오 스트림. */
data class ResolvedAudio(
    val videoId: String,
    val url: String,
    val mimeType: String,
    val itag: Int,
    val captionUrl: String = "",
    val durationMs: Long = 0L,
    val title: String = "",
    val artist: String = "",
    val thumbUrl: String = "",
)

/**
 * 유튜브 InnerTube API 래퍼.
 *
 *  - 검색: youtubei/v1/search
 *  - 스트림: youtubei/v1/player  → 암호화 없는 직접 URL 을 돌려준다.
 *
 * 클라이언트를 여러 개 순서대로 시도해서, 하나라도 재생 가능한 URL 을 주면
 * 그걸 쓴다. (유튜브가 특정 클라이언트를 막는 경우가 있어 대비)
 */
class YoutubeRepository(var quality: String = "high") {

    suspend fun search(query: String, limit: Int = 20): List<Track> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("context", clientContext(CLIENTS.first()))
            put("query", query)
            put("params", "EgWKAQIIAWoKEAkQBRAK")
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val json = Net.postJson("$API/search", body.toString(), ytHeaders())
        parseSearch(json, limit)
    }

    /** 차트 곡 → 실제 재생 가능한 오디오 URL. */
    suspend fun resolve(track: Track, preferItags: List<Int>? = null): ResolvedAudio? =
        withContext(Dispatchers.IO) {
            var videoId = track.videoId
            var title = track.title
            var artist = track.artist
            var thumb = track.thumbUrl
            var duration = track.durationMs

            if (videoId.isBlank()) {
                val results = search(buildQuery(track), 10)
                val best = pickBest(results, track) ?: return@withContext null
                videoId = best.videoId
                title = best.title
                artist = best.artist
                thumb = best.thumbUrl
                duration = best.durationMs
            }
            resolveVideo(videoId, title, artist, thumb, duration, preferItags)
        }

    private fun resolveVideo(
        videoId: String,
        title: String,
        artist: String,
        thumb: String,
        duration: Long,
        preferItags: List<Int>? = null,
    ): ResolvedAudio? {
        for (client in CLIENTS) {
            try {
                val ctx = clientContext(client)
                if (client.embed) {
                    ctx.put("thirdParty", JSONObject().put("embedUrl", "https://www.youtube.com/"))
                }
                val body = JSONObject().apply {
                    put("context", ctx)
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }
                val json = Net.postJson("$API/player", body.toString(), ytHeaders(client.ua))
                val obj = JSONObject(json)
                val status = obj.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                if (status != "OK") continue

                val streaming = obj.optJSONObject("streamingData") ?: continue
                val fmt = pickAudio(streaming.optJSONArray("adaptiveFormats"), preferItags) ?: continue

                return ResolvedAudio(
                    videoId = videoId,
                    url = fmt.url,
                    mimeType = fmt.mime,
                    itag = fmt.itag,
                    captionUrl = pickCaption(obj),
                    durationMs = duration,
                    title = title,
                    artist = artist,
                    thumbUrl = thumb,
                )
            } catch (_: Exception) {
                // 다음 클라이언트로 넘어간다
            }
        }
        return null
    }

    // ── 응답 파싱 ─────────────────────────────────────────────
    private fun parseSearch(json: String, limit: Int): List<Track> {
        val root = JSONObject(json)
        val raw = ArrayList<JSONObject>()
        collect(root, "videoRenderer", raw)
        collect(root, "compactVideoRenderer", raw)

        val seen = HashSet<String>()
        val out = ArrayList<Track>(limit)
        for (item in raw) {
            val id = item.optString("videoId")
            if (id.isBlank() || !seen.add(id)) continue
            val title = textOf(item.optJSONObject("title"))
            if (title.isBlank()) continue
            val artist = textOf(item.optJSONObject("longBylineText"))
                .ifBlank { textOf(item.optJSONObject("shortBylineText")) }
            val duration = parseDuration(item.optJSONObject("lengthText")?.optString("simpleText").orEmpty())
            val thumb = pickThumb(item.optJSONObject("thumbnail"))
            out.add(
                Track(
                    id = "yt_$id",
                    title = title,
                    artist = artist,
                    source = "youtube",
                    pageUrl = "https://www.youtube.com/watch?v=$id",
                    videoId = id,
                    thumbUrl = thumb,
                    durationMs = duration,
                )
            )
            if (out.size >= limit) break
        }
        return out
    }

    private fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                val it = node.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = node.opt(k)
                    if (k == key && v is JSONObject) out.add(v) else collect(v, key, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
        }
    }

    private fun textOf(o: JSONObject?): String {
        if (o == null) return ""
        val runs = o.optJSONArray("runs")
        if (runs != null && runs.length() > 0) {
            val sb = StringBuilder()
            for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
            if (sb.isNotEmpty()) return sb.toString()
        }
        return o.optString("simpleText")
    }

    private fun pickThumb(o: JSONObject?): String {
        val arr = o?.optJSONArray("thumbnails") ?: return ""
        var url = ""
        var width = -1
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val w = t.optInt("width", 0)
            if (w >= width) {
                width = w
                url = t.optString("url")
            }
        }
        return url
    }

    private fun parseDuration(text: String): Long {
        val parts = text.split(":")
        if (parts.isEmpty()) return 0L
        var sec = 0L
        for (p in parts) {
            val v = p.trim().toLongOrNull() ?: return 0L
            sec = sec * 60 + v
        }
        return sec * 1000L
    }

    // ── 포맷 선택 ─────────────────────────────────────────────
    private data class Fmt(val itag: Int, val url: String, val mime: String, val bitrate: Int)

    private fun pickAudio(formats: JSONArray?, preferItags: List<Int>?): Fmt? {
        if (formats == null) return null
        val list = ArrayList<Fmt>()
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val url = f.optString("url")
            if (url.isBlank()) continue           // 암호화(cipher) 필요한 포맷은 사용 불가
            val mime = f.optString("mimeType")
            if (!mime.startsWith("audio")) continue
            list.add(Fmt(f.optInt("itag"), url, mime, f.optInt("bitrate")))
        }
        if (list.isEmpty()) return null
        val preferred = preferItags ?: (QUALITY_ITAGS[quality] ?: QUALITY_ITAGS.getValue("high"))
        for (itag in preferred) {
            list.firstOrNull { it.itag == itag }?.let { return it }
        }
        return list.maxByOrNull { it.bitrate }
    }

    private fun pickCaption(obj: JSONObject): String {
        val tracks = obj.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks") ?: return ""
        var manual = ""
        var asr = ""
        var other = ""
        for (i in 0 until tracks.length()) {
            val t = tracks.optJSONObject(i) ?: continue
            val url = t.optString("baseUrl")
            if (url.isBlank()) continue
            val lang = t.optString("languageCode")
            val kind = t.optString("kind")
            if (lang == "ko" || lang.startsWith("ko-")) {
                if (kind.isEmpty()) {
                    if (manual.isEmpty()) manual = url
                } else if (asr.isEmpty()) {
                    asr = url
                }
            } else if (other.isEmpty()) {
                other = url
            }
        }
        return manual.ifEmpty { asr }.ifEmpty { other }
    }

    // ── 검색어 매칭 ───────────────────────────────────────────
    private fun buildQuery(track: Track): String {
        val artist = track.artist.trim()
        val title = track.title.trim()
        return if (artist.isEmpty()) title else "$artist $title"
    }

    private fun pickBest(results: List<Track>, target: Track): Track? {
        if (results.isEmpty()) return null
        var best: Track? = null
        var bestScore = Int.MIN_VALUE
        for (r in results) {
            val s = score(target, r)
            if (s > bestScore) {
                bestScore = s
                best = r
            }
        }
        return if (bestScore >= 1) best else results.firstOrNull()
    }

    private fun score(target: Track, cand: Track): Int {
        val nt = Text.norm(target.title)
        val nc = Text.norm(cand.title)
        var s = 0
        if (nt.isNotEmpty() && nc.isNotEmpty()) {
            if (nc.contains(nt) || nt.contains(nc)) s += 4
            else if (bigrams(nt, nc) >= 2) s += 2
        }
        val na = Text.norm(target.artist)
        if (na.isNotEmpty() && nc.contains(na)) s += 2

        val low = cand.title.lowercase()
        for (w in BAD_WORDS) if (low.contains(w)) s -= 3
        for (w in GOOD_WORDS) if (low.contains(w)) s += 1
        return s
    }

    private fun bigrams(a: String, b: String): Int {
        if (a.length < 2 || b.length < 2) return 0
        val set = HashSet<String>()
        for (i in 0 until a.length - 1) set.add(a.substring(i, i + 2))
        var count = 0
        for (i in 0 until b.length - 1) if (set.contains(b.substring(i, i + 2))) count++
        return count
    }

    private fun clientContext(client: ClientSpec): JSONObject {
        val c = JSONObject()
            .put("clientName", client.name)
            .put("clientVersion", client.version)
            .put("hl", "ko")
            .put("gl", "KR")
        client.extra.forEach { (k, v) -> c.put(k, v) }
        return JSONObject().put("client", c)
    }

    private fun ytHeaders(ua: String = Net.UA_YT): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "X-Goog-Api-Format-Version" to "2",
        "Accept" to "*/*",
    )

    private data class ClientSpec(
        val name: String,
        val version: String,
        val extra: Map<String, Any> = emptyMap(),
        val ua: String = Net.UA_YT,
        val embed: Boolean = false,
    )

    private companion object {
        const val API = "https://www.youtube.com/youtubei/v1"

        val CLIENTS = listOf(
            ClientSpec("ANDROID", "20.10.38", mapOf("androidSdkVersion" to 30)),
            ClientSpec("ANDROID_VR", "1.65.10", mapOf("androidSdkVersion" to 32)),
            ClientSpec("IOS", "20.10.4", mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2")),
            ClientSpec("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", embed = true),
            ClientSpec("WEB", "2.20250101.00.00"),
        )

        val QUALITY_ITAGS = mapOf(
            "high" to listOf(140, 251, 141, 172),
            "medium" to listOf(139, 249, 140, 251),
            "low" to listOf(600, 599, 139, 249),
        )

        val BAD_WORDS = listOf(
            "cover", "커버", "카페", "instrumental", "반복", "1시간", "live", "라이브",
            "remix", "nightcore", "practice", "연습", "8d", "concert", "공연",
        )
        val GOOD_WORDS = listOf("official", "mv", "m/v", "audio", "키WI", "정오fficial")
    }
}
