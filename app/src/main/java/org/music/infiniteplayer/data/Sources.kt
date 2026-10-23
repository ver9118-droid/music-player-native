package org.music.infiniteplayer.data

enum class SourceKind { MELON, GENIE, YOUTUBE }

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
 * 차트/음원 소스 정의 (FLO 스타일 UI).
 *
 * FLO 사이트는 로그인·결제가 필요해서 곡 목록을 직접 가져올 수 없다.
 * 그래서 화면 구성만 FLO 스타일로 하고, 실제 곡 목록은
 *   - 멜론 실시간 차트 / 멜론 장르 차트
 *   - 지니 장르 차트
 * 에서 가져온다. (곡 제목·가수만 받고, 재생은 유튜브에서)
 *
 * v3.1.0 처럼 유튜브 검색 결과를 그대로 목록으로 쓰면
 * 2~10시간짜리 "모음/플레이리스트" 영상이 올라와서
 *   - 한 곡이 2시간이 되고
 *   - 그 영상의 스트림이 403 을 주는 경우가 많아 재생이 실패한다.
 * 그래서 v3.1.1 부터는 "곡 단위" 목록을 쓴다.
 */
object Sources {

    const val GROUP_FLO_CHART = "FLO 차트"
    const val GROUP_FLO_GENRE = "FLO 장르"
    const val GROUP_FLO_MOOD = "FLO 상황"
    const val GROUP_MELON = "멜론 차트"
    const val GROUP_GENIE = "지니 장르차트"
    const val GROUP_KOREA = "한국대중음악"

    // ── 연령 필터 ───────────────────────────────────────────────
    const val AGE_ALL = "all"
    const val AGE_1020 = "1020"
    const val AGE_3040 = "3040"
    const val AGE_5060 = "5060"

    fun ageLabel(age: String): String = when (age) {
        AGE_1020 -> "1020대"
        AGE_3040 -> "3040대"
        AGE_5060 -> "5060대"
        else -> "전체 연령"
    }

    private const val MELON_TOP = "https://www.melon.com/chart/index.htm"
    private const val MELON_GENRE = "https://www.melon.com/genre/song_list.htm?gnrCode=%s"
    private const val GENIE_URL = "https://www.genie.co.kr/chart/genre?ditc=D&genrecode=%s&pg=%d"

    private fun melon(code: String): String = MELON_GENRE.format(code)
    private fun genie(code: String, page: Int = 1): String = GENIE_URL.format(code, page)

    // ── 플로 차트 ───────────────────────────────────────────────
    val floChart: List<Source> = listOf(
        Source("flo_chart", "플로 차트", GROUP_FLO_CHART, SourceKind.MELON,
            url = MELON_TOP, limit = 50),
        Source("flo_top40", "Top 40", GROUP_FLO_CHART, SourceKind.GENIE,
            url = genie("M0100"), limit = 40),
        Source("flo_rising", "급상승", GROUP_FLO_CHART, SourceKind.GENIE,
            url = genie("M0900"), limit = 40),
    )

    /**
     * 연령 필터.
     *
     * 실제 연령별 차트 데이터를 구할 수 없으므로, 차트 탭에서는
     * 연령대에 어울리는 차트로 바꿔서 보여준다.
     *  - 전체 : 지금 고른 차트
     *  - 1020 : 지니 댄스 차트 (아이돌·댄스)
     *  - 3040 : 지니 가요 차트 (발라드·대중가요)
     *  - 5060 : 멜론 트로트 차트
     */
    fun applyAge(source: Source, age: String): Source {
        if (age == AGE_ALL) return source
        if (source.kind == SourceKind.YOUTUBE) {
            // 유튜브 검색 소스는 검색어에 연령 키워드를 붙인다
            val suffix = when (age) {
                AGE_1020 -> "1020대 인기"
                AGE_3040 -> "3040대 인기"
                AGE_5060 -> "5060대 인기"
                else -> return source
            }
            return source.copy(query = "${source.query} $suffix")
        }
        if (!isChart(source.key)) return source
        return when (age) {
            AGE_1020 -> source.copy(
                label = "${source.label} · 1020대",
                kind = SourceKind.GENIE,
                url = genie("M0900"),
            )
            AGE_3040 -> source.copy(
                label = "${source.label} · 3040대",
                kind = SourceKind.GENIE,
                url = genie("M0100"),
            )
            AGE_5060 -> source.copy(
                label = "${source.label} · 5060대",
                kind = SourceKind.MELON,
                url = melon("GN0700"),
            )
            else -> source
        }
    }

    fun isChart(key: String): Boolean = floChart.any { it.key == key }

    // ── 플로 장르 (FLO browse 장르 카드) ────────────────────────
    // 멜론 장르 코드(GNxxxx)로 실제 곡 목록을 가져온다.
    val floGenres: List<Source> = listOf(
        Source("flo_g_ballad", "국내 발라드", GROUP_FLO_GENRE, SourceKind.MELON,
            url = melon("GN0100")),
        Source("flo_g_dance", "국내 댄스/일렉", GROUP_FLO_GENRE, SourceKind.MELON,
            url = melon("GN0200")),
        Source("flo_g_hiphop", "국내 힙합", GROUP_FLO_GENRE, SourceKind.MELON,
            url = melon("GN0300")),
        Source("flo_g_rnb", "국내 알앤비", GROUP_FLO_GENRE, SourceKind.MELON,
            url = melon("GN0400")),
        Source("flo_g_indie", "국내 인디", GROUP_FLO_GENRE, SourceKind.MELON,
            url = melon("GN0500")),
        Source("flo_g_rock", "국내 록/메탈", GROUP_FLO_GENRE, SourceKind.MELON,
            url = melon("GN0600")),
        Source("flo_g_trot", "트로트", GROUP_FLO_GENRE, SourceKind.MELON,
            url = melon("GN0700")),
        Source("flo_g_folk", "포크/블루스", GROUP_FLO_GENRE, SourceKind.MELON,
            url = melon("GN0800")),
        Source("flo_g_ost", "OST/BGM", GROUP_FLO_GENRE, SourceKind.MELON,
            url = melon("GN0900")),
        Source("flo_g_classic", "클래식", GROUP_FLO_GENRE, SourceKind.MELON,
            url = melon("GN1000")),
        Source("flo_g_jazz", "재즈", GROUP_FLO_GENRE, SourceKind.MELON,
            url = melon("GN1100")),
        Source("flo_g_newage", "뉴에이지", GROUP_FLO_GENRE, SourceKind.MELON,
            url = melon("GN1200")),
        Source("flo_g_ccm", "CCM", GROUP_FLO_GENRE, SourceKind.MELON,
            url = melon("GN1300")),
        Source("flo_g_gugak", "국악", GROUP_FLO_GENRE, SourceKind.MELON,
            url = melon("GN1400")),
        Source("flo_g_kids", "키즈/태교", GROUP_FLO_GENRE, SourceKind.MELON,
            url = melon("GN1500")),
        Source("flo_g_world", "월드뮤직", GROUP_FLO_GENRE, SourceKind.MELON,
            url = melon("GN1600")),
        Source("flo_g_jpop", "J-POP", GROUP_FLO_GENRE, SourceKind.MELON,
            url = melon("GN1700")),
        Source("flo_g_pop", "해외 팝", GROUP_FLO_GENRE, SourceKind.MELON,
            url = melon("GN1800")),
        Source("flo_g_electronic", "해외 일렉트로닉", GROUP_FLO_GENRE, SourceKind.MELON,
            url = melon("GN1900")),
    )

    // ── 플로 상황 (FLO browse 상황 카드) ────────────────────────
    // 상황별로 어울리는 장르 차트를 연결한다. (같은 장르라도 페이지를 달리해 구성)
    val floMoods: List<Source> = listOf(
        Source("flo_s_top40", "Top 40", GROUP_FLO_MOOD, SourceKind.GENIE,
            url = genie("M0100"), limit = 40),
        Source("flo_s_autumn", "가을", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN0100")),
        Source("flo_s_drive", "드라이브", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN1800")),
        Source("flo_s_remake", "추억/리메이크", GROUP_FLO_MOOD, SourceKind.GENIE,
            url = genie("M0100", 2)),
        Source("flo_s_love", "사랑", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN0100")),
        Source("flo_s_breakup", "이별", GROUP_FLO_MOOD, SourceKind.GENIE,
            url = genie("M0100", 3)),
        Source("flo_s_weather", "날씨", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN0400")),
        Source("flo_s_cafe", "카페", GROUP_FLO_MOOD, SourceKind.GENIE,
            url = genie("M0500")),
        Source("flo_s_study", "공부/독서", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN1200")),
        Source("flo_s_workout", "운동/헬스", GROUP_FLO_MOOD, SourceKind.GENIE,
            url = genie("M0900")),
        Source("flo_s_walk", "산책/여행", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN0800")),
        Source("flo_s_pet", "반려동물", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN0500")),
        Source("flo_s_morning", "아침", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN1800")),
        Source("flo_s_night", "밤/새벽", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN0400")),
        Source("flo_s_rest", "휴식/힐링", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN1200")),
        Source("flo_s_club", "클럽/파티", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN1900")),
        Source("flo_s_karaoke", "노래방", GROUP_FLO_MOOD, SourceKind.GENIE,
            url = genie("M0100", 2)),
        Source("flo_s_commute", "출/퇴근", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN0200")),
        Source("flo_s_work", "입/작업", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN1100")),
        Source("flo_s_solo", "혼술/혼밥", GROUP_FLO_MOOD, SourceKind.GENIE,
            url = genie("M0500")),
        Source("flo_s_voice", "목소리/음색", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN0100")),
        Source("flo_s_room", "내 방", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN0500")),
        Source("flo_s_pub", "펍/바", GROUP_FLO_MOOD, SourceKind.GENIE,
            url = genie("M0500")),
        Source("flo_s_lounge", "호텔/라운지", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN1100")),
        Source("flo_s_live", "공연/아티스트", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN0600")),
        Source("flo_s_sleep", "잠자기", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN1200")),
        Source("flo_s_mbti", "MBTI", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN1800")),
        Source("flo_s_spring", "봄", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN0500")),
        Source("flo_s_summer", "여름", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN0200")),
        Source("flo_s_winter", "겨울", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN0100")),
        Source("flo_s_tv", "TV/라디오", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN0900")),
        Source("flo_s_era", "시대별", GROUP_FLO_MOOD, SourceKind.GENIE,
            url = genie("M0100", 3)),
        Source("flo_s_go", "Music-to-Go", GROUP_FLO_MOOD, SourceKind.MELON,
            url = melon("GN1800")),
        Source("flo_s_christmas", "크리스마스", GROUP_FLO_MOOD, SourceKind.GENIE,
            url = genie("M0200")),
    )

    // ── 기존 소스 (호환용) ─────────────────────────────────────
    private val legacy: List<Source> = listOf(
        Source("top100", "TOP100", GROUP_MELON, SourceKind.MELON, url = MELON_TOP),
        Source("g_genie_pop", "가요", GROUP_GENIE, SourceKind.GENIE, url = genie("M0100")),
        Source("g_genie_dance", "댄스", GROUP_GENIE, SourceKind.GENIE, url = genie("M0900")),
        Source("g_genie_rap", "랩/힙합", GROUP_GENIE, SourceKind.GENIE, url = genie("M1000")),
        Source("g_genie_rock", "록/메탈", GROUP_GENIE, SourceKind.GENIE, url = genie("M0700")),
        Source("g_genie_ccm", "CCM", GROUP_GENIE, SourceKind.GENIE, url = genie("M0800")),
        Source("g_genie_pop2", "POP", GROUP_GENIE, SourceKind.GENIE, url = genie("M0200")),
        Source("g_genie_ost", "OST", GROUP_GENIE, SourceKind.GENIE, url = genie("M0300")),
        Source("g_genie_jpop", "JPOP", GROUP_GENIE, SourceKind.GENIE, url = genie("M0400")),
        Source("g_genie_jazz", "JAZZ", GROUP_GENIE, SourceKind.GENIE, url = genie("M0500")),
        Source("g_genie_classic", "CLASSIC", GROUP_GENIE, SourceKind.GENIE, url = genie("M0600")),
        Source("g_band", "밴드", GROUP_KOREA, SourceKind.YOUTUBE,
            query = "한국대중음악 밴드 히트곡 명곡 모음"),
        Source("g_dance", "댄스", GROUP_KOREA, SourceKind.YOUTUBE,
            query = "K-POP 댄스 신나는 명곡 모음 플레이리스트"),
        Source("g_rap", "랩/힙합", GROUP_KOREA, SourceKind.YOUTUBE,
            query = "한국 랩/힙합 명곡 모음"),
        Source("g_rnb", "R&B/Soul", GROUP_KOREA, SourceKind.YOUTUBE,
            query = "R&B 소울 한국 힙트 명곡 모음"),
        Source("g_indie", "인디음악", GROUP_KOREA, SourceKind.YOUTUBE,
            query = "한국 인디음악 명곡 모음"),
        Source("g_rock", "록/메탈", GROUP_KOREA, SourceKind.YOUTUBE,
            query = "한국 록 메탈 명곡 모음"),
        Source("g_trot", "트로트", GROUP_KOREA, SourceKind.YOUTUBE,
            query = "트로트 명곡 모음"),
        Source("g_folk", "포크/블루스", GROUP_KOREA, SourceKind.YOUTUBE,
            query = "포크 블루스 명곡 모음"),
    )

    val all: List<Source> = floChart + floGenres + floMoods + legacy

    fun byKey(key: String): Source? = all.firstOrNull { it.key == key }

    /** FLO 소스인지 (예전 멜론/지니 설정을 플로 차트로 바꿀 때 사용) */
    fun isFlo(key: String): Boolean = key.startsWith("flo_")

    val default: Source get() = floChart.first()
}
