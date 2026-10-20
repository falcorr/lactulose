// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// tempseries.kt
// Holds time-ordered temperature and feels-like samples in primitive arrays, with linear interpolation
// and exact time-weighted averages over any window.
package com.falcorr.lactulose.core


// Samples sorted by strictly ascending epoch-millisecond times; temps and feels are degrees Celsius and
// share indices with times.
class TempSeries(val times: LongArray, val temps: DoubleArray, val feels: DoubleArray)
{
  init
  {
    require(times.size == temps.size && times.size == feels.size) { "TempSeries arrays differ in length" }
    for (i in 1 until times.size)
    {
      require(times[i] > times[i - 1]) { "TempSeries times not ascending at index " + i }
    }
  }


  // Returns values interpolated at timeMs, or NaN when timeMs falls outside the sampled range.
  // values must be temps or feels of this series.
  fun valueAt(values: DoubleArray, timeMs: Long): Double
  {
    var upper = 0

    if (times.isEmpty() || timeMs < times[0] || timeMs > times[times.size - 1]) return Double.NaN
    upper = firstIndexAtOrAfter(timeMs)
    if (times[upper] == timeMs) return values[upper]
    return lerp(upper - 1, upper, values, timeMs)
  }


  // Returns the exact time-weighted mean of the piecewise-linear values between startMs and endMs, or NaN
  // if the samples don't cover the whole window or the window is empty.
  fun average(values: DoubleArray, startMs: Long, endMs: Long): Double
  {
    var area = 0.0
    var segStart = 0L
    var segEnd = 0L

    if (times.isEmpty() || endMs <= startMs || startMs < times[0] || endMs > times[times.size - 1])
    {
      return Double.NaN
    }
    for (i in 1 until times.size)
    {
      segStart = maxOf(times[i - 1], startMs)
      segEnd = minOf(times[i], endMs)
      if (segEnd > segStart)
      {
        area += (lerp(i - 1, i, values, segStart) + lerp(i - 1, i, values, segEnd)) / 2.0 * (segEnd - segStart)
      }
    }
    return area / (endMs - startMs)
  }


  // Returns the exact time-weighted mean of max(value, floor) between startMs and endMs, so any time below
  // floor counts as floor; NaN if the samples don't cover the window. Segments crossing floor are split at
  // the crossing so the result is exact.
  fun averageClamped(values: DoubleArray, startMs: Long, endMs: Long, floor: Double): Double
  {
    var area = 0.0
    var segStart = 0L
    var segEnd = 0L
    var a = 0.0
    var b = 0.0
    var cross = 0.0

    if (times.isEmpty() || endMs <= startMs || startMs < times[0] || endMs > times[times.size - 1])
    {
      return Double.NaN
    }
    for (i in 1 until times.size)
    {
      segStart = maxOf(times[i - 1], startMs)
      segEnd = minOf(times[i], endMs)
      if (segEnd <= segStart) continue
      a = lerp(i - 1, i, values, segStart)
      b = lerp(i - 1, i, values, segEnd)
      if (a >= floor && b >= floor) area += (a + b) / 2.0 * (segEnd - segStart)
      else if (a <= floor && b <= floor) area += floor * (segEnd - segStart)
      else
      {
        // Fraction of the segment before the line crosses floor.
        cross = (floor - a) / (b - a)
        if (a > floor) area += (a + floor) / 2.0 * cross * (segEnd - segStart) + floor * (1 - cross) * (segEnd - segStart)
        else area += floor * cross * (segEnd - segStart) + (floor + b) / 2.0 * (1 - cross) * (segEnd - segStart)
      }
    }
    return area / (endMs - startMs)
  }


  // Binary search for the first sample index whose time is at or after timeMs.
  private fun firstIndexAtOrAfter(timeMs: Long): Int
  {
    var low = 0
    var high = times.size - 1
    var mid = 0

    while (low < high)
    {
      mid = (low + high) ushr 1
      if (times[mid] < timeMs) low = mid + 1 else high = mid
    }
    return low
  }


  // Linearly interpolates values between sample indices a and b at timeMs.
  private fun lerp(a: Int, b: Int, values: DoubleArray, timeMs: Long): Double
  {
    val fraction = (timeMs - times[a]).toDouble() / (times[b] - times[a])

    return values[a] + (values[b] - values[a]) * fraction
  }
}
