package pro.noodle.mobile.core.download

import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.workmanager.WorkManagerScheduler
import pro.noodle.mobile.R

@UnstableApi
class NoodleDownloadService:DownloadService(NOTIFICATION_ID,1000L,CHANNEL_ID,R.string.download_channel_name,R.string.download_channel_description){
    override fun getDownloadManager():DownloadManager=MediaDownloadRuntime.manager(applicationContext)
    override fun getScheduler():Scheduler = WorkManagerScheduler(applicationContext, SCHEDULER_WORK_NAME)
    override fun getForegroundNotification(downloads:List<Download>,notMetRequirements:Int):Notification{
        val active=downloads.count{it.state==Download.STATE_DOWNLOADING};val queued=downloads.count{it.state==Download.STATE_QUEUED}
        val total=downloads.sumOf{it.contentLength.coerceAtLeast(0)};val done=downloads.sumOf{it.bytesDownloaded.coerceAtLeast(0)}
        val b=NotificationCompat.Builder(this,CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Noodle Mobile • офлайн-загрузка").setContentText("Активно: $active • в очереди: $queued").setOngoing(active>0||queued>0).setOnlyAlertOnce(true)
        if(total>0)b.setProgress(100,((done*100L/total).coerceIn(0,100)).toInt(),false) else b.setProgress(0,0,true)
        return b.build()
    }
    companion object{const val CHANNEL_ID="noodle_media_downloads";const val NOTIFICATION_ID=24051;const val STOP_REASON_USER=1001;const val SCHEDULER_WORK_NAME="noodle_media_download_restart"}
}
