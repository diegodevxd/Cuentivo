package com.cuentivo.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Rotating the phone mid-review must not cost the review.
 *
 * The host [LedgerLabHostActivity] has no configChanges, so rotating it recreates the Activity
 * through the real saved-state machinery — the same mechanism the production app goes through on
 * process death and uiMode changes, which its manifest cannot shield it from. The callable is a
 * double that counts its own invocations: the defect this phase removes is the review coming back
 * empty and the only way forward being to read the page again, which is a second paid call.
 * After the rotation the count must still be one.
 */
@RunWith(AndroidJUnit4::class)
class LedgerReviewRotationTest {

    @get:Rule val composeRule = createAndroidComposeRule<LedgerLabHostActivity>()

    private var scenario: ActivityScenario<LedgerLabHostActivity>? = null
    private var imageFile: File? = null

    @Before
    fun setUp() {
        LedgerLabHostActivity.extractionCalls = 0
        // A tiny real JPEG served by the app's own FileProvider — the same one production uses
        // for camera captures — so the gallery path runs end to end: copy, prepare, callable.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cameraDir = File(context.cacheDir, "ai-camera").apply { mkdirs() }
        imageFile = File(cameraDir, "test-page.jpg").apply { writeBytes(tinyJpeg()) }
        LedgerLabHostActivity.pickedUri = android.net.Uri.parse(
            "content://" + context.packageName + ".fileprovider/ai-camera/" + imageFile!!.name,
        )
    }

    @After
    fun tearDown() {
        scenario?.close()
        imageFile?.delete()
    }

    @Test
    fun rotating_mid_review_keeps_the_operations_and_never_calls_the_callable_again() {
        // Open the import and pick from the gallery: the stubbed picker answers immediately, the
        // image is copied and prepared, and the callable double reads the page.
        composeRule.onNodeWithTag("ai-ledger-import-entry").performClick()
        composeRule.onNodeWithTag("ai-ledger-source-gallery").performClick()

        // The review is on screen with both rows.
        awaitReviewWithBothRows()
        composeRule.runOnIdle { assertEquals("the callable ran exactly once for this page", 1, LedgerLabHostActivity.extractionCalls) }

        // Rotate. The host has no configChanges, so this recreates the Activity for real.
        val activityBefore = composeRule.activity
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        // The review is still there, with both rows, and the callable was not called again.
        awaitReviewWithBothRows()
        composeRule.runOnIdle {
            assertNotEquals(
                "the rotation must recreate the Activity for this test to mean anything",
                activityBefore,
                composeRule.activity,
            )
            assertEquals(
                "restoring the review must not read the page again: the callable is paid",
                1,
                LedgerLabHostActivity.extractionCalls,
            )
        }
    }

    /**
     * The header (summary, page date, due date) scrolls with the rows, so in landscape it fills the
     * screen and the rows are below the fold. A lazy list does not compose what is off screen, so
     * waiting for a row without scrolling waits forever: scroll to each row the way a person would.
     */
    private fun awaitReviewWithBothRows() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("ledger-review-list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("ledger-review-list").performScrollToNode(hasTestTag("ledger-op-row-0"))
        composeRule.onNodeWithTag("ledger-op-row-0").assertExists()
        composeRule.onNodeWithTag("ledger-review-list").performScrollToNode(hasTestTag("ledger-op-row-1"))
        composeRule.onNodeWithTag("ledger-op-row-1").assertExists()
    }

    /** A 1x1 JPEG, small enough to pass every cap, real enough for the decoder and the preparer. */
    private fun tinyJpeg(): ByteArray {
        val stream = ByteArrayOutputStream()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }
}
