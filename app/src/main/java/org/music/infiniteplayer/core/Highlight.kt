package org.music.infiniteplayer.core

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 갤럭시 사운드 선택기의 **"선택된 하이라이트만 재생"** 을 앱 안에서 똑같이 한다.
 *
 * 삼성은 하이라이트 구간을 자기 데이터베이스(곡 ID 기준) 에 저장하고, 그 값을
 * 밖으로 바꿔 주는 공개 API 가 없다. 그래서 앱이 **음원을 실제로 잘라서**
 * 하이라이트 구간만 벨소리 파일로 만든다 — 구간이 파일 자체에 들어 있으므로
 * 사운드 선택기를 열거나 설정을 다시 거치지 않아도 그대로 재생된다.
 *
 * 구간을 고르는 방식은 갤럭시와 같은 원리다.
 *   1) 음원을 디코딩해 초 단위 음량(RMS)을 재는다
 *   2) 도입부(약 12초)를 빼고 가장 활발한 약 30초 구간(보통 후렴)을 고른다
 *   3) 바로 앞 구간보다 louder 이어야 하고(대조), 중간에 조용한 틈이 없어야 한다
 *   4) 시작점은 직전 초가 조용한 지점(절의 시작)에 맞춘다
 *
 * 잘라낼 때는 재인코딩하지 않고 AAC 샘플을 그대로 복사한다 (음질 변화 없음).
 * 분석이나 잘라내기가 실패하면 곡 전체를 그대로 쓴다.
 *
 * 페이드 인/아웃을 켠 경우에만 예외로 재인코딩한다: 벨소리는 **시스템이**
 * 재생하므로 앱이 음량을 조절할 수 없다. 그래서 페이드는 파일 안에 실제로
 * 기록해야 하고, PCM 으로 디코딩해 샘플에 게인 램프를 입힌 뒤 AAC 로 다시
 * 인코딩한다 (encodeWithFade).
 */
object Highlight {

    /** 벨소리 구간 길이 (갤럭시는 약 30초를 쓴다) */
    const val CLIP_SECONDS = 30

    /** 도입부는 제외한다 (갤럭시도 도입부를 하이라이트로 잡지 않는다) */
    const val INTRO_SECONDS = 12

    /** 고른 하이라이트 구간 (초) */
    data class Window(val startSec: Int, val endSec: Int) {
        fun label(): String = "${mmss(startSec)} ~ ${mmss(endSec)}"
    }

    /**
     * 음파 에너지로 하이라이트 구간을 고른다.
     * 실패하면 null (그때는 곡 전체를 그대로 쓴다).
     */
    fun analyze(file: File): Window? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = audioTrack(extractor)
            if (track < 0) return null
            extractor.selectTrack(track)

            val format = extractor.getTrackFormat(track)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            if (durationUs <= 0) return null
            val durationSec = (durationUs / 1_000_000L).toInt()
            if (durationSec <= CLIP_SECONDS + 2) return Window(0, durationSec)

            val energy = decodeEnergy(extractor, format, durationSec)
            if (energy == null) return null
            return bestWindow(energy)
        } catch (_: Exception) {
            return null
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 선택한 구간만 잘라 out 에 쓴다.
     * 재인코딩 없이 샘플을 그대로 복사한다 (AAC 는 모든 프레임이 독립적 → 어디서 시작해도 재생된다).
     */
    fun cut(input: File, out: File, window: Window): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val trackIndex = audioTrack(extractor)
            if (trackIndex < 0) return false
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            if (!muxable(mime)) return false

            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(format)
            muxer.start()

            val startUs = window.startSec * 1_000_000L
            val endUs = window.endSec * 1_000_000L
            val buffer = ByteBuffer.allocate(1 shl 16)
            val info = MediaCodec.BufferInfo()

            // 구간 앞의 샘플은 건너뛴다
            while (extractor.getSampleTime() < startUs && extractor.advance()) {
            }

            var written = 0
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size <= 0) break
                val time = extractor.getSampleTime()
                if (time >= endUs) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = time - startUs
                info.flags = extractor.getSampleFlags() and MediaCodec.BUFFER_FLAG_KEY_FRAME
                muxer.writeSampleData(outTrack, buffer, info)
                written++
                extractor.advance()
            }

            muxer.stop()
            return written > 0 && out.length() > 0
        } catch (_: Exception) {
            return false
        } finally {
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

    /**
     * 선택한 구간을 **페이드 인/아웃과 함께** out 에 쓴다.
     *
     * 페이드는 파일 안에 실제로 박혀 있어야 한다 — 벨소리는 시스템이 재생하므로
     * 앱이 음량을 조절할 방법이 없다. 그래서 PCM 으로 디코딩하고, 각 샘플에
     * 게인 램프를 입힌 뒤 AAC-LC 로 다시 인코딩해 MP4 에 담는다.
     *
     * 페이드가 꺼져 있으면(0 초) 음질 손실이 없는 샘플 복사 경로(cut)를 그대로 쓴다.
     * 실패하면 호출자가 곡 전체를 쓰는 쪽으로 fallback 한다.
     */
    fun encodeWithFade(input: File, out: File, window: Window, fadeInSec: Double, fadeOutSec: Double): Boolean {
        // 페이드가 구간 절반보다 길면 램프가 겹치므로 절반으로 제한한다
        val inSec = fadeInSec.coerceIn(0.0, CLIP_SECONDS / 2.0)
        val outSec = fadeOutSec.coerceIn(0.0, CLIP_SECONDS / 2.0)
        if (inSec <= 0.0 && outSec <= 0.0) return cut(input, out, window)

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val trackIndex = audioTrack(extractor)
            if (trackIndex < 0) return false
            extractor.selectTrack(trackIndex)

            val inFormat = extractor.getTrackFormat(trackIndex)
            val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: return false

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inFormat, null, null, 0)
            decoder.start()

            // 구간 시작 직전 동기 지점으로 이동 (디코더는 sync 에서만 시작할 수 있다)
            extractor.seekTo(window.startSec * 1_000_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val startUs = max(0L, extractor.getSampleTime())
            val endUs = window.endSec * 1_000_000L
            if (endUs <= startUs) return false

            val info = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var decoderInputDone = false
            var decoderDrained = false
            var encoderInputDone = false
            var outTrack = -1
            var written = 0
            var guard = 0

            while (guard++ < 200_000) {
                // 1) 디코더 입력 — 구간 안의 샘플만 넣는다
                if (!decoderInputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size <= 0 || extractor.getSampleTime() >= endUs) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decoderInputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0)
                            extractor.advance()
                        }
                    }
                }

                // 2) 디코더 출력(16bit PCM) → 게인 램프 → 인코더 입력
                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    if (info.size > 0 && !decoderDrained) {
                        val pcm = decoder.getOutputBuffer(outIndex)
                        if (pcm != null) {
                            if (encoder == null) {
                                encoder = createEncoder(decoder.getOutputFormat())
                                if (encoder == null) return false
                                muxer = MediaMuxer(
                                    out.absolutePath,
                                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                                )
                            }
                            applyGain(pcm, info, startUs, endUs, inSec, outSec)

                            val encIn = encoder.dequeueInputBuffer(10_000)
                            if (encIn >= 0) {
                                val encBuffer = encoder.getInputBuffer(encIn)
                                if (encBuffer != null) {
                                    // 인코더 시간 기준은 파일 시작(0) 이다
                                    val pts = info.presentationTimeUs - startUs
                                    pcm.position(info.offset)
                                    encBuffer.clear()
                                    encBuffer.put(pcm)
                                    encoder.queueInputBuffer(encIn, 0, info.size, pts, 0)
                                }
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        decoderDrained = true
                        if (!encoderInputDone && encoder != null) {
                            val encIn = encoder.dequeueInputBuffer(10_000)
                            if (encIn >= 0) {
                                encoder.queueInputBuffer(
                                    encIn, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                encoderInputDone = true
                            }
                        }
                    }
                }

                if (encoder == null || muxer == null) {
                    // 아직 PCM 이 나오지 않았다 — 인코더는 첫 출력 buffer 에서 만든다
                    if (decoderInputDone && decoderDrained) break
                    continue
                }
                val enc = encoder
                val mx = muxer

                // 3) 인코더 출력 → muxer
                val encOut = enc.dequeueOutputBuffer(encInfo, 10_000)
                if (encOut >= 0) {
                    if (encInfo.size > 0) {
                        if (outTrack < 0) {
                            outTrack = mx.addTrack(enc.getOutputFormat())
                            mx.start()
                        }
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val encBuffer = enc.getOutputBuffer(encOut)
                            if (encBuffer != null) {
                                mx.writeSampleData(outTrack, encBuffer, encInfo)
                                written++
                            }
                        }
                    }
                    enc.releaseOutputBuffer(encOut, false)
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && outTrack < 0) {
                    outTrack = mx.addTrack(enc.getOutputFormat())
                    mx.start()
                }

                if (encoderInputDone && outTrack >= 0 && encOut == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // 입력이 끝났고 출력도 더 나오지 않는다
                    break
                }
            }

            if (outTrack >= 0 && muxer != null) muxer.stop()
            return written > 0 && out.length() > 0
        } catch (_: Exception) {
            return false
        } finally {
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            try {
                encoder?.stop()
            } catch (_: Exception) {
            }
            try {
                encoder?.release()
            } catch (_: Exception) {
            }
            try {
                decoder?.stop()
            } catch (_: Exception) {
            }
            try {
                decoder?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    // ── 내부 ────────────────────────────────────────────────────

    private fun audioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    /** MediaMuxer(MP4) 가 받아들이는 오디오 포맷 */
    private fun muxable(mime: String): Boolean =
        mime == "audio/mp4a-latm" || mime == "audio/ac3" || mime == "audio/eac3"

    /** 페이드를 입힐 때 쓰는 AAC-LC 인코더 */
    private fun createEncoder(pcmFormat: MediaFormat): MediaCodec? {
        try {
            val rate = if (pcmFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                pcmFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            else 44100
            val channels = if (pcmFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                pcmFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            else 2

            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, channels)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            // AAC-LC (MediaCodecInfo.AACProfile.AACProfileLC = 2). SDK 에 공개 상수가
            // 없으므로 값으로 적는다 — 기본값도 AAC-LC 이지만 명시적으로 지정한다.
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, 2)

            val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            return codec
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * PCM 샘플에 게인 램프를 입힌다 (라인 램프).
     * MediaCodec 의 PCM 은 16bit little-endian 이므로 byte order 를 바꿔 읽는다.
     */
    private fun applyGain(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        startUs: Long,
        endUs: Long,
        fadeInSec: Double,
        fadeOutSec: Double,
    ) {
        val t = info.presentationTimeUs
        val gainIn = if (fadeInSec <= 0.0) 1.0
        else ((t - startUs) / (fadeInSec * 1_000_000.0)).coerceIn(0.0, 1.0)
        val gainOut = if (fadeOutSec <= 0.0) 1.0
        else ((endUs - t) / (fadeOutSec * 1_000_000.0)).coerceIn(0.0, 1.0)
        val gain = gainIn * gainOut
        if (gain >= 0.9999) return

        buffer.position(info.offset)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val samples = info.size / 2
        for (i in 0 until samples) {
            val v = (buffer.short * gain).toInt().coerceIn(-32768, 32767)
            buffer.putShort(v.toShort())
        }
        buffer.order(ByteOrder.BIG_ENDIAN)
    }

    /** 초 단위 음량(RMS) */
    private fun decodeEnergy(extractor: MediaExtractor, format: MediaFormat, durationSec: Int): FloatArray? {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sum = DoubleArray(durationSec + 1)
            val count = IntArray(durationSec + 1)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var guard = 0

            while (guard++ < 200_000) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        codec.getOutputBuffer(outIndex)?.let { addEnergy(sum, count, it, info, durationSec) }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    break
                }
            }

            val energy = FloatArray(durationSec + 1)
            var seen = false
            for (i in 0..durationSec) {
                if (count[i] > 0) {
                    energy[i] = sqrt(sum[i] / count[i]).toFloat()
                    seen = true
                }
            }
            return if (seen) energy else null
        } catch (_: Exception) {
            return null
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
        }
    }

    /** 16bit PCM 출력 버퍼를 초 단위 에너지에 더한다 */
    private fun addEnergy(
        sum: DoubleArray,
        count: IntArray,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        durationSec: Int,
    ) {
        buffer.position(info.offset)
        val bin = (info.presentationTimeUs / 1_000_000L).toInt().coerceIn(0, durationSec)
        val samples = info.size / 2
        if (samples <= 0) return
        var local = 0.0
        for (i in 0 until samples) {
            val s = buffer.short.toDouble()
            local += s * s
        }
        sum[bin] += local
        count[bin] += samples
    }

    /**
     * 가장 활발한 구간을 고른다 (갤럭시와 같은 판단 기준).
     *
     *   · 구간 평균 음량            — 기본값, 가장 큰 곳
     *   · 직전 구간과 대조          — 도입부보다 후렴이 크면 그쪽을 택한다
     *   · 중간에 조용한 틈이 없는지 — 벨소리로 울릴 때 끊기지 않게
     *   · 시작점의 자연스러움        — 직전 초가 조용하면 절의 시작점으로 본다
     */
    private fun bestWindow(energy: FloatArray): Window {
        val n = energy.size
        val w = min(CLIP_SECONDS, n)
        val peak = energy.maxOrNull() ?: return Window(0, w)
        if (peak <= 0f) return Window(0, w)

        val lastStart = n - w
        if (lastStart < INTRO_SECONDS) return Window(0, w)

        var bestStart = INTRO_SECONDS
        var bestScore = -1.0

        for (start in INTRO_SECONDS..lastStart) {
            var winSum = 0.0
            var winMin = Double.MAX_VALUE
            for (i in start until start + w) {
                val v = energy[i].toDouble()
                winSum += v
                if (v < winMin) winMin = v
            }
            val winMean = winSum / w
            if (winMean <= 0.0) continue

            // 직전 최대 15초과 비교
            val from = max(0, start - 15)
            var preSum = 0.0
            var preCount = 0
            for (i in from until start) {
                preSum += energy[i].toDouble()
                preCount++
            }
            val preMean = if (preCount > 0) preSum / preCount else 0.0
            val contrast = if (preMean > 0) min(winMean / preMean, 2.0) / 2.0 else 0.5

            val onset = if (energy[start] >= winMean * 0.6) 1.0 else 0.4
            val boundary = if (start > 0 && energy[start - 1] < winMean * 0.55) 1.0 else 0.5
            val gap = min(winMin / winMean, 1.0)

            val score = 0.45 * (winMean / peak) + 0.25 * contrast + 0.15 * gap + 0.10 * onset + 0.05 * boundary
            if (score > bestScore) {
                bestScore = score
                bestStart = start
            }
        }

        return Window(bestStart, min(bestStart + w, n))
    }

    private fun mmss(sec: Int): String = String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60)
}
