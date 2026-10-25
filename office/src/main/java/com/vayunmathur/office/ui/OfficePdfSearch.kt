package com.vayunmathur.office.ui

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

internal data class PdfTextMatch(
    val pageIndex: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal fun searchPdf(
    context: Context,
    uri: Uri,
    query: String,
    caseSensitive: Boolean = false,
): List<PdfTextMatch> {
    if (query.isBlank()) return emptyList()
    PDFBoxResourceLoader.init(context.applicationContext)
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IllegalArgumentException("Cannot read PDF")

    return input.use { stream ->
        PDDocument.load(stream).use { document ->
            val matches = mutableListOf<PdfTextMatch>()
            for (pageIndex in 0 until document.numberOfPages) {
                val page = document.getPage(pageIndex)
                val pageWidth = page.cropBox.width.coerceAtLeast(1f)
                val pageHeight = page.cropBox.height.coerceAtLeast(1f)
                val stripper = object : PDFTextStripper() {
                    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                        var start = 0
                        while (start <= text.length - query.length) {
                            val found = text.indexOf(query, start, ignoreCase = !caseSensitive)
                            if (found < 0) break
                            if (textPositions.isNotEmpty()) {
                                val first = found.coerceAtMost(textPositions.lastIndex)
                                val last = (found + query.length - 1).coerceAtMost(textPositions.lastIndex)
                                val selected = textPositions.subList(first, last + 1)
                                val left = selected.minOf { it.xDirAdj }
                                val top = selected.minOf { it.yDirAdj - it.heightDir }
                                val right = selected.maxOf { it.xDirAdj + it.widthDirAdj }
                                val bottom = selected.maxOf { it.yDirAdj }
                                matches += PdfTextMatch(
                                    pageIndex = pageIndex,
                                    left = (left / pageWidth).coerceIn(0f, 1f),
                                    top = (top / pageHeight).coerceIn(0f, 1f),
                                    right = (right / pageWidth).coerceIn(0f, 1f),
                                    bottom = (bottom / pageHeight).coerceIn(0f, 1f),
                                )
                            }
                            start = found + 1
                        }
                        super.writeString(text, textPositions)
                    }
                }.apply {
                    sortByPosition = true
                    startPage = pageIndex + 1
                    endPage = pageIndex + 1
                }
                stripper.getText(document)
            }
            matches
        }
    }
}
