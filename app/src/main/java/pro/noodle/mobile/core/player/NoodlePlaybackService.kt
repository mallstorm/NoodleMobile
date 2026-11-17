package pro.noodle.mobile.core.player

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Держит ссылку на MediaSession активного плеера, чтобы сервис мог показать уведомление и работать в фоне. */
object PlaybackSessionHolder {
    @Volatile var session: MediaSession? = null
}

/** Фоновое воспроизведение (режим «только звук»): уведомление с кнопками и управление с экрана блокировки. */
@UnstableApi
class NoodlePlaybackService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        PlaybackSessionHolder.session?.let { runCatching { addSession(it) } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = PlaybackSessionHolder.session

    override fun onTaskRemoved(rootIntent: Intent?) {
        PlaybackSessionHolder.session?.player?.pause()
        stopSelf()
    }
}
