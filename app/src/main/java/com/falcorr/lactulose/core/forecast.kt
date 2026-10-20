// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// forecast.kt
// Plain data held by the app: one day's forecast and a complete snapshot shown on screen.
package com.falcorr.lactulose.core


// One day's forecast. dateMs is local midnight; temperatures are degrees Celsius and rain is millimetres,
// with NaN where the source gives no value (BoM omits today's min once it has passed). rainChance is a
// percentage (BoM gives 5% steps), or -1 when unknown. stormLevel is BoM's thunderstorm wording as a
// likelihood: 0 none, 1 slight chance, 2 chance or possible, 3 likely (or a storm icon). icon is BoM's icon
// descriptor such as "mostly_sunny", or "" when not given.
class DayForecast(
  val dateMs: Long,
  val minC: Double,
  val maxC: Double,
  val rainChance: Int,
  val rainMinMm: Double,
  val rainMaxMm: Double,
  val summary: String,
  val stormLevel: Int,
  val icon: String = ""
)


// Hourly forecast extras: rain chance in percent (-1 unknown), whether the hour's forecast icon is a storm,
// BoM's icon descriptor, whether it is night, and the rain amount range in mm (NaN where not given), indexed like
// times (each the start of a forecast hour, epoch ms).
class HourlyExtras(
  val times: LongArray,
  val rainChance: IntArray,
  val storm: BooleanArray,
  val icon: Array<String> = Array(times.size) { "" },
  val night: BooleanArray = BooleanArray(times.size),
  val rainMinMm: DoubleArray = DoubleArray(times.size) { Double.NaN },
  val rainMaxMm: DoubleArray = DoubleArray(times.size) { Double.NaN }
)
{
  // Returns the index of the forecast hour containing timeMs, or -1 when no hour covers it.
  fun indexAt(timeMs: Long): Int
  {
    for (i in times.indices) if (timeMs >= times[i] && timeMs < times[i] + 3_600_000L) return i
    return -1
  }
}


// Everything on screen: place name and BoM geohash, when it was fetched, the latest observation (with rain since
// 9am, NaN when not reported), the graph series
// (past observations followed by hourly forecasts), hourly rain and storm forecasts, times of observations
// that reported thunder, and daily forecasts starting with today.
class WeatherSnapshot(
  val place: String,
  val geohash: String,
  val fetchedMs: Long,
  val obsTimeMs: Long,
  val nowTemp: Double,
  val nowFeels: Double,
  val rainSince9amMm: Double,
  val series: TempSeries,
  val hourly: HourlyExtras,
  val thunderObsTimes: LongArray,
  val days: List<DayForecast>
)
