package app.recipebox

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class AppScreenshotTest : UiTest() {
    @Test
    fun appLight() = runComposeUiTest {
        setContent { App(darkTheme = false) }
        onRoot().captureRoboImage("src/androidHostTest/screenshots/app_light.png")
    }

    @Test
    fun appDark() = runComposeUiTest {
        setContent { App(darkTheme = true) }
        onRoot().captureRoboImage("src/androidHostTest/screenshots/app_dark.png")
    }
}
