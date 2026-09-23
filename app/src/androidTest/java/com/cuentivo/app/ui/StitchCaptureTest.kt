package com.cuentivo.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.cuentivo.app.ui.transaction.NEW_SALE_LIST_TAG
import com.cuentivo.app.ui.transaction.PAYMENT_LIST_TAG
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.ui.account.SubscriptionContent
import com.cuentivo.app.ui.auth.AuthenticationScreen
import com.cuentivo.app.ui.dashboard.DashboardUiState
import com.cuentivo.app.ui.theme.CuentivoTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test

/**
 * Renders each redesigned screen at the supported widths and writes a PNG the reviewer can hold
 * next to the approved references in `design/stitch`.
 *
 * This exists because the screens behind Firebase Auth cannot be reached by an automated visual
 * pass without signing in. Driving `CuentivoApp` directly needs no account, no network, and no
 * Firestore, so the composition itself can still be inspected pixel by pixel.
 *
 * The fixtures below are test data for a test surface. They never reach the app: production
 * activity, balances, and customers all come from Room.
 */
class StitchCaptureTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun capture_dashboard_at_every_supported_width() {
        captureScreen("dashboard-320dp", 320) { CuentivoApp(populatedState(), CUSTOMERS) }
    }

    @Test fun capture_dashboard_360() {
        captureScreen("dashboard-360dp", 360) { CuentivoApp(populatedState(), CUSTOMERS) }
    }

    @Test fun capture_dashboard_412() {
        captureScreen("dashboard-412dp", 412) { CuentivoApp(populatedState(), CUSTOMERS) }
    }

    @Test fun capture_dashboard_480() {
        captureScreen("dashboard-480dp", 480) { CuentivoApp(populatedState(), CUSTOMERS) }
    }

    @Test fun capture_dashboard_empty_sections() {
        captureScreen("dashboard-empty-360dp", 360) {
            CuentivoApp(
                DashboardUiState.Content(
                    businessId = "business-1",
                    businessName = "Mi negocio",
                    receivableCents = 0,
                    soldCents = 0,
                    collectedCents = 0,
                    overdueCents = 0,
                    customersWithDebt = 0,
                ),
            )
        }
    }

    @Test fun capture_no_business_state() {
        captureScreen("no-business-360dp", 360) { CuentivoApp(DashboardUiState.NoBusiness) }
    }

    @Test fun capture_customers_320() = captureCustomers(320)

    @Test fun capture_customers_360() = captureCustomers(360)

    @Test fun capture_customers_412() = captureCustomers(412)

    @Test fun capture_customers_480() = captureCustomers(480)

    @Test fun capture_customers_empty() {
        captureScreen("customers-empty-360dp", 360) {
            CuentivoApp(state = populatedState(), customers = emptyList())
        }
        openCustomers()
        shoot("customers-empty-360dp")
    }

    @Test fun capture_customer_detail_320() = captureDetail(320)

    @Test fun capture_customer_detail_360() = captureDetail(360)

    @Test fun capture_customer_detail_412() = captureDetail(412)

    @Test fun capture_customer_detail_480() = captureDetail(480)

    @Test fun capture_products_320() = captureProducts(320)

    @Test fun capture_products_360() = captureProducts(360)

    @Test fun capture_products_412() = captureProducts(412)

    @Test fun capture_products_480() = captureProducts(480)

    @Test fun capture_products_empty() {
        captureScreen("products-empty-360dp", 360) {
            CuentivoApp(state = populatedState(), products = emptyList())
        }
        openDestination("Productos")
        shoot("products-empty-360dp")
    }

    @Test fun capture_new_sale_320() = captureNewSale(320)

    @Test fun capture_new_sale_360() = captureNewSale(360)

    @Test fun capture_new_sale_412() = captureNewSale(412)

    @Test fun capture_new_sale_480() = captureNewSale(480)

    /** The invalid state: the task just opened, nothing chosen, and registering is refused. */
    @Test fun capture_new_sale_empty() {
        captureScreen("new-sale-empty-360dp", 360) {
            CuentivoApp(state = populatedState(), customers = CUSTOMERS, products = SALE_PRODUCTS)
        }
        composeRule.onNodeWithContentDescription("Nueva venta").invokeClick()
        shoot("new-sale-empty-360dp")
    }

    @Test fun capture_payment_320() = capturePayment(320)

    @Test fun capture_payment_360() = capturePayment(360)

    @Test fun capture_payment_412() = capturePayment(412)

    @Test fun capture_payment_480() = capturePayment(480)

    /** The empty state: Abonos reached from the bar with nobody chosen and no balance to show. */
    @Test fun capture_payment_empty() {
        captureScreen("payment-empty-360dp", 360) {
            CuentivoApp(state = populatedState(), customers = CUSTOMERS)
        }
        openDestination("Abonos")
        shoot("payment-empty-360dp")
    }

    /**
     * A sale worth looking at: a customer chosen, two lines in the cart, a discount, and a due
     * date. One product name is deliberately long, because a cart that only ever holds short names
     * cannot show whether the amount survives at 320 dp.
     */
    private fun captureNewSale(widthDp: Int) {
        captureScreen("new-sale-${widthDp}dp", widthDp) {
            CuentivoApp(state = populatedState(), customers = CUSTOMERS, products = SALE_PRODUCTS)
        }
        composeRule.onNodeWithContentDescription("Nueva venta").invokeClick()
        chooseInSelector("selector-cliente", CUSTOMERS[0].customer.name)
        addToCart(SALE_PRODUCTS[0].name)
        addToCart(SALE_PRODUCTS[1].name)
        scrollSaleTo("Descuento de la venta (opcional)")
        composeRule.onNodeWithText("Descuento de la venta (opcional)").performTextInput("120")
        // Dismissed before the chip, not after: typing raises the soft keyboard, `imePadding`
        // correctly shrinks the task to sit above it, and a chip left underneath the keyboard
        // cannot be tapped at all. Getting the keyboard out of the way first also gives the
        // capture the whole composition instead of a screen half-owned by the IME.
        Espresso.closeSoftKeyboard()
        scrollSaleTo("15 días")
        composeRule.onNodeWithText("15 días").invokeClick()
        // T5 removed the obsolete "CAPTURA GUIADA" eyebrow; the actual task title is now the
        // stable top-of-screen anchor for returning to the beginning of the form.
        scrollSaleTo("Nueva venta")
        shoot("new-sale-${widthDp}dp")
    }

    /** Abonos with the largest debtor chosen and an amount typed, so both figures are on screen. */
    private fun capturePayment(widthDp: Int) {
        captureScreen("payment-${widthDp}dp", widthDp) {
            CuentivoApp(state = populatedState(), customers = CUSTOMERS)
        }
        openDestination("Abonos")
        chooseInSelector("selector-cliente", CUSTOMERS[0].customer.name)
        composeRule.onNodeWithText("Monto del abono").performTextInput("450")
        Espresso.closeSoftKeyboard()
        // The old uppercase movement eyebrow was removed; the task title is still a stable anchor
        // in the real payment form and returns this capture to the top after the keyboard closes.
        composeRule.onNodeWithTag(PAYMENT_LIST_TAG).performScrollToNode(hasText("Registrar abono"))
        shoot("payment-${widthDp}dp")
    }

    private fun chooseInSelector(tag: String, option: String) {
        composeRule.onNodeWithTag(tag).invokeClick()
        composeRule.onNodeWithText(option).invokeClick()
        composeRule.waitForIdle()
    }

    private fun addToCart(productName: String) {
        chooseInSelector("selector-producto", productName)
        scrollSaleTo("Agregar al carrito")
        composeRule.onNodeWithText("Agregar al carrito").invokeClick()
        composeRule.waitForIdle()
    }

    private fun scrollSaleTo(text: String) {
        composeRule.onNodeWithTag(NEW_SALE_LIST_TAG).performScrollToNode(hasText(text))
    }

    @Test fun capture_authentication_sign_in_320() = captureSignIn(320)

    @Test fun capture_authentication_sign_in_360() = captureSignIn(360)

    @Test fun capture_authentication_sign_in_412() = captureSignIn(412)

    @Test fun capture_authentication_sign_in_480() = captureSignIn(480)

    /** The other mode, with the confirmation field and a mismatch showing its inline error. */
    @Test fun capture_authentication_create() {
        captureScreen("authentication-create-360dp", 360) { SignInScreen() }
        composeRule.onNodeWithText("¿No tienes cuenta? Crear una").invokeClick()
        composeRule.onNodeWithText("Correo electrónico").performTextInput("ana@ejemplo.mx")
        composeRule.onNodeWithText("Contraseña").performTextInput("claveSegura8")
        composeRule.onNodeWithText("Confirma tu contraseña").performTextInput("otraClave")
        Espresso.closeSoftKeyboard()
        shoot("authentication-create-360dp")
    }

    /**
     * The alert list, rendered on its own rather than inside the sheet.
     *
     * `AlertsSheet` is a `ModalBottomSheet` and therefore lives in a window of its own, so a
     * capture keyed on [CAPTURE_TAG] would come back showing the screen underneath with no sheet in
     * it. What this validates is the shared content the sheet actually renders — the rows, the
     * severity eyebrows and the accents — not the modal window around it.
     */
    @Test fun capture_alerts_content() {
        captureScreen("alerts-content-360dp", 360) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                AlertsContent(alerts = ALERTS, onOpenAlert = {})
            }
        }
    }

    /** Same shared content, with nothing to report. */
    @Test fun capture_alerts_empty_content() {
        captureScreen("alerts-empty-content-360dp", 360) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                AlertsContent(alerts = emptyList(), onOpenAlert = {})
            }
        }
    }

    /** The plan section, captured directly for the same reason: Cuenta is itself a modal sheet. */
    @Test fun capture_subscription_content() {
        captureScreen("subscription-content-360dp", 360) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.padding(NeoSpacing.lg)) {
                    SubscriptionContent(tier = SubscriptionTier.FREE, onUpgrade = {})
                }
            }
        }
    }

    private fun captureSignIn(widthDp: Int) {
        captureScreen("authentication-sign-in-${widthDp}dp", widthDp) { SignInScreen() }
        composeRule.onNodeWithText("Correo electrónico").performTextInput("ana@ejemplo.mx")
        composeRule.onNodeWithText("Contraseña").performTextInput("claveSegura8")
        Espresso.closeSoftKeyboard()
        shoot("authentication-sign-in-${widthDp}dp")
    }

    /** The real screen; only the two callbacks are inert, since no capture may reach Firebase. */
    @Composable
    private fun SignInScreen() {
        AuthenticationScreen(
            isWorking = false,
            message = null,
            onSignIn = { _, _ -> },
            onSignUp = { _, _ -> },
        )
    }

    private fun captureProducts(widthDp: Int) {
        // A real photo, not the placeholder: the rounded corners are only wrong when something is
        // actually drawn inside the thumbnail, which is how the missing clip went unnoticed.
        val photo = writeSolidPhoto()
        captureScreen("products-${widthDp}dp", widthDp) {
            CuentivoApp(
                state = populatedState(),
                products = listOf(PRODUCTS[0].copy(imageUri = photo)) + PRODUCTS.drop(1),
            )
        }
        openDestination("Productos")
        shoot("products-${widthDp}dp")
    }

    /**
     * Fills a bitmap edge to edge with one bright colour. If the thumbnail clips, the corners of
     * the capture stay graphite; if it does not, they come out bright.
     */
    private fun writeSolidPhoto(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "product-images").apply { mkdirs() }
        val name = "capture-thumbnail.jpg"
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(0, 229, 255))
        FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return name
    }

    private fun captureCustomers(widthDp: Int) {
        captureScreen("customers-${widthDp}dp", widthDp) {
            CuentivoApp(state = populatedState(), customers = CUSTOMERS)
        }
        openCustomers()
        shoot("customers-${widthDp}dp")
    }

    /**
     * The detail is driven by the host, so the capture supplies it the same way production does
     * rather than trying to click through to it.
     */
    private fun captureDetail(widthDp: Int) {
        captureScreen("customer-detail-${widthDp}dp", widthDp) {
            CuentivoApp(
                state = populatedState(),
                customers = CUSTOMERS,
                statementCustomer = CUSTOMERS[0],
                statement = STATEMENT,
                cancelables = CANCELABLES,
            )
        }
    }

    private fun openCustomers() = openDestination("Clientes")

    private fun openDestination(label: String) {
        composeRule.onNodeWithContentDescription(label).invokeClick()
        composeRule.waitForIdle()
    }

    private companion object {
        const val CAPTURE_TAG = "capture-root"
        // A real 360 dp phone is about 800 dp tall and the Medium_Phone AVD is 914 dp. Capturing
        // shorter than that clips content the device would actually show and makes the comparison
        // against the reference dishonest.
        const val CAPTURE_HEIGHT_DP = 900

        // The shop itself lives in CaptureFixtures, shared with PlayStoreCaptureTest so the store
        // screenshots and the design references show the same business.
        val CUSTOMERS = CaptureFixtures.CUSTOMERS
        val PRODUCTS = CaptureFixtures.PRODUCTS
        val ALERTS = CaptureFixtures.ALERTS
        val SALE_PRODUCTS = CaptureFixtures.SALE_PRODUCTS
        val STATEMENT = CaptureFixtures.STATEMENT
        val CANCELABLES = CaptureFixtures.CANCELABLES
    }

    private fun populatedState() = CaptureFixtures.populatedState()

    /** Renders at an exact dp width so the capture matches the reference's phone proportions. */
    private fun captureScreen(name: String, widthDp: Int, content: @Composable () -> Unit) {
        composeRule.setContent {
            Box(
                Modifier
                    .testTag(CAPTURE_TAG)
                    .requiredWidth(widthDp.dp)
                    .requiredHeight(CAPTURE_HEIGHT_DP.dp),
            ) {
                CuentivoTheme { content() }
            }
        }
        composeRule.waitForIdle()
        val bitmap = captureStableImage()
        write(name, bitmap)
    }

    /** Re-captures the current composition, e.g. after navigating into a destination. */
    private fun shoot(name: String) {
        composeRule.waitForIdle()
        write(name, captureStableImage())
    }

    /**
     * PixelCopy is an external compositor request. On this AVD it can reject an otherwise idle
     * frame while the IME finishes hiding; retry only after Compose reports idle again. A PNG is
     * still written only after PixelCopy returns a real bitmap.
     */
    private fun captureStableImage(): Bitmap {
        var lastFailure: AssertionError? = null
        repeat(3) {
            composeRule.waitForIdle()
            try {
                return composeRule.onNodeWithTag(CAPTURE_TAG).captureToImage().asAndroidBitmap()
            } catch (failure: AssertionError) {
                lastFailure = failure
            }
        }
        throw checkNotNull(lastFailure)
    }

    /**
     * Invokes a control's own click action instead of touching it.
     *
     * A tap emits a press interaction, and the ripple that follows is driven by real frames — not
     * by composition work and not by the test clock. So neither `waitForIdle` nor advancing the
     * virtual clock ends it, and a control clicked immediately before a shot keeps its indication
     * in the picture: that is the rectangle that sat behind "Abonos" in `payment-empty-360dp`, and
     * the square patch the "15 días" chip's 48 dp box printed around the rounded pill it paints.
     *
     * There is nothing to wait for once the press never happens. The semantics action performs the
     * same state change the tap would have, so the next frame is already the stable state. Real tap
     * handling is covered by the flow suites, which is where it belongs.
     */
    private fun SemanticsNodeInteraction.invokeClick(): SemanticsNodeInteraction =
        performSemanticsAction(SemanticsActions.OnClick)

    private fun write(name: String, bitmap: Bitmap) {
        // Internal storage on purpose: since Android 11 the shell user cannot read another
        // package's /sdcard/Android/data, so an external capture is invisible to `adb pull`.
        // `adb exec-out run-as com.cuentivo.app cat files/stitch-captures/<name>.png` reads these.
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "stitch-captures",
        )
        dir.mkdirs()
        try {
            FileOutputStream(File(dir, "$name.png")).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
