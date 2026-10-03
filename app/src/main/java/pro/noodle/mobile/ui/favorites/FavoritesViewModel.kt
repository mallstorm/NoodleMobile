package pro.noodle.mobile.ui.favorites

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.player.PlaybackRepository
import pro.noodle.mobile.core.player.PlaybackSession
import pro.noodle.mobile.core.search.BundledSearchRepository
import pro.noodle.mobile.core.storage.UserMediaRepository

data class FavoritesUiState(val items:List<VideoItem> = emptyList(),val resolving:Boolean=false,val playback:PlaybackSession?=null,val message:String="")

class FavoritesViewModel(app:Application):AndroidViewModel(app){
    private val user=UserMediaRepository(app)
    private val http=SecureHttpClient()
    private val sources=BundledSearchRepository(app,http)
    private val playbackRepo=PlaybackRepository(app,http)
    private val mutable=MutableStateFlow(FavoritesUiState())
    val state:StateFlow<FavoritesUiState> = combine(user.favorites(),mutable){items,s->s.copy(items=items)}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),FavoritesUiState())
    fun remove(v:VideoItem)=viewModelScope.launch{user.removeFavorite(v)}
    fun open(v:VideoItem){val a=sources.adapterFor(v)?:run{mutable.update{it.copy(message="SNP источника не найден")};return};mutable.update{it.copy(resolving=true,message="")};viewModelScope.launch{user.saveHistory(v,0L,0L);val r=playbackRepo.resolve(v,a);mutable.update{if(r.success&&r.session!=null)it.copy(resolving=false,playback=r.session) else it.copy(resolving=false,message="${r.errorCode}: ${r.errorMessage}")}}}
    fun closePlayer(){mutable.update{it.copy(playback=null,resolving=false)}}
    fun streamingClient(s:PlaybackSession):OkHttpClient=http.streamingClient(s.networkPolicy,s.sourceKey)
}
