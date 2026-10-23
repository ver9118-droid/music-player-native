package org.music.infiniteplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.music.infiniteplayer.core.Net
import org.music.infiniteplayer.core.Text

/**
 * 차트/음악 데이터 로더.
 *
 *  - 멜론 : TOP100 · 장르별 차트 · 최신 뮤직비디오 (HTML 파싱)
 *  - 유튜브 : 장르 검색 (모음 영상)
 *
 * 지니 차트는 v3.5.0 에서 삭제했다.
 */
class ChartRepository(private val youtube: YoutubeRepository) {

    suspend fun load(source: Source): List<Track> = withContext(Dispatchers.IO) {
        when (source.kind) {
            SourceKind.MELON -> parseMelon(
                Net.get(source.url, mapOf("User-Agent" to Net.UA_DESKTOP))
            ).take(source.limit)

            SourceKind.MELON_MV -> parseMelonMv(
                Net.get(source.url, mapOf("User-Agent" to Net.UA_DESKTOP))
            ).take(source.limit)

            SourceKind.YOUTUBE -> youtube.search(source.query, source.limit)
        }
    }

    /** 통합 검색 : 노래 관련 영상만 골라서 검색 */
    suspend fun searchMusic(query: String, limit: Int = 40): List<Track> =
        youtube.searchMusic(query, limit)

    // ── 멜론 차트 ──────────────────────────────────────────────
    private fun parseMelon(html: String): List<Track> {
        val tracks = ArrayList<Track>(60)
        TR_RE.findAll(html).forEach { m ->
            val attrs = m.groupValues[1]
            val body = m.groupValues[2]
            if (!body.contains("ellipsis")) return@forEach

            val blocks = LinkedHashMap<String, String>()
            BLOCK_RE.findAll(body).forEach { b ->
                blocks[b.groupValues[1]] = b.groupValues[2]
            }

            var title = firstLink(blocks["rank01"].orEmpty())
            var artist = allLinks(blocks["rank02"].orEmpty())
            var album = firstLink(blocks["rank03"].orEmpty())
            if (title.isEmpty()) {
                title = firstLink(blocks["rank03"].orEmpty())
                album = ""
                artist = artist.ifEmpty { allLinks(blocks["rank01"].orEmpty()) }
            }
            if (title.isEmpty()) return@forEach

            val songNo = SONG_NO_RE.find(attrs + body)?.groupValues?.get(1).orEmpty()
            val rank = RANK_RE.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: (tracks.size + 1)
            val thumb = THUMB_RE.find(body)?.groupValues?.get(1).orEmpty()

            tracks.add(
                Track(
                    id = "melon_" + songNo,
                    title = title,
                    artist = artist,
                    album = album,
                    rank = rank,
                    source = "melon",
                    pageUrl = if (songNo.isNotEmpty())
                        "https://www.melon.com/song/detail.htm?songId=$songNo" else "",
                    thumbUrl = thumb,
                )
            )
        }
        if (tracks.isEmpty()) throw IllegalStateException("멜론 차트 파싱 실패 (페이지 구조 변경?)")
        return tracks
    }

    // ── 멜론 최신 뮤직비디오 ───────────────────────────────────
    private fun parseMelonMv(html: String): List<Track> {
        val tracks = ArrayList<Track>(40)
        MV_ITEM_RE.findAll(html).forEach { m ->
            val body = m.groupValues[1]
            val mvId = MV_ID_RE.find(body)?.groupValues?.get(1) ?: return@forEach
            val rawTitle = MV_TITLE_ATTR_RE.find(body)?.groupValues?.get(1)
                ?: MV_TITLE_TEXT_RE.find(body)?.groupValues?.get(1)
                ?: return@forEach

            var title = Text.clean(rawTitle)
            title = title.substringBeforeLast(" - 페이지 이동").trim()
            title = title.removePrefix("[MV]").trim()
            if (title.isEmpty()) return@forEach
            // 예고편(Teaser)은 30초 내외라 곡으로 재생하기엔 부적합하다
            if (title.contains("teaser", true) || title.contains("티저", true)) return@forEach

            val artist = MV_ARTIST_RE.find(body)?.groupValues?.get(1)
                ?.let { Text.clean(it) }
                ?.substringBeforeLast(" - 페이지 이동")
                ?.trim()
                .orEmpty()
            val thumb = MV_THUMB_RE.find(body)?.groupValues?.get(1).orEmpty()
            val duration = parseTime(MV_TIME_RE.find(body)?.groupValues?.get(1).orEmpty())

            tracks.add(
                Track(
                    id = "melonmv_$mvId",
                    title = title,
                    artist = artist,
                    rank = tracks.size + 1,
                    source = "melonmv",
                    thumbUrl = thumb,
                    durationMs = duration,
                )
            )
        }
        if (tracks.isEmpty()) {
            throw IllegalStateException("멜론 뮤직비디오 파싱 실패 (페이지 구조 변경?)")
        }
        return tracks
    }

    private fun parseTime(text: String): Long {
        if (text.isBlank()) return 0L
        var sec = 0L
        for (p in text.split(":")) {
            val v = p.trim().toLongOrNull() ?: return 0L
            sec = sec * 60 + v
        }
        return sec * 1000L
    }

    private fun firstLink(fragment: String): String {
        val m = A_RE.find(fragment) ?: return ""
        return Text.clean(m.groupValues[1])
    }

    private fun allLinks(fragment: String): String {
        val out = ArrayList<String>()
        A_RE.findAll(fragment).forEach { m ->
            val name = Text.clean(m.groupValues[1])
            if (name.isNotEmpty() && !out.contains(name)) out.add(name)
        }
        return out.joinToString(", ")
    }

    private companion object {
        val TR_RE = Regex("<tr([^>]*)>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
        val RANK_RE = Regex("<span class=\"rank[^\"]*\">\\s*(\\d+)</span>")
        val BLOCK_RE = Regex("class=\"ellipsis (rank\\d+)\"[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
        val A_RE = Regex("<a[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val SONG_NO_RE = Regex("data-song-no=\"(\\d+)\"")
        val THUMB_RE = Regex("<img[^>]+src=\"(https://cdnimg\\.melon\\.co\\.kr[^\"]+)\"")

        // 최신 뮤직비디오 페이지 (/new/mv/index.htm)
        val MV_ITEM_RE = Regex("<li>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
        val MV_ID_RE = Regex("goMvDetail\\(\\s*'[^']*'\\s*,\\s*'(\\d+)'")
        val MV_TITLE_ATTR_RE = Regex("class=\"ellipsis album_name\"[^>]*title=\"([^\"]*)\"")
        val MV_TITLE_TEXT_RE = Regex("class=\"ellipsis album_name\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val MV_ARTIST_RE = Regex("class=\"artist_name[^\"]*\"[^>]*title=\"([^\"]*)\"")
        val MV_THUMB_RE = Regex("<img[^>]+src=\"(https://cdnimg[^\"]+)\"")
        val MV_TIME_RE = Regex("class=\"time\">(\\d+:\\d+)</span>")
    }
}
