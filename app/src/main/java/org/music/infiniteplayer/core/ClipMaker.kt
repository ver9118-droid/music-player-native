package org.music.infiniteplayer.core

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * 오디오 구간 자르기 (인코딩 변환 없이).
 *
 * MediaExtractor 로 샘플을 읽고 MediaMuxer 로 그대로 다시 묶는다.
 * (ffmpeg 없이도 m4a 구간 추출 가능 → 벨소리용 짧은 클립 제작)
 */
object ClipMaker {

    fun trim(
        source: File,
        startMs: Long,
        endMs: Long,
        dest: File,
        mimeType: String = "audio/mp4",
    ): Boolean {
        if (!source.exists() || source.length() < 1024) return false
        if (endMs <= startMs) return false

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(source.absolutePath)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = runCatching { f.getString(MediaFormat.KEY_MIME) }.getOrNull().orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return false

            try {
                dest.delete()
            } catch (_: Exception) {
            }
            muxer = MediaMuxer(
                dest.absolutePath,
                if (mimeType.contains("webm"))
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                else
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            extractor.selectTrack(trackIndex)
            muxer.addTrack(format)
            muxer.start()
            started = true

            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val buffer = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val time = extractor.sampleTime
                if (time > endUs) break
                if (time >= startUs) {
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = (time - startUs).coerceAtLeast(0)
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(trackIndex, buffer, info)
                }
                extractor.advance()
            }
            return true
        } catch (_: Exception) {
            return false
        } finally {
            try {
                if (started) muxer?.stop()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }
}
