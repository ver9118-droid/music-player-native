package org.music.infiniteplayer

import android.app.Application
import org.music.infiniteplayer.core.CrashLog
import org.music.infiniteplayer.core.Images
import org.music.infiniteplayer.core.Prefs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 앱 시작 시 해야 할 일은 이것뿐 (로딩 지연 최소화)
        Prefs.init(this)
        Images.init(this)
        // 예외가 앱을 죽이지 않게 하고 로그를 남긴다
        CrashLog.install(this)
    }
}
