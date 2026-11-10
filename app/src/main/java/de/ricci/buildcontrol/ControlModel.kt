package de.ricci.buildcontrol

import android.app.Application
import android.os.SystemClock
import de.ricci.nexus.ota.AndroidOta
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
    var needsProjectAccess by mutableStateOf(false); private set
    var projects by mutableStateOf(store.projects()); private set
    var selected by mutableStateOf(projects.firstOrNull{it.repo == store.selected} ?: projects.first()); private set
    var savedModes by mutableStateOf(projects.associateWith{store.runnerMode(it)}); private set
    private fun rememberMode(project: Project, mode: String?) {
        if(mode==null)return
        store.saveRunnerMode(project,mode)
        savedModes=savedModes+(project to mode)
    }
    var snapshot by mutableStateOf<Snapshot?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var detail by mutableStateOf<String?>(null)
    var downloads by mutableStateOf<List<File>>(emptyList())
    var updatePrompt by mutableStateOf<AppUpdate?>(null); private set
    var updateError by mutableStateOf<String?>(null); private set
    var updateFile by mutableStateOf<File?>(null); private set
    var updateChecking by mutableStateOf(false); private set
    var updateProgress by mutableStateOf<Float?>(null); private set
    private var lastUpdateCheck: Long? = null
    private var lastDiscovery: Long? = null
    var unavailableProjects by mutableStateOf<Set<Long>>(emptySet()); private set
    private val publicReleases = GitHub("")
    var dispatchable by mutableStateOf<Set<String>>(emptySet()); private set
    init { runCatching {store.token()}.onSuccess {if(it.isNotBlank()){api=GitHub(it);connected=true;refresh()}}.onFailure{message="Sicherer Token nicht lesbar. Bitte erneut verbinden."} }
    private fun work(action: suspend () -> Unit) {
        if(busy)return
        busy=true;message=""
        viewModelScope.launch {
            try{action()}catch(e:CancellationException){throw e}catch(e:Exception){
                if(e is ApiException && e.status==401){
                    store.logout();api=null;connected=false;needsProjectAccess=false;snapshot=null
                    message="Deine GitHub-Freigabe ist ungültig oder abgelaufen. Bitte unten neu verbinden."
                }else message=e.message ?: "Aktion fehlgeschlagen."
            }finally{busy=false}
        }
    }
    fun connect(token: String) = work {
        val value=GitHubSetup.token(token) ?: error("Bitte den von GitHub erzeugten Code kopieren und „Einfügen & verbinden“ antippen.")
        val client=GitHub(value)
        val p=selected
        val user=withContext(Dispatchers.IO){client.user()}
        val result=try {withContext(Dispatchers.IO){client.snapshot(p)}} catch(e:ApiException){
            if(e.status==404)error("GitHub ist erreichbar, aber ${p.name} ist nicht freigegeben. Auf GitHub unter „Only select repositories“ auch ${p.repo.substringAfter('/')} auswählen und einen neuen Code erzeugen.")
            if(e.status==403)error("GitHub hat noch nicht alle benötigten Rechte freigegeben. Bitte über „GitHub-Freigabe öffnen“ einen Code mit den vorbereiteten Rechten erstellen.")
            throw e
        }
        store.saveToken(value);api=client;connected=true;needsProjectAccess=false;snapshot=result;dispatchable=client.dispatchable
        rememberMode(p,result.mode)
        runCatching { discover() }
        message="Verbunden als $user · ${p.name} ist bereit."
    }
    fun logout(){if(busy)return;store.logout();api=null;connected=false;needsProjectAccess=false;snapshot=null;downloads=emptyList();message="Abgemeldet. Token auf diesem Gerät entfernt."}
    private suspend fun load(){
        val p=selected;val client=api ?: return
        val result=try {withContext(Dispatchers.IO){client.snapshot(p)}} catch(e:ApiException){
            if(e.status==403||e.status==404){
                needsProjectAccess=true;snapshot=null
                message="Deine GitHub-Verbindung ist gespeichert. Für ${p.name} bitte die Freigabe prüfen: Auf GitHub auch ${p.repo.substringAfter('/')} auswählen. Die passende Einrichtung findest du direkt hier."
                return
            }
            throw e
        }
        snapshot=result;dispatchable=client.dispatchable;needsProjectAccess=false
        rememberMode(p,result.mode)
    }
    private suspend fun discover() {
        val client=api ?: return
        val owner=selected.repo.substringBefore('/')
        val found=withContext(Dispatchers.IO){client.discoverProjects(owner)}
        val byId = found.associateBy { it.id }
        val byName = found.associateBy { it.repo.lowercase() }
        val reconciled = projects.map { previous ->
            val current = if (previous.id != null) byId[previous.id] else byName[previous.repo.lowercase()]
            current?.copy(branch = previous.branch, name = if (previous.name == previous.repo.substringAfter('/'))
                current.name else previous.name) ?: previous
        }
        val knownIds = reconciled.mapNotNull { it.id }.toSet()
        val next = reconciled + found.filter { it.id !in knownIds }
        unavailableProjects = reconciled.filter {
            it.id != null && it.repo.substringBefore('/').equals(owner, true) && it.id !in byId
        }.mapNotNull { it.id }.toSet()
        projects.zip(reconciled).forEach { (previous, current) ->
            if (previous.repo != current.repo && store.runnerMode(current) == null) {
                store.runnerMode(previous)?.let { store.saveRunnerMode(current, it) }
            }
        }
        val current = reconciled[projects.indexOf(selected).coerceAtLeast(0)]
        if (current.repo != selected.repo) {
            store.selected = current.repo
        }
        projects = next
        selected = current
        store.saveProjects(next)
        savedModes = next.associateWith { store.runnerMode(it) }
        lastDiscovery = SystemClock.elapsedRealtime()
    }
    fun refresh()=work{
        runCatching { discover() }.onFailure { message="Projektliste konnte nicht aktualisiert werden: ${it.message}" }
        load()
    }
    fun select(p:Project){if(busy)return;selected=p;store.selected=p.repo;snapshot=null;downloads=emptyList();needsProjectAccess=false;message="";if(connected)refresh()}
    fun saveProject(repo:String,branch:String,name:String)=work{
        val clean=repo.trim().removePrefix("https://github.com/").removeSuffix("/").removeSuffix(".git")
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(clean)) { "Repository als Besitzer/Name angeben." }
        val client=api ?: error("Zuerst GitHub verbinden.")
        val actualBranch=withContext(Dispatchers.IO){if(branch.isBlank())client.defaultBranch(clean) else branch.trim()}
        val existing = projects.firstOrNull { it.repo.equals(clean, true) }
        val p=Project(clean,actualBranch,name.ifBlank{clean.substringAfter('/')},existing?.id,existing?.archived ?: false)
        withContext(Dispatchers.IO){client.json("repos/${p.repo}/branches/${GitHub.enc(p.branch)}")}
        projects=projects.filterNot{it.repo.equals(p.repo, true)}+p;store.saveProjects(projects);selected=p;store.selected=p.repo;snapshot=null;load()
    }
    fun branches()=work{detail=withContext(Dispatchers.IO){api!!.branches(selected.repo).joinToString("\n")}}
    fun switch(target:String)=work{
        val client=api ?: error("Nicht verbunden.")
        val project=selected
        // Clear stale live state before writing. Only a verified response is saved.
        snapshot=null
        withContext(Dispatchers.IO){client.switch(project,target)}
        rememberMode(project,target)
        message="Für ${project.name} gespeichert: ${if(target=="pc")"PC" else "GitHub"}."
        // Keep the confirmed result usable even if optional monitor reads fail.
        val confirmedMessage=message
        try {
            load()
            if(snapshot?.mode!=null&&snapshot?.mode!=target){
                message="$confirmedMessage GitHub zeigt inzwischen eine andere Auswahl. Bitte erneut prüfen."
            }
        } catch(e:Exception) {
            message+=" Auswahl bestätigt. Weitere Angaben konnten gerade nicht geladen werden."
        }
    }
    fun dispatch(workflow:Workflow,inputs:Map<String,String>)=work{withContext(Dispatchers.IO){api!!.dispatch(selected,workflow,inputs)};message="Workflow-Start angefordert. GitHub legt den Lauf an; bitte aktualisieren.";load()}
    fun failure(build:Build)=work{detail=withContext(Dispatchers.IO){api!!.failure(selected,build)}}
    fun buildJobs(build: Build) = work { detail = withContext(Dispatchers.IO) { api!!.buildJobs(selected, build) } }
    fun cancel(build: Build) = work {
        require(snapshot?.builds?.any { it.id == build.id && it.status != "completed" } == true)
        withContext(Dispatchers.IO) { api!!.cancel(selected, build) }
        message = "Abbruch bei GitHub angefordert."
        load()
    }
    fun download(asset:PackageAsset)=work{
        val folder=File(getApplication<Application>().cacheDir,"apks")
        downloads=withContext(Dispatchers.IO){api!!.download(asset,folder)}
        message="APK heruntergeladen. Datei auswählen und Installation bestätigen."
    }
    fun onForeground() {
        checkUpdates()
        if (connected && lastDiscovery?.let { SystemClock.elapsedRealtime() - it >= 300_000 } != false) {
            refresh()
        }
    }

    fun checkUpdates(manual: Boolean = false) {
        if (updateChecking || updateProgress != null || updateFile != null) return
        val now = SystemClock.elapsedRealtime()
        if (!manual && lastUpdateCheck?.let { now - it < 300_000 } == true) return
        lastUpdateCheck = now
        updateChecking = true
        viewModelScope.launch {
            try {
                val update = withContext(Dispatchers.IO) { publicReleases.ownUpdate(BuildConfig.VERSION_CODE) }
                if (update != null && (manual || !store.updateSnoozed(update.versionCode))) updatePrompt = update
                else if (manual) updateError = "Keine neuere freigegebene Version gefunden."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) updateError = e.message ?: "Update-Prüfung fehlgeschlagen."
            } finally {
                updateChecking = false
            }
        }
    }

    fun snoozeUpdate() {
        updatePrompt?.let { store.snoozeUpdate(it.versionCode) }
        updatePrompt = null
    }
    fun clearUpdateError() { updateError = null }
    fun onInstallerOpened(file: File) { if (updateFile == file) updateFile = null }

    fun downloadUpdate(update: AppUpdate) {
        if (updateProgress != null) return
        updatePrompt = null
        updateProgress = 0f
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val file = withContext(Dispatchers.IO) {
                    val downloaded = publicReleases.download(update.asset, File(app.cacheDir, "apks")) { done, total ->
                        ensureActive()
                        updateProgress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f
                    }.single()
                    try {
                        AndroidOta.verifyUpdate(app, downloaded, update.versionCode.toLong())
                        downloaded
                    } catch (e: Exception) {
                        downloaded.delete()
                        throw e
                    }
                }
                updateFile = file
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateError = e.message ?: "Download fehlgeschlagen. Bitte erneut versuchen."
            } finally {
                updateProgress = null
            }
        }
    }
}
