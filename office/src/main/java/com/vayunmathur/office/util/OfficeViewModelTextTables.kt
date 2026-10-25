package com.vayunmathur.office.util

import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.library.ui.odf.OdfTableRow
import com.vayunmathur.library.ui.odf.applyRunSpanStyle
import com.vayunmathur.library.ui.odf.mutateRunParagraphs
import com.vayunmathur.library.ui.odf.runParagraphIndexAt
import com.vayunmathur.library.ui.odf.runRangeHasFormat
import com.vayunmathur.library.ui.odf.updateParagraphRun

/**
 * In-text table operations (split from OfficeViewModelTables.kt for file length).
 * Behavior identical, call sites unchanged.
 */

internal fun OfficeViewModel.withTextTable(blockIndex: Int, transform: (OdfTable) -> OdfTable) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val block = doc.content.getOrNull(blockIndex) as? OdfContentBlock.Table ?: return
    val newContent = doc.content.toMutableList()
    newContent[blockIndex] = OdfContentBlock.Table(transform(block.table))
    updateDocument(doc.copy(content = newContent))
}

internal fun updateTextTableCellModel(table: OdfTable, row: Int, col: Int, newText: String): OdfTable {
    val rows = table.rows.toMutableList()
    val currentRow = rows.getOrNull(row) ?: return table
    val cells = currentRow.cells.toMutableList()
    val cell = cells.getOrNull(col) ?: return table
    val sourceDocument = textDocumentForCell(cell)
    val updatedDocument = sourceDocument.updateParagraphRun(
        start = 0,
        endInclusive = sourceDocument.content.lastIndex,
        newText = newText
    ) ?: sourceDocument
    cells[col] = cell.copy(paragraphs = paragraphsFrom(updatedDocument))
    rows[row] = OdfTableRow(cells)
    return table.copy(rows = rows)
}

internal fun updateTextTableCellSpans(
    table: OdfTable,
    row: Int,
    col: Int,
    transform: (OdfSpan) -> OdfSpan,
): OdfTable = updateTextTableCell(table, row, col) { cell ->
    cell.copy(paragraphs = cell.paragraphs.map { paragraph ->
        paragraph.copy(spans = paragraph.spans.map(transform))
    })
}

internal fun updateTextTableCellAlignment(
    table: OdfTable,
    row: Int,
    col: Int,
    alignment: androidx.compose.ui.text.style.TextAlign?,
): OdfTable = updateTextTableCell(table, row, col) { cell ->
    cell.copy(paragraphs = cell.paragraphs.map { paragraph -> paragraph.copy(alignment = alignment) })
}

internal fun updateTextTableCellSpanRange(
    table: OdfTable,
    row: Int,
    col: Int,
    start: Int,
    end: Int,
    transform: (OdfSpan) -> OdfSpan,
): OdfTable = updateTextTableCell(table, row, col) { cell ->
    val source = textDocumentForCell(cell)
    val updated = source.applyRunSpanStyle(0, source.content.lastIndex, start, end, transform) ?: source
    cell.copy(paragraphs = paragraphsFrom(updated))
}

internal fun updateTextTableCellParagraphsInRange(
    table: OdfTable,
    row: Int,
    col: Int,
    start: Int,
    end: Int,
    transform: (OdfParagraph) -> OdfParagraph,
): OdfTable = updateTextTableCell(table, row, col) { cell ->
    val source = textDocumentForCell(cell)
    val updated = source.mutateRunParagraphs(0, source.content.lastIndex, start, end, transform) ?: source
    cell.copy(paragraphs = paragraphsFrom(updated))
}

internal fun textTableCellParagraphAt(table: OdfTable, row: Int, col: Int, position: Int): OdfParagraph? {
    val cell = table.rows.getOrNull(row)?.cells?.getOrNull(col) ?: return null
    val source = textDocumentForCell(cell)
    val index = source.runParagraphIndexAt(0, source.content.lastIndex, position)
    return cell.paragraphs.getOrNull(index)
}

internal fun textTableCellRangeHasFormat(
    table: OdfTable,
    row: Int,
    col: Int,
    start: Int,
    end: Int,
    predicate: (OdfSpan) -> Boolean,
): Boolean {
    val cell = table.rows.getOrNull(row)?.cells?.getOrNull(col) ?: return false
    val source = textDocumentForCell(cell)
    return source.runRangeHasFormat(0, source.content.lastIndex, start, end, predicate)
}

private fun textDocumentForCell(cell: OdfTableCell): OdfDocument.TextDocument {
    val paragraphs = cell.paragraphs.ifEmpty { listOf(OdfParagraph(listOf(OdfSpan(text = "")))) }
    return OdfDocument.TextDocument(
        title = "table cell",
        content = paragraphs.map { OdfContentBlock.Paragraph(it) }
    )
}

private fun paragraphsFrom(document: OdfDocument.TextDocument): List<OdfParagraph> =
    document.content.mapNotNull { (it as? OdfContentBlock.Paragraph)?.paragraph }

private fun updateTextTableCell(
    table: OdfTable,
    row: Int,
    col: Int,
    transform: (OdfTableCell) -> OdfTableCell,
): OdfTable {
    val rows = table.rows.toMutableList()
    val currentRow = rows.getOrNull(row) ?: return table
    val cells = currentRow.cells.toMutableList()
    val cell = cells.getOrNull(col) ?: return table
    cells[col] = transform(cell)
    rows[row] = OdfTableRow(cells)
    return table.copy(rows = rows)
}

fun OfficeViewModel.updateTextTableCell(
    blockIndex: Int,
    row: Int,
    col: Int,
    newText: String
) = withTextTable(blockIndex) { table ->
    updateTextTableCellModel(table, row, col, newText)
}

fun OfficeViewModel.textTableAddRow(blockIndex: Int, afterRow: Int) = withTextTable(blockIndex) { table ->
    val colCount = table.rows.firstOrNull()?.cells?.size ?: 1
    val newRow =
        OdfTableRow(List(colCount) { OdfTableCell(paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = ""))))) })
    val rows = table.rows.toMutableList()
    val at = (afterRow + 1).coerceIn(0, rows.size)
    rows.add(at, newRow)
    table.copy(rows = rows)
}

fun OfficeViewModel.textTableAddColumn(blockIndex: Int, afterCol: Int) = withTextTable(blockIndex) { table ->
    table.copy(rows = table.rows.map { row ->
        val cells = row.cells.toMutableList()
        val at = (afterCol + 1).coerceIn(0, cells.size)
        cells.add(at, OdfTableCell(paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = ""))))))
        OdfTableRow(cells)
    })
}

fun OfficeViewModel.textTableDeleteRow(blockIndex: Int, row: Int) = withTextTable(blockIndex) { table ->
    if (table.rows.size <= 1 || row !in table.rows.indices) table
    else table.copy(rows = table.rows.toMutableList().apply { removeAt(row) })
}

fun OfficeViewModel.textTableDeleteColumn(blockIndex: Int, col: Int) = withTextTable(blockIndex) { table ->
    table.copy(rows = table.rows.map { row ->
        if (row.cells.size <= 1 || col !in row.cells.indices) row
        else OdfTableRow(row.cells.toMutableList().apply { removeAt(col) })
    })
}

/** Per-cell character formatting for text-document tables (C26). */
fun OfficeViewModel.setTextTableCellSpanFormat(
    blockIndex: Int,
    row: Int,
    col: Int,
    transform: (OdfSpan) -> OdfSpan
) = withTextTable(blockIndex) { table ->
    updateTextTableCellSpans(table, row, col, transform)
}

fun OfficeViewModel.setTextTableCellAlignment(
    blockIndex: Int,
    row: Int,
    col: Int,
    alignment: androidx.compose.ui.text.style.TextAlign?
) = withTextTable(blockIndex) { table ->
    updateTextTableCellAlignment(table, row, col, alignment)
}

internal fun OfficeViewModel.applyTextTableCellSpanRange(
    blockIndex: Int,
    row: Int,
    col: Int,
    start: Int,
    end: Int,
    transform: (OdfSpan) -> OdfSpan,
) = withTextTable(blockIndex) { table ->
    updateTextTableCellSpanRange(table, row, col, start, end, transform)
}

internal fun OfficeViewModel.setTextTableCellParagraphAlignment(
    blockIndex: Int,
    row: Int,
    col: Int,
    start: Int,
    end: Int,
    alignment: androidx.compose.ui.text.style.TextAlign?,
) = withTextTable(blockIndex) { table ->
    updateTextTableCellParagraphsInRange(table, row, col, start, end) { it.copy(alignment = alignment) }
}

internal fun OfficeViewModel.setTextTableCellParagraphStyle(
    blockIndex: Int,
    row: Int,
    col: Int,
    start: Int,
    end: Int,
    style: com.vayunmathur.library.ui.odf.ParagraphStyle,
) = withTextTable(blockIndex) { table ->
    updateTextTableCellParagraphsInRange(table, row, col, start, end) { it.copy(style = style) }
}

internal fun OfficeViewModel.textTableCellParagraphAt(
    blockIndex: Int,
    row: Int,
    col: Int,
    position: Int,
): OdfParagraph? = currentTextTable(blockIndex)?.let { textTableCellParagraphAt(it, row, col, position) }

internal fun OfficeViewModel.textTableCellRangeHasFormat(
    blockIndex: Int,
    row: Int,
    col: Int,
    start: Int,
    end: Int,
    predicate: (OdfSpan) -> Boolean,
): Boolean = currentTextTable(blockIndex)?.let {
    textTableCellRangeHasFormat(it, row, col, start, end, predicate)
} ?: false

private fun OfficeViewModel.currentTextTable(blockIndex: Int): OdfTable? {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return null
    return (doc.content.getOrNull(blockIndex) as? OdfContentBlock.Table)?.table
}

fun OfficeViewModel.setTextTableCellBackground(
    blockIndex: Int,
    row: Int,
    col: Int,
    color: Long?
) = withTextTable(blockIndex) { table ->
    val rows = table.rows.toMutableList()
    val r = rows.getOrNull(row) ?: return@withTextTable table
    val cells = r.cells.toMutableList()
    val cell = cells.getOrNull(col) ?: return@withTextTable table
    cells[col] = cell.copy(backgroundColor = color)
    rows[row] = OdfTableRow(cells)
    table.copy(rows = rows)
}

/** Merge a rectangular block of text-table cells (C27). */
fun OfficeViewModel.mergeTextTableCells(
    blockIndex: Int,
    startRow: Int,
    startCol: Int,
    endRow: Int,
    endCol: Int
) = withTextTable(blockIndex) { table ->
    val rows = table.rows.toMutableList()
    val colSpan = endCol - startCol + 1
    val rowSpan = endRow - startRow + 1
    if (colSpan < 1 || rowSpan < 1) return@withTextTable table
    for (r in startRow..endRow) {
        val rr = rows.getOrNull(r) ?: continue
        val cells = rr.cells.toMutableList()
        for (c in startCol..endCol) {
            if (c !in cells.indices) continue
            cells[c] = if (r == startRow && c == startCol) cells[c].copy(colSpan = colSpan, rowSpan = rowSpan)
            else cells[c].copy(isCovered = true)
        }
        rows[r] = OdfTableRow(cells)
    }
    table.copy(rows = rows)
}

fun OfficeViewModel.unmergeTextTableCells(blockIndex: Int, row: Int, col: Int) = withTextTable(blockIndex) { table ->
    val rows = table.rows.toMutableList()
    val cell = rows.getOrNull(row)?.cells?.getOrNull(col) ?: return@withTextTable table
    if (cell.colSpan <= 1 && cell.rowSpan <= 1) return@withTextTable table
    for (r in row until minOf(row + cell.rowSpan, rows.size)) {
        val cells = rows[r].cells.toMutableList()
        for (c in col until minOf(col + cell.colSpan, cells.size)) {
            cells[c] = if (r == row && c == col) cells[c].copy(
                colSpan = 1,
                rowSpan = 1
            ) else cells[c].copy(isCovered = false)
        }
        rows[r] = OdfTableRow(cells)
    }
    table.copy(rows = rows)
}
