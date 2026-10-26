package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box


import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.IconCrop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.res.stringResource


import androidx.compose.ui.unit.dp

import com.vayunmathur.library.ui.odf.ContinuousParagraphEditor
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfImage


// --- Text Document (continuous editor) ---

internal sealed class DocSegment {
    data class Paragraphs(val start: Int, val endInclusive: Int) : DocSegment()
    data class Block(val index: Int) : DocSegment()
    data object PageBreak : DocSegment()
}

private fun buildSegments(
    content: List<OdfContentBlock>,
    showPageBreaks: Boolean,
): List<DocSegment> {
    val segments = mutableListOf<DocSegment>()
    var runStart = -1
    content.forEachIndexed { i, block ->
        if (block is OdfContentBlock.Paragraph) {
            if (showPageBreaks && block.paragraph.breakBeforePage) {
                if (runStart >= 0) {
                    segments.add(DocSegment.Paragraphs(runStart, i - 1))
                    runStart = -1
                }
                segments.add(DocSegment.PageBreak)
            }
            if (runStart < 0) runStart = i
        } else {
            if (runStart >= 0) {
                segments.add(DocSegment.Paragraphs(runStart, i - 1)); runStart = -1
            }
            segments.add(DocSegment.Block(i))
        }
    }
    if (runStart >= 0) segments.add(DocSegment.Paragraphs(runStart, content.size - 1))
    return segments
}

@Composable
fun TextDocumentView(
    doc: OdfDocument.TextDocument,
    searchQuery: String = "",
    fontSizeMultiplier: Float = 1f,
    listState: LazyListState = rememberLazyListState(),
    onRunSelectionChange: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> },
    onRunTextChange: (Int, Int, String) -> Unit = { _, _, _ -> },
    onRunEnter: (Int, Int, Int) -> Int? = { _, _, _ -> null },
    onRunBackspace: (Int, Int, Int) -> Int? = { _, _, _ -> null },
    onToggleCheckbox: (Int) -> Unit = {},
    onDeletePrevBlock: (Int) -> Unit = {},
    onCellTextChange: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> },
    onCellFocus: (Int, Int, Int, Int, Int) -> Unit = { _, _, _, _, _ -> },
    onChartClick: (Int) -> Unit = {},
    onCropImage: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    readOnly: Boolean = false,
    showPageBreaks: Boolean = false,
) {
    val segments = remember(doc.content, showPageBreaks) {
        buildSegments(doc.content, showPageBreaks)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().padding(contentPadding)
    ) {
        if (doc.headerParagraphs.isNotEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        for (para in doc.headerParagraphs) ParagraphView(
                            para,
                            "",
                            fontSizeMultiplier
                        )
                    }
                }
            }
        }

        items(segments.size) { si ->
            when (val seg = segments[si]) {
                DocSegment.PageBreak -> PageBreakView()

                is DocSegment.Paragraphs -> ContinuousParagraphEditor(
                    doc, seg.start, seg.endInclusive, fontSizeMultiplier, onRunSelectionChange,
                    onTextChange = { runStart, runEnd, text ->
                        if (!readOnly) onRunTextChange(runStart, runEnd, text)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    onEnter = { gPos -> if (readOnly) null else onRunEnter(seg.start, seg.endInclusive, gPos) },
                    onBackspace = { gPos -> if (readOnly) null else onRunBackspace(seg.start, seg.endInclusive, gPos) },
                    onToggleCheckbox = if (readOnly) null else onToggleCheckbox,
                    onDeletePrevBlock = { if (!readOnly) onDeletePrevBlock(seg.start) },
                    readOnly = readOnly,
                )

                is DocSegment.Block -> when (val block = doc.content[seg.index]) {
                    is OdfContentBlock.Table -> TextTableView(
                        block.table,
                        seg.index,
                        fontSizeMultiplier,
                        onCellTextChange = { bi, row, column, text ->
                            if (!readOnly) onCellTextChange(bi, row, column, text)
                        },
                        onCellFocus = onCellFocus,
                        readOnly = readOnly,
                    )

                    is OdfContentBlock.Image -> {
                        var fullScreen by remember { mutableStateOf(false) }
                        if (fullScreen) FullScreenImage(
                            block.image,
                            onCrop = if (readOnly) null else {
                                { fullScreen = false; onCropImage(seg.index) }
                            }
                        ) { fullScreen = false }
                        else Box(modifier = Modifier.clickable { fullScreen = true }) { OdfImageView(block.image) }
                    }

                    is OdfContentBlock.PageBreak -> PageBreakView()
                    is OdfContentBlock.Chart -> OdfChartView(
                        block.chart,
                        onClick = { if (!readOnly) onChartClick(seg.index) },
                    )

                    is OdfContentBlock.Formula -> MathView(block.mathml)
                    is OdfContentBlock.TableOfContents -> {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(
                                block.title,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            for (entry in block.entries) {
                                Text(
                                    entry.spans.joinToString("") { it.text },
                                    modifier = Modifier.padding(start = entry.marginLeft.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    is OdfContentBlock.Paragraph -> {}
                    is OdfContentBlock.SectionStart, OdfContentBlock.SectionEnd -> {}
                }
            }
        }

        if (doc.footnotes.isNotEmpty()) {
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
            items(doc.footnotes.size) { index ->
                val fn = doc.footnotes[index]
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("${fn.citation} ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f)) {
                        for (para in fn.body) ParagraphView(
                            para,
                            searchQuery,
                            fontSizeMultiplier
                        )
                    }
                }
            }
        }

        if (doc.footerParagraphs.isNotEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        for (para in doc.footerParagraphs) ParagraphView(
                            para,
                            "",
                            fontSizeMultiplier
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenImage(image: OdfImage, onCrop: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val bitmap = remember(
        image.path,
        image.imageData.size
    ) { BitmapFactory.decodeByteArray(image.imageData, 0, image.imageData.size) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            contentScale = ContentScale.Fit
        )
        if (onCrop != null) {
            TextButton(onClick = onCrop, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                IconCrop(tint = Color.White)
                androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
                Text(stringResource(io.github.farhun.office.R.string.crop), color = Color.White)
            }
        }
    }
}

@Composable
private fun PageBreakView() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 24.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}
