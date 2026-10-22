package org.music.infiniteplayer.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 메인 스레드 감시자.
 *
 * 앱이 멈췄을 때(버튼이 안 눌리는 증상) 멈춘 지점을 정확히 알아야 하므로,
 *  - 3초 넘게 메인 스레드가 응답하지 않으면 스택트레이스를 즉시 기록
 *  - 정상이어도 30초마다 메인 스레드 스택을 덤프해 둠 (멈춘 뒤에도 단서 확보)
 *
 * → 진단 화면의 "크래시 로그 복사" / "스택 덤프 복사" 로 확인 가능
 */
object Watchdog {

    private const val DUMP_COUNT = 6

    @Volatile
    private var running = false

    private val dumps = ArrayDeque<String>()

    fun start(context: Context) {
        if (running) return
        running = true
        val handler = Handler(Looper.getMainLooper())
        Thread({
            var tick = 0
            while (running) {
                try {
                    val latch = CountDownLatch(1)
                    handler.post { latch.countDown() }
                    val ok = latch.await(3, TimeUnit.SECONDS)
                    if (!ok) {
                        record("=== 메인 스레드 멈춤 감지 (" + java.util.Date() + ") ===")
                    } else if (tick % 3 == 0) {
                        // 30초마다 정상 상태 스택도 남긴다
                        record("--- 정상 스택 덤프 (" + java.util.Date() + ") ---")
                    }
                } catch (_: Exception) {
                }
                tick++
                try {
                    Thread.sleep(10_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "app-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    private fun record(title: String) {
        try {
            val main = Looper.getMainLooper().thread
            val stack = main.stackTrace.joinToString("\n") { "    at $it" }
            val state = org.music.infiniteplayer.playback.PlayerBus.stateLine
            val head = if (state.isBlank()) "" else "\n[상태] $state"
            val text = "$title$head\n스레드 상태: ${main.state}\n$stack"
            synchronized(dumps) {
                dumps.addLast(text)
                while (dumps.size > DUMP_COUNT) dumps.removeFirst()
            }
            CrashLog.saveStack(dumps.joinToString("\n\n"))
        } catch (_: Exception) {
        }
    }
}
