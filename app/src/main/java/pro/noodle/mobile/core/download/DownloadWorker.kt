package pro.noodle.mobile.core.download

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.StatFs
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
        ensureChannel(); setForeground(foreground(item.title, item.downloadedBytes, item.totalBytes,0L,-1L))
        if(item.protocol==DownloadRepository.CONCAT) return doConcat(id,item)
        if(item.destinationUri!=DownloadRepository.ADAPTIVE_DESTINATION && (item.protocol.equals("hls",true) || item.sourceUrl.substringBefore('?').endsWith(".m3u8",true))) return doHls(id,item)
        val partial=partialFile(applicationContext,id); partial.parentFile?.mkdirs()
        var offset=if(partial.exists())partial.length() else 0L
        val policy=NetworkPolicy(item.allowedHosts.lines().filter{it.isNotBlank()},allowHttp=item.allowHttp,allowCookies=item.allowCookies,maxResponseBytes=4*1024*1024)
        val client=SecureHttpClient().streamingClient(policy,item.sourceKey)
        val referer=SourceReferers.forDownload(applicationContext,id,item.sourceKey)
        val request=Request.Builder().url(item.sourceUrl).apply{if(offset>0)header("Range","bytes=$offset-"); if(referer.isNotBlank())header("Referer",referer)}.header("User-Agent",BuildIdentity.USER_AGENT).header("Accept","*/*").build()
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
            if(item.destinationUri.isBlank() && total>0){
                val remaining=(total-offset).coerceAtLeast(0L)
                val available=availableInternalBytes()
                if(available>0 && remaining+MIN_FREE_RESERVE_BYTES>available){
                    dao.updateProgress(id,DownloadState.FAILED,offset,total,"DOWNLOAD_NO_SPACE","Недостаточно места: требуется ещё ${humanBytes(remaining)}, свободно ${humanBytes(available)}.",System.currentTimeMillis())
                    return Result.failure()
                }
            }
            dao.updateProgress(id,DownloadState.RUNNING,offset,total,"","",System.currentTimeMillis())
            val output=java.io.FileOutputStream(partial,offset>0).buffered()
            var sampleBytes=offset
            var sampleAt=System.currentTimeMillis()
            var smoothSpeed=0L
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
                        val now=System.currentTimeMillis();if(now-lastUpdate>=750){
                            lastUpdate=now
                            val dt=(now-sampleAt).coerceAtLeast(1L)
                            val instant=((offset-sampleBytes)*1000L/dt).coerceAtLeast(0L)
                            smoothSpeed=if(smoothSpeed<=0) instant else ((smoothSpeed*3L+instant)/4L)
                            sampleBytes=offset;sampleAt=now
                            val eta=if(total>offset && smoothSpeed>0)(total-offset)/smoothSpeed else -1L
                            dao.updateProgress(id,DownloadState.RUNNING,offset,total,"","",now)
                            setForeground(foreground(item.title,offset,total,smoothSpeed,eta))
                        }
                    }
                }
            }
            if(offset<=0){
                dao.updateProgress(id,DownloadState.FAILED,offset,total,"DOWNLOAD_EMPTY_FILE","Сервер не передал данные.",System.currentTimeMillis())
                return Result.failure()
            }
            if(total>0 && offset!=total){
                dao.updateProgress(id,DownloadState.FAILED,offset,total,"DOWNLOAD_INCOMPLETE","Получено ${humanBytes(offset)} из ${humanBytes(total)}; файл оставлен partial для продолжения.",System.currentTimeMillis())
                return Result.retry()
            }
            val localPath=try { finalizeFile(item.destinationUri,partial,id,item.sourceUrl) } catch(e:Exception){dao.updateProgress(id,DownloadState.FAILED,offset,total,"DOWNLOAD_FINALIZE_FAILED",e.message?:e.javaClass.simpleName,System.currentTimeMillis());return Result.failure()}
            if(!verifyFinal(localPath,offset)){
                dao.updateProgress(id,DownloadState.FAILED,offset,total,"DOWNLOAD_INTEGRITY_FAILED","Финальный файл не прошёл проверку размера.",System.currentTimeMillis())
                return Result.failure()
            }
            dao.markCompleted(id,offset,if(total>0)total else offset,localPath,System.currentTimeMillis())
            return Result.success()
        }
    }

    /** HLS → single regular file (MP4 after remux, raw TS as a fallback). */
    private suspend fun doHls(id:String,item:pro.noodle.mobile.core.storage.DownloadEntity):Result {
        val referer=SourceReferers.forDownload(applicationContext,id,item.sourceKey)
        val client=SecureHttpClient().streamingClient(NetworkPolicy(emptyList(),allowCookies=item.allowCookies),item.sourceKey)
        val dl=HlsFileDownloader(client,referer)
        suspend fun fail(code:String,msg:String,bytes:Long=0L){ dao.updateProgress(id,DownloadState.FAILED,bytes,-1,code,msg,System.currentTimeMillis()) }
        val plan=try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){ dl.plan(item.sourceUrl,item.videoMaxHeight) } }
        catch(e:HlsFileDownloader.Failure){ fail(e.code,e.message.orEmpty()); return Result.failure() }
        catch(e:Exception){ fail("HLS_PLAN_IO",e.message?:e.javaClass.simpleName); return Result.retry() }
        val partial=partialFile(applicationContext,id); partial.parentFile?.mkdirs()
        val idxFile=File(partial.path+".idx")
        var start=if(partial.exists()) (idxFile.takeIf{it.exists()}?.readText()?.trim()?.toIntOrNull() ?: 0) else 0
        if(start<=0 || start>=plan.segments.size){ start=0; partial.delete() }
        val startedAt=System.currentTimeMillis(); val startBytes=if(start>0)partial.length() else 0L
        try {
            java.io.FileOutputStream(partial,start>0).buffered(256*1024).use { out ->
                dl.run(plan,out,start,stopped={isStopped}) { next,written ->
                    out.flush(); idxFile.writeText(next.toString())
                    val bytes=startBytes+written
                    val estTotal=if(next>0) bytes*plan.segments.size/next else -1L
                    val speed=written*1000L/(System.currentTimeMillis()-startedAt).coerceAtLeast(1L)
                    val eta=if(speed>0 && estTotal>bytes)(estTotal-bytes)/speed else -1L
                    dao.updateProgress(id,DownloadState.RUNNING,bytes,estTotal,"","",System.currentTimeMillis())
                    setForeground(foreground(item.title,bytes,estTotal,speed,eta))
                }
            }
        } catch(e:java.util.concurrent.CancellationException){
            val requested=dao.get(id)?.state
            if(requested!=DownloadState.PAUSED && requested!=DownloadState.CANCELED)dao.updateState(id,DownloadState.QUEUED,System.currentTimeMillis())
            return if(requested==DownloadState.PAUSED||requested==DownloadState.CANCELED)Result.failure() else Result.retry()
        } catch(e:HlsFileDownloader.Failure){ fail(e.code,e.message.orEmpty(),partial.length()); return Result.failure() }
        catch(e:Exception){ fail("HLS_IO",e.message?:e.javaClass.simpleName,partial.length()); return Result.retry() }
        idxFile.delete()
        val rawSize=partial.length()
        if(rawSize<=0){ fail("DOWNLOAD_EMPTY_FILE","Сервер не передал данные."); return Result.failure() }
        val remuxed=File(partial.path+".mp4")
        val ok=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){ MediaRemuxer.toMp4(partial,remuxed) }
        val finalFile=if(ok) remuxed else partial
        val size=finalFile.length()
        val localPath=try { finalizeFile(item.destinationUri,finalFile,id,item.sourceUrl) } catch(e:Exception){ fail("DOWNLOAD_FINALIZE_FAILED",e.message?:e.javaClass.simpleName,size); return Result.failure() }
        partial.delete(); remuxed.delete()
        if(!ok && localPath.startsWith("content://")) runCatching { android.provider.DocumentsContract.renameDocument(applicationContext.contentResolver,Uri.parse(localPath),(item.title.replace(Regex("[^\\p{L}\\p{N}._ -]+"),"_").trim().take(80).ifBlank{"video"})+(if(plan.fmp4)".mp4" else ".ts")) }
        if(!verifyFinal(localPath,size)){ fail("DOWNLOAD_INTEGRITY_FAILED","Финальный файл не прошёл проверку размера.",size); return Result.failure() }
        dao.markCompleted(id,size,size,localPath,System.currentTimeMillis())
        return Result.success()
    }

    /** Фильм из частей: качаем части по очереди (с докачкой), затем склеиваем в один MP4 без перекодирования. */
    private suspend fun doConcat(id:String,item:pro.noodle.mobile.core.storage.DownloadEntity):Result {
        val parts=DownloadRepository.readParts(applicationContext,id)
        suspend fun fail(code:String,msg:String,bytes:Long=0L,total:Long=-1L,retry:Boolean=false):Result {
            dao.updateProgress(id,DownloadState.FAILED,bytes,total,code,msg,System.currentTimeMillis()); return if(retry) Result.retry() else Result.failure()
        }
        if(parts.size<2) return fail("CONCAT_NO_PARTS","Список частей потерян — начните скачивание заново.")
        val policy=NetworkPolicy(item.allowedHosts.lines().filter{it.isNotBlank()},allowHttp=item.allowHttp,allowCookies=item.allowCookies,maxResponseBytes=4*1024*1024)
        val client=SecureHttpClient().streamingClient(policy,item.sourceKey)
        val referer=SourceReferers.forDownload(applicationContext,id,item.sourceKey)
        fun req(url:String,from:Long,to:Long?=null)=Request.Builder().url(url).header("User-Agent",BuildIdentity.USER_AGENT).header("Accept","*/*")
            .apply{ if(referer.isNotBlank())header("Referer",referer); if(from>0||to!=null)header("Range","bytes=$from-${to?.toString().orEmpty()}") }.build()
        val dir=File(applicationContext.filesDir,"downloads").apply{mkdirs()}
        // Размеры частей (для общего прогресса и проверки места).
        val sizes=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){ parts.map { u ->
            runCatching { client.newCall(req(u,0,0)).execute().use { r -> if(r.code==206) parseTotal(r.header("Content-Range")) ?: -1L else r.body?.contentLength() ?: -1L } }.getOrDefault(-1L)
        } }
        val grand=if(sizes.all{it>0}) sizes.sum() else -1L
        val have=parts.indices.sumOf{ File(dir,"$id.p$it").takeIf{f->f.exists()}?.length() ?: 0L }
        if(grand>0){
            val need=(grand-have).coerceAtLeast(0)+(sizes.maxOrNull()?:0L)+MIN_FREE_RESERVE_BYTES
            val available=availableInternalBytes()
            if(available in 1 until need) return fail("DOWNLOAD_NO_SPACE","Недостаточно места: нужно ещё ${humanBytes(need)}, свободно ${humanBytes(available)}.",have,grand)
        }
        var doneBytes=0L
        var sampleAt=System.currentTimeMillis(); var sampleBytes=have; var smooth=0L
        for((i,u) in parts.withIndex()){
            val f=File(dir,"$id.p$i"); val done=File(dir,"$id.p$i.done")
            if(done.exists() && f.exists()){ doneBytes+=f.length(); continue }
            var offset=if(f.exists()) f.length() else 0L
            val r=try{ kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){ client.newCall(req(u,offset)).execute() } }catch(e:Exception){ return fail("DOWNLOAD_IO","Часть ${i+1}: ${e.message?:e.javaClass.simpleName}",doneBytes+offset,grand,retry=true) }
            val res:Result?=r.use { resp ->
                if(!resp.isSuccessful && resp.code!=206) return@use fail("HTTP_${resp.code}","Часть ${i+1}: HTTP ${resp.code}. Если ссылка устарела — откройте видео заново и запустите скачивание.",doneBytes+offset,grand)
                if(resp.header("Content-Type").orEmpty().contains("text/html",true)) return@use fail("CONCAT_NOT_VIDEO","Часть ${i+1}: сервер вернул страницу вместо видео (ссылка устарела). Откройте видео заново и скачайте.",doneBytes+offset,grand)
                if(offset>0 && resp.code==200){ f.delete(); offset=0 }
                val body=resp.body ?: return@use fail("DOWNLOAD_EMPTY_FILE","Часть ${i+1}: пустой ответ.",doneBytes,grand,retry=true)
                java.io.FileOutputStream(f,offset>0).buffered(256*1024).use { out ->
                    body.byteStream().use { input ->
                        val buf=ByteArray(64*1024); var last=0L
                        while(true){
                            if(isStopped){
                                val requested=dao.get(id)?.state
                                if(requested!=DownloadState.PAUSED && requested!=DownloadState.CANCELED)dao.updateState(id,DownloadState.QUEUED,System.currentTimeMillis())
                                return if(requested==DownloadState.PAUSED||requested==DownloadState.CANCELED)Result.failure() else Result.retry()
                            }
                            val n=input.read(buf); if(n<0)break; out.write(buf,0,n); offset+=n
                            val now=System.currentTimeMillis()
                            if(now-last>=750){
                                last=now
                                val cur=doneBytes+offset
                                val dt=(now-sampleAt).coerceAtLeast(1L); val inst=((cur-sampleBytes)*1000L/dt).coerceAtLeast(0L)
                                smooth=if(smooth<=0)inst else (smooth*3+inst)/4; sampleAt=now; sampleBytes=cur
                                val eta=if(grand>cur && smooth>0)(grand-cur)/smooth else -1L
                                dao.updateProgress(id,DownloadState.RUNNING,cur,grand,"","",now)
                                setForeground(foreground(item.title,cur,grand,smooth,eta))
                            }
                        }
                    }
                }
                null
            }
            if(res!=null) return res
            if(sizes[i]>0 && f.length()!=sizes[i]) return fail("DOWNLOAD_INCOMPLETE","Часть ${i+1}: получено ${humanBytes(f.length())} из ${humanBytes(sizes[i])}, продолжу.",doneBytes+f.length(),grand,retry=true)
            done.createNewFile(); doneBytes+=f.length()
        }
        // Склейка.
        setForeground(foreground(item.title,doneBytes,grand,0L,-1L))
        dao.updateProgress(id,DownloadState.RUNNING,doneBytes,grand,"","",System.currentTimeMillis())
        val inputs=parts.indices.map{File(dir,"$id.p$it")}
        val lowSpace=availableInternalBytes().let{ it in 1 until (doneBytes+MIN_FREE_RESERVE_BYTES) }
        var localPath:String
        var size:Long
        val ok:Boolean
        if(item.destinationUri.isNotBlank()){
            val uri=Uri.parse(item.destinationUri)
            val pfd=runCatching{ applicationContext.contentResolver.openFileDescriptor(uri,"rw") }.getOrNull()
            if(pfd!=null){
                ok=pfd.use { p -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){ MediaRemuxer.concat(inputs,p.fileDescriptor,null,deleteEach=lowSpace) } }
                localPath=item.destinationUri
                size=runCatching{ applicationContext.contentResolver.openAssetFileDescriptor(uri,"r")?.use{it.length} ?: -1L }.getOrDefault(-1L)
            } else {
                val tmp=File(dir,"$id.joined.mp4")
                ok=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){ MediaRemuxer.concat(inputs,null,tmp.absolutePath,deleteEach=lowSpace) }
                size=tmp.length()
                localPath=if(ok) try{ finalizeFile(item.destinationUri,tmp,id,parts.first()) }catch(e:Exception){ return fail("DOWNLOAD_FINALIZE_FAILED",e.message?:e.javaClass.simpleName,doneBytes,grand) } else ""
            }
        } else {
            val target=File(File(applicationContext.filesDir,"downloads/completed").apply{mkdirs()},"$id.mp4")
            ok=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){ MediaRemuxer.concat(inputs,null,target.absolutePath,deleteEach=lowSpace) }
            localPath=target.absolutePath; size=target.length()
        }
        if(!ok){
            if(lowSpace) cleanupParts(applicationContext,id)
            return fail("CONCAT_MUX_FAILED","Части скачаны, но склеить их не удалось (разный формат частей). Скачайте части по отдельности через «Источник».",doneBytes,grand)
        }
        cleanupParts(applicationContext,id)
        if(size<=0L) size=doneBytes
        dao.markCompleted(id,size,size,localPath,System.currentTimeMillis())
        return Result.success()
    }

    private fun verifyFinal(localPath:String,expected:Long):Boolean {
        if(expected<=0) return false
        if(localPath.startsWith(applicationContext.filesDir.absolutePath)) return File(localPath).isFile && File(localPath).length()==expected
        if(localPath.startsWith("content://")) return runCatching {
            applicationContext.contentResolver.openAssetFileDescriptor(Uri.parse(localPath),"r")?.use { afd -> afd.length<0 || afd.length==expected } ?: false
        }.getOrDefault(false)
        return true
    }

    private fun availableInternalBytes():Long=runCatching { StatFs(applicationContext.filesDir.absolutePath).availableBytes }.getOrDefault(-1L)

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
    private fun foreground(title:String,downloaded:Long,total:Long,speed:Long,etaSeconds:Long):ForegroundInfo{
        val intent=Intent(applicationContext,MainActivity::class.java);val pi=PendingIntent.getActivity(applicationContext,0,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text=buildString {
            if(total>0) append("${downloaded*100/total}%") else append(humanBytes(downloaded))
            if(speed>0) append(" • ${humanBytes(speed)}/с")
            if(etaSeconds>=0) append(" • ${formatEta(etaSeconds)}")
        }
        val b=NotificationCompat.Builder(applicationContext,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(text).setOnlyAlertOnce(true).setOngoing(true).setContentIntent(pi)
        if(total>0)b.setProgress(100,(downloaded*100/total).toInt().coerceIn(0,100),false) else b.setProgress(0,0,true)
        return if(Build.VERSION.SDK_INT>=29)ForegroundInfo(title.hashCode(),b.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(title.hashCode(),b.build())
    }
    private fun humanBytes(v:Long):String=when { v>=1024L*1024*1024 -> "%.2f ГБ".format(v/(1024.0*1024*1024)); v>=1024L*1024 -> "%.1f МБ".format(v/(1024.0*1024)); v>=1024 -> "%.1f КБ".format(v/1024.0); else -> "$v Б" }
    private fun formatEta(seconds:Long):String=when { seconds>=3600 -> "%d:%02d:%02d".format(seconds/3600,(seconds/60)%60,seconds%60); else -> "%02d:%02d".format(seconds/60,seconds%60) }
    companion object { const val KEY_ID="download_id";private const val CHANNEL="noodle_downloads";private const val MIN_FREE_RESERVE_BYTES=128L*1024*1024;private val gate=Mutex(); fun partialFile(context:Context,id:String)=File(context.filesDir,"downloads/$id.partial")
        fun cleanupParts(context:Context,id:String){ File(context.filesDir,"downloads").listFiles()?.filter{ it.name.startsWith("$id.p") || it.name=="$id.joined.mp4" }?.forEach{ it.delete() } } }
}
