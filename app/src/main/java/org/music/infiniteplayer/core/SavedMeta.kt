package org.music.infiniteplayer.core

import android.content.Context

/**
 * 저장한 파일의 곡 정보(제목 / 아티스트).
 *
 * 유튜브에서 받은 m4a 에는 ID3 태그가 없어서 MediaStore 가 ARTIST 를 `"<unknown>"` 으로 준다.
 * 다운로드할 때 이미 알고 있던 제목·아티스트를 **파일명 기준**으로 저장해 두고,
 * 저장 파일 목록에서 그 값을 보여 준다.
 */
object SavedMeta {

    private const val PREFS = "saved_meta"

    /** 너무 많이 쌓이지 않게 유지하는 항목 수 */
    private const val MAX = 800

    /** 파일명 → (제목, 아티스트) 을 저장한다. */
    fun put(context: Context, displayName: String, title: String, artist: String) {
        val name = displayName.trim()
        if (name.isBlank()) return
        try {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val e = p.edit()
            e.putString("t:$name", title.trim().take(160))
            e.putString("a:$name", artist.trim().take(120))
            // 오래된 항목 정리 (대략적인 순서 — 크기만 유지하면 충분하다)
            val keys = p.all.keys.filter { it.startsWith("t:") }
            if (keys.size > MAX) {
                keys.take(keys.size - MAX).forEach {
                    e.remove(it)
                    e.remove("a:" + it.substring(2))
                }
            }
            e.apply()
        } catch (_: Exception) {
            // 저장 실패는 다운로드에 영향을 주지 않는다
        }
    }

    /** 파일명 → (제목, 아티스트) 전체. 목록을 읽을 때 한 번만 호출한다. */
    fun load(context: Context): Map<String, Pair<String, String>> {
        return try {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val out = HashMap<String, Pair<String, String>>()
            for ((key, value) in p.all) {
                if (!key.startsWith("t:")) continue
                val name = key.substring(2)
                val title = (value as? String) ?: ""
                val artist = p.getString("a:$name", "") ?: ""
                if (title.isBlank() && artist.isBlank()) continue
                out[name] = title to artist
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
