package com.vayunmathur.office.ui

import com.vayunmathur.library.ui.EditorFormat
import com.vayunmathur.library.ui.EditorFormatter
import com.vayunmathur.library.ui.LinkContext
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.office.util.OfficeViewModel
import com.vayunmathur.office.util.applyRunSpanStyle
import com.vayunmathur.office.util.applyTextTableCellSpanRange
import com.vayunmathur.office.util.linkAt
import com.vayunmathur.office.util.removeLinkInRun
import com.vayunmathur.office.util.runRangeHasFormat
import com.vayunmathur.office.util.runSelectedText
import com.vayunmathur.office.util.setLink
import com.vayunmathur.office.util.textTableCellRangeHasFormat

/** Inline character formatting for a text-document run selection. */
internal class TextRunFormatter(
    private val viewModel: OfficeViewModel,
    private val runStart: Int,
    private val runEnd: Int,
    private val selStart: Int,
    private val selEnd: Int,
    override val enabled: Boolean,
) : EditorFormatter {
    override val supported = setOf(
        EditorFormat.BOLD, EditorFormat.ITALIC, EditorFormat.UNDERLINE, EditorFormat.STRIKETHROUGH,
        EditorFormat.LINK,
    )

    override fun isActive(format: EditorFormat): Boolean = enabled && when (format) {
        EditorFormat.BOLD -> viewModel.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.bold }
        EditorFormat.ITALIC -> viewModel.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.italic }
        EditorFormat.UNDERLINE -> viewModel.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.underline }
        EditorFormat.STRIKETHROUGH -> viewModel.runRangeHasFormat(
            runStart,
            runEnd,
            selStart,
            selEnd
        ) { it.strikethrough }

        else -> false
    }

    override fun toggle(format: EditorFormat) {
        if (!enabled) return
        when (format) {
            EditorFormat.BOLD -> {
                val t = !isActive(EditorFormat.BOLD); viewModel.applyRunSpanStyle(
                    runStart,
                    runEnd,
                    selStart,
                    selEnd
                ) { it.copy(bold = t) }
            }

            EditorFormat.ITALIC -> {
                val t = !isActive(EditorFormat.ITALIC); viewModel.applyRunSpanStyle(
                    runStart,
                    runEnd,
                    selStart,
                    selEnd
                ) { it.copy(italic = t) }
            }

            EditorFormat.UNDERLINE -> {
                val t = !isActive(EditorFormat.UNDERLINE); viewModel.applyRunSpanStyle(
                    runStart,
                    runEnd,
                    selStart,
                    selEnd
                ) { it.copy(underline = t) }
            }

            EditorFormat.STRIKETHROUGH -> {
                val t = !isActive(EditorFormat.STRIKETHROUGH); viewModel.applyRunSpanStyle(
                    runStart,
                    runEnd,
                    selStart,
                    selEnd
                ) { it.copy(strikethrough = t) }
            }

            else -> {}
        }
    }

    override fun linkContext(): LinkContext? {
        if (!enabled) return null
        val link = viewModel.linkAt(runStart, runEnd, selStart)
        if (link != null) return LinkContext(editing = true, text = link.text, url = link.url)
        if (selStart != selEnd) return LinkContext(
            editing = false,
            text = viewModel.runSelectedText(runStart, runEnd, selStart, selEnd),
            url = ""
        )
        return null
    }

    override fun applyLink(context: LinkContext, text: String, url: String) {
        if (!enabled || url.isBlank()) return
        val link = viewModel.linkAt(runStart, runEnd, selStart)
        val gs: Int;
        val ge: Int
        if (link != null) {
            gs = link.gStart; ge = link.gEnd
        } else if (selStart != selEnd) {
            gs = selStart; ge = selEnd
        } else return
        viewModel.setLink(runStart, runEnd, gs, ge, text.ifBlank { url }, url)
    }

    override fun removeLink(context: LinkContext) {
        val link = viewModel.linkAt(runStart, runEnd, selStart) ?: return
        viewModel.removeLinkInRun(runStart, runEnd, link.gStart, link.gEnd)
    }
}

/** Inline formatting for a paragraph run inside an ODF text-table cell. */
internal class TextTableRunFormatter(
    private val viewModel: OfficeViewModel,
    private val target: FormatTarget.TextTableRun,
) : EditorFormatter {
    override val enabled = true
    override val supported = setOf(
        EditorFormat.BOLD,
        EditorFormat.ITALIC,
        EditorFormat.UNDERLINE,
        EditorFormat.STRIKETHROUGH,
    )

    override fun isActive(format: EditorFormat): Boolean = when (format) {
        EditorFormat.BOLD -> viewModel.textTableCellRangeHasFormat(
            target.block,
            target.row,
            target.col,
            target.selStart,
            target.selEnd
        ) { it.bold }

        EditorFormat.ITALIC -> viewModel.textTableCellRangeHasFormat(
            target.block,
            target.row,
            target.col,
            target.selStart,
            target.selEnd
        ) { it.italic }

        EditorFormat.UNDERLINE -> viewModel.textTableCellRangeHasFormat(
            target.block,
            target.row,
            target.col,
            target.selStart,
            target.selEnd
        ) { it.underline }

        EditorFormat.STRIKETHROUGH -> viewModel.textTableCellRangeHasFormat(
            target.block,
            target.row,
            target.col,
            target.selStart,
            target.selEnd
        ) { it.strikethrough }

        else -> false
    }

    override fun toggle(format: EditorFormat) {
        val active = isActive(format)
        val transform: (OdfSpan) -> OdfSpan = when (format) {
            EditorFormat.BOLD -> { span -> span.copy(bold = !active) }
            EditorFormat.ITALIC -> { span -> span.copy(italic = !active) }
            EditorFormat.UNDERLINE -> { span -> span.copy(underline = !active) }
            EditorFormat.STRIKETHROUGH -> { span -> span.copy(strikethrough = !active) }
            else -> return
        }
        viewModel.applyTextTableCellSpanRange(
            target.block,
            target.row,
            target.col,
            target.selStart,
            target.selEnd,
            transform,
        )
    }
}
