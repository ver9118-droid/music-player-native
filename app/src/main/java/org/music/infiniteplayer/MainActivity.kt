package org.music.infiniteplayer

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

        askPermissions()

        // 서비스(백그라운드 재생 + 차량 블루투스 세션)를 먼저 띄워둔다.
        // 앱이 포그라운드일 때 호출하므로 startService 로도 안전하다.
        startService(Intent(this, PlaybackService::class.java))

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
        if (wanted.isNotEmpty()) {
            permissionLauncher.launch(wanted.toTypedArray())
        }
    }
}
