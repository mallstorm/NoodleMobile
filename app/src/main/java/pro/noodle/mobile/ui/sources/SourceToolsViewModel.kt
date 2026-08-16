package pro.noodle.mobile.ui.sources

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import pro.noodle.mobile.core.catalog.*
import pro.noodle.mobile.core.studio.AdapterStudioService


data class SourceToolsUiState(
    val catalogUrl:String="", val catalog:VerifiedCatalog?=null, val marketplaceBusy:Boolean=false,
    val trustedKeys:List<TrustedCatalogKey> = emptyList(), val message:String="", val error:String="",
    val studioUrl:String="", val studioJson:String="", val studioBusy:Boolean=false, val studioMessage:String="",
)

class SourceToolsViewModel(app:Application):AndroidViewModel(app){
    private val market=MarketplaceRepository(app)
    private val catalogSettings=CatalogSettingsRepository(app)
    private val studio=AdapterStudioService(app)
    private val _state=MutableStateFlow(SourceToolsUiState(trustedKeys=market.trustedKeys().list()))
    val state:StateFlow<SourceToolsUiState> = _state.asStateFlow()
    init{viewModelScope.launch{catalogSettings.settings.collect{c->_state.update{it.copy(catalogUrl=c.indexUrl)}}}}
    fun setCatalogUrl(v:String)=_state.update{it.copy(catalogUrl=v)}
    fun saveCatalogUrl()=viewModelScope.launch{market.saveIndexUrl(_state.value.catalogUrl);_state.update{it.copy(message="URL каталога сохранён.",error="")}}
    fun refreshCatalog()=viewModelScope.launch{
        _state.update{it.copy(marketplaceBusy=true,message="",error="")}
        val r=market.refresh(_state.value.catalogUrl)
        _state.update{it.copy(marketplaceBusy=false,catalog=if(r.success)r.catalog else it.catalog,message=if(r.success)r.message else "",error=if(r.success)"" else "${r.errorCode}: ${r.message}",trustedKeys=market.trustedKeys().list())}
    }
    fun install(e:CatalogEntry,onDone:()->Unit={})=viewModelScope.launch{
        _state.update{it.copy(marketplaceBusy=true,message="",error="")};val r=market.install(e)
        _state.update{it.copy(marketplaceBusy=false,message=if(r.success)r.message else "",error=if(r.success)"" else "${r.errorCode}: ${r.message}")};onDone()
    }
    fun importKeys(uri:Uri)=viewModelScope.launch{
        val app=getApplication<Application>();val text=runCatching{app.contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()}.orEmpty()}.getOrElse{_state.update{s->s.copy(error="KEY_IMPORT_IO: ${it.message}")};return@launch}
        val r=market.trustedKeys().importRegistry(text);_state.update{it.copy(trustedKeys=market.trustedKeys().list(),message=if(r.valid)r.message else "",error=if(r.valid)"" else "${r.errorCode}: ${r.message}")}
    }
    fun exportKeys(uri:Uri)=viewModelScope.launch{
        val app=getApplication<Application>();runCatching{app.contentResolver.openOutputStream(uri,"wt")?.bufferedWriter()?.use{it.write(market.trustedKeys().exportUserRegistry())}?:error("Не удалось открыть файл назначения")}.onSuccess{_state.update{it.copy(message="Пользовательские trusted keys экспортированы.",error="")}}.onFailure{e->_state.update{it.copy(error="KEY_EXPORT_IO: ${e.message}")}}
    }
    fun removeKey(id:String){val r=market.trustedKeys().remove(id);_state.update{it.copy(trustedKeys=market.trustedKeys().list(),message=if(r.valid)r.message else "",error=if(r.valid)"" else "${r.errorCode}: ${r.message}")}}
    fun setStudioUrl(v:String)=_state.update{it.copy(studioUrl=v)}
    fun setStudioJson(v:String)=_state.update{it.copy(studioJson=v)}
    fun analyzeStudio()=viewModelScope.launch{
        _state.update{it.copy(studioBusy=true,studioMessage="",error="")};val r=studio.analyze(_state.value.studioUrl)
        _state.update{it.copy(studioBusy=false,studioJson=if(r.success)r.json else it.studioJson,studioMessage=if(r.success)r.message else "",error=if(r.success)"" else "${r.errorCode}: ${r.message}")}
    }
    fun saveStudio(onDone:()->Unit={})=viewModelScope.launch{
        _state.update{it.copy(studioBusy=true,error="")};val r=studio.saveJson(_state.value.studioJson)
        _state.update{it.copy(studioBusy=false,studioMessage=if(r.success)r.message else it.studioMessage,error=if(r.success)"" else "${r.errorCode}: ${r.message}")};if(r.success)onDone()
    }
}
