package org.music.infiniteplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.music.infiniteplayer.core.Net
import org.music.infiniteplayer.core.Text

/**
 * 차트 파서.
 *  - 멜론 : TOP100 (HTML 테이블 파싱)
 *  - 지니 : 장르별 차트 (HTML 테이블 파싱)
 *  - 유튜브 : 장르별 검색 결과
 */
class ChartRepository(private val youtube: YoutubeRepository) {

    suspend fun load(source: Source): List<Track> = withContext(Dispatchers.IO) {
        when (source.kind) {
            SourceKind.MELON -> parseMelon(
                Net.get(source.url, mapOf("User-Agent" to Net.UA_DESKTOP))
            ).take(source.limit)

            SourceKind.GENIE -> parseGenie(
                Net.get(source.url, mapOf("User-Agent" to Net.UA_DESKTOP))
            ).take(source.limit)

            SourceKind.YOUTUBE -> youtube.search(source.query, source.limit)
        }
    }

    /** 통합 검색 : 음악 영상만 골라서 검색 */
    suspend fun searchMusic(query: String, limit: Int = 40): List<Track> =
        youtube.searchMusic(query, limit)

    // ── 멜론 ──────────────────────────────────────────────────
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
        if (tracks.isEmpty()) throw IllegalStateException("멜론 차트 파싱 실패 (사이트 구조 변경)")
        return tracks
    }

    // ── 지니 ──────────────────────────────────────────────────
    private fun parseGenie(html: String): List<Track> {
        val tracks = ArrayList<Track>(60)
        G_TR_RE.findAll(html).forEach { m ->
            val body = m.groupValues[1]
            val titleM = G_TITLE_RE.find(body) ?: return@forEach
            val title = Text.clean(titleM.groupValues[1])
            if (title.isEmpty()) return@forEach
            val artist = G_ARTIST_RE.find(body)?.let { Text.clean(it.groupValues[1]) }.orEmpty()
            val rank = G_RANK_RE.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: (tracks.size + 1)
            tracks.add(
                Track(
                    id = "genie_" + Text.norm(title) + "_" + Text.norm(artist),
                    title = title,
                    artist = artist,
                    rank = rank,
                    source = "genie",
                )
            )
        }
        if (tracks.isEmpty()) throw IllegalStateException("지니 차트 파싱 실패 (사이트 구조 변경)")
        return tracks
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

        val G_TR_RE = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
        val G_TITLE_RE = Regex("class=\"title[^\"]*\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val G_ARTIST_RE = Regex("class=\"artist[^\"]*\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val G_RANK_RE = Regex("class=\"rank[^\"]*\"[^>]*>.*?(\\d+)<", RegexOption.DOT_MATCHES_ALL)
    }
}
