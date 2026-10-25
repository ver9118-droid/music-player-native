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
 *  - 이미 미디어 스토어 파일이면 그 URI 를 그대로 벨소리로 설정 (복사하지 않는다 → 하이라이트 유지)
 *  - 그 외(SAF/외부 파일) 이면 저장소 루트 `Ringtones` 에 복사한 뒤 설정
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
                // 이미 이 곡이 기본 벨소리로 걸려 있으면 → **다시 설정하지 않는다**.
                // (다시 설정하면 갤럭시 사운드 선택기가 저장해 둔
                //  "선택한 하이라이트" 구간이 풀려 벨소리가 처음부터 재생된다)
                val already = isAlreadyDefault(source, target)
                val ok = already || RingtoneSetter.setRingtone(this, target)
                toast(
                    if (ok) "벨소리 적용 완료 · 선택한 곡"
                    else "저장은 했지만 적용 실패 · 권한을 확인해 주세요",
                )
                finish()
            }
        }.start()
    }

    /**
     * 고른 소리가 **이미 시스템 기본 벨소리** 로 걸려 있는지.
     *
     * 걸려 있으면 다시 설정하지 않는다 — 다시 설정하면(또는 파일을 새로 만들면)
     * 사운드 선택기가 저장해 둔 **하이라이트 구간이 풀려 처음부터 재생**된다.
     *
     * 비교는 ① URI 같음 ② 실제 파일 경로 같음 ③ 파일명 같음 (음악 사본/루트 사본 구분)
     */
    private fun isAlreadyDefault(source: Uri, target: Uri?): Boolean {
        val current = RingtoneSetter.currentRingtoneUri(this) ?: return false
        if (RingtoneSetter.isSameRingtone(this, current, source)) return true
        if (target != null && RingtoneSetter.isSameRingtone(this, current, target)) return true
        val currentName = RingtoneSetter.currentRingtoneFileName(this) ?: return false
        val srcName = (queryDisplayName(source) ?: source.lastPathSegment ?: "")
            .substringBeforeLast('.').lowercase()
        return srcName.isNotEmpty() && srcName == currentName
    }

    /**
     * 선택 결과를 **저장소 루트 `Ringtones`** URI 로 만든다.
     *
     *  - 설정 > 벨소리 목록은 저장소 루트 `Ringtones` 만 읽는다
     *  - **이미 미디어 스토어 파일이면 복사하지 않는다.**
     *    복사하면 MediaStore ID 가 바뀌어 사운드 선택기가 그 ID 에 붙여 둔
     *    **하이라이트 구간이 풀리고, 목록에 같은 곡이 두 개로 보인다.**
     *    → 같은 이름이 이미 있으면 그 URI 를 재사용하고,
     *      앱이 만든 사본이 음악 폴더에 있으면 **행만** 루트 `Ringtones` 로 옮긴다.
     *  - SAF/외부 파일일 때만 실제로 복사해 루트 `Ringtones` 에 넣는다
     *    (같은 이름이 이미 있으면 그 행을 재사용한다)
     *  - 처리 실패 시 원본이 미디어 스토어 파일이면 그 URI 를 그대로 쓴다
     */
    private fun prepare(source: Uri): Uri? {
        return try {
            val mime = contentResolver.getType(source) ?: "audio/mp4"
            val displayName = queryDisplayName(source) ?: "벨소리"
            val ext = MediaSaver.extensionFor(mime)
            val name = if (displayName.lowercase().endsWith(".$ext")) {
                displayName
            } else {
                "$displayName.$ext"
            }

            if (source.authority == "media") {
                val existing = MediaSaver.findExistingByName(this, name)
                if (existing != null) return existing
                if (MediaSaver.isAppCreated(name)) {
                    val moved = MediaSaver.moveToRingtoneFolder(this, source)
                    if (moved != null) return moved
                }
                return source
            }

            val tmp = File(cacheDir, "picked_sound.$ext")
            val copied = contentResolver.openInputStream(source)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            } != null
            if (!copied) return null
            val saved = kotlinx.coroutines.runBlocking {
                MediaSaver.saveToRingtoneFolder(this@SoundPickerActivity, tmp, name, mime)
            }
            tmp.delete()
            saved.uri ?: source
        } catch (_: Exception) {
            if (source.authority == "media") source else null
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
