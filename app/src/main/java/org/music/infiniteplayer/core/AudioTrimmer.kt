package org.music.infiniteplayer.core

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * 벨소리용 구간 잘라내기.
 *
 * 두 경로가 있다.
 *
 *  1) 페이드 없음 : AAC(m4a) 를 **재인코딩 없이** 원하는 구간만 잘라 쓴다.
 *     (MediaExtractor 로 읽어 MediaMuxer 로 그대로 쓴다 → 음질 손실 없음)
 *  2) 페이드 있음 : 페이드는 샘플 값(음량)을 바꾸는 작업이라 반드시 재인코딩이 필요하다.
 *     구간만 PCM 으로 디코딩 → 음량 램프 적용 → AAC(128k) 로 인코딩 → MediaMuxer.
 *     30초 구간은 PCM 약 5MB 라 메모리에 올려도 안전하다.
 *
 * 벨소리는 보통 30초 내외라, 곡 전체를 벨소리로 쓰면 용량도 크고
 * 울릴 때 곡 도입부(무음/인트로)만 들리는 문제가 있다.
 * 그래서 기본값은 "하이라이트 30초" 이다.
 */
object AudioTrimmer {

    /** 페이드 기본값 (ms). 0 으로 넘기면 페이드 없이 원본 복사 경로로 간다. */
    const val FADE_IN_MS = 1200
    const val FADE_OUT_MS = 1800

    private const val ENCODE_BITRATE = 128_000
    private const val MAX_ENCODE_SECONDS = 60      // PCM 상한 (약 10MB)
    private const val DECODE_TIMEOUT_MS = 30_000L
    private const val ENCODE_TIMEOUT_MS = 30_000L

    /**
     * @param startMs   잘라내기 시작 위치 (ms)
     * @param lengthMs  길이 (ms, 0 이면 끝까지)
     * @param fadeInMs  페이드 인 (0 이면 없음)
     * @param fadeOutMs 페이드 아웃 (0 이면 없음)
     * @return true = 성공 (dest 파일 생성)
     */
    fun trim(
        src: File,
        dest: File,
        startMs: Long,
        lengthMs: Long,
        fadeInMs: Int = FADE_IN_MS,
        fadeOutMs: Int = FADE_OUT_MS,
    ): Boolean {
        if (!src.exists() || src.length() < 1024) return false
        if (startMs <= 0 && lengthMs <= 0) return false
        if (fadeInMs <= 0 && fadeOutMs <= 0) return trimCopy(src, dest, startMs, lengthMs)
        return trimWithFade(src, dest, startMs, lengthMs, fadeInMs, fadeOutMs)
    }

    // ── 경로 1 : 재인코딩 없이 복사 ─────────────────────────────

    private fun trimCopy(src: File, dest: File, startMs: Long, lengthMs: Long): Boolean {
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

    // ── 경로 2 : 페이드 (디코딩 → 음량 램프 → AAC 인코딩) ──────

    private class Pcm(val bytes: ByteArray, val sampleRate: Int, val channels: Int)

    private fun trimWithFade(
        src: File,
        dest: File,
        startMs: Long,
        lengthMs: Long,
        fadeInMs: Int,
        fadeOutMs: Int,
    ): Boolean {
        val pcm = decodeSegment(src, startMs, lengthMs) ?: return false
        if (pcm.bytes.isEmpty() || pcm.sampleRate <= 0 || pcm.channels <= 0) return false
        applyFade(pcm, fadeInMs, fadeOutMs)
        return encodeAac(pcm, dest)
    }

    /** 구간만 PCM(16bit, 리틀엔디안) 으로 디코딩한다. */
    private fun decodeSegment(src: File, startMs: Long, lengthMs: Long): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(src.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)

            val startUs = startMs.coerceAtLeast(0) * 1000
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val endUs = if (lengthMs > 0) startUs + lengthMs * 1000 else Long.MAX_VALUE

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            val out = java.io.ByteArrayOutputStream(1 shl 20)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (sampleRate <= 0) sampleRate = 44100
            if (channels <= 0) channels = 2
            var pcmFloat = false

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            val deadline = System.currentTimeMillis() + DECODE_TIMEOUT_MS
            val maxBytes = MAX_ENCODE_SECONDS * sampleRate * channels * 2

            while (System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val inIndex = dec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = dec.getInputBuffer(inIndex)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (size < 0) {
                            dec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val timeUs = max(0L, extractor.sampleTime)
                            if (timeUs > endUs) {
                                dec.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(inIndex, 0, size, timeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = dec.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = dec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmFloat = of.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                            android.media.AudioFormat.ENCODING_PCM_FLOAT
                    }
                    continue
                }
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone) {
                        idle++
                        if (idle > 50) break
                    }
                    continue
                }
                if (outIndex < 0) continue

                val buf = dec.getOutputBuffer(outIndex)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    if (out.size + info.size > maxBytes) {
                        dec.releaseOutputBuffer(outIndex, false)
                        break
                    }
                    if (pcmFloat) {
                        val fb = buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        while (fb.hasRemaining()) {
                            val v = (fb.get() * 32768.0f).toInt().coerceIn(-32768, 32767)
                            out.write(v and 0xFF)
                            out.write((v shr 8) and 0xFF)
                        }
                    } else {
                        val raw = ByteArray(info.size)
                        buf.get(raw)
                        out.write(raw)
                    }
                }
                dec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }

            if (out.size == 0) return null
            return Pcm(out.toByteArray(), sampleRate, channels)
        } catch (_: Exception) {
            return null
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 코사인 곡선으로 음량 램프를 적용한다.
     *   gain(u) = (1 - cos(pi*u)) / 2   (u = 0 → 0, u = 1 → 1)
     * 페이드 인과 페이드 아웃 중 작은 값을 쓴다(겹치면 둘 다 적용된 것처럼 자연스럽게 들린다).
     */
    private fun applyFade(pcm: Pcm, fadeInMs: Int, fadeOutMs: Int) {
        val frameBytes = 2 * pcm.channels
        if (frameBytes <= 0) return
        val frames = pcm.bytes.size / frameBytes
        if (frames <= 0) return
        val framesPerMs = pcm.sampleRate / 1000.0
        val inFrames = fadeInMs * framesPerMs
        val outFrames = fadeOutMs * framesPerMs

        val bytes = pcm.bytes
        for (f in 0 until frames) {
            var gain = 1.0
            if (inFrames > 0) {
                val u = f / inFrames
                if (u < 1.0) gain = (1.0 - cos(PI * u)) / 2.0
            }
            if (outFrames > 0) {
                val u = (frames - f) / outFrames
                if (u < 1.0) {
                    val g = (1.0 - cos(PI * u)) / 2.0
                    if (g < gain) gain = g
                }
            }
            if (gain >= 0.999) continue
            for (ch in 0 until pcm.channels) {
                val idx = (f * pcm.channels + ch) * 2
                val raw = (bytes[idx + 1] shl 8) or (bytes[idx].toInt() and 0xFF)
                val v = (raw * gain).toInt().coerceIn(-32768, 32767)
                bytes[idx] = (v and 0xFF).toByte()
                bytes[idx + 1] = ((v shr 8) and 0xFF).toByte()
            }
        }
    }

    /** PCM 을 AAC(128k) 로 인코딩해 mp4(m4a) 로 쓴다. */
    private fun encodeAac(pcm: Pcm, dest: File): Boolean {
        val frameBytes = 2 * pcm.channels
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", pcm.sampleRate, pcm.channels)
            format.setInteger(MediaFormat.KEY_BIT_RATE, ENCODE_BITRATE)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            val enc = MediaCodec.createEncoderByType("audio/mp4a-latm")
            codec = enc
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            if (dest.exists()) dest.delete()
            muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val info = MediaCodec.BufferInfo()
            val input = ByteArray(16384)
            var offset = 0
            var inputDone = false
            var idle = 0
            var track = -1
            var baseUs = -1L
            var written = 0
            val deadline = System.currentTimeMillis() + ENCODE_TIMEOUT_MS

            while (System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val inIndex = enc.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val remaining = pcm.bytes.size - offset
                        if (remaining <= 0) {
                            enc.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val n = min(remaining, input.size / frameBytes * frameBytes)
                            if (n <= 0) {
                                enc.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                val buf = enc.getInputBuffer(inIndex)
                                if (buf == null) break
                                buf.clear()
                                buf.put(pcm.bytes, offset, n)
                                val timeUs = offset.toLong() * 1_000_000L / pcm.sampleRate
                                enc.queueInputBuffer(inIndex, 0, n, timeUs, 0)
                                offset += n
                            }
                        }
                    }
                }

                val outIndex = enc.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (track < 0) {
                        track = muxer.addTrack(enc.outputFormat)
                        muxer.start()
                    }
                    continue
                }
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone) {
                        idle++
                        if (idle > 50) break
                    }
                    continue
                }
                if (outIndex < 0) continue

                val buf = enc.getOutputBuffer(outIndex)
                if (buf != null && info.size > 0 && track >= 0) {
                    // 설정(헤더) 버퍼는 파일에 쓰지 않는다
                    if (info.flags and MediaCodec.BUFFER_FLAG_CONFIG_EXTENSION == 0) {
                        if (baseUs < 0) baseUs = info.presentationTimeUs
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        info.set(info.offset, info.size, info.presentationTimeUs - baseUs, info.flags)
                        muxer.writeSampleData(track, buf, info)
                        written++
                    }
                }
                enc.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }

            muxer.stop()
            muxer.release()
            muxer = null
            enc.stop()
            enc.release()
            codec = null
            written > 0 && dest.exists() && dest.length() > 0
        } catch (_: Exception) {
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            if (dest.exists()) dest.delete()
            false
        }
    }
}
