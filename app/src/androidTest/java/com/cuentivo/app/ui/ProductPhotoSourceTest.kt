package com.cuentivo.app.ui

import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.cuentivo.app.R
import com.cuentivo.app.ui.product.CameraCaptureStore
import com.cuentivo.app.ui.dashboard.DashboardUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProductPhotoSourceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun source_selector_offers_camera_gallery_and_cancel_without_changing_form() {
        val created = mutableListOf<String>()
        composeRule.setContent {
            CuentivoApp(
                state = DashboardUiState.Content("business-1", "Mi negocio", 0, 0, 0, 0, 0),
                onCreateProduct = { name, _, _ -> created += name },
            )
        }

        composeRule.onNodeWithTag("main-destination-products").performClick()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithContentDescription(context.getString(R.string.products_add_description)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.product_photo_add)).performClick()
        composeRule.onNodeWithTag("product-photo-source-sheet").assertIsDisplayed()
        listOf("product-photo-source-camera", "product-photo-source-gallery", "product-photo-source-cancel")
            .forEach { tag ->
                composeRule.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            }

        composeRule.onNodeWithTag("product-photo-source-cancel").performClick()
        composeRule.onAllNodesWithTag("product-photo-source-sheet").assertCountEquals(0)
        composeRule.runOnIdle { assertEquals(emptyList<String>(), created) }
    }

    /**
     * The real FileProvider round trip, which no JVM test can cover: it proves the manifest
     * authority and the `name` attribute in `product_camera_paths.xml` still line up with what
     * [CameraCaptureStore] expects. If they drift, capture files stop being deletable.
     */
    @Test fun capture_uri_is_readable_and_deletable_while_foreign_uris_are_refused() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CameraCaptureStore(context)

        val uri = store.createCaptureUri()
        context.contentResolver.openInputStream(uri).use { assertTrue(it != null) }

        assertFalse(store.delete(Uri.parse("content://com.other.app.fileprovider/product-camera/x.jpg")))
        assertFalse(store.delete(Uri.parse("file:///data/local/tmp/x.jpg")))
        assertFalse(store.delete(uri.buildUpon().path("/other-dir/x.jpg").build()))

        assertTrue(store.delete(uri))
        assertFalse(store.delete(uri))
    }

    /** An orphan from a dialog that never came back is swept, the in-flight capture is not. */
    @Test fun stale_sweep_keeps_the_capture_still_awaiting_its_result() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CameraCaptureStore(context)
        val orphan = store.createCaptureUri()
        val inFlight = store.createCaptureUri()

        store.deleteStale(except = inFlight)

        context.contentResolver.openInputStream(inFlight).use { assertTrue(it != null) }
        assertFalse(store.delete(orphan))
        assertTrue(store.delete(inFlight))
    }
}
