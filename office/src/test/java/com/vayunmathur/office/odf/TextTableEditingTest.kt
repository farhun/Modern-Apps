package com.vayunmathur.office.odf

import androidx.compose.ui.text.style.TextAlign
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.library.ui.odf.OdfTableRow
import com.vayunmathur.office.util.updateTextTableCellAlignment
import com.vayunmathur.office.util.updateTextTableCellModel
import com.vayunmathur.office.util.updateTextTableCellParagraphsInRange
import com.vayunmathur.office.util.updateTextTableCellSpanRange
import com.vayunmathur.office.util.updateTextTableCellSpans
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextTableEditingTest {
    @Test
    fun boldAndCenterModelMutationsApplyInsideCellParagraph() {
        val original = tableWithStyledCell()
        val bold = updateTextTableCellSpans(original, 0, 0) { it.copy(bold = true) }
        val centered = updateTextTableCellAlignment(bold, 0, 0, TextAlign.Center)
        val paragraph = centered.rows[0].cells[0].paragraphs[0]

        assertTrue(paragraph.spans.all { it.bold })
        assertEquals(TextAlign.Center, paragraph.alignment)
    }

    @Test
    fun typingIntoStyledCellPreservesParagraphAndRunFormatting() {
        val original = tableWithStyledCell()
        val edited = updateTextTableCellModel(original, 0, 0, "styled text!")
        val paragraph = edited.rows[0].cells[0].paragraphs[0]

        assertEquals("styled text!", paragraph.spans.joinToString("") { it.text })
        assertEquals(TextAlign.Center, paragraph.alignment)
        assertTrue(paragraph.spans.all { it.bold && it.italic })
        assertTrue(paragraph.spans.all { it.fontFamily == "Liberation Serif" })
    }

    @Test
    fun formattingSelectionTargetsParagraphInsideCell() {
        val original = tableWithTwoParagraphCell()
        val bold = updateTextTableCellSpanRange(original, 0, 0, 6, 17) { it.copy(bold = true) }
        val centered = updateTextTableCellParagraphsInRange(bold, 0, 0, 6, 17) {
            it.copy(alignment = TextAlign.Center)
        }
        val paragraphs = centered.rows[0].cells[0].paragraphs

        assertTrue(paragraphs[0].spans.none { it.bold })
        assertEquals(null, paragraphs[0].alignment)
        assertTrue(paragraphs[1].spans.all { it.bold })
        assertEquals(TextAlign.Center, paragraphs[1].alignment)
    }

    private fun tableWithTwoParagraphCell() = OdfTable(
        rows = listOf(
            OdfTableRow(
                cells = listOf(
                    OdfTableCell(
                        paragraphs = listOf(
                            OdfParagraph(listOf(OdfSpan(text = "first"))),
                            OdfParagraph(listOf(OdfSpan(text = "second text"))),
                        )
                    )
                )
            )
        )
    )

    private fun tableWithStyledCell() = OdfTable(
        rows = listOf(
            OdfTableRow(
                cells = listOf(
                    OdfTableCell(
                        paragraphs = listOf(
                            OdfParagraph(
                                spans = listOf(
                                    OdfSpan(
                                        text = "styled text",
                                        bold = true,
                                        italic = true,
                                        fontFamily = "Liberation Serif"
                                    )
                                ),
                                alignment = TextAlign.Center
                            )
                        )
                    )
                )
            )
        )
    )
}
