package com.cuentivo.app.ui.product

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cuentivo.app.R
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.data.local.ProductImageStore
import com.cuentivo.app.domain.Money
import com.cuentivo.app.ui.FormDialog
import com.cuentivo.app.ui.MoneyField
import com.cuentivo.app.ui.NeoAction
import com.cuentivo.app.ui.NeoFintechLayoutPolicy
import com.cuentivo.app.ui.NeoEmptyState
import com.cuentivo.app.ui.NeoMoney
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.NeoSpacing
import com.cuentivo.app.ui.pesosToCents
import java.util.UUID

/** Name plus one secondary line, matching the density of the customer directory. */
private val CATALOG_ROW_HEIGHT = 72.dp

const val PRODUCTS_LIST_TAG = "products-list"

/**
 * The compact product catalog.
 *
 * Visual direction comes from the OpenDesign `productos` screen, adapted to the Neo Fintech system
 * already in Compose. The prototype's "Compartir catálogo" button is deliberately absent: no such
 * capability exists in this app, and shipping a control that does nothing is worse than omitting it.
 *
 * Behaviour is carried over untouched — the wide-open MIME filter that lets both CSV and XLSX
 * through, the money parser behind the price field, photo storage, and every callback.
 */
@Composable
fun ProductsScreen(
    products: List<ProductEntity>,
    modifier: Modifier = Modifier,
    showForm: Boolean = false,
    onShowFormChange: (Boolean) -> Unit = {},
    onCreateProduct: (String, Long, String?) -> Unit = { _, _, _ -> },
    onEditProduct: (ProductEntity, String, Long, String?) -> Unit = { _, _, _, _ -> },
    onArchiveProduct: (ProductEntity) -> Unit = {},
    onImportProducts: (Uri) -> Unit = {},
    /**
     * False when the plan does not include the spreadsheet feature. The check lives here, before
     * the file picker, because deciding it afterwards made people choose a file and only then meet
     * the paywall.
     */
    canImportProducts: Boolean = true,
    onImportBlocked: () -> Unit = {},
    /**
     * Owned by whoever actually launches the import, so it also falls back to false when the plan
     * blocks it, there is no business, or the file cannot be read — none of which produce a report.
     */
    isImportingProducts: Boolean = false,
    /**
     * Whether a create is in flight. The host closes the form itself once the write succeeds — see
     * `CuentivoApp`'s `productSavedToken` — so this screen only disables Guardar while it is true,
     * which is the double-tap guard.
     */
    isSaving: Boolean = false,
) {
    val productsAddDescription = stringResource(R.string.products_add_description)
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ProductEntity?>(null) }
    var archiving by remember { mutableStateOf<ProductEntity?>(null) }
    val context = LocalContext.current
    val imageStore = remember(context) { ProductImageStore(context) }

    // Wide-open MIME list: phones and cloud drives label .csv and .xlsx inconsistently, and a
    // strict filter greys out the very file the person came to pick.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(onImportProducts)
    }

    val visible = products.filter {
        query.isBlank() ||
            it.name.contains(query, ignoreCase = true) ||
            it.sku?.contains(query, ignoreCase = true) == true
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val layout = NeoFintechLayoutPolicy.forWidth(maxWidth.value.toInt())
        LazyColumn(
            modifier = Modifier
                .widthIn(max = layout.contentMaxWidthDp.dp)
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .testTag(PRODUCTS_LIST_TAG),
            contentPadding = PaddingValues(
                start = layout.horizontalPaddingDp.dp,
                top = NeoSpacing.md,
                end = layout.horizontalPaddingDp.dp,
                bottom = NeoSpacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.products_eyebrow),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(stringResource(R.string.products_title), style = MaterialTheme.typography.titleLarge)
                    }
                    // The bottom bar's central + belongs to Nueva venta, so adding a product needs
                    // its own control rather than competing for that one.
                    IconButton(
                        onClick = { onShowFormChange(true) },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = productsAddDescription,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            item {
                NeoAction(
                    label = if (isImportingProducts) stringResource(R.string.products_importing) else stringResource(R.string.products_import),
                    onClick = {
                        if (canImportProducts) importLauncher.launch(arrayOf("*/*")) else onImportBlocked()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isImportingProducts,
                    primary = false,
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.products_search_placeholder), style = MaterialTheme.typography.bodyLarge)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    singleLine = true,
                    shape = NeoShapes.row,
                    textStyle = MaterialTheme.typography.bodyLarge,
                )
            }
            if (visible.isEmpty()) {
                item {
                    val pristine = query.isBlank()
                    NeoEmptyState(
                        title = if (pristine) stringResource(R.string.products_empty_title) else stringResource(R.string.products_no_match_title),
                        detail = if (pristine) {
                            stringResource(R.string.products_empty_detail)
                        } else {
                            stringResource(R.string.products_no_match_detail)
                        },
                        actionLabel = if (pristine) stringResource(R.string.products_add_description) else null,
                        onAction = if (pristine) ({ onShowFormChange(true) }) else null,
                    )
                }
            } else {
                items(visible, key = { it.id }) { product ->
                    ProductRow(
                        product = product,
                        store = imageStore,
                        onEdit = { editing = product },
                        onArchive = { archiving = product },
                    )
                }
            }
        }
    }

    if (showForm) {
        // The photo is picked before the product exists, so it is filed under an id minted here.
        // ProductEntity.imageUri stores the file name, not the id, so the two never have to match.
        val draftId = remember { UUID.randomUUID().toString() }
        ProductFormDialog(
            title = stringResource(R.string.products_form_new_title),
            initialName = "",
            initialPrice = "",
            initialPhoto = null,
            photoTargetId = draftId,
            store = imageStore,
            // No eager close: it used to hide a failed insert as thoroughly as the customer form
            // did. The host closes this once onCreateProduct actually succeeds.
            onConfirm = { name, cents, photo -> onCreateProduct(name, cents, photo) },
            onDismiss = { onShowFormChange(false) },
            isSaving = isSaving,
        )
    }
    editing?.let { product ->
        ProductFormDialog(
            title = stringResource(R.string.products_form_edit_title),
            initialName = product.name,
            initialPrice = Money.pesosText(product.salePriceCents),
            initialPhoto = product.imageUri,
            photoTargetId = product.id,
            store = imageStore,
            formKey = product.id,
            note = stringResource(R.string.products_form_edit_note),
            onConfirm = { name, cents, photo ->
                onEditProduct(product, name, cents, photo)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    archiving?.let { product ->
        AlertDialog(
            onDismissRequest = { archiving = null },
            title = { Text(stringResource(R.string.products_delete_title, product.name)) },
            text = {
                // True because sale_items keeps the name and price charged that day, so removing the
                // product from the catalog cannot rewrite what anyone was charged.
                Text(stringResource(R.string.products_delete_detail))
            },
            confirmButton = {
                TextButton(onClick = { archiving = null; onArchiveProduct(product) }) {
                    Text(stringResource(R.string.products_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { archiving = null }) { Text(stringResource(R.string.form_cancel)) } },
        )
    }
}

@Composable
private fun ProductFormDialog(
    title: String,
    initialName: String,
    initialPrice: String,
    initialPhoto: String?,
    photoTargetId: String,
    store: ProductImageStore,
    onConfirm: (String, Long, String?) -> Unit,
    onDismiss: () -> Unit,
    formKey: Any? = Unit,
    note: String? = null,
    isSaving: Boolean = false,
) {
    // Keyed on the product so reopening the dialog for another row starts from that row's data.
    var name by remember(formKey) { mutableStateOf(initialName) }
    var priceText by remember(formKey) { mutableStateOf(initialPrice) }
    var photo by remember(formKey) { mutableStateOf(ProductPhotoDraftPolicy.start(initialPhoto)) }
    val cents = pesosToCents(priceText)
    // Backing out must leave the saved product untouched, so only files this dialog created go.
    val dismissAndDiscard = {
        ProductPhotoDraftPolicy.onCancel(photo).forEach(store::delete)
        onDismiss()
    }
    FormDialog(
        title = title,
        confirmEnabled = name.isNotBlank() && cents != null && !isSaving,
        onConfirm = {
            if (name.isNotBlank() && cents != null && !isSaving) onConfirm(name.trim(), cents, photo.current)
        },
        onDismiss = dismissAndDiscard,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.products_form_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        MoneyField(stringResource(R.string.products_form_price_label), priceText) { priceText = it }
        ProductPhotoPicker(photo, store, photoTargetId) { photo = it }
        note?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun ProductRow(
    product: ProductEntity,
    store: ProductImageStore,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = CATALOG_ROW_HEIGHT),
        shape = NeoShapes.row,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = NeoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProductPhoto(product.imageUri, store, 44.dp)
            Column(modifier = Modifier.weight(1f).padding(horizontal = NeoSpacing.md)) {
                // The name owns its line. Sharing it with the price truncated real product names
                // at 320 dp, where the row only has about 164 dp for both.
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = secondaryLine(product).orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(NeoSpacing.sm))
                    NeoMoney(
                        amountCents = product.salePriceCents,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.products_options_description, product.name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.products_edit)) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.products_delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onArchive() },
                    )
                }
            }
        }
    }
}

/** SKU and category are both optional, so the line only appears when there is something to say. */
private fun secondaryLine(product: ProductEntity): String? {
    val parts = listOfNotNull(
        product.sku?.takeIf { it.isNotBlank() }?.let { "SKU $it" },
        product.category?.takeIf { it.isNotBlank() },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
