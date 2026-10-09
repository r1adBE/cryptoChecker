@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.cryptochecker.app.data.local.model.WatchEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Ziehen zum Sortieren ohne Zusatzbibliothek: Während des Ziehens gilt eine
 * lokale Reihenfolge; die gezogene Karte folgt dem Finger, tauscht mit der
 * Karte unter ihrer Mitte (nur innerhalb Favoriten bzw. übrige) und scrollt
 * am Rand mit. Beim Loslassen wird die Reihenfolge gespeichert.
 */
internal class ReorderState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
) {
    var items by mutableStateOf<List<WatchEntity>?>(null)
    var draggingId by mutableStateOf<Long?>(null)
    var offset by mutableFloatStateOf(0f)

    /** Aktuelle Liste aus der Datenbank, jede Komposition neu gesetzt. */
    var source: List<WatchEntity> = emptyList()
    var onDrop: (List<Long>) -> Unit = {}

    private var startOrder: List<Long> = emptyList()

    fun start(id: Long) {
        val current = items ?: source
        items = current
        startOrder = current.map { it.id }
        draggingId = id
        offset = 0f
    }

    fun drag(dy: Float) {
        val id = draggingId ?: return
        val list = items ?: return
        offset += dy

        val visible = listState.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.key == id } ?: return
        val center = current.offset + offset + current.size / 2f

        val target = visible.firstOrNull { info ->
            info.key is Long && info.key != id &&
                center >= info.offset && center <= info.offset + info.size
        }
        if (target != null) {
            val from = list.indexOfFirst { it.id == id }
            val to = list.indexOfFirst { it.id == target.key }
            // Favoriten bleiben oben: nur innerhalb der eigenen Gruppe tauschen.
            if (from >= 0 && to >= 0 && list[from].favorite == list[to].favorite) {
                // Betrifft der Tausch die oberste sichtbare Karte, hält LazyColumn
                // sonst die Scrollposition an deren Schlüssel fest — die Liste springt.
                val first = listState.firstVisibleItemIndex
                if (current.index == first || target.index == first) {
                    val firstOffset = listState.firstVisibleItemScrollOffset
                    scope.launch { listState.scrollToItem(first, firstOffset) }
                }
                items = list.toMutableList().apply { add(to, removeAt(from)) }
                offset += current.offset - target.offset
            }
        }

        // Am Rand mitscrollen, damit auch lange Listen sortierbar sind.
        val info = listState.layoutInfo
        val top = current.offset + offset
        val bottom = top + current.size
        val edge = 120f
        val step = when {
            bottom > info.viewportEndOffset - edge -> 18f
            top < info.viewportStartOffset + edge -> -18f
            else -> 0f
        }
        if (step != 0f) {
            scope.launch {
                val consumed = listState.scrollBy(step)
                offset += consumed
            }
        }
    }

    fun end() {
        val list = items
        draggingId = null
        offset = 0f
        if (list != null && list.map { it.id } != startOrder) {
            onDrop(list.map { it.id })
        } else {
            items = null
        }
    }
}

/**
 * [ReorderState] für die Liste: kennt immer die aktuelle Ansicht [visible] und legt per
 * [onDrop] ab. Nach dem Ablegen hält sie die lokale Reihenfolge, bis die Datenbank sie
 * bestätigt — sonst spränge die Karte kurz an den alten Platz zurück.
 */
@Composable
internal fun rememberReorderState(
    listState: LazyListState,
    scope: CoroutineScope,
    visible: List<WatchEntity>,
    onDrop: (List<Long>) -> Unit,
): ReorderState {
    val reorder = remember { ReorderState(listState, scope) }
    SideEffect {
        reorder.source = visible
        reorder.onDrop = onDrop
    }
    LaunchedEffect(visible) {
        val local = reorder.items
        if (reorder.draggingId == null && local != null &&
            (local.map { it.id } == visible.map { it.id } || local.size != visible.size)
        ) {
            reorder.items = null
        }
    }
    return reorder
}
