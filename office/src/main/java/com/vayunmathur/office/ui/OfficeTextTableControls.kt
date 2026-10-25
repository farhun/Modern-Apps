package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.office.util.OfficeViewModel
import com.vayunmathur.office.util.mergeTextTableCells
import com.vayunmathur.office.util.setTextTableCellSpanFormat
import com.vayunmathur.office.util.textTableAddColumn
import com.vayunmathur.office.util.textTableAddRow
import com.vayunmathur.office.util.textTableDeleteColumn
import com.vayunmathur.office.util.textTableDeleteRow
import com.vayunmathur.office.util.unmergeTextTableCells

@Composable
internal fun OfficeTextTableControls(
    activeTableBlock: Int,
    activeTableRow: Int,
    activeTableCol: Int,
    viewModel: OfficeViewModel,
    actions: BottomBarActions,
) {
    var tableMenu by remember { mutableStateOf(false) }
    Box {
        val tableEnabled = activeTableBlock >= 0
        TextButton(onClick = { tableMenu = true }) {
            Text(stringResource(io.github.farhun.office.R.string.table))
            IconArrowDropDown()
        }
        DropdownMenu(expanded = tableMenu, onDismissRequest = { tableMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.insert_table)) },
                onClick = { tableMenu = false; actions.onInsertTable() })
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.insert_row_below)) },
                enabled = tableEnabled,
                onClick = { tableMenu = false; viewModel.textTableAddRow(activeTableBlock, activeTableRow) })
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.insert_column_right)) },
                enabled = tableEnabled,
                onClick = { tableMenu = false; viewModel.textTableAddColumn(activeTableBlock, activeTableCol) })
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.delete_row)) },
                enabled = tableEnabled,
                onClick = { tableMenu = false; viewModel.textTableDeleteRow(activeTableBlock, activeTableRow) })
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.delete_column)) },
                enabled = tableEnabled,
                onClick = { tableMenu = false; viewModel.textTableDeleteColumn(activeTableBlock, activeTableCol) })
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.bold_cell)) },
                enabled = tableEnabled,
                onClick = {
                    tableMenu = false
                    viewModel.setTextTableCellSpanFormat(activeTableBlock, activeTableRow, activeTableCol) {
                        it.copy(bold = !it.bold)
                    }
                })
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.italic_cell)) },
                enabled = tableEnabled,
                onClick = {
                    tableMenu = false
                    viewModel.setTextTableCellSpanFormat(activeTableBlock, activeTableRow, activeTableCol) {
                        it.copy(italic = !it.italic)
                    }
                })
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.merge_with_right)) },
                enabled = tableEnabled,
                onClick = {
                    tableMenu = false
                    viewModel.mergeTextTableCells(
                        activeTableBlock, activeTableRow, activeTableCol, activeTableRow, activeTableCol + 1
                    )
                })
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.merge_with_below)) },
                enabled = tableEnabled,
                onClick = {
                    tableMenu = false
                    viewModel.mergeTextTableCells(
                        activeTableBlock, activeTableRow, activeTableCol, activeTableRow + 1, activeTableCol
                    )
                })
            DropdownMenuItem(
                text = { Text(stringResource(io.github.farhun.office.R.string.unmerge_cell)) },
                enabled = tableEnabled,
                onClick = {
                    tableMenu = false
                    viewModel.unmergeTextTableCells(activeTableBlock, activeTableRow, activeTableCol)
                })
        }
    }
}
