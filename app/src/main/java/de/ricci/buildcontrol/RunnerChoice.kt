package de.ricci.buildcontrol

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A tap is the explicit instruction to save this project's runner choice. */
@Composable
fun RunnerChoice(mode:String?,verified:Boolean,enabled:Boolean,automatic:Boolean = false,onChoose:(String)->Unit){
    val pcLabel = if (automatic) "Automatisch" else "PC"
    Text(when {
        mode==null -> "Bitte kurz prüfen lassen"
        !verified -> "Zuletzt gespeichert: ${if(mode=="pc")pcLabel else "GitHub"} · noch nicht geprüft"
        else -> "Gespeichert: ${if(mode=="pc")pcLabel else "GitHub"}"
    },fontSize=20.sp,fontWeight=FontWeight.Bold)
    listOf("pc" to pcLabel,"github" to "GitHub").forEach{(target,label)->
        val active=verified&&mode==target
        Button(onClick={onChoose(target)},enabled=enabled&&!active,
            modifier=Modifier.fillMaxWidth().height(68.dp).semantics{selected=active},
            shape=RoundedCornerShape(18.dp),
            colors=if(active)ButtonDefaults.buttonColors(disabledContainerColor=MaterialTheme.colorScheme.primary,disabledContentColor=MaterialTheme.colorScheme.onPrimary) else ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)){
            Text(if(active)"✓ $label" else label,fontSize=20.sp,fontWeight=FontWeight.Bold)
        }
    }
}
