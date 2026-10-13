package org.dstretch.android

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a working rear camera (physical or emulator). */
@RunWith(AndroidJUnit4::class)
class CameraFlowTest {
    @get:Rule(order = 0) val permission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun captureGalleryViewerAndConfigurationChange() {
        val app = ApplicationProvider.getApplicationContext<DStretchApplication>()
        // Old private files must not be enumerated into a new process's session.
        assertTrue(app.session.photos.isEmpty())
        compose.onNodeWithContentDescription("No session photos yet").assertIsNotEnabled()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasContentDescription("Capture DStretch photo") and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Capture DStretch photo").performClick()
        compose.waitUntil(30_000) { app.session.photos.size == 1 && !app.session.busy }
        val saved = app.session.photos.single()
        assertTrue(saved.isFile)
        assertTrue(saved.length() > 0)
        assertFalse(saved.parentFile!!.listFiles()!!.any { it.name.endsWith("-original.jpg") || it.name.endsWith(".tmp") })
        compose.onNodeWithContentDescription("Open session photos, 1 photo").performClick()
        compose.onNodeWithText("Session photos").assertIsDisplayed()
        compose.onNodeWithText("1 photo").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Session photos").assertIsDisplayed()
        assertEquals(saved, app.session.photos.single())
        compose.onNodeWithContentDescription("Open processed photo").performClick()
        compose.onNodeWithContentDescription("Processed session photo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Session photos").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Open session photos, 1 photo").assertIsDisplayed()
    }
}
