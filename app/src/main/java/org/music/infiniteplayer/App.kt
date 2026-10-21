package org.music.infiniteplayer

import android.app.Application
import org.music.infiniteplayer.core.Images
import org.music.infiniteplayer.core.Prefs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 앱 시작 시 해야 할 일은 이것뿐 (로딩 지연 최소화)
        Prefs.init(this)
        Images.init(this)
    }
}
