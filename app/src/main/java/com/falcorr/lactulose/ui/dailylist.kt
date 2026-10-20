// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// dailylist.kt
// Compact rows of daily forecasts for the days after today: always the next seven, then any further days up to
// three weeks ahead in slightly darker text: day, weather icon, min and max, rain chance and amount, and the
// forecast summary, marked when thunderstorms are forecast with BoM's wording and the day's rain chance as its
// upper limit (BoM gives no number for thunder; a storm can't be likelier than rain). Rows are built once and only their text changes on refresh.
package com.falcorr.lactulose.ui

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.falcorr.lactulose.core.DayForecast
import java.text.SimpleDateFormat
import java.util.Locale


// Rows always shown (the next seven days), and the most rows (three weeks after today).
const val DAY_ROWS = 7
const val MAX_DAY_ROWS = 21

// Opacity of rows after the first DAY_ROWS.
private const val LATER_ROW_ALPHA = 0.72f

// Text colours, as ARGB.
const val COLOUR_TEXT = 0xFFE6EAF0.toInt()
const val COLOUR_DIM = 0xFF9AA4B2.toInt()
private const val COLOUR_RAIN = 0xFF81D4FA.toInt()
private const val COLOUR_DIVIDER = 0x22FFFFFF

// Thunderstorm likelihood words and colours by level (index 0 unused): pale, amber, red.
private val STORM_WORDS = arrayOf("", "slight", "chance", "likely")
private val STORM_COLOURS = intArrayOf(0, 0xFFFFF59D.toInt(), 0xFFFFC107.toInt(), 0xFFFF5252.toInt())


class DailyList(context: Context) : LinearLayout(context)
{
  // Screen density for dp conversion.
  private val density = resources.displayMetrics.density

  // Rows and the divider above each (none above the first), then per-row cells: day name, icon, temperatures, rain,
  // and summary.
  private val rows = ArrayList<View>(MAX_DAY_ROWS)
  private val dividers = ArrayList<View?>(MAX_DAY_ROWS)
  private val dayCells = ArrayList<TextView>(DAY_ROWS)
  private val iconCells = ArrayList<WeatherIcon>(DAY_ROWS)
  private val tempCells = ArrayList<TextView>(DAY_ROWS)
  private val rainCells = ArrayList<TextView>(DAY_ROWS)
  private val summaryCells = ArrayList<TextView>(DAY_ROWS)

  // Formats the day cell, e.g. "Tue 21".
  private val dayFormat = SimpleDateFormat("EEE d", Locale.getDefault())


  init
  {
    orientation = VERTICAL
    for (i in 0 until MAX_DAY_ROWS)
    {
      dividers.add(if (i == 0) null else View(context).apply { setBackgroundColor(COLOUR_DIVIDER) })
      dividers[i]?.let { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, 1)) }
      rows.add(buildRow())
      if (i >= DAY_ROWS) rows[i].alpha = LATER_ROW_ALPHA
      addView(rows[i])
    }
  }


  // Builds one row of four cells and records the cells for updates.
  private fun buildRow(): LinearLayout
  {
    val row = LinearLayout(context)

    row.orientation = HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
    dayCells.add(cell(row, 50, COLOUR_TEXT, true))
    iconCells.add(WeatherIcon(context, 22))
    row.addView(iconCells.last(), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
      rightMargin = (6 * density).toInt()
    })
    tempCells.add(cell(row, 56, COLOUR_TEXT, true))
    rainCells.add(cell(row, 92, COLOUR_RAIN, false))
    summaryCells.add(cell(row, 0, COLOUR_DIM, false))
    return row
  }


  // Adds a text cell to row with widthDp (0 means take the remaining width) and returns it.
  private fun cell(row: LinearLayout, widthDp: Int, colour: Int, bold: Boolean): TextView
  {
    val view = TextView(context)
    val params = if (widthDp == 0) LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
      else LayoutParams((widthDp * density).toInt(), LayoutParams.WRAP_CONTENT)

    view.setTextColor(colour)
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    view.maxLines = 2
    view.setPadding(0, 0, (4 * density).toInt(), 0)
    view.ellipsize = TextUtils.TruncateAt.END
    if (bold) view.paint.isFakeBoldText = true
    row.addView(view, params)
    return view
  }


  // Returns the height in pixels of the first count rows and their dividers, as last measured.
  fun visibleRowsHeight(count: Int): Int
  {
    var total = paddingTop

    for (i in 0 until minOf(count, rows.size))
    {
      total += (dividers[i]?.measuredHeight ?: 0) + rows[i].measuredHeight
    }
    return total
  }


  // Shows the days after today from days, which starts with today: the first DAY_ROWS always (a dash when
  // missing), later ones only when given.
  fun setDays(days: List<DayForecast>)
  {
    var day: DayForecast? = null

    for (i in 0 until MAX_DAY_ROWS)
    {
      day = days.getOrNull(i + 1)
      rows[i].visibility = if (i < DAY_ROWS || day != null) View.VISIBLE else View.GONE
      dividers[i]?.visibility = rows[i].visibility
      dayCells[i].text = if (day == null) "—" else dayFormat.format(day.dateMs)
      iconCells[i].setKind(day?.icon ?: "")
      tempCells[i].text = if (day == null) "" else degrees(day.minC) + " / " + degrees(day.maxC)
      rainCells[i].text = if (day == null) "" else (if (day.rainChance < 0) "–" else day.rainChance.toString()) +
        "% " + rainRange(day)
      summaryCells[i].text = if (day == null) "" else if (day.stormLevel > 0) stormText(day) else day.summary
    }
  }


  // Returns the day's summary led by its storm label.
  private fun stormText(day: DayForecast): CharSequence =
    android.text.SpannableStringBuilder(stormLabel(day.stormLevel, day.rainChance)).append(" · " + day.summary)
}


// Returns a storm mark, BoM's thunderstorm likelihood for level 1 to 3 and, when known, the rain chance as its
// upper limit (e.g. "ϟ chance ≤30%"), coloured by likelihood.
fun stormLabel(level: Int, rainChance: Int): CharSequence
{
  val text = android.text.SpannableString(STORM_MARK + " " + STORM_WORDS[level] +
    if (rainChance > 0) " ≤" + rainChance + "%" else "")

  text.setSpan(android.text.style.ForegroundColorSpan(STORM_COLOURS[level]), 0, text.length, 0)
  return text
}


// Formats a day's rain amount range in millimetres, e.g. "0–3mm", or "" when the source gives none.
fun rainRange(day: DayForecast): String
{
  if (day.rainMaxMm.isNaN()) return ""
  if (day.rainMinMm.isNaN() || day.rainMinMm == day.rainMaxMm) return trim(day.rainMaxMm) + "mm"
  return trim(day.rainMinMm) + "–" + trim(day.rainMaxMm) + "mm"
}


// Formats whole degrees Celsius such as "24°", or "–" when the value is missing.
fun degrees(celsius: Double): String =
  if (celsius.isNaN()) "–" else Math.round(celsius).toString() + "°"


// Formats a value without a trailing ".0".
fun trim(value: Double): String =
  if (value == Math.floor(value)) value.toLong().toString() else String.format(Locale.getDefault(), "%.1f", value)
