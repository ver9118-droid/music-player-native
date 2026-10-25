package org.music.infiniteplayer.core

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * 벨소리용 "하이라이트" 구간 자동 탐색.
 *
 * 곡마다 후렴(하이라이트) 위치가 다르므로, 고정 비율(35%)로 자르면
 * 어떤 곡은 인트로가, 어떤 곡은 간주가 벨소리로 울린다.
 *
 * 그래서 실제 오디오를 PCM 으로 디코딩해 **소리가 가장 큰 30초 구간**을 찾는다.
 *  - 100ms 단위로 음량(제곱평균)을 계산
 *  - 2초 이동평균으로 튀는 소리(박수/효과음)를 완화
 *  - 30초 창을 밀면서 평균 음량이 최대인 지점 = 후렴
 *
 * 디코딩에 실패하면(코덱 없음/손상 파일) 예전처럼 곡 길이의 35% 지점을 쓴다.
 */
object HighlightFinder {

    data class Result(
        val startMs: Long,
        val lengthMs: Long,
        /** 곡 전체 길이 (ms). 0 이면 알 수 없음 */
        val durationMs: Long,
        /** true = 실제 음량 분석으로 찾음, false = 기본값 */
        val detected: Boolean,
    )

    private const val BUCKET_MS = 100L
    private const val DEFAULT_LENGTH_MS = 30_000L
    private const val SMOOTH_BUCKETS = 20          // 2초 이동평균
    private const val MIN_START_MS = 5_000L        // 인트로는 건너뛴다
    private const val MAX_BUCKETS = 4 * 60 * 60 * 10  // 4시간

    fun find(src: File, targetMs: Long = DEFAULT_LENGTH_MS): Result {
        val durationMs = durationOf(src)
        if (durationMs in 1..(targetMs + 5_000L)) {
            // 30초 남짓한 곡은 곡 전체를 벨소리로
            return Result(0L, durationMs, durationMs, false)
        }
        if (durationMs <= 1L) return fallback(0L, targetMs)

        val energy = try {
            envelope(src)
        } catch (_: Exception) {
            null
        }
        if (energy == null || energy.size < 60) return fallback(durationMs, targetMs)

        val smooth = movingAverage(energy, SMOOTH_BUCKETS)
        val window = (targetMs / BUCKET_MS).toInt().coerceAtLeast(1)
        if (smooth.size <= window) return fallback(durationMs, targetMs)

        val prefix = DoubleArray(smooth.size + 1)
        for (i in smooth.indices) prefix[i + 1] = prefix[i] + smooth[i]

        val minStartIdx = (MIN_START_MS / BUCKET_MS).toInt().coerceAtMost(smooth.size - window)
        var bestStart = -1
        var bestScore = -1.0
        for (start in minStartIdx..(smooth.size - window)) {
            val score = prefix[start + window] - prefix[start]
            if (score > bestScore) {
                bestScore = score
                bestStart = start
            }
        }
        if (bestStart < 0) return fallback(durationMs, targetMs)

        val startMs = bestStart * BUCKET_MS
        val lengthMs = min(targetMs, durationMs - startMs).coerceAtLeast(5_000L)
        return Result(startMs, lengthMs, durationMs, true)
    }

    private fun fallback(durationMs: Long, targetMs: Long): Result {
        val total = if (durationMs > 0) durationMs else targetMs
        val length = min(targetMs, total)
        val start = ((total * 35L) / 100L).coerceIn(0L, max(0L, total - length))
        return Result(start, length, total, false)
    }

    /** 100ms 구간별 평균 음량(0~1) */
    private fun envelope(src: File): List<Double>? {
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

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            val sums = ArrayList<Double>()
            val counts = ArrayList<Long>()

            fun addBucket(timeUs: Long, sum: Double, samples: Long) {
                // 손상 파일이 터무니없는 타임스탬프를 줄 수 있으므로 4시간으로 제한
                val idx = (timeUs / (BUCKET_MS * 1000L)).toInt()
                    .coerceIn(0, MAX_BUCKETS - 1)
                while (sums.size <= idx) {
                    sums.add(0.0)
                    counts.add(0L)
                }
                sums[idx] += sum
                counts[idx] += samples
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idleAfterInput = 0
            var pcmFloat = false
            val deadline = System.currentTimeMillis() + 30_000L

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
                            dec.queueInputBuffer(
                                inIndex, 0, size, max(0L, extractor.sampleTime), 0,
                            )
                            extractor.advance()
                        }
                    }
                }

                val outIndex = dec.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = dec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmFloat = of.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                            AudioFormat.ENCODING_PCM_FLOAT
                    }
                    continue
                }
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone) {
                        idleAfterInput++
                        if (idleAfterInput > 50) break
                    }
                    continue
                }
                if (outIndex < 0) continue

                val buf = dec.getOutputBuffer(outIndex)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    var sum = 0.0
                    var n = 0L
                    if (pcmFloat) {
                        val fb = buf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        while (fb.hasRemaining()) {
                            val v = fb.get().toDouble()
                            sum += v * v
                            n++
                        }
                    } else {
                        val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (sb.hasRemaining()) {
                            val v = sb.get() / 32768.0
                            sum += v * v
                            n++
                        }
                    }
                    if (n > 0) addBucket(info.presentationTimeUs, sum, n)
                }
                dec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }

            if (sums.isEmpty()) return null
            return List(sums.size) { i ->
                if (counts[i] > 0) sums[i] / counts[i] else 0.0
            }
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

    private fun movingAverage(values: List<Double>, window: Int): DoubleArray {
        val out = DoubleArray(values.size)
        var acc = 0.0
        for (i in values.indices) {
            acc += values[i]
            if (i >= window) acc -= values[i - window]
            out[i] = acc / min(i + 1, window)
        }
        return out
    }

    private fun durationOf(src: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(src.absolutePath)
            var d = 0L
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                if (f.containsKey(MediaFormat.KEY_DURATION)) {
                    d = f.getLong(MediaFormat.KEY_DURATION) / 1000L
                }
                break
            }
            d
        } catch (_: Exception) {
            0L
        } finally {
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
        }
    }
}
