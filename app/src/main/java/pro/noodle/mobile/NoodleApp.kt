package pro.noodle.mobile

import android.app.Application
import pro.noodle.mobile.core.network.CookieChallenge

class NoodleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CookieChallenge.attach(this)
        pro.noodle.mobile.core.download.BatchDownloads.init(this)
        pro.noodle.mobile.core.audio.AudioEq.init(this)
    }
}
