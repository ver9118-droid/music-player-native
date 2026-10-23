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
 * 차트/음원 소스 정의.
 *
 * v3.1.0 부터는 FLO 스타일을 기준으로 한다.
 *  - 플로 차트 / 장르 / 상황 3개 묶음
 *  - 실제 음원은 유튜브 검색으로 가져온다 (FLO 사이트는 로그인·결제가 필요)
 *  - 기존 멜론/지니 소스는 예전 설정(last_source) 호환을 위해 남겨 둔다
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

    /** 연령 필터를 검색어에 반영한다 (유튜브 소스만). */
    fun applyAge(source: Source, age: String): Source {
        if (age == AGE_ALL || source.kind != SourceKind.YOUTUBE) return source
        val suffix = when (age) {
            AGE_1020 -> "1020대 인기"
            AGE_3040 -> "3040대 인기"
            AGE_5060 -> "5060대 인기"
            else -> return source
        }
        return source.copy(query = "${source.query} $suffix")
    }

    private const val MELON_TOP = "https://www.melon.com/chart/index.htm"
    private const val GENIE_URL = "https://www.genie.co.kr/chart/genre?ditc=D&genrecode=%s&pg=1"

    // ── 플로 차트 ───────────────────────────────────────────────
    val floChart: List<Source> = listOf(
        Source("flo_chart", "플로 차트", GROUP_FLO_CHART, SourceKind.YOUTUBE,
            query = "FLO 플로 차트 인기곡 TOP100", limit = 50),
        Source("flo_top40", "Top 40", GROUP_FLO_CHART, SourceKind.YOUTUBE,
            query = "요즘 가장 많이 듣는 노래 TOP 40", limit = 40),
        Source("flo_rising", "급상승", GROUP_FLO_CHART, SourceKind.YOUTUBE,
            query = "최신 인기곡 급상승 음악 차트", limit = 40),
    )

    // ── 플로 장르 (FLO browse 장르 카드) ────────────────────────
    val floGenres: List<Source> = listOf(
        Source("flo_g_ballad", "국내 발라드", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "한국 발라드 명곡 모음 인기곡"),
        Source("flo_g_pop", "해외 팝", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "해외 팝 히트곡 명곡 모음"),
        Source("flo_g_dance", "국내 댄스/일렉", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "국내 댄스 일렉트로닉 인기곡"),
        Source("flo_g_rnb", "국내 알앤비", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "한국 R&B 알앤비 명곡"),
        Source("flo_g_hiphop", "국내 힙합", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "한국 힙합 랩 명곡"),
        Source("flo_g_trot", "트로트", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "트로트 인기곡 명곡 모음"),
        Source("flo_g_rnb_global", "해외 알앤비", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "해외 R&B 알앤비 명곡"),
        Source("flo_g_hiphop_global", "해외 힙합", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "해외 힙합 랩 명곡"),
        Source("flo_g_ost", "OST/BGM", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "드라마 영화 OST 명곡 모음"),
        Source("flo_g_kids", "키즈", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "키즈 동요 인기곡 모음"),
        Source("flo_g_indie", "국내 인디", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "한국 인디 음악 명곡"),
        Source("flo_g_classic", "클래식", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "클래식 명곡 모음"),
        Source("flo_g_newage", "뉴에이지", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "뉴에이지 피아노 명곡"),
        Source("flo_g_jazz", "재즈", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "재즈 명곡 모음"),
        Source("flo_g_acoustic", "국내 팝/어쿠스틱", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "한국 어쿠스틱 팝 명곡"),
        Source("flo_g_folk", "국내 포크/블루스", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "한국 포크 블루스 명곡"),
        Source("flo_g_rock", "국내 록/메탈", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "한국 록 메탈 명곡"),
        Source("flo_g_electronic", "해외 일렉트로닉", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "해외 일렉트로닉 EDM 명곡"),
        Source("flo_g_rock_global", "해외 록", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "해외 록 명곡 모음"),
        Source("flo_g_metal", "해외 메탈", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "해외 메탈 명곡 모음"),
        Source("flo_g_jpop", "J-POP", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "J-POP 인기곡 명곡"),
        Source("flo_g_mom", "맘/태교", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "태교 음악 맘 음악 모음"),
        Source("flo_g_ccm", "CCM", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "CCM 찬양 모음"),
        Source("flo_g_world", "월드뮤직", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "월드뮤직 명곡 모음"),
        Source("flo_g_gugak", "국악", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "국악 판소리 민요"),
        Source("flo_g_religion", "종교음악", GROUP_FLO_GENRE, SourceKind.YOUTUBE,
            query = "종교 음악 명상 음악"),
    )

    // ── 플로 상황 (FLO browse 상황 카드) ────────────────────────
    val floMoods: List<Source> = listOf(
        Source("flo_s_top40", "Top 40", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "요즘 인기곡 TOP 40"),
        Source("flo_s_autumn", "가을", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "가을 감성 노래 모음"),
        Source("flo_s_drive", "드라이브", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "드라이브 노래 모음"),
        Source("flo_s_remake", "추억/리메이크", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "추억의 노래 리메이크 모음"),
        Source("flo_s_love", "사랑", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "사랑 노래 모음"),
        Source("flo_s_breakup", "이별", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "이별 노래 모음"),
        Source("flo_s_weather", "날씨", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "비 오는 날 노래 모음"),
        Source("flo_s_cafe", "카페", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "카페 음악 모음"),
        Source("flo_s_study", "공부/독서", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "공부 독서 집중 음악"),
        Source("flo_s_workout", "운동/헬스", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "운동 헬스 신나는 노래"),
        Source("flo_s_walk", "산책/여행", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "산책 여행 음악"),
        Source("flo_s_pet", "반려동물", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "반려동물 노래 모음"),
        Source("flo_s_morning", "아침", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "아침에 듣기 좋은 노래"),
        Source("flo_s_night", "밤/새벽", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "밤 새벽 감성 노래"),
        Source("flo_s_rest", "휴식/힐링", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "휴식 힐링 음악"),
        Source("flo_s_club", "클럽/파티", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "클럽 파티 신나는 음악"),
        Source("flo_s_karaoke", "노래방", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "노래방 인기곡 모음"),
        Source("flo_s_commute", "출/퇴근", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "출퇴근 길 노래"),
        Source("flo_s_work", "입/작업", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "작업할 때 듣는 음악"),
        Source("flo_s_solo", "혼술/혼밥", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "혼술 혼밥 노래"),
        Source("flo_s_voice", "목소리/음색", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "목소리 좋은 가수 노래"),
        Source("flo_s_room", "내 방", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "방에서 듣기 좋은 노래"),
        Source("flo_s_pub", "펍/바", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "펍 바 재즈 음악"),
        Source("flo_s_lounge", "호텔/라운지", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "호텔 라운지 음악"),
        Source("flo_s_live", "공연/아티스트", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "라이브 공연 명곡"),
        Source("flo_s_sleep", "잠자기", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "잠잘 때 듣는 음악"),
        Source("flo_s_mbti", "MBTI", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "MBTI별 인기 노래"),
        Source("flo_s_spring", "봄", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "봄 노래 모음"),
        Source("flo_s_summer", "여름", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "여름 노래 모음"),
        Source("flo_s_winter", "겨울", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "겨울 노래 모음"),
        Source("flo_s_tv", "TV/라디오", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "TV 라디오 인기곡"),
        Source("flo_s_era", "시대별", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "시대별 인기 가요"),
        Source("flo_s_go", "Music-to-Go", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "Music to Go 플레이리스트"),
        Source("flo_s_christmas", "크리스마스", GROUP_FLO_MOOD, SourceKind.YOUTUBE,
            query = "크리스마스 캐롤 모음"),
    )

    // ── 기존 소스 (호환용) ─────────────────────────────────────
    private val legacy: List<Source> = listOf(
        Source("top100", "TOP100", GROUP_MELON, SourceKind.MELON, url = MELON_TOP),
        Source("g_genie_pop", "가요", GROUP_GENIE, SourceKind.GENIE, url = GENIE_URL.format("M0100")),
        Source("g_genie_dance", "댄스", GROUP_GENIE, SourceKind.GENIE, url = GENIE_URL.format("M0900")),
        Source("g_genie_rap", "랩/힙합", GROUP_GENIE, SourceKind.GENIE, url = GENIE_URL.format("M1000")),
        Source("g_genie_rock", "록/메탈", GROUP_GENIE, SourceKind.GENIE, url = GENIE_URL.format("M0700")),
        Source("g_genie_ccm", "CCM", GROUP_GENIE, SourceKind.GENIE, url = GENIE_URL.format("M0800")),
        Source("g_genie_pop2", "POP", GROUP_GENIE, SourceKind.GENIE, url = GENIE_URL.format("M0200")),
        Source("g_genie_ost", "OST", GROUP_GENIE, SourceKind.GENIE, url = GENIE_URL.format("M0300")),
        Source("g_genie_jpop", "JPOP", GROUP_GENIE, SourceKind.GENIE, url = GENIE_URL.format("M0400")),
        Source("g_genie_jazz", "JAZZ", GROUP_GENIE, SourceKind.GENIE, url = GENIE_URL.format("M0500")),
        Source("g_genie_classic", "CLASSIC", GROUP_GENIE, SourceKind.GENIE, url = GENIE_URL.format("M0600")),
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
