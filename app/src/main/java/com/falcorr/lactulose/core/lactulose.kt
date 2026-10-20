// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 4.6
// lactulose.kt
// The lactulose dose rule (user, 20/10/2026). The temperature dose follows the user's points (defaults 15 °C 0.3 mL,
// 20 °C 0.4 mL ... 50 °C 1.0 mL), interpolated in straight lines and extended past the ends along the end segments.
// The floor dose is the minimum for both the temperature dose and the feels-like dose (user, 09:36).
// The feels-like dose uses the same curve and floor settings; with shiftFeelsFloor on, the floor temperature is offset
// by feels-like minus temperature (user, 08:46); with it off the floor temperature is the same (user, 09:36).
// A window's dose is the mean of per-minute doses across the window (user, 09:35), plus a user-set offset
// (0.00 to 1.00 mL) added to both. Works for any floor temperature, including below zero.
package com.falcorr.lactulose.core

import java.util.Locale


// Defaults for the user-adjustable floor temperature and floor dose.
const val DEFAULT_FLOOR_C = 15.0
const val DEFAULT_FLOOR_ML = 0.3

// The user's points as (°C, mL): 0.02 mL per °C from 15 to 50 °C.
val DEFAULT_DOSE_POINTS = (15..50 step 5).map { Pair(it.toDouble(), it * 0.02) }

// Allowed ranges for the floor temperature, doses (floor dose and points) and point temperatures (user, 20/10/2026).
const val MIN_FLOOR_C = -15.0
const val MAX_FLOOR_C = 50.0
const val MIN_FLOOR_ML = 0.1
const val MAX_FLOOR_ML = 3.0

// Allowed range of the offset added to both window doses (user, 20/10/2026).
const val MIN_OFFSET_ML = 0.0
const val MAX_OFFSET_ML = 1.0

// Step between dose samples in the per-minute averaging (user, 09:35).
const val DOSE_STEP_MS = MINUTE_MS

// A dose rule with floor temperature floorC in °C, the floor dose floorMl in mL, and the points (°C, mL),
// sorted by temperature with no repeats and at least two of them, offsetMl added to both window doses, and
// shiftFeelsFloor choosing whether the feels-like dose's floor temperature moves by feels-like minus temperature
// (true) or uses the same floor temperature as the temperature dose (false) (user, 09:36).
class DoseRule(
  val floorC: Double = DEFAULT_FLOOR_C,
  val floorMl: Double = DEFAULT_FLOOR_ML,
  val points: List<Pair<Double, Double>> = DEFAULT_DOSE_POINTS,
  val offsetMl: Double = 0.0,
  val shiftFeelsFloor: Boolean = true
)
{
  // Returns the points' dose at celsius: straight lines between points, and past either end along the end segment.
  fun curveMl(celsius: Double): Double
  {
    var i = 1

    while (i < points.size - 1 && celsius > points[i].first) i++
    return points[i - 1].second + (points[i].second - points[i - 1].second) *
      (celsius - points[i - 1].first) / (points[i].first - points[i - 1].first)
  }


  // Returns the temperature dose in mL for one temperature, or NaN when unknown: the curve looked up directly,
  // with the floor dose as a minimum (user, 09:23).
  fun doseMl(celsius: Double): Double =
    if (celsius.isNaN()) Double.NaN else maxOf(floorMl, curveMl(celsius))


  // Returns the feels-like dose in mL for one pair of temperature and feels-like, or NaN when either is unknown.
  // Same floor dose as doseMl (user, 09:36). With shiftFeelsFloor on: the floor temperature is offset by
  // feels − temp; with it off, it uses the same floor temperature as the temperature dose (user, 09:36).
  fun feelsDoseMl(temp: Double, feels: Double): Double
  {
    if (temp.isNaN() || feels.isNaN()) return Double.NaN
    if (shiftFeelsFloor)
    {
      val feelsFloorC = floorC + (feels - temp)
      return maxOf(floorMl, curveMl(maxOf(feels, feelsFloorC)))
    }
    return maxOf(floorMl, curveMl(maxOf(feels, floorC)))
  }


  // Returns the mean of per-minute temperature doses across the window plus the offset, or NaN when not covered.
  fun doseByTemp(series: TempSeries, startMs: Long, endMs: Long): Double =
    meanOver(series, startMs, endMs) { t, _ -> doseMl(t) }


  // Returns the mean of per-minute feels-like doses across the window plus the offset, or NaN when not covered.
  fun doseByFeel(series: TempSeries, startMs: Long, endMs: Long): Double =
    meanOver(series, startMs, endMs) { t, f -> feelsDoseMl(t, f) }


  // Averages dose(temp, feels) at each minute from startMs to endMs inclusive, adds offsetMl. Returns NaN when
  // any sample falls outside the series.
  private fun meanOver(series: TempSeries, startMs: Long, endMs: Long, dose: (Double, Double) -> Double): Double
  {
    var sum = 0.0
    var count = 0
    var t = startMs

    while (t <= endMs)
    {
      val temp = series.valueAt(series.temps, t)
      val feels = series.valueAt(series.feels, t)

      if (temp.isNaN() || feels.isNaN()) return Double.NaN
      sum += dose(temp, feels)
      count++
      t += DOSE_STEP_MS
    }
    if (count == 0) return Double.NaN
    return sum / count + offsetMl
  }
}


// Formats a dose with exactly two decimals, e.g. "0.35mL", or "–mL" when unknown. A tiny nudge stops binary
// rounding error turning an exact half such as 0.425 into 0.42.
fun formatMl(millilitres: Double): String =
  if (millilitres.isNaN()) "–mL" else String.format(Locale.ROOT, "%.2fmL", millilitres + 1e-9)


// Formats points one per line as "°C mL", e.g. "15 0.30", for the settings box and preferences.
fun formatDosePoints(points: List<Pair<Double, Double>>): String =
  points.joinToString("\n") { trimNumber(it.first) + " " + String.format(Locale.ROOT, "%.2f", it.second) }


// Reads points written one per line (or comma separated) as "°C mL", sorted by temperature. Returns null unless
// there are at least two, with distinct temperatures within MIN_FLOOR_C..MAX_FLOOR_C and doses within
// MIN_FLOOR_ML..MAX_FLOOR_ML.
fun parseDosePoints(text: String): List<Pair<Double, Double>>?
{
  val points = ArrayList<Pair<Double, Double>>()
  var parts: List<String>

  for (line in text.split('\n', ','))
  {
    if (line.isBlank()) continue
    parts = line.trim().replace("=", " ").replace("°C", " ").replace("mL", " ").trim().split(Regex("\\s+"))
    if (parts.size != 2) return null
    val celsius = parts[0].toDoubleOrNull() ?: return null
    val millilitres = parts[1].toDoubleOrNull() ?: return null

    if (celsius !in MIN_FLOOR_C..MAX_FLOOR_C || millilitres !in MIN_FLOOR_ML..MAX_FLOOR_ML) return null
    points.add(Pair(celsius, millilitres))
  }
  points.sortBy { it.first }
  if (points.size < 2 || points.zipWithNext().any { it.first.first == it.second.first }) return null
  return points
}


// Formats a number without a trailing ".0", e.g. "15" or "17.5".
private fun trimNumber(value: Double): String =
  if (value == Math.floor(value)) value.toLong().toString() else value.toString()
