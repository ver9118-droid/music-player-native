package org.music.infiniteplayer.data

enum class SourceKind { MELON, MELON_MV, YOUTUBE }

data class Source(
    val key: String,
    val label: String,
    val group: String,
    val kind: SourceKind,
    val url: String = "",
    val query: String = "",
    val limit: Int = 50,
)

/**
 * 차트/음원 소스 정의 (데스크톱 v1.6.1 과 같은 구성).
 *
 * v3.5.0 부터는 지니 차트를 모두 걷어내고 **멜론 차트 구성 그대로** 사용한다.
 *   - 멜론 TOP100
 *   - 멜론 장르별 차트 (발라드/댄스/힙합/R&B/인디/록/트로트/OST/클래식/재즈 …)
 *   - 멜론 최신 뮤직비디오 (켜고 끌 수 있음)
 *   - 유튜브 한국대중음악 (모음)
 *
 * 곡 제목·가수만 받아 오고, 실제 재생은 유튜브에서 해당 곡을 찾아 스트리밍한다.
 */
object Sources {

    const val GROUP_MELON = "멜론 차트"
    const val GROUP_MV = "멜론 최신 뮤직비디오"
    const val GROUP_KOREA = "한국대중음악"

    private const val MELON_TOP = "https://www.melon.com/chart/index.htm"
    private const val MELON_GENRE = "https://www.melon.com/genre/song_list.htm?gnrCode=%s"
    private const val MELON_MV = "https://www.melon.com/new/mv/index.htm"

    private fun melon(code: String): String = MELON_GENRE.format(code)

    // ── 멜론 차트 (TOP100 + 장르) ──────────────────────────────
    val melonChart: List<Source> = listOf(
        Source("top100", "TOP100", GROUP_MELON, SourceKind.MELON,
            url = MELON_TOP, limit = 100),
        Source("m_ballad", "발라드", GROUP_MELON, SourceKind.MELON, url = melon("GN0100")),
        Source("m_dance", "댄스", GROUP_MELON, SourceKind.MELON, url = melon("GN0200")),
        Source("m_hiphop", "랩/힙합", GROUP_MELON, SourceKind.MELON, url = melon("GN0300")),
        Source("m_rnb", "R&B/Soul", GROUP_MELON, SourceKind.MELON, url = melon("GN0400")),
        Source("m_indie", "인디", GROUP_MELON, SourceKind.MELON, url = melon("GN0500")),
        Source("m_rock", "록/메탈", GROUP_MELON, SourceKind.MELON, url = melon("GN0600")),
        Source("m_trot", "트로트", GROUP_MELON, SourceKind.MELON, url = melon("GN0700")),
        Source("m_folk", "포크/블루스", GROUP_MELON, SourceKind.MELON, url = melon("GN0800")),
        Source("m_ost", "OST", GROUP_MELON, SourceKind.MELON, url = melon("GN0900")),
        Source("m_classic", "클래식", GROUP_MELON, SourceKind.MELON, url = melon("GN1000")),
        Source("m_jazz", "재즈", GROUP_MELON, SourceKind.MELON, url = melon("GN1100")),
        Source("m_newage", "뉴에이지", GROUP_MELON, SourceKind.MELON, url = melon("GN1200")),
        Source("m_ccm", "CCM", GROUP_MELON, SourceKind.MELON, url = melon("GN1300")),
        Source("m_gugak", "국악", GROUP_MELON, SourceKind.MELON, url = melon("GN1400")),
        Source("m_kids", "키즈/태교", GROUP_MELON, SourceKind.MELON, url = melon("GN1500")),
        Source("m_world", "월드뮤직", GROUP_MELON, SourceKind.MELON, url = melon("GN1600")),
        Source("m_jpop", "J-POP", GROUP_MELON, SourceKind.MELON, url = melon("GN1700")),
        Source("m_pop", "POP", GROUP_MELON, SourceKind.MELON, url = melon("GN1800")),
        Source("m_electronic", "일렉트로닉", GROUP_MELON, SourceKind.MELON, url = melon("GN1900")),
    )

    /** 멜론 최신 뮤직비디오 (설정에서 끄면 목록에서 사라진다) */
    val melonMv: List<Source> = listOf(
        Source("melon_mv", "최신 뮤직비디오", GROUP_MV, SourceKind.MELON_MV,
            url = MELON_MV, limit = 30),
    )

    // ── 유튜브 (한국대중음악 모음) ─────────────────────────────
    private val youtube: List<Source> = listOf(
        Source("g_band", "밴드", GROUP_KOREA, SourceKind.YOUTUBE,
            query = "한국대중음악 밴드 히트곡 명곡 모음"),
        Source("g_dance", "댄스", GROUP_KOREA, SourceKind.YOUTUBE,
            query = "K-POP 댄스 신나는 명곡 모음 플레이리스트"),
        Source("g_rap", "랩/힙합", GROUP_KOREA, SourceKind.YOUTUBE,
            query = "한국 랩/힙합 명곡 모음"),
        Source("g_rnb", "R&B/Soul", GROUP_KOREA, SourceKind.YOUTUBE,
            query = "R&B 소울 한국 히트 명곡 모음"),
        Source("g_indie", "인디음악", GROUP_KOREA, SourceKind.YOUTUBE,
            query = "한국 인디음악 명곡 모음"),
        Source("g_rock", "록/메탈", GROUP_KOREA, SourceKind.YOUTUBE,
            query = "한국 록 메탈 명곡 모음"),
        Source("g_trot", "트로트", GROUP_KOREA, SourceKind.YOUTUBE,
            query = "트로트 명곡 모음"),
        Source("g_folk", "포크/블루스", GROUP_KOREA, SourceKind.YOUTUBE,
            query = "포크 블루스 명곡 모음"),
    )

    /**
     * 소스 전체 목록.
     *
     * @param includeMv 최신 뮤직비디오 사용 여부 (설정 스위치)
     */
    fun list(includeMv: Boolean = true): List<Source> =
        if (includeMv) melonChart + melonMv + youtube else melonChart + youtube

    fun byKey(key: String, includeMv: Boolean = true): Source? =
        list(includeMv).firstOrNull { it.key == key }

    val default: Source get() = melonChart.first()
}
