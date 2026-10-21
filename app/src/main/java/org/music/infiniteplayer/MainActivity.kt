package org.music.infiniteplayer

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import org.music.infiniteplayer.playback.PlaybackService
import org.music.infiniteplayer.ui.AppTheme
import org.music.infiniteplayer.ui.RootScreen

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 결과는 앱 동작에 영향 없음 (거부해도 오디오 포커스로 동작) */ }

    /**
     * MediaController 연결.
     *
     * 이게 매우 중요합니다:
     *  - Media3 은 "활성 세션(연결된 컨트롤러가 있는 세션)" 이 아니면
     *    onTaskRemoved 에서 서비스를 종료합니다.
     *    → 컨트롤러를 연결하지 않으면 앱을 최근목록에서 밀어낼 때
     *      서비스가 죽어 음악이 멈추고 곡 정보가 사라집니다.
     *  - 활성 세션이어야 시스템(알림/차량 블루투스)가 우리 앱을 인식합니다.
     */
    private var mediaController: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 상태바/네비게이션바 투명 + 엣지투엣 (Compose 쪽에서 inset 패딩 처리)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        askPermissions()

        // 세션 활성화 (서비스 유지 + 알림 + 차량 블루투스 제어의 전제조건)
        connectMediaController()

        // 서비스(백그라운드 재생 + 차량 블루투스 세션)를 먼저 띄워둔다.
        // 앱이 포그라운드일 때 호출하므로 startService 로도 안전하다.
        // (일부 기기에서 서비스 시작이 거부돼도 앱은 계속 동작해야 한다)
        try {
            startService(Intent(this, PlaybackService::class.java))
        } catch (e: Exception) {
            org.music.infiniteplayer.core.CrashLog.save(e)
        }

        setContent {
            AppTheme {
                RootScreen()
            }
        }
    }

    private fun connectMediaController() {
        try {
            val token = SessionToken(
                this,
                android.content.ComponentName(this, PlaybackService::class.java),
            )
            val future = MediaController.Builder(this, token).buildAsync()
            future.addListener({
                try {
                    // 필드를 유지해야 컨트롤러 연결이 유지된다 (세션 활성 상태)
                    mediaController = future.get()
                } catch (e: Exception) {
                    org.music.infiniteplayer.core.CrashLog.save(e)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            org.music.infiniteplayer.core.CrashLog.save(e)
        }
    }

    override fun onResume() {
        super.onResume()
        // 서비스가 죽었었으면(최근 목록에서 제거 등) 다시 살린다.
        // 화면이 다시 앞으로 왔으므로 startService 가 허용된다.
        ensureService()
    }

    private fun ensureService() {
        if (org.music.infiniteplayer.playback.PlayerBus.isAttached()) return
        try {
            startService(Intent(this, PlaybackService::class.java))
        } catch (e: Exception) {
            org.music.infiniteplayer.core.CrashLog.save(e)
        }
    }

    private fun askPermissions() {
        val wanted = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            wanted.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // Android 9 이하 : 음악 라이브러리/벨소리 저장용
            wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (wanted.isNotEmpty()) {
            permissionLauncher.launch(wanted.toTypedArray())
        }
    }
}
