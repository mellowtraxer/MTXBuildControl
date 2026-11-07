package de.ricci.buildcontrol

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import java.io.File

class ControlModel(app: Application): AndroidViewModel(app) {
    private val store=Store(app)
    private var api: GitHub? = null
    var connected by mutableStateOf(false); private set
    var projects by mutableStateOf(store.projects()); private set
    var selected by mutableStateOf(projects.firstOrNull{it.repo == store.selected} ?: projects.first()); private set
    var snapshot by mutableStateOf<Snapshot?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var detail by mutableStateOf<String?>(null)
    var downloads by mutableStateOf<List<File>>(emptyList())
    var updateAssets by mutableStateOf<List<PackageAsset>>(emptyList())
    var dispatchable by mutableStateOf<Set<String>>(emptySet()); private set
    init { runCatching {store.token()}.onSuccess {if(it.isNotBlank()){api=GitHub(it);connected=true;refresh()}}.onFailure{message="Sicherer Token nicht lesbar. Bitte erneut verbinden."} }
    private fun work(action: suspend () -> Unit) {
        if(busy)return
        busy=true;message=""
        viewModelScope.launch {
            try{action()}catch(e:Exception){message=e.message ?: "Aktion fehlgeschlagen."}finally{busy=false}
        }
    }
    fun connect(token: String) = work {
        require(token.isNotBlank()){ "Bitte einen GitHub-Token eingeben." }
        val client=GitHub(token.trim())
        val user=withContext(Dispatchers.IO){client.user()}
        store.saveToken(token.trim());api=client;connected=true;message="Verbunden als $user"
        load()
    }
    fun logout(){if(busy)return;store.logout();api=null;connected=false;snapshot=null;downloads=emptyList();updateAssets=emptyList();message="Abgemeldet. Token auf diesem Gerät entfernt."}
    private suspend fun load(){val p=selected;val client=api ?: return;val result=withContext(Dispatchers.IO){client.snapshot(p)};snapshot=result;dispatchable=client.dispatchable}
    fun refresh()=work{load()}
    fun select(p:Project){if(busy)return;selected=p;store.selected=p.repo;snapshot=null;downloads=emptyList();refresh()}
    fun saveProject(repo:String,branch:String,name:String)=work{
        val clean=repo.trim().removePrefix("https://github.com/").removeSuffix("/").removeSuffix(".git")
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(clean)) { "Repository als Besitzer/Name angeben." }
        val client=api ?: error("Zuerst GitHub verbinden.")
        val actualBranch=withContext(Dispatchers.IO){if(branch.isBlank())client.defaultBranch(clean) else branch.trim()}
        val p=Project(clean,actualBranch,name.ifBlank{clean.substringAfter('/')})
        withContext(Dispatchers.IO){client.json("repos/${p.repo}/branches/${GitHub.enc(p.branch)}")}
        projects=projects.filter{it.repo!=p.repo}+p;store.saveProjects(projects);selected=p;store.selected=p.repo;snapshot=null;load()
    }
    fun branches()=work{detail=withContext(Dispatchers.IO){api!!.branches(selected.repo).joinToString("\n")}}
    fun switch(target:String)=work{
        val client=api ?: error("Nicht verbunden.")
        // If a response is lost, leave the result uncertain and require refresh before retry.
        message=withContext(Dispatchers.IO){client.switch(selected,target)}
        load()
    }
    fun dispatch(workflow:Workflow,inputs:Map<String,String>)=work{withContext(Dispatchers.IO){api!!.dispatch(selected,workflow,inputs)};message="Workflow-Start angefordert. GitHub legt den Lauf an; bitte aktualisieren.";load()}
    fun failure(build:Build)=work{detail=withContext(Dispatchers.IO){api!!.failure(selected,build)}}
    fun download(asset:PackageAsset)=work{
        val folder=File(getApplication<Application>().cacheDir,"apks")
        downloads=withContext(Dispatchers.IO){api!!.download(asset,folder)}
        message="APK heruntergeladen. Datei auswählen und Installation bestätigen."
    }
    fun updates()=work{
        val result=withContext(Dispatchers.IO){api!!.snapshot(Project("mellowtraxer/MTXBuildControl","main","MTX Build Control"))}
        val current=BuildConfig.VERSION_CODE
        updateAssets=result.assets.filter{!it.zip && it.version.removePrefix("build-").toIntOrNull()?.let{v->v>current} == true}
        message=if(updateAssets.isEmpty())"Keine neuere veröffentlichte Version gefunden. Installiert: ${BuildConfig.VERSION_NAME} ($current)." else "Neue Version verfügbar. Download wählen."
    }
}
