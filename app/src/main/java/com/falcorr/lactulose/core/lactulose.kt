// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// lactulose.kt
// The lactulose dose rule (user, 20/10/2026): the floor (floor temperature, floor dose) is itself the first point
// of the curve, followed by the user's points above the floor temperature, interpolated in straight lines and
// extended past the ends along the end segments, so the curve can be made nonlinear by editing points. Points at
// or below the floor temperature are replaced by the floor. The defaults are the user's points (15 °C 0.3 mL,
// 20 °C 0.4 mL ... 50 °C 1.0 mL). Changing the floor dose therefore bends the curve smoothly up to the next point
// rather than flattening it. The temperature dose never goes below the floor dose; below the floor temperature the
// floor temperature counts instead. The feels-like dose uses a floor temperature lowered by how far feels-like is
// below the temperature at each moment and follows the same curve, continued below the floor along its first
// segment, so it may go below the floor dose (never below zero) and moves with the floor dose. A window's dose is the mean of each moment's dose, plus a
// user-set offset (0.00 to 1.00 mL) added to both doses. Works for any floor temperature, including below zero.
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

// Window doses average the dose at the middle of each minute.
private const val DOSE_STEP_MS = 60_000L


// A dose rule with floor temperature floorC in °C, the floor dose floorMl in mL, and the points (°C, mL),
// sorted by temperature with no repeats and at least two of them, and offsetMl added to both window doses.
class DoseRule(
  val floorC: Double = DEFAULT_FLOOR_C,
  val floorMl: Double = DEFAULT_FLOOR_ML,
  val points: List<Pair<Double, Double>> = DEFAULT_DOSE_POINTS,
  val offsetMl: Double = 0.0
)
{
  // The curve's points: the floor, then the points above the floor temperature. With none above it, a point 5 °C
  // higher continues at the points' last slope (or level if they give none).
  private val curve: List<Pair<Double, Double>> = run {
    val above = points.filter { it.first > floorC }
    val slope = if (points.size >= 2) (points.last().second - points[points.size - 2].second) /
      (points.last().first - points[points.size - 2].first) else 0.0

    listOf(Pair(floorC, floorMl)) + above.ifEmpty { listOf(Pair(floorC + 5.0, floorMl + 5.0 * slope)) }
  }


  // Returns the curve's dose at celsius: straight lines between points, and past either end along the end segment.
  fun curveMl(celsius: Double): Double
  {
    val points = curve
    var i = 1

    while (i < points.size - 1 && celsius > points[i].first) i++
    return points[i - 1].second + (points[i].second - points[i - 1].second) *
      (celsius - points[i - 1].first) / (points[i].first - points[i - 1].first)
  }


  // Returns the dose in mL for one temperature, or NaN when unknown.
  fun doseMl(celsius: Double): Double =
    if (celsius.isNaN()) Double.NaN else maxOf(floorMl, curveMl(maxOf(celsius, floorC)))


  // Returns the mean dose over series' temperatures between startMs and endMs plus the offset, or NaN when the
  // window isn't covered.
  fun doseByTemp(series: TempSeries, startMs: Long, endMs: Long): Double =
    meanOver(startMs, endMs) { doseMl(series.valueAt(series.temps, it)) } + offsetMl


  // Returns the mean dose over series' feels-like between startMs and endMs plus the offset. At each moment
  // feels-like is held no lower than the floor temperature minus that moment's gap between temperature and
  // feels-like, which is the same as adding the amount temperature is below the floor; feelsDoseMl gives its dose.
  fun doseByFeel(series: TempSeries, startMs: Long, endMs: Long): Double =
    meanOver(startMs, endMs) {
      val temp = series.valueAt(series.temps, it)
      val feels = series.valueAt(series.feels, it)

      if (temp.isNaN() || feels.isNaN()) Double.NaN else feelsDoseMl(feels + maxOf(0.0, floorC - temp))
    } + offsetMl


  // Returns the by-feel dose for an adjusted feels-like of celsius: at or above the floor temperature the same as
  // the temperature dose; below it the curve continued below the floor, never below zero.
  fun feelsDoseMl(celsius: Double): Double =
    if (celsius >= floorC) doseMl(celsius) else maxOf(0.0, curveMl(celsius))


  // Returns the mean of dose sampled at the middle of each DOSE_STEP_MS step from startMs to endMs; NaN if any is.
  private fun meanOver(startMs: Long, endMs: Long, dose: (Long) -> Double): Double
  {
    val steps = maxOf(1L, (endMs - startMs) / DOSE_STEP_MS)
    val step = (endMs - startMs).toDouble() / steps
    var total = 0.0

    for (i in 0 until steps) total += dose(startMs + ((i + 0.5) * step).toLong())
    return total / steps
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
