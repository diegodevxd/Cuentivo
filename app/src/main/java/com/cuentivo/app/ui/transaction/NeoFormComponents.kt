package com.cuentivo.app.ui.transaction

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.cuentivo.app.ui.NeoDisabled
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.ui.NeoAction
import com.cuentivo.app.ui.NeoMoney
import com.cuentivo.app.R
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.NeoSpacing

/**
 * The pieces the two transaction tasks are built from: a compact header, a compact selector backed
 * by a searchable sheet, an accessible single-choice chip, and a labelled figure row.
 *
 * Nothing here computes money. Every amount arrives already in `Long` cents and is only formatted.
 */

/** A selector's visible panel stays inside the reference's 52–60 dp band. */
private val SELECTOR_HEIGHT = 58.dp

/** Chips paint shorter than they are tappable; see [NeoChoiceChip]. */
private val CHIP_HEIGHT = 40.dp

internal const val SELECTOR_SHEET_TAG = "neo-selector-sheet"

/**
 * Resolves a preselected customer against the list actually on screen. Matching by id rather than
 * trusting the passed row keeps a stale balance from being shown after the ledger moved, and an
 * archived or deleted customer simply falls back to the empty selector instead of vanishing
 * mid-form.
 */
internal fun preselectedCustomer(
    candidate: CustomerBalanceRow?,
    customers: List<CustomerBalanceRow>,
): CustomerBalanceRow? =
    candidate?.let { pre -> customers.firstOrNull { it.customer.id == pre.customer.id } }

/**
 * The compact task header: back affordance, eyebrow, title.
 *
 * `onBack` is null for Abonos, a bottom-bar destination rather than a task that can be closed.
 * Nueva venta passes one, and it exits by the same path the system Back button takes.
 */
@Composable
internal fun NeoTaskHeader(
    eyebrow: String? = null,
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backDescription: String = "Volver",
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = backDescription,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.size(NeoSpacing.xs))
        }
        Column(Modifier.weight(1f)) {
            eyebrow?.let {
                Text(
                    text = it.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A tappable panel that opens a searchable sheet to pick one option.
 *
 * The sheet is what makes a long catalog usable: a hundred products cannot live in a dropdown, and
 * someone looking for one of them is typing its name, not scrolling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> NeoSelectorField(
    label: String,
    selected: T?,
    placeholder: String,
    options: List<T>,
    optionTitle: (T) -> String,
    optionDetail: (T) -> String?,
    emptyText: String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether to repeat the label inside the panel. False where a section header already says it —
     * "CLIENTE" printed twice, once as the heading and once inside the control it heads, reads as a
     * mistake rather than as emphasis.
     */
    showLabel: Boolean = true,
    /**
     * Acción ofrecida dentro de la hoja cuando no hay opciones (v45).
     *
     * El selector de clientas vacío decía "Primero agrega un cliente en la pestaña Clientes", pero
     * esa pestaña ya no es la ruta: el formulario de Nueva venta tiene su propio "Agregar cliente".
     * Mandar a otra pestaña era un callejón. Cuando se pasa esta acción, la hoja la ofrece en su
     * lugar y la persona crea la clienta sin salir de la venta.
     */
    emptyActionLabel: String? = null,
    onEmptyAction: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Card(
        onClick = { open = true },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SELECTOR_HEIGHT)
            .testTag(selectorTag(label)),
        shape = NeoShapes.row,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        // An interactive selector is a control: its boundary uses the 3:1 outline role, not the
        // soft decorative outlineVariant.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = SELECTOR_HEIGHT)
                .padding(horizontal = NeoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (showLabel) {
                    Text(
                        text = label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    text = selected?.let(optionTitle) ?: placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.form_choose_description, label),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    if (open) {
        var query by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(
                modifier = Modifier
                    .padding(horizontal = NeoSpacing.lg)
                    .padding(bottom = 32.dp)
                    .testTag(SELECTOR_SHEET_TAG),
                verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
            ) {
                Text(label, style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.form_search)) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    singleLine = true,
                    // Filtering is live as the person types, so the keyboard's action key dismisses
                    // the IME rather than pretending to run a separate search step.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    shape = NeoShapes.row,
                    modifier = Modifier.fillMaxWidth(),
                )
                val filtered = options.filter { optionTitle(it).contains(query, ignoreCase = true) }
                if (filtered.isEmpty()) {
                    Text(
                        text = if (options.isEmpty()) emptyText else "No se encontró nada con ese nombre",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // La hoja vacía ofrece la ruta real en vez de mandar a otra pestaña (v45).
                    if (options.isEmpty() && emptyActionLabel != null && onEmptyAction != null) {
                        NeoAction(
                            label = emptyActionLabel,
                            onClick = { open = false; onEmptyAction() },
                            modifier = Modifier.fillMaxWidth(),
                            primary = false,
                        )
                    }
                }
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
                ) {
                    filtered.forEach { option ->
                        Card(
                            onClick = { onSelect(option); open = false },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            shape = NeoShapes.row,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 56.dp)
                                    .padding(horizontal = NeoSpacing.md, vertical = NeoSpacing.sm),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = optionTitle(option),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                optionDetail(option)?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A single-choice chip.
 *
 * A browser radio button is not an option here: it would bring the platform's white dot and its own
 * hit box. The painted pill stays compact while the tappable box keeps the Android 48 dp minimum,
 * and the chip reports `Role.RadioButton` plus its selected state, so the choice never rests on
 * colour alone.
 */
@Composable
internal fun NeoChoiceChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                selected = isSelected
                role = Role.RadioButton
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = CHIP_HEIGHT)
                .background(
                    color = if (isSelected) accent.copy(alpha = 0.16f) else Color.Transparent,
                    shape = RoundedCornerShape(9.dp),
                )
                .border(
                    width = 1.dp,
                    color = if (isSelected) accent else MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(9.dp),
                )
                .padding(horizontal = NeoSpacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One labelled figure inside a panel: caption on the left, cents on the right. */
@Composable
internal fun NeoAmountRow(
    label: String,
    amountCents: Long,
    modifier: Modifier = Modifier,
    /** Lets a caller tag the figure itself, so a test can name it without matching its text. */
    valueModifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NeoMoney(amountCents = amountCents, modifier = valueModifier, color = color, style = style)
    }
}

private fun selectorTag(label: String) = "selector-${label.lowercase()}"

/**
 * The heading that opens a step of the sale.
 *
 * Nueva venta is one screen with six jobs on it — pick the customer, pick what they took, review the
 * cart, agree a date, read the total, commit — and every one of them used to arrive at the same
 * visual weight, so the screen read as a wall of fields. A small cyan label with a rule running to
 * the edge costs one row and tells you where you are without splitting the flow into wizard pages.
 */
@Composable
internal fun NeoSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    /**
     * Form sections are structural steps and announce themselves as headings so TalkBack users can
     * navigate the long sale form; pass `false` for decorative captions.
     */
    heading: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = NeoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = if (heading) Modifier.semantics { heading() } else Modifier,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * Two mutually exclusive choices drawn as one control.
 *
 * As two separate chips they read as two buttons you could somehow press both of; inside a single
 * bordered track it is obvious that picking one releases the other. Used for
 * "Del catálogo / Escribir a mano", where the wrong reading costs a mis-typed product.
 */
@Composable
internal fun <T> NeoSegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, NeoShapes.row)
            .padding(3.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = 42.dp)
                    .clip(NeoShapes.row)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                    )
                    .semantics {
                        role = Role.Tab
                        this.selected = isSelected
                    }
                    .clickable { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * Quantity as two buttons and a number, instead of a text field.
 *
 * Almost every sale is one, two or three of something. Opening a keyboard to change a 1 into a 2 —
 * on a phone held behind a counter, by someone whose reading glasses are elsewhere — is the slowest
 * possible way to do the most common edit on this screen. Typing is still possible for the rare
 * large number, so nothing is lost.
 */
@Composable
internal fun NeoQuantityStepper(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    decreaseDescription: String,
    increaseDescription: String,
) {
    val quantityDescription = stringResource(R.string.new_sale_quantity_label)
    val quantity = value.toIntOrNull() ?: 0

    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, NeoShapes.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One is the floor, not zero: a line of zero is not a line, and the add action already
        // refuses it. Disabling the control says so before the tap instead of after.
        IconButton(
            onClick = { if (quantity > 1) onValueChange((quantity - 1).toString()) },
            enabled = quantity > 1,
            // The label rides on the button, not on an icon: a minus is drawn as text here, since
            // the core Material icon set has no "remove" and pulling in the extended set for one
            // glyph would add a few thousand vectors to the APK.
            modifier = Modifier.size(48.dp).semantics { contentDescription = decreaseDescription },
        ) {
            Text(
                text = "−",
                style = MaterialTheme.typography.titleLarge,
                color = if (quantity > 1) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = NeoDisabled.LABEL)
                },
            )
        }
        BasicTextField(
            value = value,
            onValueChange = { newText ->
                val digits = newText.filter { it.isDigit() }.take(4)
                val parsed = digits.toIntOrNull()
                if (parsed != null && parsed in 1..9999) {
                    onValueChange(digits)
                } else if (digits.isEmpty() || parsed == 0) {
                    onValueChange(digits)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            textStyle = MaterialTheme.typography.titleMedium.copy(
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .defaultMinSize(minWidth = 56.dp)
                .semantics { contentDescription = quantityDescription },
        )
        IconButton(
            onClick = { if (quantity < 9999) onValueChange((quantity + 1).toString()) },
            enabled = quantity < 9999,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = increaseDescription,
                tint = if (quantity < 9999) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = NeoDisabled.LABEL),
            )
        }
    }
}
