package com.buckey.financetracker.data.sheets

import com.buckey.financetracker.data.sheets.DashboardSheetFormat.CHART_MONTHS
import com.buckey.financetracker.data.sheets.DashboardSheetFormat.HIDDEN_FIRST_COLUMN
import com.buckey.financetracker.data.sheets.DashboardSheetFormat.HIDDEN_LAST_COLUMN
import com.buckey.financetracker.data.sheets.DashboardSheetFormat.SHEET_NAME
import com.google.api.services.sheets.v4.model.AddChartRequest
import com.google.api.services.sheets.v4.model.BasicChartDomain
import com.google.api.services.sheets.v4.model.BasicChartSeries
import com.google.api.services.sheets.v4.model.BasicChartSpec
import com.google.api.services.sheets.v4.model.BooleanCondition
import com.google.api.services.sheets.v4.model.CellData
import com.google.api.services.sheets.v4.model.CellFormat
import com.google.api.services.sheets.v4.model.ChartData
import com.google.api.services.sheets.v4.model.ChartSourceRange
import com.google.api.services.sheets.v4.model.ChartSpec
import com.google.api.services.sheets.v4.model.Color
import com.google.api.services.sheets.v4.model.ConditionValue
import com.google.api.services.sheets.v4.model.DataValidationRule
import com.google.api.services.sheets.v4.model.DimensionProperties
import com.google.api.services.sheets.v4.model.DimensionRange
import com.google.api.services.sheets.v4.model.EmbeddedChart
import com.google.api.services.sheets.v4.model.EmbeddedObjectPosition
import com.google.api.services.sheets.v4.model.GridCoordinate
import com.google.api.services.sheets.v4.model.GridProperties
import com.google.api.services.sheets.v4.model.GridRange
import com.google.api.services.sheets.v4.model.NumberFormat
import com.google.api.services.sheets.v4.model.OverlayPosition
import com.google.api.services.sheets.v4.model.PieChartSpec
import com.google.api.services.sheets.v4.model.RepeatCellRequest
import com.google.api.services.sheets.v4.model.Request
import com.google.api.services.sheets.v4.model.SetDataValidationRequest
import com.google.api.services.sheets.v4.model.SheetProperties
import com.google.api.services.sheets.v4.model.TextFormat
import com.google.api.services.sheets.v4.model.UpdateDimensionPropertiesRequest
import com.google.api.services.sheets.v4.model.UpdateSheetPropertiesRequest

/**
 * Formatting, the month and Spent dropdowns, the hidden helper columns and the three charts
 * for the Dashboard tab ([DashboardSheetFormat]). Every request targets only the
 * tab [tabId]; nothing here touches Transactions or Holdings.
 */
object DashboardSheetRequests {

    // The app's palette (ui/theme/Theme.kt), so the tab reads as Buckey's.
    private val GREEN = rgb(0x2F, 0x6B, 0x4A)
    private val LIGHT_GREEN = rgb(0x8D, 0xCF, 0xA7)
    private val MUTED = rgb(0x5C, 0x62, 0x5D)

    // Literal symbols quoted, as Sheets number formats require.
    private const val RUPEES = "\"₹\"#,##0;\"−₹\"#,##0"
    private const val SIGNED_RUPEES = "\"+₹\"#,##0;\"−₹\"#,##0;\"₹\"0"
    private const val SIGNED_PERCENT = "\"+\"0%;\"−\"0%;0%"
    private const val MONTH_FORMAT = "mmmm yyyy"

    fun build(tabId: Int): List<Request> {
        val months = 0 to CHART_MONTHS + 1
        return listOf(
            Request().setUpdateSheetProperties(
                UpdateSheetPropertiesRequest()
                    .setProperties(SheetProperties().setSheetId(tabId).setGridProperties(GridProperties().setHideGridlines(true)))
                    .setFields("gridProperties.hideGridlines")
            ),
            columns(tabId, 0, 5, DimensionProperties().setPixelSize(190), "pixelSize"),
            format(tabId, 0, 1, 0, 1, CellFormat().setTextFormat(TextFormat().setFontSize(18).setBold(true)), "textFormat"),
            format(tabId, 1, 3, 0, 1, CellFormat().setTextFormat(TextFormat().setForegroundColor(MUTED)), "textFormat"),
            format(tabId, 2, 3, 1, 2, CellFormat().setNumberFormat(number(MONTH_FORMAT, "DATE"))
                .setTextFormat(TextFormat().setBold(true)), "numberFormat,textFormat"),
            format(tabId, 4, 5, 0, 5, CellFormat().setTextFormat(TextFormat().setForegroundColor(MUTED)), "textFormat"),
            format(tabId, 5, 6, 0, 5, CellFormat().setNumberFormat(number(RUPEES, "CURRENCY"))
                .setTextFormat(TextFormat().setFontSize(20)), "numberFormat,textFormat"),
            format(tabId, 5, 6, 2, 3, CellFormat().setTextFormat(TextFormat().setFontSize(20).setForegroundColor(GREEN)), "textFormat"),
            format(tabId, 5, 6, 3, 4, CellFormat().setNumberFormat(number(SIGNED_RUPEES, "CURRENCY")), "numberFormat"),
            format(tabId, 6, 7, 0, 5, CellFormat().setTextFormat(TextFormat().setFontSize(9).setForegroundColor(MUTED)), "textFormat"),
            format(tabId, 6, 7, 3, 4, CellFormat().setNumberFormat(number(SIGNED_PERCENT, "PERCENT")), "numberFormat"),
            // T holds the months: shown in the dropdown as "October 2026".
            format(tabId, 1, 400, HIDDEN_FIRST_COLUMN, HIDDEN_FIRST_COLUMN + 1,
                CellFormat().setNumberFormat(number(MONTH_FORMAT, "DATE")), "numberFormat"),
            format(tabId, 2, 3, 2, 3, CellFormat().setTextFormat(TextFormat().setForegroundColor(MUTED)), "textFormat"),
            format(tabId, 2, 3, 3, 4, CellFormat().setTextFormat(TextFormat().setBold(true)), "textFormat"),
            dropdown(tabId, column = 1, condition = BooleanCondition().setType("ONE_OF_RANGE").setValues(
                listOf(ConditionValue().setUserEnteredValue("=$SHEET_NAME!\$T\$2:\$T\$400"))
            )),
            // What Spent means, like the app's "Spent shows" preference.
            dropdown(tabId, column = 3, condition = BooleanCondition().setType("ONE_OF_LIST").setValues(
                listOf(DashboardSheetFormat.SPENT_DEFAULT, DashboardSheetFormat.SPENT_TOTAL)
                    .map { ConditionValue().setUserEnteredValue(it) }
            )),
            columns(tabId, HIDDEN_FIRST_COLUMN, HIDDEN_LAST_COLUMN + 1, DimensionProperties().setHiddenByUser(true), "hiddenByUser"),
            // A pie (user, 2026-10-25): each category's share of the month's spending.
            pie(tabId, "Spending by category", labels = Triple(1, 40, 24), values = Triple(1, 40, 25), anchor = 8 to 0),
            chart(tabId, "Spent by month", "COLUMN", domain = Triple(months.first, months.second, 20),
                series = listOf(Triple(months.first, months.second, 21) to GREEN), anchor = 8 to 4, legend = "NO_LEGEND"),
            chart(tabId, "Income vs spent by month", "COLUMN", domain = Triple(months.first, months.second, 20),
                series = listOf(
                    Triple(months.first, months.second, 22) to LIGHT_GREEN,
                    Triple(months.first, months.second, 21) to GREEN
                ), anchor = 27 to 0, legend = "BOTTOM_LEGEND")
        )
    }

    private fun chart(
        tabId: Int,
        title: String,
        kind: String,
        domain: Triple<Int, Int, Int>,
        series: List<Pair<Triple<Int, Int, Int>, Color>>,
        anchor: Pair<Int, Int>,
        legend: String
    ): Request {
        fun source(r: Triple<Int, Int, Int>) =
            ChartData().setSourceRange(ChartSourceRange().setSources(listOf(grid(tabId, r.first, r.second, r.third, r.third + 1))))
        val spec = ChartSpec()
            .setTitle(title)
            .setHiddenDimensionStrategy("SHOW_ALL") // the data sits in hidden columns
            .setBasicChart(
                BasicChartSpec()
                    .setChartType(kind)
                    .setLegendPosition(legend)
                    .setHeaderCount(1)
                    .setDomains(listOf(BasicChartDomain().setDomain(source(domain))))
                    .setSeries(series.map { (range, color) ->
                        BasicChartSeries()
                            .setSeries(source(range))
                            .setTargetAxis(if (kind == "BAR") "BOTTOM_AXIS" else "LEFT_AXIS")
                            .setColor(color)
                    })
            )
        val position = EmbeddedObjectPosition().setOverlayPosition(
            OverlayPosition()
                .setAnchorCell(GridCoordinate().setSheetId(tabId).setRowIndex(anchor.first).setColumnIndex(anchor.second))
                .setWidthPixels(600)
                .setHeightPixels(340)
        )
        return Request().setAddChart(AddChartRequest().setChart(EmbeddedChart().setSpec(spec).setPosition(position)))
    }

    /** A strict dropdown in row 3 at [column]. */
    private fun dropdown(tabId: Int, column: Int, condition: BooleanCondition) =
        Request().setSetDataValidation(
            SetDataValidationRequest()
                .setRange(grid(tabId, 2, 3, column, column + 1))
                .setRule(DataValidationRule().setCondition(condition).setShowCustomUi(true).setStrict(true))
        )

    /** A pie over the category table's rows (no header row in the ranges). */
    private fun pie(
        tabId: Int,
        title: String,
        labels: Triple<Int, Int, Int>,
        values: Triple<Int, Int, Int>,
        anchor: Pair<Int, Int>
    ): Request {
        fun source(r: Triple<Int, Int, Int>) =
            ChartData().setSourceRange(ChartSourceRange().setSources(listOf(grid(tabId, r.first, r.second, r.third, r.third + 1))))
        val spec = ChartSpec()
            .setTitle(title)
            .setHiddenDimensionStrategy("SHOW_ALL") // the data sits in hidden columns
            .setPieChart(
                PieChartSpec()
                    .setDomain(source(labels))
                    .setSeries(source(values))
                    .setLegendPosition("LABELED_LEGEND")
            )
        val position = EmbeddedObjectPosition().setOverlayPosition(
            OverlayPosition()
                .setAnchorCell(GridCoordinate().setSheetId(tabId).setRowIndex(anchor.first).setColumnIndex(anchor.second))
                .setWidthPixels(600)
                .setHeightPixels(340)
        )
        return Request().setAddChart(AddChartRequest().setChart(EmbeddedChart().setSpec(spec).setPosition(position)))
    }

    private fun columns(tabId: Int, start: Int, end: Int, properties: DimensionProperties, fields: String) =
        Request().setUpdateDimensionProperties(
            UpdateDimensionPropertiesRequest()
                .setRange(DimensionRange().setSheetId(tabId).setDimension("COLUMNS").setStartIndex(start).setEndIndex(end))
                .setProperties(properties)
                .setFields(fields)
        )

    private fun format(tabId: Int, r0: Int, r1: Int, c0: Int, c1: Int, format: CellFormat, fields: String) =
        Request().setRepeatCell(
            RepeatCellRequest()
                .setRange(grid(tabId, r0, r1, c0, c1))
                .setCell(CellData().setUserEnteredFormat(format))
                .setFields("userEnteredFormat($fields)")
        )

    private fun grid(tabId: Int, r0: Int, r1: Int, c0: Int, c1: Int) =
        GridRange().setSheetId(tabId).setStartRowIndex(r0).setEndRowIndex(r1)
            .setStartColumnIndex(c0).setEndColumnIndex(c1)

    private fun number(pattern: String, type: String) = NumberFormat().setType(type).setPattern(pattern)

    private fun rgb(r: Int, g: Int, b: Int) = Color().setRed(r / 255f).setGreen(g / 255f).setBlue(b / 255f)
}
