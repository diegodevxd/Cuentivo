package com.cuentivo.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cuentivo.app.R

/** The language globe, filled with the current theme's text colour rather than a hard-coded black. */
@Composable
private fun globeIcon(): ImageVector {
    val fill = SolidColor(MaterialTheme.colorScheme.onSurface)
    return remember(fill) {
        ImageVector.Builder(
            name = "Language",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(
                "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM4.06 13h3.98c.1 2.3.63 4.48 1.52 6.31A8.03 8.03 0 0 1 4.06 13zM8.04 11H4.06a8.03 8.03 0 0 1 5.5-6.31A18.2 18.2 0 0 0 8.04 11zM12 19.96c-1.23-1.78-2.08-4.26-2.15-6.96h4.3c-.07 2.7-.92 5.18-2.15 6.96zM9.85 11C9.92 8.3 10.77 5.82 12 4.04 13.23 5.82 14.08 8.3 14.15 11h-4.3zM14.44 19.31c.89-1.83 1.42-4.01 1.52-6.31h3.98a8.03 8.03 0 0 1-5.5 6.31zM15.96 11a18.2 18.2 0 0 0-1.52-6.31A8.03 8.03 0 0 1 19.94 11h-3.98z",
            ).toNodes(),
            fill = fill,
        ).build()
    }
}

/** The four places in the app. Nueva venta deliberately remains an action, not a destination. */
enum class MainDestination {
    HOME,
    CUSTOMERS,
    PRODUCTS,
    PAYMENTS,
}

/** Small pure policy surface so action semantics do not get entangled with Compose state. */
object MainNavigationPolicy {
    val destinations = listOf(
        MainDestination.HOME,
        MainDestination.CUSTOMERS,
        MainDestination.PRODUCTS,
        MainDestination.PAYMENTS,
    )

    fun destinationAfterNewSale(current: MainDestination): MainDestination = current

    /**
     * Which destination the bottom bar marks as current.
     *
     * Nueva venta is an action, not a destination, but it still owns the screen while it is open.
     * Marking the tab the person came from read as "you are here" over a screen they were not on;
     * with the sale open the bar marks the sale action instead and no destination. A customer's
     * account is the same trap: it is a task, but it belongs to Clientes, so it marks that tab and
     * not whichever one it was opened from.
     */
    fun barSelection(
        destination: MainDestination,
        newSaleActive: Boolean,
        detailOpen: Boolean = false,
    ): MainDestination? = when {
        newSaleActive -> null
        detailOpen -> MainDestination.CUSTOMERS
        else -> destination
    }

    /**
     * What tapping the bar does. `currentSelection` is the tab that the bar actually marks, not the
     * destination underneath an open task. Tapping that tab returns it to its root; tapping another
     * opens it. A sale marks no destination, while a customer detail marks Clientes.
     */
    fun tabTapAction(
        currentSelection: MainDestination?,
        selected: MainDestination,
    ): TabTapAction =
        if (selected == currentSelection) TabTapAction.RESET_ROOT else TabTapAction.OPEN
}

/** The two outcomes of a tap on the bottom bar. */
enum class TabTapAction { OPEN, RESET_ROOT }

@Composable
fun NeoFintechShell(
    destination: MainDestination,
    onDestination: (MainDestination) -> Unit,
    onNewSale: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenAlerts: () -> Unit,
    /** Debug-only AI lab entry; null hides it entirely (release builds). */
    onOpenAi: (() -> Unit)? = null,
    /**
     * True while the Nueva venta task owns the screen. The bar marks the sale action as current and
     * marks no destination, instead of leaving the previous tab lit under a screen it does not own.
     */
    newSaleActive: Boolean = false,
    /**
     * True while a customer's account owns the screen. It is a task, but it belongs to Clientes, so
     * the bar marks that tab rather than the one the detail was opened from.
     */
    detailOpen: Boolean = false,
    snackbarHost: @Composable () -> Unit = {},
    /**
     * A dedicated task that owns the whole viewport — customer detail, for instance — brings its
     * own back arrow and title. Leaving the brand row above it would stack two headers and steal
     * 52 dp the reference does not spend. The bottom bar deliberately stays.
     */
    showBrandHeader: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            if (!showBrandHeader) return@Scaffold
            val accountDescription = stringResource(R.string.nav_account_description)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // The app draws edge-to-edge, so without this the logo and the bell sit under
                    // the status bar's clock and icons instead of below them.
                    .statusBarsPadding()
                    .heightIn(min = 52.dp)
                    .padding(horizontal = NeoSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = accountDescription
                            role = Role.Button
                        }
                        // Clipped before `clickable` so the press state is a rounded rectangle in
                        // the app's own shape language. Unclipped it spilled as a hard-edged
                        // rectangle beside two circular icon ripples — three controls in one header,
                        // each reacting to a touch differently.
                        .clip(NeoShapes.row)
                        .clickable(onClick = onOpenAccount)
                        .padding(horizontal = NeoSpacing.xs)
                        .padding(end = NeoSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // This used to be the "C" badge beside the word "Cuentivo", which read as a
                    // logo rather than a control: nobody guesses that tapping the brand opens their
                    // account, and only the screen reader was ever told. The wordmark lives on in
                    // the launcher icon, the sign-in screen and the Play listing, none of which
                    // needed this row to carry it.
                    Icon(
                        imageVector = Icons.Filled.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp),
                    )
                    Text(
                        text = stringResource(R.string.nav_account),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = NeoSpacing.xs),
                    )
                }
                Box(Modifier.weight(1f))
                onOpenAi?.let { openAi ->
                    IconButton(
                        onClick = openAi,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("global-ai-action"),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_cuentivo_ai_robot),
                            contentDescription = stringResource(R.string.ai_debug_lab_entry),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                IconButton(
                    onClick = onOpenLanguage,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("global-language-action"),
                ) {
                    Icon(
                        imageVector = globeIcon(),
                        contentDescription = stringResource(R.string.account_language),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onOpenAlerts, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = stringResource(R.string.nav_alerts_description),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        snackbarHost = snackbarHost,
        bottomBar = {
            CompactBottomNavigation(
                destination = destination,
                onDestination = onDestination,
                onNewSale = onNewSale,
                newSaleActive = newSaleActive,
                detailOpen = detailOpen,
            )
        },
        // Screens arrive instead of appearing: a short lift and fade whenever the destination
        // changes, so moving between Inicio, Clientes, Productos and Abonos reads as travel rather
        // than as the screen being replaced under you.
        //
        // Keyed on the destination and deliberately entrance-only. The outgoing screen is not
        // animated out, because `content` renders from the host's current state — during a
        // transition both halves would draw the *new* screen, and cross-fading something with
        // itself is motion that costs frames and communicates nothing.
        content = { padding ->
            // Respect the system animator setting: with animations disabled the destination swaps
            // instantly instead of animating an entrance nobody asked for.
            if (LocalMotionEnabled.current) {
                AnimatedContent(
                    targetState = destination,
                    transitionSpec = {
                        (fadeIn(tween(NeoMotion.ENTRANCE_MS)) +
                            slideInVertically(tween(NeoMotion.ENTRANCE_MS)) { it / 14 })
                            .togetherWith(ExitTransition.None)
                    },
                    label = "destination entrance",
                ) { targetDestination ->
                    key(targetDestination) { content(padding) }
                }
            } else {
                content(padding)
            }
        },
    )
}

@Composable
private fun CompactBottomNavigation(
    destination: MainDestination,
    onDestination: (MainDestination) -> Unit,
    onNewSale: () -> Unit,
    newSaleActive: Boolean,
    detailOpen: Boolean,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = NeoClay.shape,
        shadowElevation = NeoClay.elevation,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = NeoClay.inset),
    ) {
        BoxWithConstraints {
            // The width that actually reaches the bar and the system font scale the user asked
            // for decide together whether every label can keep its own line. When either runs out,
            // the shell falls back to a compact column that gives Nueva venta the whole width.
            // The selection is resolved here, once: a sale in progress marks the action, not a tab.
            val selected = MainNavigationPolicy.barSelection(destination, newSaleActive, detailOpen)
            when (NavigationLayoutPolicy.modeFor(maxWidth, LocalDensity.current.fontScale)) {
                NavigationLayoutMode.LABELLED -> LabelledNavigationRow(selected, onDestination, onNewSale, newSaleActive)
                NavigationLayoutMode.COMPACT -> CompactNavigationColumn(selected, onDestination, onNewSale, newSaleActive)
            }
        }
    }
}

@Composable
private fun LabelledNavigationRow(
    destination: MainDestination?,
    onDestination: (MainDestination) -> Unit,
    onNewSale: () -> Unit,
    newSaleActive: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The domed rim: a light edge along the top fading into the surface, which is what
            // makes a flat rectangle read as a thick slab of clay rather than a panel.
            .background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.onSurface.copy(alpha = NeoClay.HIGHLIGHT_ALPHA),
                    0.35f to Color.Transparent,
                ),
            )
            .heightIn(min = 68.dp)
            .padding(horizontal = NeoSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ShellDestination.HOME.item(destination, onDestination, Modifier.weight(NavigationLayoutPolicy.LABELLED_DESTINATION_WEIGHT), showLabel = true)
        ShellDestination.CUSTOMERS.item(destination, onDestination, Modifier.weight(NavigationLayoutPolicy.LABELLED_DESTINATION_WEIGHT), showLabel = true)
        // La acción central pesa más que las pestañas: "Nueva venta" es la etiqueta más larga y a
        // partes iguales quedaba cortada entre 360 y 411 dp (v45).
        NewSaleNavigationAction(
            onClick = onNewSale,
            modifier = Modifier.weight(NavigationLayoutPolicy.LABELLED_SALE_ACTION_WEIGHT),
            isActive = newSaleActive,
        )
        ShellDestination.PRODUCTS.item(destination, onDestination, Modifier.weight(NavigationLayoutPolicy.LABELLED_DESTINATION_WEIGHT), showLabel = true)
        ShellDestination.PAYMENTS.item(destination, onDestination, Modifier.weight(NavigationLayoutPolicy.LABELLED_DESTINATION_WEIGHT), showLabel = true)
    }
}

@Composable
private fun CompactNavigationColumn(
    destination: MainDestination?,
    onDestination: (MainDestination) -> Unit,
    onNewSale: () -> Unit,
    newSaleActive: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.onSurface.copy(alpha = NeoClay.HIGHLIGHT_ALPHA),
                    0.35f to Color.Transparent,
                ),
            )
            .padding(horizontal = NeoSpacing.xs),
    ) {
        // Nueva venta keeps the whole bar width so its label can stay on one line even at the
        // font scales that forced the compact layout in the first place.
        NewSaleNavigationAction(onClick = onNewSale, modifier = Modifier.fillMaxWidth(), isActive = newSaleActive)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = NeoSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Destinations are icon-only here: at the font scales that selected this column a
            // visible label would truncate, and the content description still names each Tab.
            ShellDestination.HOME.item(destination, onDestination, Modifier.weight(1f), showLabel = false)
            ShellDestination.CUSTOMERS.item(destination, onDestination, Modifier.weight(1f), showLabel = false)
            ShellDestination.PRODUCTS.item(destination, onDestination, Modifier.weight(1f), showLabel = false)
            ShellDestination.PAYMENTS.item(destination, onDestination, Modifier.weight(1f), showLabel = false)
        }
    }
}

private enum class ShellDestination(
    val destination: MainDestination,
    val labelRes: Int,
    val icon: ImageVector,
) {
    HOME(MainDestination.HOME, R.string.nav_home, Icons.Filled.Home),
    CUSTOMERS(MainDestination.CUSTOMERS, R.string.nav_customers, Icons.Filled.Person),
    PRODUCTS(MainDestination.PRODUCTS, R.string.nav_products, Icons.Filled.Star),
    PAYMENTS(MainDestination.PAYMENTS, R.string.nav_payments, Icons.Filled.CheckCircle),
    ;

    @Composable
    fun item(
        selectedDestination: MainDestination?,
        onDestination: (MainDestination) -> Unit,
        modifier: Modifier,
        showLabel: Boolean,
    ) {
        val isSelected = selectedDestination == destination
        val label = stringResource(labelRes)
        // Clay does not highlight, it dents. The selected tab sinks into the bar: darker at the top
        // where the material would fall away from the light, lighter at the bottom where it catches
        // it again. Animated through alpha so the press reads as pressure, not as a lamp switching
        // on. Unselected stays fully transparent and the bar shows through untouched.
        val pressedAlpha by animateFloatAsState(
            targetValue = if (isSelected) 1f else 0f,
            animationSpec = tween(NeoMotion.STANDARD_MS),
            label = "navigation press depth",
        )
        val shadeColor = MaterialTheme.colorScheme.onSurface
        val contentColor by animateColorAsState(
            targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            animationSpec = tween(NeoMotion.STANDARD_MS),
            label = "navigation content color",
        )
        Column(
            modifier = modifier
                .testTag("main-destination-${destination.name.lowercase()}")
                // Merged so the destination reads as one control: TalkBack and the UI tests see a
                // single Tab whose name is the content description. The description is set here
                // explicitly because merging a child's text into a parent does not by itself
                // produce one.
                .semantics(mergeDescendants = true) {
                    selected = isSelected
                    role = Role.Tab
                    contentDescription = label
                }
                .padding(horizontal = 2.dp, vertical = NeoSpacing.xs)
                .clip(NeoClay.tabShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            shadeColor.copy(alpha = NeoClay.PRESSED_TOP_ALPHA * pressedAlpha),
                            shadeColor.copy(alpha = NeoClay.PRESSED_BOTTOM_ALPHA * pressedAlpha),
                        ),
                    ),
                )
                .heightIn(min = 56.dp)
                .clickable { onDestination(destination) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 22 dp rather than 19: beside 13 sp labels a 19 dp glyph looks undernourished.
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(22.dp))
            // The compact bar drops the visual label so a huge system font can never truncate it;
            // the localized description above keeps the destination fully announced either way.
            if (showLabel) {
                Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall, color = contentColor, maxLines = 1)
            }
        }
    }
}

@Composable
private fun NewSaleNavigationAction(
    onClick: () -> Unit,
    modifier: Modifier,
    isActive: Boolean = false,
) {
    val label = stringResource(R.string.nav_new_sale)
    // The sale owns the screen while it is open, so the action gets the same sunken treatment the
    // destinations use when selected: the bar says where you are, and here that is not a tab.
    val pressedAlpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = tween(NeoMotion.STANDARD_MS),
        label = "navigation press depth",
    )
    val shadeColor = MaterialTheme.colorScheme.onSurface
    val contentColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(NeoMotion.STANDARD_MS),
        label = "navigation content color",
    )
    Column(
        modifier = modifier
            .heightIn(min = 56.dp)
            .testTag("main-action-new-sale")
            // The description lives on the clickable parent, exactly once, so TalkBack announces
            // the action instead of its chrome. `selected` is what lets a test — and a screen
            // reader — tell that the sale screen is the current one.
            .semantics(mergeDescendants = true) {
                selected = isActive
                contentDescription = label
            }
            .padding(horizontal = 2.dp, vertical = NeoSpacing.xs)
            .clip(NeoClay.tabShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        shadeColor.copy(alpha = NeoClay.PRESSED_TOP_ALPHA * pressedAlpha),
                        shadeColor.copy(alpha = NeoClay.PRESSED_BOTTOM_ALPHA * pressedAlpha),
                    ),
                ),
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = NeoShapes.row,
            modifier = Modifier.size(30.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = contentColor)
    }
}
