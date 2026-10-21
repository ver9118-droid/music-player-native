package org.music.infiniteplayer.core

/** HTML 태그 제거 + 엔티티 디코딩 + 공백 정리. */
object Text {

    private val TAG_RE = Regex("<[^>]+>")
    private val SPACE_RE = Regex("\\s+")

    private val ENTITIES = mapOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
        "&quot;" to "\"", "&apos;" to "'", "&#39;" to "'", "&middot;" to "·",
        "&hellip;" to "…", "&mdash;" to "—", "&ndash;" to "–",
    )

    fun stripTags(html: String): String = TAG_RE.replace(html, "")

    fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        var out = text
        for ((k, v) in ENTITIES) out = out.replace(k, v)
        // 숫자 엔티티 (&#39; &#x27;)
        out = NUM_ENTITY_RE.replace(out) { m ->
            val body = m.groupValues[1]
            val code = if (body.startsWith("x") || body.startsWith("X")) {
                body.drop(1).toIntOrNull(16)
            } else {
                body.toIntOrNull()
            }
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        return out
    }

    private val NUM_ENTITY_RE = Regex("&#([0-9a-zA-Z]+);")

    /** 태그 제거 + 엔티티 해제 + 연속 공백 정리 */
    fun clean(html: String): String {
        var s = stripTags(html)
        s = decodeEntities(s).replace('\u00a0', ' ')
        return SPACE_RE.replace(s, " ").trim()
    }

    /** 검색/비교용 정규화 (영문 소문자 + 한글 + 숫자만 남김) */
    fun norm(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase()) {
            if (ch in '0'..'9' || ch in 'a'..'z' || ch in '가'..'힣') sb.append(ch)
        }
        return sb.toString()
    }
}
