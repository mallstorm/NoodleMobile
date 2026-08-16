package pro.noodle.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mainShellStarts() {
        compose.onNodeWithText("Noodle Mobile").assertIsDisplayed()
        compose.onNodeWithText("Поиск").assertIsDisplayed()
        compose.onNodeWithText("Загрузки").assertIsDisplayed()
    }
}
