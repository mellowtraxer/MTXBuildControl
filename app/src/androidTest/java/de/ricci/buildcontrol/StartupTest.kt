package de.ricci.buildcontrol

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @After fun clearClipboard(){compose.activity.runOnUiThread{(compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).clearPrimaryClip()}}
    @Test fun coldStartOffersGuidedConnectionInsteadOfTokenForm(){
        compose.onNodeWithText("GITHUB VERBINDEN").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("GitHub-Freigabe öffnen").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Von GitHub kopierter Code").assertDoesNotExist()
    }
    @Test fun sleepSyncIsSelectableBeforeLoginAndSurvivesRecreation(){
        compose.onNodeWithText("SleepSync").performScrollTo().performClick()
        compose.onNodeWithText("SleepSync").assertIsSelected()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("SleepSync").assertIsSelected()
        compose.onNodeWithText("SleepSync ist vorbereitet").performScrollTo().assertIsDisplayed()
    }
    @Test fun invalidClipboardGetsUsefulHelpWithoutConnecting(){
        compose.activity.runOnUiThread{(compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("test","GarminSleepTasker"))}
        compose.onNodeWithText("Einfügen & verbinden").performScrollTo().performClick()
        compose.onNodeWithText("Bitte auf GitHub zuerst den erzeugten Code kopieren. Er beginnt normalerweise mit github_pat_. Danach hier erneut tippen.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("GITHUB VERBINDEN").assertExists()
    }
}
