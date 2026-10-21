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

/** 차트/음원 소스 정의 (데스크톱 v1.6.1 과 동일 구성). */
object Sources {

    const val GROUP_MELON = "멜론 차트"
    const val GROUP_GENIE = "지니 장르차트"
    const val GROUP_KOREA = "한국대중음악"

    private const val MELON_TOP = "https://www.melon.com/chart/index.htm"
    private const val GENIE_URL = "https://www.genie.co.kr/chart/genre?ditc=D&genrecode=%s&pg=1"

    val all: List<Source> = listOf(
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

    fun byKey(key: String): Source? = all.firstOrNull { it.key == key }

    val default: Source get() = all.first()
}
