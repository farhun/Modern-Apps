package com.vayunmathur.office.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

import com.vayunmathur.library.ui.odf.OdfDocument


import com.vayunmathur.library.ui.odf.renumberLists
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.office.R

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext


/** Local metadata for a document in the online folder (title/key stay client-side; never sent in the clear). */
internal const val MAX_UNDO = 30
internal const val MAX_RECENT = 20


class OfficeViewModel(application: Application) : AndroidViewModel(application) {
    internal val stateMutable = MutableStateFlow<ViewState>(ViewState.Empty)
    val state: StateFlow<ViewState> = stateMutable

    internal val isEditModeMutable = MutableStateFlow(false)
    val isEditMode: StateFlow<Boolean> = isEditModeMutable

    internal val hasUnsavedChangesMutable = MutableStateFlow(false)
    val hasUnsavedChanges: StateFlow<Boolean> = hasUnsavedChangesMutable

    internal val isSavingMutable = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = isSavingMutable

    internal val canUndoMutable = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = canUndoMutable

    internal val canRedoMutable = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = canRedoMutable

    internal val nightModeMutable = MutableStateFlow(false)
    val nightMode: StateFlow<Boolean> = nightModeMutable

    enum class DocumentThemeMode {
        UNCHANGED,
        FOLLOW_SYSTEM;

        fun toggled(): DocumentThemeMode = if (this == UNCHANGED) FOLLOW_SYSTEM else UNCHANGED

        companion object {
            fun parse(name: String?): DocumentThemeMode = try {
                valueOf(name ?: UNCHANGED.name)
            } catch (_: Exception) {
                UNCHANGED
            }
        }
    }

    // Persisted document theme preference: view-only, does not mutate file (#479).
    // UNCHANGED = always show as authored; FOLLOW_SYSTEM = follow system dark theme via view inversion.
    internal val documentThemeModeMutable = MutableStateFlow(DocumentThemeMode.UNCHANGED)
    val documentThemeMode: StateFlow<DocumentThemeMode> = documentThemeModeMutable

    // Legacy in-memory toggle (kept for View menu compat). Derived from persisted theme when needed.
    // Google-Docs-style dark mode: inverts the displayed colors of the document so the
    // paper appears dark, without modifying the document itself. View-only inversion.
    internal val documentDarkModeMutable = MutableStateFlow(false)
    val documentDarkMode: StateFlow<Boolean> = documentDarkModeMutable

    // Incremented whenever the document changes shape via undo/redo so the UI can
    // reset/clamp hoisted selection state (active cell/slide/element). (A4)
    internal val selectionInvalidationMutable = MutableStateFlow(0)
    val selectionInvalidation: StateFlow<Int> = selectionInvalidationMutable

    /** App-private read cache of the open document; used as the source package when re-writing. */
    internal var documentUri: Uri? = null

    /** The document the user actually opened. Save writes back here, never to [documentUri]. */
    internal var originalUri: Uri? = null

    /** Current open document (null unless [state] holds [ViewState.Loaded]). */
    val document: StateFlow<OdfDocument?> = stateMutable
        .map { (it as? ViewState.Loaded)?.document }
        .stateIn(viewModelScope, SharingStarted.Eagerly, (stateMutable.value as? ViewState.Loaded)?.document)

    // --- Auto-save ---
    internal var autoSaveJob: Job? = null
    internal var autoSaveEnabledMutable = MutableStateFlow(false)
    val autoSaveEnabled: StateFlow<Boolean> = autoSaveEnabledMutable
    internal var autoSaveIntervalMs: Long = 60_000L // default 1 minute

    private companion object {
        private const val MS_PER_SECOND = 1000L
        private const val DEFAULT_AUTO_SAVE_SECONDS = 60
        private const val DEFAULT_FONT_SIZE = 16f
    }

    fun setAutoSave(enabled: Boolean, intervalSeconds: Int = DEFAULT_AUTO_SAVE_SECONDS) {
        autoSaveIntervalMs = intervalSeconds * MS_PER_SECOND
        autoSaveEnabledMutable.value = enabled
        autoSaveJob?.cancel()
        if (enabled) {
            autoSaveJob = viewModelScope.launch {
                while (true) {
                    delay(autoSaveIntervalMs)
                    if (hasUnsavedChangesMutable.value && !needsSaveAs()) save()
                }
            }
        }
    }

    // --- Settings ---
    fun loadSettings(context: Context) {
        val prefs = context.getSharedPreferences("office_settings", Context.MODE_PRIVATE)
        val autoSave = prefs.getBoolean("auto_save", false)
        val interval = prefs.getInt("auto_save_interval", DEFAULT_AUTO_SAVE_SECONDS)
        setAutoSave(autoSave, interval)
        val themeName = prefs.getString("document_theme_mode", DocumentThemeMode.UNCHANGED.name)
        val mode = DocumentThemeMode.parse(themeName)
        val legacyDark = if (prefs.contains("document_dark_mode")) prefs.getBoolean(
            "document_dark_mode",
            false
        ) else null
        val resolved = if (legacyDark != null && !prefs.contains("document_theme_mode")) {
            if (legacyDark) DocumentThemeMode.FOLLOW_SYSTEM else DocumentThemeMode.UNCHANGED
        } else mode
        documentThemeModeMutable.value = resolved
        documentDarkModeMutable.value = resolved == DocumentThemeMode.FOLLOW_SYSTEM
    }

    fun saveSettings(context: Context, autoSave: Boolean, autoSaveInterval: Int, defaultFontSize: Float) {
        saveSettings(context, autoSave, autoSaveInterval, defaultFontSize, documentThemeModeMutable.value)
    }

    fun saveSettings(
        context: Context,
        autoSave: Boolean,
        autoSaveInterval: Int,
        defaultFontSize: Float,
        documentThemeMode: DocumentThemeMode
    ) {
        context.getSharedPreferences("office_settings", Context.MODE_PRIVATE).edit {
            putBoolean("auto_save", autoSave)
            putInt("auto_save_interval", autoSaveInterval)
            putFloat("default_font_size", defaultFontSize)
            putString("document_theme_mode", documentThemeMode.name)
        }
        setAutoSave(autoSave, autoSaveInterval)
        documentThemeModeMutable.value = documentThemeMode
        documentDarkModeMutable.value = documentThemeMode == DocumentThemeMode.FOLLOW_SYSTEM
    }

    fun getDocumentThemeMode(context: Context): DocumentThemeMode {
        val prefs = context.getSharedPreferences("office_settings", Context.MODE_PRIVATE)
        val name = prefs.getString("document_theme_mode", DocumentThemeMode.UNCHANGED.name)
        return DocumentThemeMode.parse(name)
    }

    fun setDocumentThemeMode(context: Context, mode: DocumentThemeMode) {
        documentThemeModeMutable.value = mode
        documentDarkModeMutable.value = mode == DocumentThemeMode.FOLLOW_SYSTEM
        context.getSharedPreferences(
            "office_settings",
            Context.MODE_PRIVATE
        ).edit { putString("document_theme_mode", mode.name) }
    }

    fun getDefaultFontSize(context: Context): Float {
        return context.getSharedPreferences("office_settings", Context.MODE_PRIVATE)
            .getFloat("default_font_size", DEFAULT_FONT_SIZE)
    }

    fun getAutoSaveInterval(context: Context): Int {
        return context.getSharedPreferences("office_settings", Context.MODE_PRIVATE)
            .getInt("auto_save_interval", DEFAULT_AUTO_SAVE_SECONDS)
    }

    fun getAutoSaveEnabled(context: Context): Boolean {
        return context.getSharedPreferences("office_settings", Context.MODE_PRIVATE)
            .getBoolean("auto_save", false)
    }

    // --- Undo/Redo ---
    internal val undoStack = ArrayDeque<OdfDocument>(maxOf(1, MAX_UNDO))
    internal val redoStack = ArrayDeque<OdfDocument>(maxOf(1, MAX_UNDO))

    internal fun pushUndo(doc: OdfDocument) {
        undoStack.addLast(doc)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        canUndoMutable.value = undoStack.isNotEmpty()
        canRedoMutable.value = false
    }

    fun undo() {
        val current = (stateMutable.value as? ViewState.Loaded)?.document ?: return
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current)
        stateMutable.value = ViewState.Loaded(previous)
        canUndoMutable.value = undoStack.isNotEmpty()
        canRedoMutable.value = redoStack.isNotEmpty()
        hasUnsavedChangesMutable.value = true
        selectionInvalidationMutable.value++
    }

    fun redo() {
        val current = (stateMutable.value as? ViewState.Loaded)?.document ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current)
        stateMutable.value = ViewState.Loaded(next)
        canUndoMutable.value = undoStack.isNotEmpty()
        canRedoMutable.value = redoStack.isNotEmpty()
        hasUnsavedChangesMutable.value = true
        selectionInvalidationMutable.value++
    }

    internal fun updateDocument(newDoc: OdfDocument) {
        val current = (stateMutable.value as? ViewState.Loaded)?.document ?: return
        pushUndo(current)
        // Keep ordered-list numbering live after every text edit (matches the markdown editor).
        val stored = if (newDoc is OdfDocument.TextDocument) renumberLists(newDoc) else newDoc
        stateMutable.value = ViewState.Loaded(stored)
        hasUnsavedChangesMutable.value = true
    }


    // --- Recent files ---


    // --- Night mode ---
    fun toggleNightMode() {
        nightModeMutable.value = !nightModeMutable.value
    }

    fun toggleDocumentDarkMode() {
        val next = documentThemeModeMutable.value.toggled()
        documentThemeModeMutable.value = next
        documentDarkModeMutable.value = next == DocumentThemeMode.FOLLOW_SYSTEM
        try {
            val app = getApplication<Application>()
            app.getSharedPreferences(
                "office_settings",
                Context.MODE_PRIVATE
            ).edit { putString("document_theme_mode", next.name) }
        } catch (_: Exception) {
        }
    }

    fun toggleDocumentDarkModePersisted(context: Context) {
        setDocumentThemeMode(context, documentThemeModeMutable.value.toggled())
    }

    // --- Load / Clear live in OfficeViewModelDocuments.kt ---

    fun toggleEditMode() {
        isEditModeMutable.value = !isEditModeMutable.value
    }


    sealed class ViewState {
        data object Empty : ViewState()
        data object Loading : ViewState()
        data class Loaded(val document: OdfDocument) : ViewState()
        data class Error(val message: String) : ViewState()
    }

    // Split members live as extensions in OfficeViewModel* files (same package).
}
