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
 * 주기적으로 메인 스레드가 응답하는지 확인하고 멈춰 있으면
 * 그 순간의 스택트레이스를 크래시 로그에 남긴다.
 *
 * → 진단 화면의 "크래시 로그 복사" 로 바로 확인 가능
 */
object Watchdog {

    @Volatile
    private var running = false

    fun start(context: Context) {
        if (running) return
        running = true
        val handler = Handler(Looper.getMainLooper())
        Thread({
            while (running) {
                try {
                    val latch = CountDownLatch(1)
                    handler.post { latch.countDown() }
                    val ok = latch.await(3, TimeUnit.SECONDS)
                    if (!ok) {
                        val main = Looper.getMainLooper().thread
                        val stack = main.stackTrace.joinToString("\n") { "    at $it" }
                        CrashLog.saveText(
                            "=== 메인 스레드 멈춤 감지 (" +
                                java.util.Date() + ") ===\n" +
                                "스레드 상태: " + main.state + "\n" + stack
                        )
                    }
                } catch (_: Exception) {
                }
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
}
