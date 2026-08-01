package com.cuentivo.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
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
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.domain.DashboardActivity
import com.cuentivo.app.domain.DueSale
import com.cuentivo.app.domain.StatementEntry
import com.cuentivo.app.ui.dashboard.DashboardUiState
import com.cuentivo.app.ui.theme.CuentivoTheme
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
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
        scrollSaleTo("CAPTURA GUIADA")
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
        composeRule.onNodeWithTag(PAYMENT_LIST_TAG).performScrollToNode(hasText("MOVIMIENTO ENTRANTE"))
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
        composeRule.onNodeWithText(label).invokeClick()
        composeRule.waitForIdle()
    }

    private companion object {
        const val CAPTURE_TAG = "capture-root"
        // A real 360 dp phone is about 800 dp tall and the Medium_Phone AVD is 914 dp. Capturing
        // shorter than that clips content the device would actually show and makes the comparison
        // against the reference dishonest.
        const val CAPTURE_HEIGHT_DP = 900

        val CUSTOMERS = listOf(
            balance("ramirez", "Distribuidora Ramírez", 150_000),
            balance("todotexto", "Librería Todo Texto", 98_000),
            balance("martinez", "Minimarket Martínez", 75_000),
            balance("centro", "Farmacia del Centro", 24_000),
        )

        val PRODUCTS = listOf(
            product("p1", "Café molido 500 g", 12_500, "CAF-500", "Abarrotes"),
            product("p2", "Aceite vegetal 1 L", 4_800, "ACE-1L", "Abarrotes"),
        )

        /**
         * The first name is long on purpose: the cart row has to prove that the amount still fits
         * beside it at 320 dp, and a catalog of tidy short names would never show that.
         */
        val SALE_PRODUCTS = listOf(
            product("s1", "Café molido premium de altura 500 g", 12_500, "CAF-500", "Abarrotes"),
            product("s2", "Aceite vegetal 1 L", 4_800, "ACE-1L", "Abarrotes"),
        )

        val STATEMENT = listOf(
            StatementEntry("2026-07-14", "CHARGE", "DEBIT", 150_000, "Pedido semanal"),
            StatementEntry("2026-07-21", "PAYMENT", "CREDIT", 25_000, null),
        )

        val CANCELABLES = listOf(
            CancelableItem("s1", CancelableItem.Kind.SALE, "Venta del 14 jul", "Pedido semanal", 150_000),
            CancelableItem("p1", CancelableItem.Kind.PAYMENT, "Abono del 21 jul", "Efectivo", 25_000),
        )

        fun balance(id: String, name: String, cents: Long) = CustomerBalanceRow(
            customer = CustomerEntity(
                id = id,
                businessId = "business-1",
                name = name,
                normalizedName = name.lowercase(),
                phone = "55 0000 0000",
                email = null,
                address = null,
                notes = null,
                creditLimitCents = null,
                isActive = true,
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 1,
                deletedAtEpochMillis = null,
            ),
            balanceCents = cents,
        )

        fun product(id: String, name: String, cents: Long, sku: String, category: String) = ProductEntity(
            id = id,
            businessId = "business-1",
            name = name,
            description = null,
            sku = sku,
            category = category,
            salePriceCents = cents,
            costPriceCents = null,
            stock = null,
            imageUri = null,
            isActive = true,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
            deletedAtEpochMillis = null,
        )
    }

    private fun populatedState(): DashboardUiState.Content {
        val today = LocalDate.now().toEpochDay()
        return DashboardUiState.Content(
            businessId = "business-1",
            businessName = "Mi negocio",
            receivableCents = 4_250_000,
            soldCents = 6_100_000,
            collectedCents = 1_850_000,
            overdueCents = 345_000,
            customersWithDebt = 4,
            dueTodayCents = 128_000,
            dueSales = listOf(
                DueSale("s1", "ramirez", "Distribuidora Ramírez", today - 15, 150_000),
                DueSale("s2", "todotexto", "Librería Todo Texto", today, 98_000),
                DueSale("s3", "martinez", "Minimarket Martínez", today, 75_000),
            ),
            recentActivity = listOf(
                activity("a1", "centro", "Farmacia del Centro", "PAYMENT", "CREDIT", 25_000, 1),
                activity("a2", "alpes", "Bodega Los Alpes", "CHARGE", "DEBIT", 42_000, 26),
                activity("a3", "martinez", "Minimarket Martínez", "PAYMENT", "CREDIT", 18_000, 30),
                activity("a4", "ramirez", "Distribuidora Ramírez", "REFUND", "DEBIT", 6_000, 40),
                activity("a5", "todotexto", "Librería Todo Texto", "CANCELLATION", "CREDIT", 9_000, 50),
            ),
        )
    }

    private fun activity(
        id: String,
        customerId: String,
        customerName: String,
        type: String,
        direction: String,
        cents: Long,
        hoursAgo: Long,
    ) = DashboardActivity(
        id = id,
        customerId = customerId,
        customerName = customerName,
        type = type,
        direction = direction,
        amountCents = cents,
        occurredAtEpochMillis = System.currentTimeMillis() - hoursAgo * 3_600_000L,
    )

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
        val bitmap = composeRule.onNodeWithTag(CAPTURE_TAG).captureToImage().asAndroidBitmap()
        write(name, bitmap)
    }

    /** Re-captures the current composition, e.g. after navigating into a destination. */
    private fun shoot(name: String) {
        composeRule.waitForIdle()
        write(name, composeRule.onNodeWithTag(CAPTURE_TAG).captureToImage().asAndroidBitmap())
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
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
