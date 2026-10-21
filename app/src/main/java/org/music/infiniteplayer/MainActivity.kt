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
import org.music.infiniteplayer.playback.PlaybackService
import org.music.infiniteplayer.ui.AppTheme
import org.music.infiniteplayer.ui.RootScreen

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 결과는 앱 동작에 영향 없음 (거부해도 오디오 포커스로 동작) */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 상태바/네비게이션바 투명 + 엣지투엣 (Compose 쪽에서 inset 패딩 처리)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        askPermissions()

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
