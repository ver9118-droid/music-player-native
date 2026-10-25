package org.music.infiniteplayer.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.music.infiniteplayer.core.MediaSaver
import org.music.infiniteplayer.core.RingtoneSetter
import java.io.File

/**
 * 사운드 선택기를 띄우고 **결과를 받아 실제로 벨소리로 적용**하는 투명 창.
 *
 * 사운드 선택기는 두 가지로 열린다.
 *
 *  1. **설정과 같은 화면** (벨소리 목록 + ＋) → 선택하면 시스템이 알아서 적용
 *  2. **"선택 결과를 호출자에게 돌려주는" 화면** (＋ 목록) →
 *     완료를 눌러야 결과가 돌아오는데, **결과를 안 받으면 아무 일도 안 일어난다**
 *     (앱이 그냥 startActivity 만 했던 게 "완료 눌러도 적용 안 됨" 이던 원인)
 *
 * 그래서 이 창이 결과를 받아온다.
 *  - 이미 미디어 스토어 파일이면 그 URI 를 그대로 벨소리로 설정
 *  - 그 외(SAF/외부 파일) 이면 우리 Music 폴더로 복사한 뒤 설정 (재부팅 후에도 읽히도록)
 *  - 권한이 없으면 권한 화면을 열고 안내한다
 *  - 뒤로가기(취소) → 그냥 닫는다 (자동 적용된 벨소리는 그대로 유지된다)
 */
class SoundPickerActivity : ComponentActivity() {

    companion object {
        /** 실제로 띄울 사운드 선택기 인텐트 */
        const val EXTRA_TARGET = "sound_picker_target"
    }

    /** 선택기에서 돌아온 결과를 받아 벨소리로 적용한다 */
    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            // 뒤로가기/취소 → 자동 적용된 벨소리는 그대로 둔다
            finish()
            return@registerForActivityResult
        }
        val data: Intent? = result.data
        val picked: Uri? = data?.data ?: run {
            @Suppress("DEPRECATION")
            data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        if (picked == null) {
            // 삼성 쪽에서 이미 적용했을 수도 있다 → 그냥 닫는다
            finish()
            return@registerForActivityResult
        }
        applyPicked(picked)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent?.let {
            @Suppress("DEPRECATION")
            it.getParcelableExtra<Intent>(EXTRA_TARGET)
        }
        if (target == null) {
            finish()
            return
        }
        try {
            pickerLauncher.launch(target)
        } catch (_: Exception) {
            // 띄우기 실패 → 조용히 닫는다 (재생에는 영향 없음)
            finish()
        }
    }

    /** 선택한 소리를 시스템 기본 벨소리로 설정한다 (파일 준비는 백그라운드) */
    private fun applyPicked(source: Uri) {
        if (!RingtoneSetter.canWriteSettings(this)) {
            toast("벨소리 권한이 없습니다 · 권한을 허용한 뒤 다시 적용해 주세요")
            RingtoneSetter.openWriteSettingsPage(this)
            finish()
            return
        }
        Thread {
            val target = prepare(source)
            runOnUiThread {
                if (target == null) {
                    toast("벨소리 적용 실패 · 다시 시도해 주세요")
                    finish()
                    return@runOnUiThread
                }
                val ok = RingtoneSetter.setRingtone(this, target)
                toast(
                    if (ok) "벨소리 적용 완료 · 선택한 곡"
                    else "저장은 했지만 적용 실패 · 권한을 확인해 주세요",
                )
                finish()
            }
        }.start()
    }

    /**
     * 선택 결과를 그대로 벨소리로 쓸 수 있는 URI 로 만든다.
     *
     *  - `content://media/...` → 이미 미디어 스토어 파일이라 그대로 사용
     *  - 그 외 (파일 선택기/외부 앱 URI) → 우리 Music 폴더로 복사 후 사용
     *    (SAF URI 는 재부팅 후에도 시스템이 벨소리로 못 읽는다)
     */
    private fun prepare(source: Uri): Uri? {
        return try {
            if (source.authority == "media") {
                source
            } else {
                val mime = contentResolver.getType(source) ?: "audio/mp4"
                val displayName = queryDisplayName(source) ?: "벨소리"
                val ext = MediaSaver.extensionFor(mime)
                val name = if (displayName.lowercase().endsWith(".$ext")) {
                    displayName
                } else {
                    "$displayName.$ext"
                }
                val tmp = File(cacheDir, "picked_sound.$ext")
                contentResolver.openInputStream(source)?.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                } ?: return null
                val saved = kotlinx.coroutines.runBlocking {
                    MediaSaver.saveAsRingtone(this@SoundPickerActivity, tmp, name, mime)
                }
                tmp.delete()
                saved.uri
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 파일 선택기가 돌려준 이름 (없으면 null) */
    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) c.getString(i) else null
                    } else {
                        null
                    }
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun toast(text: String) {
        try {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }
}
