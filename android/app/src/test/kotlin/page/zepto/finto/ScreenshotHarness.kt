package page.zepto.finto

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import page.zepto.finto.core.designsystem.theme.FintoTheme
import page.zepto.finto.ui.TopLevelDestination

/**
 * Renders [content] in the app theme, inside the navigation bar with [tab]
 * selected when one is given, and records it as `build/outputs/roborazzi/<name>.png`.
 *
 * Screenshot classes use `@RunWith(RobolectricTestRunner::class)`,
 * `@GraphicsMode(GraphicsMode.Mode.NATIVE)` and
 * `@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")`, as [ScreenshotTest] does.
 */
fun ComposeContentTestRule.captureScreen(
    name: String,
    dark: Boolean = false,
    tab: TopLevelDestination? = null,
    content: @Composable () -> Unit,
) {
    setContent {
        FintoTheme(darkTheme = dark, dynamicColor = false) {
            if (tab == null) {
                Surface(Modifier.fillMaxSize()) { content() }
            } else {
                NavigationSuiteScaffold(
                    navigationSuiteItems = {
                        TopLevelDestination.entries.forEach { destination ->
                            item(
                                selected = destination == tab,
                                onClick = {},
                                icon = { Icon(if (destination == tab) destination.selectedIcon else destination.icon, null) },
                                label = { Text(destination.label) },
                            )
                        }
                    },
                ) { content() }
            }
        }
    }
    waitForIdle()
    onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
}
