package com.vayunmathur.office.ui

import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler

import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconKeyboardArrowDown
import com.vayunmathur.library.ui.IconKeyboardArrowUp
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.appBarScrollBehavior
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext


private class PdfRendererHandle(
    private val descriptor: ParcelFileDescriptor,
    val renderer: PdfRenderer,
) : AutoCloseable {
    override fun close() {
        renderer.close()
        descriptor.close()
    }
}

private sealed interface PdfLoadState {
    data object Loading : PdfLoadState
    data class Ready(val handle: PdfRendererHandle) : PdfLoadState
    data object Error : PdfLoadState
}

@Composable
fun OfficePdfViewer(uri: Uri, title: String, onBack: () -> Unit) {
    val context = LocalContext.current

    val loadState by produceState<PdfLoadState>(PdfLoadState.Loading, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: error("Cannot open PDF")
                try {
                    PdfRendererHandle(descriptor, PdfRenderer(descriptor))
                } catch (error: Exception) {
                    descriptor.close()
                    throw error
                }
            }.fold({ PdfLoadState.Ready(it) }, { PdfLoadState.Error })
        }
    }
    val handle = (loadState as? PdfLoadState.Ready)?.handle
    DisposableEffect(handle) { onDispose { handle?.close() } }

    val listState = rememberLazyListState()
    val renderMutex = remember(handle) { Mutex() }
    val pageCache = remember(handle, renderMutex) {
        handle?.let { PdfPageBitmapCache(it.renderer, renderMutex) }
    }
    DisposableEffect(pageCache) { onDispose { pageCache?.close() } }


    var searching by remember(uri) { mutableStateOf(false) }
    var query by remember(uri) { mutableStateOf("") }
    var matches by remember(uri) { mutableStateOf<List<PdfTextMatch>>(emptyList()) }
    var matchIndex by remember(uri) { mutableIntStateOf(0) }
    var searchComplete by remember(uri) { mutableStateOf(false) }
    var searchFailed by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(query, handle) {
        if (query.isBlank() || handle == null) {
            matches = emptyList()
            searchComplete = false
            searchFailed = false
            return@LaunchedEffect
        }
        matches = emptyList()
        matchIndex = 0
        delay(300)
        searchComplete = false
        searchFailed = false
        matchIndex = 0
        try {
            matches = withContext(Dispatchers.IO) { searchPdf(context, uri, query) }
            searchComplete = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            searchComplete = true
            searchFailed = true
        }
    }

    LaunchedEffect(matchIndex, matches) {
        matches.getOrNull(matchIndex)?.let { listState.animateScrollToItem(it.pageIndex) }
    }

    BackHandler {
        if (searching) {
            searching = false
            query = ""
            matches = emptyList()
        } else {
            onBack()
        }
    }

    AppScaffold(
        modifier = Modifier.fillMaxSize(),
        title = {
            if (searching) {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(io.github.farhun.office.R.string.pdf_search)) },
                        singleLine = true,
                    )
                    IconButton({
                        searching = false
                        query = ""
                        matches = emptyList()
                    }) { IconClose() }
                }
            } else {
                Text(title, maxLines = 1)
            }
        },
        navigationIcon = {
            IconNavigation {
                if (searching) {
                    searching = false
                    query = ""
                    matches = emptyList()
                } else {
                    onBack()
                }
            }
        },
        actions = {
            if (searching) {
                if (matches.isNotEmpty()) {
                    Text(
                        stringResource(io.github.farhun.office.R.string.pdf_match_count, matchIndex + 1, matches.size),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    IconButton({ matchIndex = (matchIndex - 1 + matches.size) % matches.size }) {
                        IconKeyboardArrowUp()
                    }
                    IconButton({ matchIndex = (matchIndex + 1) % matches.size }) {
                        IconKeyboardArrowDown()
                    }
                } else if (searchComplete && !searchFailed && query.isNotBlank()) {
                    Text(stringResource(io.github.farhun.office.R.string.pdf_no_matches))
                } else if (searchFailed) {
                    Text(stringResource(io.github.farhun.office.R.string.pdf_search_error))
                }
            } else {
                IconButton({ searching = true }) { IconSearch() }
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { innerPadding ->
        when {
            loadState is PdfLoadState.Loading -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            loadState is PdfLoadState.Error -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(io.github.farhun.office.R.string.pdf_open_error)) }

            handle != null && pageCache != null -> OfficePdfViewport(
                uriKey = uri.toString(),
                pageCount = handle.renderer.pageCount,
                cache = pageCache,
                matches = matches,
                currentMatch = matches.getOrNull(matchIndex),
                listState = listState,
                contentPadding = innerPadding,
            )
        }
    }
}
