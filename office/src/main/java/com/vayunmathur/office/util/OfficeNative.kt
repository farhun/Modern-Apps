package com.vayunmathur.office.util

import com.vayunmathur.library.ui.odf.OdfNamedRange
import com.vayunmathur.library.ui.odf.OdfNumberFormat
import com.vayunmathur.library.ui.odf.OdfSheet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JNI bridge to the native `office_engine` Rust ODF formula engine.
 */
object OfficeNative {
    init {
        System.loadLibrary("office_engine")
    }

    // ---- ODF formula engine ----

    /**
     * Parses a compact workbook JSON (see [buildWorkbookJson]) into a native
     * workbook; returns a handle (0 on failure). [nowMillis] seeds TODAY/NOW/RAND.
     */
    external fun nativeCreateWorkbook(json: String, nowMillis: Long): Long

    /** Display string for a cell (empty string out of range). */
    external fun nativeDisplayValue(handle: Long, sheetIdx: Int, row: Int, col: Int): String?

    /** Whether the (evaluated) cell holds a numeric value. */
    external fun nativeIsNumeric(handle: Long, sheetIdx: Int, row: Int, col: Int): Boolean

    /** Releases a workbook. */
    external fun nativeFree(handle: Long)

    /**
     * Formats [value] using a serialized [OdfNumberFormat] ([numberFormatJson]);
     * `"null"` or empty means "no format" (plain number). Standalone number
     * formatter for callers with no workbook context.
     */
    external fun nativeFormatValue(value: Double, numberFormatJson: String): String

    /** Formats [value] with an optional [fmt], serializing it to the NfDto JSON the Rust engine parses. */
    fun formatValue(value: Double, fmt: OdfNumberFormat?): String =
        nativeFormatValue(value, fmt?.let { json.encodeToString(nfDtoOf(it)) } ?: "null")

    /**
     * Convenience: serialize [sheets] and create a native workbook handle, or
     * return 0 if the native lib is unavailable or creation fails.
     */
    fun createWorkbook(
        sheets: List<OdfSheet>,
        nowMillis: Long = System.currentTimeMillis(),
        namedRanges: List<OdfNamedRange> = emptyList(),
    ): Long = nativeCreateWorkbook(buildWorkbookJson(sheets, namedRanges), nowMillis)

    // ---- Workbook serialization (must agree with the serde schema in formula.rs) ----

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /** Serializes all [sheets] (name + 2D cell grid) to the compact JSON the Rust engine parses. */
    fun buildWorkbookJson(sheets: List<OdfSheet>, namedRanges: List<OdfNamedRange> = emptyList()): String {
        val dto = WbDto(sheets.map { s ->
            SheetDto(s.name, s.rows.map { r ->
                RowDto(r.cells.map { c ->
                    CellDto(
                        text = c.text,
                        formula = c.formula,
                        numberValue = c.numberValue,
                        valueType = c.valueType,
                        isCovered = c.isCovered,
                        numberFormat = c.numberFormat?.let { nfDtoOf(it) }
                    )
                })
            })
        }, namedRanges.map { NrDto(it.name, it.cellRangeAddress) })
        return json.encodeToString(dto)
    }

    /** Maps an [OdfNumberFormat] to the compact NfDto the Rust engine parses. */
    private fun nfDtoOf(nf: OdfNumberFormat): NfDto = NfDto(
        decimals = nf.decimals,
        percent = nf.percent,
        currencySymbol = nf.currencySymbol,
        grouping = nf.grouping,
        isDate = nf.isDate,
        isTime = nf.isTime,
        isScientific = nf.isScientific,
        isFraction = nf.isFraction,
        fractionDenominatorDigits = nf.fractionDenominatorDigits,
        dateTimeTokens = nf.dateTimeTokens.map { t -> TokDto(t.kind, t.style, t.text, t.textual) }
    )

    @Serializable private data class WbDto(
        val sheets: List<SheetDto>,
        val namedRanges: List<NrDto> = emptyList(),
    )
    @Serializable private data class SheetDto(val name: String, val rows: List<RowDto>)
    @Serializable private data class RowDto(val cells: List<CellDto>)
    @Serializable private data class NrDto(val name: String, val addr: String)

    @Serializable private data class CellDto(
        val text: String,
        val formula: String? = null,
        val numberValue: Double? = null,
        val valueType: String? = null,
        val isCovered: Boolean = false,
        val numberFormat: NfDto? = null,
    )

    @Serializable private data class NfDto(
        val decimals: Int? = null,
        val percent: Boolean = false,
        val currencySymbol: String? = null,
        val grouping: Boolean = false,
        val isDate: Boolean = false,
        val isTime: Boolean = false,
        val isScientific: Boolean = false,
        val isFraction: Boolean = false,
        val fractionDenominatorDigits: Int = 1,
        val dateTimeTokens: List<TokDto> = emptyList(),
    )

    @Serializable private data class TokDto(
        val kind: String,
        val style: String? = null,
        val text: String? = null,
        val textual: Boolean = false,
    )
}
