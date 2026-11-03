package dev.macdroid.app

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Search bounded shelf contents off Main, without concatenating entire note bodies. */
internal suspend fun searchShelf(entries: List<ShelfEntry>, query: String, filter: String): List<ShelfEntry> = withContext(Dispatchers.Default) {
    val context = currentCoroutineContext()
    val search = query.takeUnless { it.isBlank() }
    entries.filter { entry ->
        context.ensureActive()
        val matchesKind = when (filter) {
            "Files" -> entry.kind == "file"
            "Notes" -> entry.kind == "text"
            "Links" -> entry.kind == "link"
            "Waiting" -> entry.pending
            else -> true
        }
        matchesKind && (search == null || entry.title.contains(search, ignoreCase = true) || entry.text?.contains(search, ignoreCase = true) == true)
    }.sortedByDescending { it.pinned }
}

private class ShelfSearchRequest(val entries: List<ShelfEntry>, val query: String, val filter: String)
private class ShelfSearchResult(val request: ShelfSearchRequest, val entries: List<ShelfEntry>)
internal data class ShelfSearchState(val entries: List<ShelfEntry>, val ready: Boolean, val loading: Boolean)

@Composable
internal fun rememberShelfSearch(entries: List<ShelfEntry>, query: String, filter: String): ShelfSearchState {
    val request = remember(entries, query, filter) { ShelfSearchRequest(entries, query, filter) }
    val result by produceState<ShelfSearchResult?>(null, request) {
        value = ShelfSearchResult(request, searchShelf(request.entries, request.query, request.filter))
    }
    val ready = result?.request === request
    var loading by remember(request) { mutableStateOf(false) }
    LaunchedEffect(request, ready) {
        if (ready) loading = false
        else { delay(150); loading = true }
    }
    // Keep the last completed list while typing, but never announce stale empty results.
    return ShelfSearchState(result?.entries.orEmpty(), ready, loading)
}
