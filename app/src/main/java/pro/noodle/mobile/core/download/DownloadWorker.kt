package pro.noodle.mobile.core.download

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import pro.noodle.mobile.MainActivity
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.storage.DownloadState
import pro.noodle.mobile.core.storage.NoodleDatabase
import pro.noodle.mobile.core.version.BuildIdentity
import pro.noodle.mobile.core.settings.AppSettingsRepository
import java.io.File
import java.io.FileInputStream

class DownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val dao=NoodleDatabase.get(appContext).downloads()
    override suspend fun doWork(): Result {
        val id=inputData.getString(KEY_ID).orEmpty(); if(id.isBlank()) return Result.failure()
        val item=dao.get(id) ?: return Result.failure()
        val limit=AppSettingsRepository(applicationContext).settings.first().parallelDownloads
        val admitted=gate.withLock {
            if(dao.countRunningRecentExcluding(id,System.currentTimeMillis()-15_000L) >= limit) false
            else { dao.updateState(id,DownloadState.RUNNING,System.currentTimeMillis()); true }
        }
        if(!admitted) return Result.retry()
        ensureChannel(); setForeground(foreground(item.title, item.downloadedBytes, item.totalBytes))
        val partial=partialFile(applicationContext,id); partial.parentFile?.mkdirs()
        var offset=if(partial.exists())partial.length() else 0L
        val policy=NetworkPolicy(item.allowedHosts.lines().filter{it.isNotBlank()},allowHttp=item.allowHttp,allowCookies=item.allowCookies,maxResponseBytes=4*1024*1024)
        val client=SecureHttpClient().streamingClient(policy,item.sourceKey)
        val request=Request.Builder().url(item.sourceUrl).apply{if(offset>0)header("Range","bytes=$offset-")}.header("User-Agent","${BuildIdentity.USER_AGENT} Download").build()
        val response=try{client.newCall(request).execute()}catch(e:Exception){
            dao.updateProgress(id,DownloadState.FAILED,offset,item.totalBytes,"DOWNLOAD_IO",e.message?:e.javaClass.simpleName,System.currentTimeMillis()); return Result.retry()
        }
        response.use { r ->
            if(!r.isSuccessful && r.code!=206){dao.updateProgress(id,DownloadState.FAILED,offset,item.totalBytes,"HTTP_${r.code}","HTTP ${r.code}",System.currentTimeMillis());return Result.failure()}
            if(offset>0 && r.code==200){partial.delete();offset=0}
            if(offset>0 && r.code==206){
                val start=parseStart(r.header("Content-Range"))
                if(start!=null && start!=offset){partial.delete();dao.updateProgress(id,DownloadState.QUEUED,0,-1,"DOWNLOAD_RANGE_MISMATCH","Сервер вернул диапазон с позиции $start вместо $offset; partial сброшен.",System.currentTimeMillis());return Result.retry()}
            }
            val body=r.body ?: return Result.failure()
            val total=when { r.code==206 -> parseTotal(r.header("Content-Range")) ?: (offset + body.contentLength().coerceAtLeast(0)); body.contentLength()>=0 -> body.contentLength(); else -> -1 }
            dao.updateProgress(id,DownloadState.RUNNING,offset,total,"","",System.currentTimeMillis())
            val output=java.io.FileOutputStream(partial,offset>0).buffered()
            output.use { out ->
                body.byteStream().use { input ->
                    val buf=ByteArray(64*1024); var lastUpdate=0L
                    while(true){
                        if(isStopped){
                            val requested=dao.get(id)?.state
                            if(requested!=DownloadState.PAUSED && requested!=DownloadState.CANCELED)dao.updateState(id,DownloadState.QUEUED,System.currentTimeMillis())
                            return if(requested==DownloadState.PAUSED||requested==DownloadState.CANCELED)Result.failure() else Result.retry()
                        }
                        val n=input.read(buf);if(n<0)break;out.write(buf,0,n);offset+=n
                        val now=System.currentTimeMillis();if(now-lastUpdate>=750){lastUpdate=now;dao.updateProgress(id,DownloadState.RUNNING,offset,total,"","",now);setForeground(foreground(item.title,offset,total))}
                    }
                }
            }
            val localPath=try { finalizeFile(item.destinationUri,partial,id,item.sourceUrl) } catch(e:Exception){dao.updateProgress(id,DownloadState.FAILED,offset,total,"DOWNLOAD_FINALIZE_FAILED",e.message?:e.javaClass.simpleName,System.currentTimeMillis());return Result.failure()}
            dao.markCompleted(id,offset,if(total>0)total else offset,localPath,System.currentTimeMillis())
            return Result.success()
        }
    }

    private fun finalizeFile(destinationUri:String, partial:File, id:String, sourceUrl:String):String{
        if(destinationUri.isBlank()) {
            val ext=runCatching{Uri.parse(sourceUrl).lastPathSegment.orEmpty().substringAfterLast('.',"").take(8)}.getOrDefault("").takeIf{it.matches(Regex("[A-Za-z0-9]{1,8}"))}?.let{".$it"}.orEmpty()
            val dir=File(applicationContext.filesDir,"downloads/completed").apply{mkdirs()}
            val target=File(dir,"$id$ext"); if(target.exists())target.delete()
            if(!partial.renameTo(target)){FileInputStream(partial).use{input->target.outputStream().use{out->input.copyTo(out,128*1024)}};partial.delete()}
            return target.absolutePath
        }
        val uri=Uri.parse(destinationUri)
        applicationContext.contentResolver.openOutputStream(uri,"w")!!.use { out -> FileInputStream(partial).use { it.copyTo(out,128*1024) } }
        partial.delete(); return destinationUri
    }
    private fun parseTotal(value:String?):Long?=value?.substringAfter('/')?.toLongOrNull()
    private fun parseStart(value:String?):Long?=Regex("bytes\\s+(\\d+)-",RegexOption.IGNORE_CASE).find(value.orEmpty())?.groupValues?.getOrNull(1)?.toLongOrNull()
    private fun ensureChannel(){if(Build.VERSION.SDK_INT>=26){val nm=applicationContext.getSystemService(NotificationManager::class.java);nm.createNotificationChannel(NotificationChannel(CHANNEL,"Загрузки Noodle",NotificationManager.IMPORTANCE_LOW))}}
    private fun foreground(title:String,downloaded:Long,total:Long):ForegroundInfo{
        val intent=Intent(applicationContext,MainActivity::class.java);val pi=PendingIntent.getActivity(applicationContext,0,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b=NotificationCompat.Builder(applicationContext,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(if(total>0)"${downloaded*100/total}%" else "${downloaded/1024/1024} МБ").setOnlyAlertOnce(true).setOngoing(true).setContentIntent(pi)
        if(total>0)b.setProgress(100,(downloaded*100/total).toInt().coerceIn(0,100),false) else b.setProgress(0,0,true)
        return if(Build.VERSION.SDK_INT>=29)ForegroundInfo(title.hashCode(),b.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(title.hashCode(),b.build())
    }
    companion object { const val KEY_ID="download_id";private const val CHANNEL="noodle_downloads";private val gate=Mutex(); fun partialFile(context:Context,id:String)=File(context.filesDir,"downloads/$id.partial") }
}
