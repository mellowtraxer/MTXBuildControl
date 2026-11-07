package de.ricci.buildcontrol

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateColorAsState
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.Duration

class MainActivity: ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);enableEdgeToEdge();window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent{MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFB998FF),background=Color.Black,surface=Color(0xFF121016),onSurface=Color(0xFFF0EAF8))){ Surface(Modifier.fillMaxSize(),color=Color.Black){ControlApp()} }}
    }
}
private val Purple=Color(0xFFB998FF)
@Composable private fun Glass(title:String,content:@Composable ColumnScope.()->Unit){
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFF141218)),border=BorderStroke(1.dp,Color.White.copy(alpha=.08f))){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,color=Purple,fontWeight=FontWeight.Bold,fontSize=13.sp,letterSpacing=1.sp);content()}}
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ControlApp(model:ControlModel=viewModel()){
    val context=LocalContext.current;val haptics=LocalHapticFeedback.current
    var tab by rememberSaveable {mutableIntStateOf(0)}
    var settings by rememberSaveable {mutableStateOf(false)}
    var edit by remember {mutableStateOf(false)}
    var repo by remember {mutableStateOf("")};var branch by remember {mutableStateOf("")};var name by remember {mutableStateOf("")}
    var pending by remember {mutableStateOf<String?>(null)}
    var workflow by remember {mutableStateOf<Workflow?>(null)}
    var inputs by remember {mutableStateOf<Map<String,String>>(emptyMap())}
    var apk by remember {mutableStateOf<File?>(null)}
    var installError by remember {mutableStateOf<String?>(null)}
    var pendingInstall by remember {mutableStateOf<File?>(null)}
    fun open(url:String){runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}.onFailure{installError="Kein Browser verfügbar."}}
    fun install(file:File){
        try{
            val info=context.packageManager.getPackageArchiveInfo(file.path,PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Ungültige APK.")
            if(info.packageName==context.packageName){
                require(info.longVersionCode>BuildConfig.VERSION_CODE){"Diese Build-Control-Version ist nicht neuer."}
                val installed=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
                val ours=installed.signingInfo!!.apkContentsSigners.map{it.toCharsString()}.toSet()
                val theirs=info.signingInfo!!.apkContentsSigners.map{it.toCharsString()}.toSet()
                require(ours==theirs){"Update hat eine andere Signatur. Installation abgelehnt."}
            }
            if(!context.packageManager.canRequestPackageInstalls()){
                pendingInstall=file
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")))
                return
            }
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }catch(e:Exception){installError=e.message}
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=18.dp)){
        Row(Modifier.fillMaxWidth().padding(vertical=18.dp),horizontalArrangement=Arrangement.SpaceBetween){Column{Text("MTX",color=Purple,fontSize=28.sp,fontWeight=FontWeight.Black,fontFamily=FontFamily.Monospace);Text("BUILD CONTROL",fontSize=13.sp,letterSpacing=3.sp)};TextButton(onClick={model.updates()},enabled=model.connected&&!model.busy){Text("v${BuildConfig.VERSION_NAME}")}}
        if(model.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
            Glass(if(model.connected)"PROJEKT" else "DEIN PROJEKT"){
                model.projects.forEach{p->FilterChip(selected=model.selected==p,onClick={tab=0;model.select(p)},enabled=!model.busy,label={Text(p.name)})}
                if(model.connected){
                    TextButton(onClick={settings=!settings}){Text(if(settings)"Einstellungen schließen" else "Einstellungen")}
                    if(settings){
                    Text(model.selected.repo,fontSize=12.sp)
                    Text(model.selected.branch,fontFamily=FontFamily.Monospace,color=Purple)
                    Row{TextButton(onClick={repo="";branch="";name="";edit=true},enabled=!model.busy){Text("+ Projekt")};TextButton(onClick={repo=model.selected.repo;branch=model.selected.branch;name=model.selected.name;edit=true},enabled=!model.busy){Text("Bearbeiten")};TextButton(onClick={model.refresh()},enabled=!model.busy){Text("Aktualisieren")}}
                    }
                }else{
                    Text("${model.selected.name} ist bereits eingerichtet. Wähle das Projekt und verbinde unten einmal GitHub.",fontSize=13.sp)
                }
            }
            if(!model.connected||model.needsProjectAccess){
                ConnectionWizard(model.selected,model.projects,model.busy,model.message,::open){model.connect(it)}
            }else{
                PrimaryTabRow(selectedTabIndex=tab){listOf("Auswahl","Builds","APKs").forEachIndexed{i,t->Tab(selected=tab==i,onClick={tab=i},text={Text(t)})}}
                if(tab==0){
                    val live=model.snapshot
                    Glass("WO SOLL ${model.selected.name.uppercase()} BAUEN?"){
                        RunnerChoice(mode=live?.mode ?: model.savedModes[model.selected],verified=live?.mode!=null,
                            enabled=!model.busy&&live?.mode!=null,onChoose={target->
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress);model.switch(target)
                            })
                        Text("Antippen speichert die Auswahl für dieses Projekt. Laufende Builds bleiben erhalten.",fontSize=12.sp)
                        if(live!=null&&live.mode==null)Text("Die Umschaltung ist für dieses Projekt noch nicht eingerichtet.")
                        if(live?.mode==null)TextButton(onClick={model.refresh()},enabled=!model.busy){Text("Erneut prüfen")}
                        if(live!=null&&live.runners.none{it.name=="RICCI"&&it.status=="online"})Text("PC gerade nicht erreichbar. Du kannst GitHub wählen.",fontSize=12.sp)
                    }
                    Glass("DEINE PROJEKTE"){
                        model.projects.forEach{p->Text("${p.name}: ${when(model.savedModes[p]){"pc"->"PC";"github"->"GitHub";else->"noch nicht geprüft"}}",fontSize=14.sp)}
                        Text("Zuletzt bestätigte Auswahl. Jedes Projekt hat seine eigene Einstellung.",fontSize=12.sp)
                    }
                }
                model.snapshot?.let{s->when(tab){
                    0->{
                        s.builds.firstOrNull()?.let{BuildCard(it,{model.failure(it)},{open(it.url)})}
                    }
                    1->{
                        Glass("WORKFLOWS"){
                            s.workflows.forEach{w->Text(w.name,fontWeight=FontWeight.Bold);Text(w.path,fontSize=11.sp);OutlinedButton(onClick={workflow=w;inputs=w.dispatch.mapValues{it.value.default}},enabled=!model.busy&&w.path in model.dispatchable){Text(if(w.path in model.dispatchable)"Manuell starten" else "Kein manueller Start im Workflow")}}
                        }
                        if(s.builds.isEmpty())Text("Keine Builds auf diesem Branch.")
                        s.builds.forEach{b->BuildCard(b,{model.failure(b)},{open(b.url)})}
                    }
                    2->{
                        Glass("APK CENTER"){
                            Text("Releases und Artefakte der letzten fünf Builds dieses Branches. APK-Version und Paketname werden nach dem Download aus der Datei gelesen.",fontSize=12.sp)
                            if(s.assets.isEmpty())Text("Keine verfügbaren APK-Releases oder Build-Artefakte.")
                            s.assets.forEach{a->Text(a.title,fontWeight=FontWeight.Bold);Text(a.version,fontSize=12.sp);OutlinedButton(onClick={model.download(a)},enabled=!model.busy){Text(if(a.zip)"Artefakt prüfen & APK laden" else "APK herunterladen")}}
                        }
                    }
                }
                if(settings&&s.notes.isNotEmpty())Glass("HINWEISE"){s.notes.distinct().forEach{Text(it,fontSize=12.sp)}}}
                if(model.downloads.isNotEmpty())Glass("HERUNTERGELADEN"){
                    model.downloads.forEach{f->
                        val info=remember(f){context.packageManager.getPackageArchiveInfo(f.path,0)}
                        Text(if(info==null)"Ungültige APK" else "${info.packageName}\nv${info.versionName} · Build ${info.longVersionCode}")
                        Button(onClick={apk=f},enabled=info!=null){Text("Installation öffnen")}
                    }
                }
                pendingInstall?.let{f->OutlinedButton(onClick={pendingInstall=null;install(f)}){Text("Berechtigung erteilt – Installation fortsetzen")}}
                if(model.updateAssets.isNotEmpty())Glass("BUILD CONTROL UPDATE"){model.updateAssets.forEach{a->Text(a.version);Button(onClick={model.download(a)},enabled=!model.busy){Text("Update herunterladen")}}}
            }
            if(model.connected&&settings)TextButton(onClick={pending="logout"},enabled=!model.busy){Text("GitHub abmelden")}
            if(model.connected&&!model.needsProjectAccess&&model.message.isNotBlank())Glass("STATUS"){Text(model.message)}
            Spacer(Modifier.height(16.dp))
        }
    }
    if(edit)AlertDialog(onDismissRequest={edit=false},title={Text("Projekt & Branch")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(repo,{repo=it},label={Text("Besitzer/Repository")});OutlinedTextField(branch,{branch=it},label={Text("Branch (leer = Standard)")});OutlinedTextField(name,{name=it},label={Text("Anzeigename")});TextButton(onClick={model.branches()}){Text("Branches des gewählten Projekts anzeigen")}}},confirmButton={TextButton(onClick={edit=false;model.saveProject(repo,branch,name)}){Text("Speichern")}},dismissButton={TextButton(onClick={edit=false}){Text("Abbrechen")}})
    if(pending=="logout")AlertDialog(onDismissRequest={pending=null},title={Text("Abmelden?")},text={Text("Die GitHub-Verbindung wird auf diesem Gerät entfernt. Deine Projekte und ihre Auswahl bleiben gespeichert.")},confirmButton={TextButton(onClick={pending=null;model.logout()}){Text("Abmelden")}},dismissButton={TextButton(onClick={pending=null}){Text("Abbrechen")}})
    workflow?.let{w->AlertDialog(onDismissRequest={workflow=null},title={Text("Workflow starten?")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("${w.name}\n${model.selected.repo}\n${model.selected.branch}");w.dispatch.forEach{(k,v)->OutlinedTextField(inputs[k].orEmpty(),{inputs=inputs+(k to it)},label={Text(k+if(v.required)" *" else "")},supportingText={Text(if(v.options.isEmpty())v.type else v.options.joinToString(" | "))})}}},confirmButton={TextButton(onClick={workflow=null;model.dispatch(w,inputs)}){Text("Start bestätigen")}},dismissButton={TextButton(onClick={workflow=null}){Text("Abbrechen")}})}
    apk?.let{f->AlertDialog(onDismissRequest={apk=null},title={Text("APK installieren?")},text={Text("Android prüft die APK und verlangt die Installationsbestätigung. Bei Build Control werden zusätzlich Version und Signatur geprüft.")},confirmButton={TextButton(onClick={apk=null;install(f)}){Text("Systeminstaller öffnen")}},dismissButton={TextButton(onClick={apk=null}){Text("Abbrechen")}})}
    model.detail?.let{d->AlertDialog(onDismissRequest={model.detail=null},title={Text("GitHub-Details")},text={Text(d,Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={model.detail=null}){Text("Schließen")}})}
    installError?.let{d->AlertDialog(onDismissRequest={installError=null},title={Text("Hinweis")},text={Text(d)},confirmButton={TextButton(onClick={installError=null}){Text("OK")}})}
}
@Composable private fun BuildCard(b:Build,failure:()->Unit,open:()->Unit){
    Glass("BUILD #${b.number}"){
        Text(b.label,fontSize=21.sp,fontWeight=FontWeight.Bold,color=when{b.conclusion=="success"->Color(0xFF86D6AA);b.conclusion=="failure"->Color(0xFFFF9EAA);else->Purple})
        Text(b.name);Text("${b.branch} · ${b.sha.take(12)}",fontFamily=FontFamily.Monospace,fontSize=12.sp)
        val duration=runCatching{Duration.between(Instant.parse(b.started),if(b.status=="completed")Instant.parse(b.updated) else Instant.now()).seconds.coerceAtLeast(0)}.getOrDefault(0)
        Text("${duration/60} min ${duration%60} s${if(b.status!="completed")" · bisher" else ""}",fontSize=12.sp)
        Row{TextButton(onClick=open){Text("GitHub öffnen")};if(b.conclusion=="failure")TextButton(onClick=failure){Text("Fehlerdetails")}}
    }
}
