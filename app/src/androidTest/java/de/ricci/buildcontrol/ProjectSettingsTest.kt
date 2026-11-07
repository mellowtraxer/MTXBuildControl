package de.ricci.buildcontrol

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProjectSettingsTest {
    @get:Rule val compose=createComposeRule()
    @Test fun confirmedChoicesSurviveNewStoreAndStayIndependent(){
        val context=ApplicationProvider.getApplicationContext<Context>()
        val first=Project("test/mtx","studio","MTX")
        val second=Project("test/sleep","compose","Sleep")
        val otherBranch=first.copy(branch="other")
        try {
            Store(context).apply{saveRunnerMode(first,"pc");saveRunnerMode(second,"github")}
            val reopened=Store(context)
            assertEquals("pc",reopened.runnerMode(first))
            assertEquals("github",reopened.runnerMode(second))
            assertNull(reopened.runnerMode(otherBranch))
            reopened.saveRunnerMode(second,"pc")
            assertEquals("pc",Store(context).runnerMode(first))
            assertEquals("pc",Store(context).runnerMode(second))
            reopened.saveRunnerMode(otherBranch,"github")
            assertEquals("pc",Store(context).runnerMode(first))
            assertEquals("github",Store(context).runnerMode(otherBranch))
            reopened.logout()
            assertEquals("pc",Store(context).runnerMode(first))
        } finally {
            // Tests use isolated project keys and preserve normal app projects.
            val prefs=context.getSharedPreferences("mtx",Context.MODE_PRIVATE)
            prefs.edit().also{edit->prefs.all.keys.filter{it.startsWith("runner:")&&it.contains("test/")}.forEach{edit.remove(it)}}.commit()
        }
    }
    @Test fun choosingAnotherRunnerCallsSaveOnceWithoutExtraDialog(){
        val choices=mutableListOf<String>()
        compose.setContent{MaterialTheme{Column{RunnerChoice("pc",true,true){choices.add(it)}}}}
        compose.onNodeWithText("✓ PC").assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithText("GitHub").performClick()
        compose.runOnIdle{assertEquals(listOf("github"),choices)}
        compose.onNodeWithText("Runner wirklich umschalten?").assertDoesNotExist()
    }
    @Test fun afterUncertainWriteBothChoicesCanBeRetriedWithoutRestart(){
        val choices=mutableListOf<String>()
        compose.setContent{MaterialTheme{Column{RunnerChoice("pc",false,true){choices.add(it)}}}}
        compose.onNodeWithText("GitHub").assertIsEnabled().performClick()
        compose.onNodeWithText("PC").assertIsEnabled().performClick()
        compose.runOnIdle{assertEquals(listOf("github","pc"),choices)}
    }
    @Test fun cachedChoiceIsNotClaimedActiveBeforeVerification(){
        compose.setContent{MaterialTheme{Column{RunnerChoice("pc",false,false){error("Must not write before verification")}}}}
        compose.onNodeWithText("Zuletzt gespeichert: PC · noch nicht geprüft").assertExists()
        compose.onNodeWithText("PC").assertIsNotSelected().assertIsNotEnabled()
        compose.onNodeWithText("GitHub").assertIsNotEnabled()
    }
}
