package de.ricci.buildcontrol
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class StartupTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun coldStartShowsRealConnectionScreen(){compose.onNodeWithText("GITHUB VERBINDEN").assertIsDisplayed();compose.onNodeWithText("Sicher verbinden").assertIsDisplayed()}
}
