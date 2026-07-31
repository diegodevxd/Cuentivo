package com.cuentivo.app.ui.product

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cuentivo.app.data.local.ProductImageStore
import com.cuentivo.app.ui.NeoShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A product's photo, or a neutral placeholder when it has none.
 *
 * Extracted from the monolith with its behaviour untouched: decoding still happens on
 * `Dispatchers.IO`, because a list of products would otherwise stutter on a cheap phone.
 */
@Composable
internal fun ProductPhoto(
    fileName: String?,
    store: ProductImageStore,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, fileName) {
        value = fileName?.let { name -> withContext(Dispatchers.IO) { store.load(name) } }
    }
    Box(
        modifier = modifier
            .size(size)
            // clip before background: background(shape=) paints a rounded fill but does not clip
            // children, so a real photo drew square corners over it.
            .clip(NeoShapes.row)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "Foto del producto",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Icon(
            imageVector = Icons.Filled.ShoppingCart,
            contentDescription = "Sin foto",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size / 2),
        )
    }
}

/**
 * The tappable photo box inside the new/edit product dialogs.
 *
 * It edits a [PhotoDraft] and never deletes a photo a product row still points at. The only files
 * it removes are ones it created itself in this same dialog and then replaced.
 */
@Composable
internal fun ProductPhotoPicker(
    draft: PhotoDraft,
    store: ProductImageStore,
    targetId: String,
    onDraftChange: (PhotoDraft) -> Unit,
) {
    val fileName = draft.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    // PickVisualMedia opens the system photo picker, which needs no storage permission at all.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        working = true
        scope.launch {
            // A camera photo is several megapixels; decoding it on the main thread freezes the
            // dialog long enough to look broken.
            val saved = withContext(Dispatchers.IO) { store.save(uri, targetId) }
            working = false
            if (saved != null) {
                val update = ProductPhotoDraftPolicy.picked(draft, saved)
                update.deleteNow.forEach(store::delete)
                onDraftChange(update.draft)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProductPhoto(
            fileName = fileName,
            store = store,
            size = 88.dp,
            modifier = Modifier.clickable {
                launcher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
        )
        Spacer(Modifier.width(12.dp))
        Column {
            TextButton(
                onClick = {
                    launcher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = !working,
            ) {
                Text(
                    when {
                        working -> "Guardando…"
                        fileName == null -> "Agregar foto"
                        else -> "Cambiar foto"
                    }
                )
            }
            if (fileName != null) {
                TextButton(onClick = {
                    val update = ProductPhotoDraftPolicy.cleared(draft)
                    update.deleteNow.forEach(store::delete)
                    onDraftChange(update.draft)
                }) {
                    Text("Quitar foto", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
