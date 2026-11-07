package de.ricci.buildcontrol

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ConnectionWizard(project: Project, projects: List<Project>, busy: Boolean, message: String, open: (String)->Unit, connect: (String)->Unit) {
    val context=LocalContext.current
    var manual by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    val repositories=GitHubSetup.repositories(project,projects)
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFF141218)),border=BorderStroke(1.dp,Color.White.copy(alpha=.08f))) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("GITHUB VERBINDEN",color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold,fontSize=13.sp)
            Text("${project.name} ist vorbereitet",fontSize=24.sp,fontWeight=FontWeight.Bold)
            Text("Einmal GitHub freigeben. Danach kannst du deine Projekte direkt hier auswählen und den Runner wechseln.")
            Text("1 · Freigabe auf GitHub öffnen",fontWeight=FontWeight.Bold)
            Text("Name, Konto, Gültigkeit und benötigte Rechte sind bereits eingetragen. Die Freigabe gilt 90 Tage.",fontSize=13.sp)
            Button(onClick={pasteError=null;opened=true;open(GitHubSetup.authorizationUrl(project))},enabled=!busy,modifier=Modifier.fillMaxWidth()) {
                Text(if(opened)"GitHub-Freigabe erneut öffnen" else "GitHub-Freigabe öffnen")
            }
            if(!opened) {
                RepositoryGrantSteps(repositories)
            }
            Text(if(opened)"Zurück von GitHub?" else "3 · Code übernehmen und verbinden",fontWeight=FontWeight.Bold)
            if(opened)Text("Den gerade auf GitHub kopierten Code kannst du hier mit einem Tipp übernehmen.",fontSize=13.sp)
            Button(onClick={
                val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip=clipboard.primaryClip
                val copied=if(clip!=null&&clip.itemCount>0) clip.getItemAt(0).text?.toString().orEmpty() else ""
                val value=GitHubSetup.token(copied)
                if(value==null) pasteError="Bitte auf GitHub zuerst den erzeugten Code kopieren. Er beginnt normalerweise mit github_pat_. Danach hier erneut tippen."
                else {pasteError=null;connect(value)}
            },enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
                Text(if(busy)"Verbindung wird geprüft …" else "Einfügen & verbinden")
            }
            pasteError?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp)}
            if(message.isNotBlank())Text(message,color=MaterialTheme.colorScheme.error,fontSize=13.sp)
            if(opened){
                TextButton(onClick={help=!help}){Text(if(help)"GitHub-Hilfe schließen" else "Was muss ich auf GitHub auswählen?")}
                if(help){
                    RepositoryGrantSteps(repositories)
                }
            }
            Text("Der Code wird verschlüsselt auf deinem Handy gespeichert. Laufende Builds bleiben beim Anmelden unverändert.",fontSize=12.sp)
            TextButton(onClick={manual=!manual},enabled=!busy){Text(if(manual)"Manuelle Eingabe schließen" else "Code manuell einfügen")}
            if(manual){
                OutlinedTextField(token,{token=it;pasteError=null},Modifier.fillMaxWidth(),label={Text("Von GitHub kopierter Code")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
                Button(onClick={
                    val value=GitHubSetup.token(token)
                    if(value==null)pasteError="Hier den von GitHub erzeugten Code einfügen (github_pat_…)."
                    else {pasteError=null;token="";connect(value)}
                },enabled=!busy&&token.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Verbinden")}
            }
        }
    }
}

@Composable
private fun RepositoryGrantSteps(repositories: List<Pair<String,String>>) {
    Text("2 · Auf GitHub diese Projekte auswählen",fontWeight=FontWeight.Bold)
    Text("Bei „Repository access“ auf „Only select repositories“ tippen. GitHub zeigt dafür folgende Namen:",fontSize=13.sp)
    repositories.forEach{(name,repo)->Text("$name → $repo",fontSize=13.sp)}
    Text("Unten „Generate token“ antippen und den angezeigten Code mit dem Kopiersymbol kopieren. Anschließend zu dieser App zurückkehren.",fontSize=13.sp)
}
