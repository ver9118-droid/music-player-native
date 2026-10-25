package org.music.infiniteplayer.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 이미지 로더 (코일/피코 등 라이브러리 없이 직접 구현).
 *
 *  - 메모리 캐시(LruCache) + 디스크 캐시(앱 캐시 폴더)
 *  - 차량/알림용 앨범아트는 여기서 만든 Bitmap 을 그대로 쓴다.
 */
object Images {

    private lateinit var appContext: Context
    private lateinit var thumbDir: File

    private val memory: LruCache<String, Bitmap> by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceIn(4 * 1024, 32 * 1024)
        object : LruCache<String, Bitmap>(maxKb) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        thumbDir = File(appContext.cacheDir, "thumbs")
        try {
            thumbDir.mkdirs()
        } catch (_: Exception) {
        }
    }

    fun thumbFile(url: String): File? {
        if (url.isBlank() || !::thumbDir.isInitialized) return null
        return File(thumbDir, hash(url) + ".jpg")
    }

    /** 메모리 캐시 비우기 (디스크 캐시는 Storage 가 정리한다) */
    fun clearMemory() {
        try {
            memory.evictAll()
        } catch (_: Exception) {
        }
    }

    /** 네트워크에서 받아서 디스크+메모리에 캐시하고 경로를 돌려준다. */
    suspend fun cacheRemote(url: String): File? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val file = thumbFile(url) ?: return@withContext null
        if (file.exists() && file.length() > 512) return@withContext file
        try {
            val bytes = Net.getBytes(url, mapOf("User-Agent" to Net.UA_DESKTOP))
            if (bytes.size < 512) return@withContext null
            file.writeBytes(bytes)
            file
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 메모리/디스크 캐시를 먼저 확인하고, 없으면 네트워크에서 가져온다.
     */
    suspend fun load(url: String, maxPx: Int = 512): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        memory.get(url)?.let { return@withContext it }

        val file = thumbFile(url)
        if (file != null && file.exists() && file.length() > 512) {
            decodeFile(file, maxPx)?.let { bmp ->
                memory.put(url, bmp)
                return@withContext bmp
            }
        }
        val cached = cacheRemote(url) ?: return@withContext null
        decodeFile(cached, maxPx)?.let { bmp ->
            memory.put(url, bmp)
            bmp
        }
    }

    /**
     * 이미 캐시된 이미지만 가져온다 (네트워크 요청 없음).
     *
     * AVRCP 메타데이터는 메인 스레드에서 만들어야 하므로 여기서 블록하면
     * 화면이 얼어붙는다. 없으면 null 을 돌려주고, 나중에 캐시가 채워진 뒤
     * 메타데이터를 다시 보낸다 (onTrackChanged 의 carMetadataTrackId ).
     */
    fun cachedBitmap(url: String, maxPx: Int = 256): Bitmap? {
        if (url.isBlank()) return null
        memory.get(url)?.let { return it }
        val file = thumbFile(url) ?: return null
        if (!file.exists() || file.length() <= 512) return null
        return decodeFile(file, maxPx)?.also { bmp -> memory.put(url, bmp) }
    }

    /**
     * 캐시된 이미지 **바이트**를 그대로 읽는다 (디코딩 없음, 네트워크 없음).
     *
     * MediaSession 은 아트웍을 압축 이미지 바이트로 받으면 BitmapLoader 로
     * 직접 비트맵을 만든다. 차량(AVRCP)은 URI 를 무시하는 헤드유닛이 많아서
     * 바이트를 그대로 넘기는 편이 가장 안정적이다.
     */
    fun cachedBytes(url: String): ByteArray? {
        if (url.isBlank() || !::thumbDir.isInitialized) return null
        val file = thumbFile(url) ?: return null
        if (!file.exists() || file.length() <= 512 || file.length() > 256 * 1024) return null
        return try {
            file.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    /** 앱 전용 파일(알림/차량 표시용)에서 바로 읽는다. */
    fun decodeFile(file: File, maxPx: Int = 512): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            var w = bounds.outWidth
            var h = bounds.outHeight
            while (w / sample > maxPx || h / sample > maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun hash(url: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(url.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
