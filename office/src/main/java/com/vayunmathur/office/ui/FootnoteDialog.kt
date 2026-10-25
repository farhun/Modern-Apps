package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.fillMaxWidth
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

@Composable
fun FootnoteDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(io.github.farhun.office.R.string.insert_footnote)) },
        text = { TextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(io.github.farhun.office.R.string.footnote_text)) },
            modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) { onAdd(text); onDismiss() } }) { Text(stringResource(io.github.farhun.office.R.string.insert)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } })
}
