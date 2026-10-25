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
     *
     * 반환: null = 성공, 그 외 **실패 이유**. 예외를 삼키면 어느 지점에서 실패했는지
     * 알 수 없으므로 이유를 돌려준다 (호출자가 토스트에 표시한다).
     */
    fun encodeWithFade(input: File, out: File, window: Window, fadeInSec: Double, fadeOutSec: Double): String? {
        // 페이드가 구간 절반보다 길면 램프가 겹치므로 절반으로 제한한다
        val inSec = fadeInSec.coerceIn(0.0, CLIP_SECONDS / 2.0)
        val outSec = fadeOutSec.coerceIn(0.0, CLIP_SECONDS / 2.0)
        if (inSec <= 0.0 && outSec <= 0.0) return if (cut(input, out, window)) null else "샘플 복사 실패"

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        // 실패 지점을 토스트에 표시한다. catch 에서도 읽어야 하므로 try 밖에서 선언한다.
        var stage = "준비"
        try {
            extractor.setDataSource(input.absolutePath)
            val trackIndex = audioTrack(extractor)
            if (trackIndex < 0) return "음성 트랙 없음"
            extractor.selectTrack(trackIndex)

            val inFormat = extractor.getTrackFormat(trackIndex)
            val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: return "포맷 확인 실패"

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inFormat, null, null, 0)
            decoder.start()

            // 구간 시작 직전 동기 지점으로 이동 (디코더는 sync 에서만 시작할 수 있다)
            // seekTo 는 반환값이 없다 (void) → 이동 후 샘플이 없으면 실패로 본다
            extractor.seekTo(window.startSec * 1_000_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val seekedUs = extractor.getSampleTime()
            if (seekedUs < 0) return "구간 시작점으로 이동 실패"
            val startUs = max(0L, seekedUs)
            val endUs = window.endSec * 1_000_000L
            if (endUs <= startUs) return "구간이 파일 밖에 있다"

            val info = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var decoderInputDone = false
            var decoderDrained = false
            var encoderInputDone = false
            var outTrack = -1
            var written = 0
            var guard = 0
            var frameBytes = 2
            var pcmBuf: ByteBuffer? = null

            while (guard++ < 200_000) {
                // 1) 디코더 입력 — 구간 안의 샘플만 넣는다
                if (!decoderInputDone) {
                    stage = "디코더 입력"
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
                                if (encoder == null) return "AAC 인코더 생성 실패"
                                val pcmFormat = decoder.getOutputFormat()
                                val channels = if (pcmFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                                    pcmFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                else 2
                                frameBytes = max(1, 2 * channels)
                                muxer = MediaMuxer(
                                    out.absolutePath,
                                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                                )
                            }
                            // **중요**: decoder.getOutputBuffer() 가 돌려주는 버퍼는 **읽기 전용**이다
                            // (MediaCodec 문서: "Returns a read-only ByteBuffer"). 여기에 게인을 직접
                            // 쓰면 ReadOnlyBufferException (메시지 없음) 으로 실패한다.
                            // → 우리가 만든 쓰기 가능한 버퍼로 복사한 뒤 그 버퍼에 게인을 입힌다.
                            var w = pcmBuf
                            if (w == null || w.capacity() < info.size) {
                                w = ByteBuffer.allocate(max(info.size, 1 shl 16))
                                pcmBuf = w
                            }
                            val work = w
                            work.clear()
                            // 디코더가 준 버퍼에서 **유효 바이트만** 복사한다.
                            // 버퍼의 limit 은 이전 호출의 값으로 남아 있을 수 있으므로
                            // info.offset + info.size 로 명시적으로 제한해야 한다.
                            val src = pcm.duplicate()
                            src.position(info.offset)
                            src.limit(min(src.capacity(), info.offset + info.size))
                            val valid = src.remaining()
                            if (valid <= 0) {
                                decoder.releaseOutputBuffer(outIndex, false)
                                continue
                            }
                            work.put(src)
                            work.limit(valid)

                            val gainInfo = MediaCodec.BufferInfo()
                            gainInfo.offset = 0
                            gainInfo.size = valid
                            gainInfo.presentationTimeUs = info.presentationTimeUs
                            stage = "게인 램프"
                            applyGain(work, gainInfo, startUs, endUs, inSec, outSec)

                            // 인코더 시간 기준은 파일 시작(0) 이다
                            stage = "인코더 입력"
                            work.position(0)
                            val basePts = info.presentationTimeUs - startUs
                            var left = valid
                            while (left > 0) {
                                val encIn = encoder.dequeueInputBuffer(10_000)
                                if (encIn < 0) break
                                val encBuffer = encoder.getInputBuffer(encIn) ?: break
                                encBuffer.clear()
                                // work 의 유효 범위를 넘기지 않는다 (BufferUnderflow 방지)
                                val chunk = min(min(left, work.remaining()), encBuffer.remaining())
                                if (chunk <= 0) break
                                // 인코더 입력 버퍼 캐패시티가 작을 수 있으므로 chunk 로 나눠 넣는다
                                work.limit(work.position() + chunk)
                                encBuffer.put(work)
                                work.limit(valid)
                                val pts = basePts + (valid - left) * 1_000_000L / frameBytes
                                encoder.queueInputBuffer(encIn, 0, chunk, pts, 0)
                                left -= chunk
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
                stage = "인코더 출력/muxer"
                val encOut = enc.dequeueOutputBuffer(encInfo, 10_000)
                if (encOut >= 0) {
                    if (encInfo.size > 0) {
                        val encBuffer = enc.getOutputBuffer(encOut)
                        if (outTrack < 0) {
                            // MediaMuxer 는 csd-0 없이 AAC 트랙을 받아들이지 않는다.
                            // INFO_OUTPUT_FORMAT_CHANGED 를 먼저 받지 못하면 포맷에 csd-0 가 없다
                            // → 첫 codec-config buffer 에서 채운 뒤 addTrack 한다.
                            val fmt = enc.getOutputFormat()
                            if (!fmt.containsKey("csd-0") && encBuffer != null) {
                                val csd = ByteArray(encInfo.size)
                                val copy = encBuffer.duplicate()
                                copy.position(encInfo.offset)
                                copy.limit(encInfo.offset + encInfo.size)
                                copy.get(csd)
                                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                            }
                            outTrack = mx.addTrack(fmt)
                            mx.start()
                        }
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && encBuffer != null) {
                            mx.writeSampleData(outTrack, encBuffer, encInfo)
                            written++
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
            return if (written > 0 && out.length() > 0) null else "인코딩 출력 없음"
        } catch (e: Exception) {
            // 앱 안에서도 원인을 볼 수 있게 남긴다 (토스트는 길면 잘린다)
            android.util.Log.w("Highlight", "페이드 인코딩 실패", e)
            return "$stage: ${e.message ?: e.javaClass.simpleName}"
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

            val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")

            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, channels)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            // AAC-LC (CodecProfileLevel.AACObjectLC = 2). SDK 에 공개 상수가 없으므로 값으로 적는다.
            // 단, **그 인코더가 LC 프로파일을 지원하는 경우에만** 지정한다 — 지원 목록에 없는
            // 프로파일을 넣으면 configure 가 예외를 내어 실패한다. 지원하지 않으면 기본값(LC) 을 쓴다.
            // (capabilities 는 MediaCodecInfo 에 있다)
            val caps = codec.getCodecInfo().getCapabilitiesForType("audio/mp4a-latm")
            val supportsLc = caps.profileLevels.any { level -> level.profile == 2 }
            if (supportsLc) format.setInteger(MediaFormat.KEY_AAC_PROFILE, 2)

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
     *
     * **주의**: `buffer.short` 은 읽기만 하고 position 을 2 앞으로 밀고,
     * `buffer.putShort` 은 그 **뒤**에 쓴다. 즉 샘플 하나당 4 바이트를 소비한다
     * (읽기 2 + 쓰기 2). 버퍼 limit 이 유효 데이터 크기(info.size) 이면
     * limit/4 번째 샘플에서 `BufferUnderflowException` 이 난다.
     * → position 을 쓰지 않고 **절대 인덱스**(getShort/putShort) 로 같은 위치를 읽기와 쓰기에 쓴다.
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

        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val base = info.offset
        // 절대 인덱스 읽기는 limit 을 넘길 수 있으므로 읽을 수 있는 샘플로 제한한다
        val samples = min(info.size / 2, (buffer.limit() - base) / 2)
        for (i in 0 until samples) {
            val idx = base + i * 2
            val v = (buffer.getShort(idx) * gain).toInt().coerceIn(-32768, 32767)
            buffer.putShort(idx, v.toShort())
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
