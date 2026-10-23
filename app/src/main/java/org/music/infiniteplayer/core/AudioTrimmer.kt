package org.music.infiniteplayer.core

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * 벨소리용 구간 잘라내기.
 *
 * AAC(m4a) 오디오를 **재인코딩 없이** 원하는 구간만 잘라낸다.
 * (MediaExtractor 로 읽어 MediaMuxer 로 그대로 쓴다 → 음질 손실/속도 문제 없음)
 *
 * 벨소리는 보통 30초 내외라, 곡 전체를 벨소리로 쓰면 용량도 크고
 * 울릴 때 곡 도입부(무음/인트로)만 들리는 문제가 있다.
 * 그래서 기본값은 "하이라이트 30초" 이다.
 */
object AudioTrimmer {

    /**
     * @param startMs   잘라내기 시작 위치 (ms)
     * @param lengthMs  길이 (ms, 0 이면 끝까지)
     * @return true = 성공 (dest 파일 생성)
     */
    fun trim(src: File, dest: File, startMs: Long, lengthMs: Long): Boolean {
        if (!src.exists() || src.length() < 1024) return false
        if (startMs <= 0 && lengthMs <= 0) return false

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(src.absolutePath)

            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    format = f
                    break
                }
            }
            if (audioTrack < 0 || format == null) {
                extractor.release()
                return false
            }

            extractor.selectTrack(audioTrack)
            val startUs = startMs.coerceAtLeast(0) * 1000
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val endUs = if (lengthMs > 0) startUs + lengthMs * 1000 else Long.MAX_VALUE

            if (dest.exists()) dest.delete()
            muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(format)
            muxer.start()

            val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                256 * 1024
            }
            val buffer = ByteBuffer.allocate(maxSize.coerceAtLeast(256 * 1024))
            val info = MediaCodec.BufferInfo()

            var baseUs = -1L
            var written = 0
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val timeUs = extractor.sampleTime
                if (timeUs < 0) break
                if (timeUs < startUs) {
                    extractor.advance()
                    continue
                }
                if (timeUs > endUs) break
                if (baseUs < 0) baseUs = timeUs

                buffer.position(0)
                buffer.limit(size)
                info.set(0, size, timeUs - baseUs, extractor.sampleFlags)
                muxer.writeSampleData(outTrack, buffer, info)
                written++
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            muxer = null
            extractor.release()
            written > 0 && dest.exists() && dest.length() > 0
        } catch (_: Exception) {
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
            if (dest.exists()) dest.delete()
            false
        }
    }
}
