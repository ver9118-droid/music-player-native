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
            put("context", clientContext(SEARCH_CLIENT))
            put("query", query)
            put("params", "EgWKAQIIAWoKEAkQBRAK")
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val json = Net.postJson("$API/search", body.toString(), searchHeaders())
        parseSearch(json, limit)
    }

    /**
     * 음악만 골라서 검색한다. (노래 관련 영상만)
     *  - 먹방/게임/브이로그 같은 일반 영상은 제외
     *  - 오피ICIAL/뮤직비디오/공연/노래방 같은 음악 영상은 포함
     */
    suspend fun searchMusic(query: String, limit: Int = 40): List<Track> = withContext(Dispatchers.IO) {
        val all = search(query, limit + 30)
        all.filter { isMusic(it) }.take(limit)
    }

    private fun isMusic(t: Track): Boolean {
        val low = t.title.lowercase()
        for (w in NON_MUSIC_WORDS) if (low.contains(w)) return false
        if (MV_RE.containsMatchIn(t.title)) return true
        for (w in MUSIC_WORDS) if (low.contains(w)) return true
        // 길이 기준 : 1분 ~ 15분이면 대부분 음악
        val d = t.durationMs
        return d in 60_000..(15 * 60_000)
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
        // 봇 판정 회피용 방문자 ID (없으면 일부 클라이언트가 LOGIN_REQUIRED 를 준다)
        val visitor = VisitorData.get()
        for (client in CLIENTS) {
            try {
                val ctx = clientContext(client)
                if (visitor.isNotEmpty()) ctx.optJSONObject("client")?.put("visitorData", visitor)
                if (client.embed) {
                    ctx.put("thirdParty", JSONObject().put("embedUrl", "https://www.youtube.com/"))
                }
                val body = JSONObject().apply {
                    put("context", ctx)
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }
                val json = Net.postJson(
                    "$API/player?prettyPrint=false",
                    body.toString(),
                    ytHeaders(client, visitor),
                )
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

    /** 검색용 헤더 (기존과 동일하게 동작) */
    private fun searchHeaders(): Map<String, String> = mapOf(
        "User-Agent" to Net.UA_YT,
        "X-Goog-Api-Format-Version" to "2",
        "Accept" to "*/*",
    )

    private fun ytHeaders(client: ClientSpec, visitor: String = ""): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = client.ua
        h["X-Goog-Api-Format-Version"] = "2"
        h["X-YouTube-Client-Name"] = client.id.toString()
        h["X-YouTube-Client-Version"] = client.version
        h["Origin"] = "https://www.youtube.com"
        h["Accept"] = "*/*"
        if (visitor.isNotEmpty()) h["X-Goog-Visitor-Id"] = visitor
        return h
    }

    private data class ClientSpec(
        val name: String,
        val version: String,
        val id: Int,
        val extra: Map<String, Any> = emptyMap(),
        val ua: String = Net.UA_YT,
        val embed: Boolean = false,
    )

    private companion object {
        const val API = "https://www.youtube.com/youtubei/v1"

        /** 검색은 ANDROID 클라이언트 응답형식( videoRenderer )을 쓴다 */
        val SEARCH_CLIENT = ClientSpec("ANDROID", "20.10.38", 3, mapOf("androidSdkVersion" to 30))

        /** visionOS 클라이언트용 User-Agent (전체 range 를 받을 수 있는 URL 을 받는다) */
        const val VISION_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
                    "(KHTML, like Gecko) Version/26.0 Safari/605.1.15"

        val CLIENTS = listOf(
            // visionOS 클라이언트 : visitorData 를 같이 보내면
            // 전체 바이트 범위를 정상적으로 받을 수 있는 URL 이 나온다.
            // (다른 클라이언트는 1MB 창으로 제한되어 재생이 안 됨)
            ClientSpec(
                "VISIONOS", "1.02", 101,
                mapOf(
                    "deviceMake" to "Apple",
                    "deviceModel" to "RealityDevice17,1",
                    "userAgent" to VISION_UA,
                    "osName" to "visionOS",
                    "osVersion" to "26.5.23O471",
                ),
                ua = VISION_UA,
            ),
            ClientSpec("ANDROID", "20.10.38", 3, mapOf("androidSdkVersion" to 30)),
            ClientSpec("ANDROID_VR", "1.65.10", 28, mapOf("androidSdkVersion" to 32)),
            ClientSpec("IOS", "20.10.4", 5, mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2")),
            ClientSpec("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", 85, embed = true),
            ClientSpec("WEB", "2.20250101.00.00", 1),
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

        /** 일반 영상(먹방/게임/브이로그 등) 제외용 */
        val NON_MUSIC_WORDS = listOf(
            "먹방", "먹기", "mukbang", "asmr", "게임", "gameplay", "gaming", "공략",
            "vlog", "브이로그", "개밥", "요리", "레시피", "뉴스", "news", "티저",
            "예고편", "trailer", "리뷰", "review", "unboxing", "개봉", "트리포",
            "tutorial", "강의", "경제", "주식", "코인", "루비", "betting", "베팅",
            "대탈출", "드라마", "drama", "영화", "movie", "공포", "funny", "comedy",
            "코미디", "리액션", "reaction", "walk", "macro", "drawing", "드로잉",
            "일상", "루틴", "하루", "브이브이", "shorts", " shorts",
        )

        /** 음악 관련 키워드 */
        val MUSIC_WORDS = listOf(
            "official", "audio", "music", "song", "sing", "singalong", "m/v",
            "키wi", "노래", "곡", "가사", "concert", "공연", "콘서트", "컴서트",
            "festival", "live", "라이브", "ost", "오디션", "노래방", "karaoke",
            "instrumental", "acoustic", "piano", "피아노", "guitar", "기타",
            "remaster", "performance", "오케스트라", "뮤직", "music video",
            "collab", "collabo", "ver.", "take", "cover", "커버",
        )

        /** "MV" 를 단어로만 recognition (movie 등 오검출 방지) */
        val MV_RE = Regex("(^|[\\s\\[(])mv([\\s\\])\\-]|$)", RegexOption.IGNORE_CASE)
    }
}
