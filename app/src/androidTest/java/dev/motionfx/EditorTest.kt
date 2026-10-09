package dev.motionfx

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

class EditorTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun launchAddLayerAndInspectProperties() {
        compose.waitUntil(15000) { compose.onAllNodesWithText("Saved locally").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("+ Text").performClick()
        compose.onNodeWithText("Properties").performClick()
        compose.onNodeWithText("Layer name").assertIsDisplayed()
        compose.onNodeWithText("Timeline").performClick()
        compose.onNodeWithText("+ Shape").performClick()
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val screenshot=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.getExternalFilesDir(null),"editor.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG,100,it))
            }
        } finally { screenshot.recycle() }
    }
}
