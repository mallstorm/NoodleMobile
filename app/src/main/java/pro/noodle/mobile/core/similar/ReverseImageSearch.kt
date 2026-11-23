package pro.noodle.mobile.core.similar

import android.util.Base64

/**
 * Отправка кадра в поиск по картинке прямо из памяти. Страница-загрузчик открывается от имени самого сайта
 * (loadDataWithBaseURL), поэтому запрос уходит как из обычного браузера — с его куками и без CORS.
 * Если поисковик попросит капчу или формат изменится, остаётся ручной путь: на странице поисковика
 * кнопка выбора файла сама получает этот кадр.
 */
enum class ReverseEngine(val title: String, val baseUrl: String, val homeUrl: String) {
    YANDEX("Яндекс", "https://yandex.ru/images/", "https://yandex.ru/images/search?rpt=imageview"),
    BING("Bing", "https://www.bing.com/images/", "https://www.bing.com/visualsearch"),
    GOOGLE("Google", "https://www.google.com/", "https://lens.google.com/"),
}

object ReverseImageSearch {
    private const val STYLE = "<meta name=viewport content='width=device-width,initial-scale=1'><body style='font-family:sans-serif;padding:24px;color:#555'>"

    private fun blobJs(b64: String) =
        "const b=atob('$b64');const a=new Uint8Array(b.length);for(let i=0;i<b.length;i++)a[i]=b.charCodeAt(i);const blob=new Blob([a],{type:'image/jpeg'});"

    /** HTML-страница, которая отправляет кадр выбранному поисковику и переходит на результаты. */
    fun loaderHtml(engine: ReverseEngine, jpeg: ByteArray): String {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        return when (engine) {
            ReverseEngine.YANDEX -> """<html>$STYLE<div id=s>Отправляю кадр в Яндекс…</div><script>
(async()=>{try{${blobJs(b64)}
const fd=new FormData();fd.append('upfile',blob,'blob');
const r=await fetch('https://yandex.ru/images/search?rpt=imageview&format=json&request='+encodeURIComponent('{"blocks":[{"block":"b-page_type_search-by-image__link"}]}'),{method:'POST',body:fd,credentials:'include'});
const j=await r.json();const q=j.blocks[0].params.url;location.href='https://yandex.ru/images/search?'+q;
}catch(e){document.getElementById('s').innerText='Яндекс не принял кадр автоматически ('+e+'). Открываю страницу поиска по картинке — нажмите значок камеры, кадр подставится сам.';setTimeout(()=>location.href='${engine.homeUrl}',2500);}})();
</script></body></html>"""
            ReverseEngine.BING -> """<html>$STYLE<div>Отправляю кадр в Bing…</div>
<form id=f method=POST enctype="multipart/form-data" action="https://www.bing.com/images/search?view=detailv2&iss=sbiupload&FORM=SBIHMP&sbisrc=ImgDropper&idpbck=1">
<input type=hidden name=imageBin value="$b64"></form><script>document.getElementById('f').submit();</script></body></html>"""
            ReverseEngine.GOOGLE -> """<html>$STYLE<div id=s>Отправляю кадр в Google…</div><script>
(async()=>{try{${blobJs(b64)}
const fd=new FormData();fd.append('encoded_image',blob,'frame.jpg');fd.append('image_content','');
const r=await fetch('https://www.google.com/searchbyimage/upload',{method:'POST',body:fd,credentials:'include',redirect:'follow'});
if(!r.url||r.url.indexOf('searchbyimage/upload')>=0)throw new Error('нет перехода');location.href=r.url;
}catch(e){document.getElementById('s').innerText='Google не принял кадр автоматически. Открываю Google Lens — нажмите «Загрузить файл», кадр подставится сам.';setTimeout(()=>location.href='${engine.homeUrl}',2500);}})();
</script></body></html>"""
        }
    }
}
