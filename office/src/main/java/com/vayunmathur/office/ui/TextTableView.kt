package com.vayunmathur.office.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell

@Composable
internal fun TextTableView(
    table: OdfTable,
    blockIndex: Int,
    fontSizeMultiplier: Float = 1f,
    onCellTextChange: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> },
    onCellFocus: (Int, Int, Int, Int, Int) -> Unit = { _, _, _, _, _ -> },
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    fun colWidthDp(start: Int, span: Int): androidx.compose.ui.unit.Dp {
        var w = 0f
        for (k in start until start + span) {
            val px = table.columns.getOrNull(k)?.width
            w += if (px != null && px > 0f) px * (160f / 96f) else 110f
        }
        return w.dp
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).horizontalScroll(rememberScrollState())) {
        for ((r, row) in table.rows.withIndex()) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                var colspanSkip = 0
                for ((c, cell) in row.cells.withIndex()) {
                    if (cell.isCovered) {
                        if (colspanSkip > 0) {
                            colspanSkip--
                            continue
                        }
                        Box(
                            Modifier.width(colWidthDp(c, 1)).fillMaxHeight()
                                .border(0.7.dp, MaterialTheme.colorScheme.outline)
                        ) {}
                        continue
                    }
                    colspanSkip = if (cell.colSpan > 1) cell.colSpan - 1 else 0
                    Box(
                        Modifier.width(colWidthDp(c, cell.colSpan))
                            .fillMaxHeight()
                            .border(0.7.dp, MaterialTheme.colorScheme.outline)
                            .then(cell.backgroundColor?.let { Modifier.background(Color(it.toInt())) } ?: Modifier)
                            .padding(8.dp)
                    ) {
                        EditableTextTableCell(
                            cell = cell,
                            onSurface = onSurface,
                            fontSizeMultiplier = fontSizeMultiplier,
                            onFocus = { selectionStart, selectionEnd ->
                                onCellFocus(blockIndex, r, c, selectionStart, selectionEnd)
                            },
                            onSelectionChange = { selectionStart, selectionEnd ->
                                onCellFocus(blockIndex, r, c, selectionStart, selectionEnd)
                            },
                            onChange = { text -> onCellTextChange(blockIndex, r, c, text) },
                        )
                    }
                }
            }
        }
        if (table.rows.isEmpty()) Text(
            stringResource(io.github.farhun.office.R.string.empty_table),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp)
        )
    }
}

@Composable
private fun EditableTextTableCell(
    cell: OdfTableCell,
    onSurface: Color,
    fontSizeMultiplier: Float,
    onFocus: (Int, Int) -> Unit,
    onSelectionChange: (Int, Int) -> Unit,
    onChange: (String) -> Unit,
) {
    val plain = cell.paragraphs.joinToString("\n") { paragraph ->
        paragraph.spans.joinToString("") { span -> span.text }
    }
    var textFieldValue by remember { mutableStateOf(TextFieldValue(plain)) }
    if (textFieldValue.text != plain) {
        textFieldValue = TextFieldValue(plain, TextRange(textFieldValue.selection.end.coerceIn(0, plain.length)))
    }
    val transformation = remember(cell.paragraphs, fontSizeMultiplier) {
        VisualTransformation {
            val annotated = buildAnnotatedString {
                var offset = 0
                cell.paragraphs.forEachIndexed { paragraphIndex, paragraph ->
                    val paragraphStart = offset
                    paragraph.spans.forEach { span ->
                        append(span.text)
                        val spanEnd = offset + span.text.length
                        if (spanEnd > offset) {
                            val decorations = buildList {
                                if (span.underline) add(TextDecoration.Underline)
                                if (span.strikethrough) add(TextDecoration.LineThrough)
                            }
                            addStyle(
                                SpanStyle(
                                    fontWeight = if (span.bold) FontWeight.Bold else null,
                                    fontStyle = if (span.italic) FontStyle.Italic else null,
                                    fontSize = span.fontSize?.let { (it * fontSizeMultiplier).sp }
                                        ?: TextUnit.Unspecified,
                                    color = span.color?.let { Color(it.toInt()) } ?: Color.Unspecified,
                                    background = span.backgroundColor?.let { Color(it.toInt()) } ?: Color.Unspecified,
                                    textDecoration = decorations.takeIf { it.isNotEmpty() }
                                        ?.let { TextDecoration.combine(it) },
                                ),
                                offset,
                                spanEnd,
                            )
                        }
                        offset = spanEnd
                    }
                    if (paragraphIndex < cell.paragraphs.lastIndex) {
                        append("\n")
                        offset++
                    }
                    if (offset > paragraphStart) {
                        addStyle(
                            ParagraphStyle(textAlign = paragraph.alignment ?: TextAlign.Unspecified),
                            paragraphStart,
                            offset,
                        )
                    }
                }
            }
            TransformedText(annotated, OffsetMapping.Identity)
        }
    }
    val style = MaterialTheme.typography.bodyMedium.copy(
        color = onSurface,
        fontSize = (14f * fontSizeMultiplier).sp,
        textAlign = TextAlign.Unspecified,
    )
    BasicTextField(
        value = textFieldValue,
        onValueChange = { newValue ->
            val changed = newValue.text != textFieldValue.text
            textFieldValue = newValue
            onSelectionChange(newValue.selection.min, newValue.selection.max)
            if (changed) onChange(newValue.text)
        },
        textStyle = style,
        visualTransformation = transformation,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().onFocusChanged { focusState ->
            if (focusState.isFocused) onFocus(textFieldValue.selection.min, textFieldValue.selection.max)
        },
    )
}
