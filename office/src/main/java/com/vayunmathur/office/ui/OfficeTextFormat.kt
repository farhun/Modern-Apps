package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.EditorBaseButtons

import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCheckBox
import com.vayunmathur.library.ui.IconFormatAlignCenter
import com.vayunmathur.library.ui.IconFormatAlignJustify
import com.vayunmathur.library.ui.IconFormatAlignLeft
import com.vayunmathur.library.ui.IconFormatAlignRight
import com.vayunmathur.library.ui.IconFormatColorText
import com.vayunmathur.library.ui.IconFormatIndentDecrease
import com.vayunmathur.library.ui.IconFormatIndentIncrease
import com.vayunmathur.library.ui.IconFormatListBulleted
import com.vayunmathur.library.ui.IconFormatListNumbered
import com.vayunmathur.library.ui.IconMoreVert

import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.odf.ListType
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.office.util.OfficeViewModel

import com.vayunmathur.office.util.changeListLevel
import com.vayunmathur.office.util.clearRunFormatting
import com.vayunmathur.office.util.deleteParagraph
import com.vayunmathur.office.util.duplicateParagraph
import com.vayunmathur.office.util.indentParagraph


import com.vayunmathur.office.util.moveParagraphDown
import com.vayunmathur.office.util.moveParagraphUp
import com.vayunmathur.office.util.mutateRunParagraphs
import com.vayunmathur.office.util.outdentParagraph

import com.vayunmathur.office.util.restartNumbering
import com.vayunmathur.office.util.runParagraphIndexAt


import com.vayunmathur.office.util.setTextTableCellParagraphAlignment
import com.vayunmathur.office.util.setTextTableCellParagraphStyle


import com.vayunmathur.office.util.textTableCellParagraphAt
import com.vayunmathur.office.util.textTableCellRangeHasFormat

import com.vayunmathur.office.util.toggleCheckbox
import com.vayunmathur.office.util.toggleListItem
import com.vayunmathur.office.util.toggleNumberedList

import androidx.compose.ui.res.stringResource

// --- Text document branch (ported from QuickFormatBar) ---

@Composable
internal fun TextFormatControls(
    doc: OdfDocument.TextDocument,
    target: FormatTarget,
    viewModel: OfficeViewModel,
    activeTableBlock: Int,
    activeTableRow: Int,
    activeTableCol: Int,
    actions: BottomBarActions
) {
    val runTarget = target as? FormatTarget.TextRun
    val cellTarget = target as? FormatTarget.TextTableRun
    val enabled = runTarget != null || cellTarget != null
    val runStart = runTarget?.runStart ?: -1
    val runEnd = runTarget?.runEnd ?: -1
    val selStart = runTarget?.selStart ?: cellTarget?.selStart ?: 0
    val selEnd = runTarget?.selEnd ?: cellTarget?.selEnd ?: 0
    val focusedPara = if (runTarget != null) {
        viewModel.runParagraphIndexAt(runStart, runEnd, selStart)
    } else {
        -1
    }
    val para = when {
        runTarget != null -> (doc.content.getOrNull(focusedPara) as? OdfContentBlock.Paragraph)?.paragraph
        cellTarget != null -> viewModel.textTableCellParagraphAt(
            cellTarget.block,
            cellTarget.row,
            cellTarget.col,
            cellTarget.selStart
        )

        else -> null
    }
    var styleMenu by remember { mutableStateOf(false) }
    var alignMenu by remember { mutableStateOf(false) }


    val isBullet = para?.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.BULLET
    val isNumbered = para?.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.NUMBERED
    val isCheckbox = para?.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.CHECKBOX

    val textFormatter = if (cellTarget != null) {
        TextTableRunFormatter(viewModel, cellTarget)
    } else {
        TextRunFormatter(viewModel, runStart, runEnd, selStart, selEnd, runTarget != null)
    }

    val styleLabel = when (para?.style) {
        ParagraphStyle.HEADING1 ->
            "H1"; ParagraphStyle.HEADING2 ->
            "H2"; ParagraphStyle.HEADING3 -> "H3"; ParagraphStyle.HEADING4 -> "H4"; else -> "Normal"
    }
    val alignIcon: @Composable () -> Unit = when (para?.alignment) {
        TextAlign.Center -> {
            { IconFormatAlignCenter() }
        }

        TextAlign.End, TextAlign.Right -> {
            { IconFormatAlignRight() }
        }

        TextAlign.Justify -> {
            { IconFormatAlignJustify() }
        }

        else -> {
            { IconFormatAlignLeft() }
        }
    }

    fun setStyle(s: ParagraphStyle) {
        if (runTarget != null) {
            viewModel.mutateRunParagraphs(runStart, runEnd, selStart, selEnd) { it.copy(style = s) }
        } else if (cellTarget != null) {
            viewModel.setTextTableCellParagraphStyle(
                cellTarget.block,
                cellTarget.row,
                cellTarget.col,
                cellTarget.selStart,
                cellTarget.selEnd,
                s
            )
        }
    }

    fun setAlign(a: TextAlign) {
        if (runTarget != null) {
            viewModel.mutateRunParagraphs(runStart, runEnd, selStart, selEnd) { it.copy(alignment = a) }
        } else if (cellTarget != null) {
            viewModel.setTextTableCellParagraphAlignment(
                cellTarget.block,
                cellTarget.row,
                cellTarget.col,
                cellTarget.selStart,
                cellTarget.selEnd,
                a
            )
        }
    }

    Box {
        TextButton(onClick = { styleMenu = true }, enabled = enabled) {
            Text(styleLabel)
            IconArrowDropDown()
        }
        DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.normal)) },
                onClick = { styleMenu = false; setStyle(ParagraphStyle.BODY) })
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.heading_1)) },
                onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING1) })
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.heading_2)) },
                onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING2) })
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.heading_3)) },
                onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING3) })
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.heading_4)) },
                onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING4) })
        }
    }
    EditorBaseButtons(textFormatter)
    FmtIcon(false, runTarget != null, { IconFormatColorText() }) { actions.onTextColor() }
    Box {
        FmtIcon(false, enabled, alignIcon) { alignMenu = true }
        DropdownMenu(expanded = alignMenu, onDismissRequest = { alignMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.left)) },
                leadingIcon = { IconFormatAlignLeft() },
                onClick = { alignMenu = false; setAlign(TextAlign.Start) })
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.center)) },
                leadingIcon = { IconFormatAlignCenter() },
                onClick = { alignMenu = false; setAlign(TextAlign.Center) })
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.right)) },
                leadingIcon = { IconFormatAlignRight() },
                onClick = { alignMenu = false; setAlign(TextAlign.End) })
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.justify)) },
                leadingIcon = { IconFormatAlignJustify() },
                onClick = { alignMenu = false; setAlign(TextAlign.Justify) })
        }
    }
    FmtIcon(
        isBullet,
        runTarget != null,
        { IconFormatListBulleted() }) { if (focusedPara >= 0) viewModel.toggleListItem(focusedPara) }
    FmtIcon(
        isNumbered,
        runTarget != null,
        { IconFormatListNumbered() }) { if (focusedPara >= 0) viewModel.toggleNumberedList(focusedPara) }
    FmtIcon(isCheckbox, runTarget != null, { IconCheckBox() }) {
        if (focusedPara >= 0) viewModel.toggleCheckbox(
            focusedPara
        )
    }
    FmtIcon(false, runTarget != null, { IconFormatIndentIncrease() }) {
        if (focusedPara >= 0) {
            if (para?.style == ParagraphStyle.LIST_ITEM) viewModel.changeListLevel(focusedPara, 1)
            else viewModel.indentParagraph(focusedPara)
        }
    }
    FmtIcon(false, runTarget != null, { IconFormatIndentDecrease() }) {
        if (focusedPara >= 0) {
            if (para?.style == ParagraphStyle.LIST_ITEM) viewModel.changeListLevel(focusedPara, -1)
            else viewModel.outdentParagraph(focusedPara)
        }
    }
    OfficeTextTableControls(activeTableBlock, activeTableRow, activeTableCol, viewModel, actions)
    Box {
        var moreMenu by remember { mutableStateOf(false) }
        FmtIcon(false, runTarget != null, { IconMoreVert() }) { moreMenu = true }
        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.font_size_2)) },
                onClick = { moreMenu = false; actions.onFontSize() })
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.clear_formatting)) },
                onClick = { moreMenu = false; viewModel.clearRunFormatting(runStart, runEnd, selStart, selEnd) })
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.demote_list_item)) },
                enabled = focusedPara >= 0,
                onClick = { moreMenu = false; if (focusedPara >= 0) viewModel.changeListLevel(focusedPara, 1) })
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.promote_list_item)) },
                enabled = focusedPara >= 0,
                onClick = { moreMenu = false; if (focusedPara >= 0) viewModel.changeListLevel(focusedPara, -1) })
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.restart_numbering)) },
                enabled = focusedPara >= 0,
                onClick = { moreMenu = false; if (focusedPara >= 0) viewModel.restartNumbering(focusedPara) })
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.duplicate_paragraph)) },
                enabled = focusedPara >= 0,
                onClick = { moreMenu = false; viewModel.duplicateParagraph(focusedPara) })
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.move_paragraph_up)) },
                enabled = focusedPara > 0,
                onClick = { moreMenu = false; viewModel.moveParagraphUp(focusedPara) })
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.move_paragraph_down)) },
                enabled = focusedPara >= 0,
                onClick = { moreMenu = false; viewModel.moveParagraphDown(focusedPara) })
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(io.github.farhun.office.R.string.delete_paragraph),
                        color = MaterialTheme.colorScheme.error
                    )
                },
                enabled = focusedPara >= 0,
                onClick = { moreMenu = false; viewModel.deleteParagraph(focusedPara) })
        }
    }
}
