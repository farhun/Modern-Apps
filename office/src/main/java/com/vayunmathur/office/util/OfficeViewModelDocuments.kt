package com.vayunmathur.office.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.office.odf.DocumentImporter
import com.vayunmathur.office.odf.OdfWriter
import com.vayunmathur.library.ui.odf.OoxmlExporter
import com.vayunmathur.office.util.MAX_RECENT
import com.vayunmathur.office.util.OfficeViewModel.ViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun OfficeViewModel.addToRecent(context: Context, uri: Uri, name: String) {
    val prefs = context.getSharedPreferences("office_recent", Context.MODE_PRIVATE)
    val existing = getRecentFiles(context).toMutableList()
    existing.removeAll { it.first == uri.toString() }
    existing.add(0, Pair(uri.toString(), name))
    if (existing.size > MAX_RECENT) existing.subList(MAX_RECENT, existing.size).clear()
    prefs.edit {
        putInt("count", existing.size)
        existing.forEachIndexed { i, (u, n) ->
            putString("uri_$i", u)
            putString("name_$i", n)
        }
    }
}

fun OfficeViewModel.getRecentFiles(context: Context): List<Pair<String, String>> {
    val prefs = context.getSharedPreferences("office_recent", Context.MODE_PRIVATE)
    val count = prefs.getInt("count", 0)
    return (0 until count).mapNotNull { i ->
        val uri = prefs.getString("uri_$i", null) ?: return@mapNotNull null
        val name = prefs.getString("name_$i", null) ?: return@mapNotNull null
        Pair(uri, name)
    }
}

fun OfficeViewModel.clearRecentFiles(context: Context) {
    context.getSharedPreferences("office_recent", Context.MODE_PRIVATE).edit { clear() }
}

fun OfficeViewModel.loadDocument(uri: Uri, fileName: String) {
    stateMutable.value = ViewState.Loading
    isEditModeMutable.value = true
    hasUnsavedChangesMutable.value = false
    undoStack.clear(); redoStack.clear()
    canUndoMutable.value = false; canRedoMutable.value = false
    documentUri = uri
    originalUri = uri

    viewModelScope.launch(Dispatchers.IO) {
        try {
            // Copy the source into app-owned storage so a revoked SAF grant can't leave us
            // unable to re-read the untouched package parts at save time. This is only a read
            // cache -- [originalUri] stays the save target.
            val localUri = persistToAppStorage(uri, fileName)
            documentUri = localUri
            val doc = DocumentImporter.open(getApplication(), localUri, fileName)
            stateMutable.value = ViewState.Loaded(doc)
            addToRecent(getApplication(), uri, fileName)
        } catch (expected: Exception) {
            stateMutable.value = ViewState.Error(expected.message ?: "Unknown error")
        }
    }
}

internal fun OfficeViewModel.persistToAppStorage(uri: Uri, fileName: String): Uri {
    val ctx: Context = getApplication()
    val dir = java.io.File(ctx.filesDir, "documents").apply { mkdirs() }
    if (uri.scheme == "file" && uri.path?.startsWith(dir.absolutePath) == true) return uri
    val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "document" }
    val dest = java.io.File(dir, "${System.currentTimeMillis()}_$safe")
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
    } ?: throw java.io.IOException("Cannot read $uri")
    return Uri.fromFile(dest)
}

fun OfficeViewModel.clearDocument() {
    stateMutable.value = ViewState.Empty
    isEditModeMutable.value = false
    hasUnsavedChangesMutable.value = false
    undoStack.clear(); redoStack.clear()
    canUndoMutable.value = false; canRedoMutable.value = false
    documentUri = null
    originalUri = null

    autoSaveJob?.cancel()
}

fun OfficeViewModel.createNewTextDocument() {
    undoStack.clear(); redoStack.clear()
    canUndoMutable.value = false; canRedoMutable.value = false
    val doc = OdfDocument.TextDocument(
        title = "Untitled Document",
        content = listOf(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text = "")))))
    )
    stateMutable.value = ViewState.Loaded(doc)
    isEditModeMutable.value = true
    hasUnsavedChangesMutable.value = true
    documentUri = null
    originalUri = null
}

fun OfficeViewModel.createNewSpreadsheet() {
    undoStack.clear(); redoStack.clear()
    canUndoMutable.value = false; canRedoMutable.value = false
    val rows = (0 until 10).map { OdfRow(List(5) { OdfCell(text = "") }) }
    val doc = OdfDocument.Spreadsheet(
        title = "Untitled Spreadsheet",
        sheets = listOf(OdfSheet("Sheet 1", rows))
    )
    stateMutable.value = ViewState.Loaded(doc)
    isEditModeMutable.value = true
    hasUnsavedChangesMutable.value = true
    documentUri = null
    originalUri = null
}

fun OfficeViewModel.createNewPresentation() {
    undoStack.clear(); redoStack.clear()
    canUndoMutable.value = false; canRedoMutable.value = false
    val doc = OdfDocument.Presentation(
        title = "Untitled Presentation",
        slides = listOf(
            OdfSlide(
                name = "Slide 1",
                elements = listOf(
                    OdfSlideElement.Frame(
                        OdfFrame(
                            x = 50f, y = 50f, width = 600f, height = 100f,
                            paragraphs = listOf(
                                OdfParagraph(
                                    listOf(OdfSpan(text = "Title", bold = true, fontSize = 36f)),
                                    style = ParagraphStyle.HEADING1
                                )
                            )
                        )
                    )
                )
            )
        )
    )
    stateMutable.value = ViewState.Loaded(doc)
    isEditModeMutable.value = true
    hasUnsavedChangesMutable.value = true
    documentUri = null
    originalUri = null
}

fun OfficeViewModel.save(targetUri: Uri? = null) {
    val doc = (stateMutable.value as? ViewState.Loaded)?.document ?: return
    // Source may be null for a brand-new document; the writer then builds the package from scratch.
    val source = documentUri
    // Save writes back to the original URI; Save As chooses its own target and format by extension.
    val target = targetUri ?: originalUri ?: return
    isSavingMutable.value = true
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val context = getApplication<Application>()
            val extension = documentExtension(context, target)
            val ooxmlBytes = ooxmlBytesForSave(extension, doc)
            if (ooxmlBytes == null) {
                OdfWriter.save(context, source, doc, target)
                documentUri = refreshCachedCopyFromTarget(context, source, target)
            } else {
                documentUri = writeOoxmlBytes(context, source, target, ooxmlBytes)
            }
            hasUnsavedChangesMutable.value = false
            originalUri = target
            if (documentUri == null) documentUri = target

            launch(Dispatchers.Main) { AppMessages.show(getApplication<Application>().getString(UiR.string.saved)) }
        } catch (expected: Exception) {
            launch(Dispatchers.Main) {
                AppMessages.show(
                    getApplication<Application>().getString(
                        io.github.farhun.office.R.string.save_failed,
                        expected.message
                    )
                )
            }
        } finally {
            isSavingMutable.value = false
        }
    }
}

private val DOCX_SAVE_EXTENSIONS = setOf("docx", "docm", "dotx", "dotm")
private val XLSX_SAVE_EXTENSIONS = setOf("xlsx", "xlsm", "xltx", "xltm")
private val PPTX_SAVE_EXTENSIONS = setOf("pptx", "pptm", "potx", "potm", "ppsx", "ppsm", "ppam")

internal fun ooxmlBytesForSave(extension: String, document: OdfDocument): ByteArray? = when (extension.lowercase()) {
    in DOCX_SAVE_EXTENSIONS -> {
        require(document is OdfDocument.TextDocument) { "A Word document must be saved from a text document." }
        OoxmlExporter.export(document)
    }

    in XLSX_SAVE_EXTENSIONS -> {
        require(document is OdfDocument.Spreadsheet) { "An Excel document must be saved from a spreadsheet." }
        OoxmlExporter.export(document)
    }

    in PPTX_SAVE_EXTENSIONS -> {
        require(document is OdfDocument.Presentation) { "A PowerPoint document must be saved from a presentation." }
        OoxmlExporter.export(document)
    }

    else -> null
}

private fun documentExtension(context: Context, uri: Uri): String {
    val displayName = runCatching {
        if (uri.scheme == "content") {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        } else {
            null
        }
    }.getOrNull()
    val name = displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: return ""
    return name.substringAfterLast('.', "").lowercase()
}

private fun writeOoxmlBytes(
    context: Context,
    sourceUri: Uri?,
    targetUri: Uri,
    bytes: ByteArray,
): Uri {
    val temp = java.io.File.createTempFile("office-save-", ".tmp", context.cacheDir)
    try {
        temp.outputStream().use { it.write(bytes) }
        val output = context.contentResolver.openOutputStream(targetUri, "wt")
            ?: throw java.io.IOException("Cannot write to $targetUri")
        temp.inputStream().use { input -> output.use { input.copyTo(it) } }
        return refreshCachedCopy(context, sourceUri, targetUri, temp)
    } finally {
        temp.delete()
    }
}

private fun refreshCachedCopy(context: Context, sourceUri: Uri?, targetUri: Uri, temp: java.io.File): Uri {
    val cacheFile = cachedDocumentFile(context, sourceUri, targetUri) ?: return targetUri
    return runCatching {
        temp.inputStream().use { input -> cacheFile.outputStream().use(input::copyTo) }
        sourceUri ?: targetUri
    }.getOrNull() ?: targetUri
}

private fun refreshCachedCopyFromTarget(context: Context, sourceUri: Uri?, targetUri: Uri): Uri {
    val cacheFile = cachedDocumentFile(context, sourceUri, targetUri) ?: return targetUri
    val temp = java.io.File.createTempFile("office-cache-", ".tmp", context.cacheDir)
    return try {
        val input = context.contentResolver.openInputStream(targetUri)
            ?: throw java.io.IOException("Cannot read saved document $targetUri")
        input.use { source -> temp.outputStream().use(source::copyTo) }
        temp.inputStream().use { inputStream -> cacheFile.outputStream().use(inputStream::copyTo) }
        sourceUri ?: targetUri
    } catch (_: Exception) {
        targetUri
    } finally {
        temp.delete()
    }
}

private fun cachedDocumentFile(context: Context, sourceUri: Uri?, targetUri: Uri): java.io.File? {
    val cacheFile = sourceUri?.takeIf { it.scheme == "file" }?.path?.let { java.io.File(it) } ?: return null
    val docsDir = java.io.File(context.filesDir, "documents").canonicalFile
    val canonicalCache = cacheFile.canonicalFile
    val prefix = docsDir.path + java.io.File.separator
    if (!canonicalCache.path.startsWith(prefix)) return null

    val targetFile = targetUri.takeIf { it.scheme == "file" }?.path?.let { java.io.File(it) }?.canonicalFile
    return if (targetFile == canonicalCache) null else canonicalCache
}

fun OfficeViewModel.needsSaveAs(): Boolean {
    val target = originalUri ?: return true
    return !isWritable(target)
}

internal fun OfficeViewModel.isWritable(uri: Uri): Boolean = when (uri.scheme) {
    "file" -> uri.path?.let { java.io.File(it).canWrite() } == true
    else -> getApplication<Application>().checkCallingOrSelfUriPermission(
        uri,
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    ) == PackageManager.PERMISSION_GRANTED
}
