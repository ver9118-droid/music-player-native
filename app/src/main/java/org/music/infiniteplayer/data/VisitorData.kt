package org.music.infiniteplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.music.infiniteplayer.core.Net
import org.music.infiniteplayer.core.Prefs

/**
 * 유튜브 방문자 ID (visitor data).
 *
 * InnerTube API 가 "봇으로 판단"해서 LOGIN_REQUIRED 를 내는 것을 막기 위해 필요하다.
 * youtube.com 첫 화면의 ytcfg 에 들어있다.
 *  - 한 번만 받아서 캐시한다 (앱을 껐다 켜도 유지)
 */
object VisitorData {

    private const val VISITOR_RE = "\"VISITOR_DATA\":\"([^\"]+)\""
    private const val PAGE = "https://www.youtube.com/"

    @Volatile
    private var memory: String = ""

    suspend fun get(): String {
        memory.takeIf { it.isNotEmpty() }?.let { return it }
        Prefs.visitorData.takeIf { it.isNotEmpty() }?.let {
            memory = it
            return it
        }
        return withContext(Dispatchers.IO) {
            try {
                val html = Net.get(
                    PAGE,
                    mapOf(
                        "User-Agent" to Net.UA_DESKTOP,
                        "Accept-Language" to "ko,en;q=0.9",
                    ),
                )
                val found = VISITOR_RE.find(html)?.groupValues?.get(1).orEmpty()
                if (found.isNotEmpty()) {
                    memory = found
                    Prefs.visitorData = found
                }
                found
            } catch (_: Exception) {
                ""
            }
        }
    }
}
