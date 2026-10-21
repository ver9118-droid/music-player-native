package org.music.infiniteplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.music.infiniteplayer.core.Net
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/** 가사 한 줄 (노래방 싱크용). */
data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * 유튜브 자막(자막 트랙)을 파싱해서 노래방용 가사로 만든다.
 *
 * 응답 XML 형식:
 *   <timedtext format="3"><body>
 *     <p t="3680" d="4000"><s ac="186">그날은</s><s t="1000"> 반딧불을</s></p>
 */
class LyricsRepository {

    suspend fun fetch(captionUrl: String): List<LyricLine> = withContext(Dispatchers.IO) {
        if (captionUrl.isBlank()) return@withContext emptyList()
        try {
            val xml = Net.get(captionUrl, mapOf("User-Agent" to Net.UA_YT))
            parse(xml)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parse(xml: String): List<LyricLine> {
        val out = ArrayList<LyricLine>(120)
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var inP = false
            var start = 0L
            var dur = 0L
            val sb = StringBuilder()

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "p") {
                            inP = true
                            sb.setLength(0)
                            start = parser.getAttributeValue(null, "t")?.toLongOrNull() ?: 0L
                            dur = parser.getAttributeValue(null, "d")?.toLongOrNull() ?: 0L
                        }
                    }
                    XmlPullParser.TEXT -> if (inP) sb.append(parser.text ?: "")
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "p") {
                            inP = false
                            val text = sb.toString().replace('\n', ' ').trim()
                            if (text.isNotEmpty()) {
                                out.add(LyricLine(start, start + dur, text))
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // 파싱 실패 → 빈 가사
        }
        return out
    }
}
