// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// bomsource.kt
// Downloads a place's BoM hourly and daily forecasts, latest observation and recent half-hourly station
// observations into one compact JSON bundle (what gets cached), and parses a bundle into a WeatherSnapshot.
package com.falcorr.lactulose.data

import com.falcorr.lactulose.core.DayForecast
import com.falcorr.lactulose.core.HOUR_MS
import com.falcorr.lactulose.core.HourlyExtras
import com.falcorr.lactulose.core.Place
import com.falcorr.lactulose.core.TempSeries
import com.falcorr.lactulose.core.WeatherSnapshot
import com.falcorr.lactulose.io.FetchResult
import com.falcorr.lactulose.io.SecureFetch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter


// BoM hosts the app may contact, and the base URLs of the forecast service and observation feeds.
val BOM_HOSTS = setOf("api.weather.bom.gov.au", "www.bom.gov.au")
private const val BOM_API = "https://api.weather.bom.gov.au/v1/locations/"
private const val BOM_OBS = "https://www.bom.gov.au/fwo/"

// Observation feed product prefix per state (ACT uses the NSW feed).
private val OBS_PREFIX = mapOf("NSW" to "IDN", "ACT" to "IDN", "VIC" to "IDV", "QLD" to "IDQ", "SA" to "IDS",
  "WA" to "IDW", "TAS" to "IDT", "NT" to "IDD")

// How much observation history to keep, and how many nearby stations to try when one has no feed.
private const val OBS_KEEP_MS = 6 * HOUR_MS
private const val STATION_TRIES = 3

// Station feed times are UTC in this pattern, e.g. 20261020023000.
private val STATION_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

// Days kept: today plus up to three weeks (BoM currently gives today plus seven).
private const val DAYS_SHOWN = 22


class BomSource(private val fetch: SecureFetch, private val stations: () -> StationTable)
{
  // Downloads everything for place and returns the bundle as JSON text. An empty place name (phone location)
  // is filled from BoM's location service. Station history is optional: its failure still returns a bundle.
  fun download(place: Place, nowMs: Long): FetchResult<String>
  {
    val bundle = JSONObject()
    val hourly = fetch.get(BOM_API + place.geohash + "/forecasts/hourly")
    val daily = fetch.get(BOM_API + place.geohash + "/forecasts/daily")
    val obs = fetch.get(BOM_API + place.geohash + "/observations")
    var name = place.name
    var obsJson: JSONObject? = null

    for (result in listOf(hourly, daily, obs)) if (!result.ok) return FetchResult(false, error = result.error)
    try
    {
      if (name.isEmpty()) name = locationName(place)
      obsJson = JSONObject(obs.value!!)
      bundle.put("fetched", nowMs)
      bundle.put("name", name)
      bundle.put("geohash", place.geohash)
      bundle.put("hourly", JSONObject(hourly.value!!).getJSONArray("data"))
      bundle.put("daily", JSONObject(daily.value!!).getJSONArray("data"))
      bundle.put("obs", obsJson.optJSONObject("data") ?: JSONObject())
      bundle.put("obsTime", obsJson.optJSONObject("metadata")?.optString("observation_time") ?: "")
      bundle.put("station", stationHistory(place, obsJson, nowMs))
    }
    catch (e: Exception)
    {
      return FetchResult(false, error = "BoM data for " + place.geohash + " unreadable: " + e.message)
    }
    return FetchResult(true, bundle.toString())
  }


  // Returns BoM's name for the place's geohash, or its coordinates when BoM has no name.
  private fun locationName(place: Place): String
  {
    val result = fetch.get(BOM_API + place.geohash)
    val fallback = String.format(java.util.Locale.ROOT, "%.3f, %.3f", place.lat, place.lon)

    if (!result.ok) return fallback
    return JSONObject(result.value!!).optJSONObject("data")?.optString("name", fallback) ?: fallback
  }


  // Returns recent half-hourly observations as [utcMs, temp, feelsLike, weather] rows, newest first, from the
  // station BoM names for the place, or the nearest stations with a feed; an empty array if none answer.
  private fun stationHistory(place: Place, obsJson: JSONObject, nowMs: Long): JSONArray
  {
    val table = stations()
    val named = obsJson.optJSONObject("data")?.optJSONObject("station")?.optString("bom_id") ?: ""
    val candidates = (listOfNotNull(table.byId(named)) + table.nearest(place.lat, place.lon, STATION_TRIES))
      .distinctBy { it.wmo }.take(STATION_TRIES)
    val rows = JSONArray()

    for (station in candidates)
    {
      val prefix = OBS_PREFIX[station.state] ?: continue
      val result = fetch.get(BOM_OBS + prefix + "60801/" + prefix + "60801." + station.wmo + ".json")
      val data = if (result.ok) JSONObject(result.value!!).optJSONObject("observations")?.optJSONArray("data") else null

      if (data == null || data.length() == 0) continue
      for (i in 0 until data.length())
      {
        val row = data.getJSONObject(i)
        val timeMs = LocalDateTime.parse(row.getString("aifstime_utc"), STATION_TIME).toInstant(ZoneOffset.UTC)
          .toEpochMilli()

        if (timeMs < nowMs - OBS_KEEP_MS) break
        rows.put(JSONArray().put(timeMs).put(row.opt("air_temp")).put(row.opt("apparent_t"))
          .put(row.optString("weather", "-")))
      }
      return rows
    }
    return rows
  }
}


// Parses a bundle made by BomSource.download into what the screen shows. Observations fill the series up to
// the latest one; hourly forecasts continue it from there.
fun parseBundle(text: String, nowMs: Long): WeatherSnapshot
{
  val bundle = JSONObject(text)
  val hourly = bundle.getJSONArray("hourly")
  val station = bundle.getJSONArray("station")
  val obs = bundle.getJSONObject("obs")
  val times = ArrayList<Long>()
  val temps = ArrayList<Double>()
  val feels = ArrayList<Double>()
  val thunder = ArrayList<Long>()
  val hourTimes = LongArray(hourly.length())
  val hourRain = IntArray(hourly.length())
  val hourStorm = BooleanArray(hourly.length())
  val hourIcon = Array(hourly.length()) { "" }
  val hourNight = BooleanArray(hourly.length())
  val hourMinMm = DoubleArray(hourly.length()) { Double.NaN }
  val hourMaxMm = DoubleArray(hourly.length()) { Double.NaN }
  var lastObs = Long.MIN_VALUE

  // Station rows are newest first; walk them oldest first.
  for (i in station.length() - 1 downTo 0)
  {
    val row = station.getJSONArray(i)

    if (row.optString(3).contains("thunder", true) || row.optString(3).contains("storm", true)) thunder.add(row.getLong(0))
    if (row.isNull(1) || row.isNull(2) || row.getLong(0) <= lastObs) continue
    lastObs = row.getLong(0)
    times.add(lastObs)
    temps.add(row.getDouble(1))
    feels.add(row.getDouble(2))
  }
  for (i in 0 until hourly.length())
  {
    val hour = hourly.getJSONObject(i)
    val timeMs = Instant.parse(hour.getString("time")).toEpochMilli()

    hourTimes[i] = timeMs
    hourRain[i] = hour.optJSONObject("rain")?.optInt("chance", -1) ?: -1
    hourStorm[i] = hour.optString("icon_descriptor") == "storm"
    hourIcon[i] = hour.optString("icon_descriptor")
    hourNight[i] = hour.optBoolean("is_night", false)
    hour.optJSONObject("rain")?.let { rain ->
      val amount = rain.optJSONObject("amount")

      // BoM's amount range, or where it gives no upper amount its 50% and 25% chance amounts, which are what its
      // daily ranges mean (a 50% chance of at least the lower, 25% of at least the upper).
      hourMinMm[i] = number(amount, "min").let { if (it.isNaN()) number(rain, "precipitation_amount_50_percent_chance") else it }
      hourMaxMm[i] = number(amount, "max").let { if (it.isNaN()) number(rain, "precipitation_amount_25_percent_chance") else it }
    }
    if (timeMs <= lastObs || hour.isNull("temp") || hour.isNull("temp_feels_like")) continue
    times.add(timeMs)
    temps.add(hour.getDouble("temp"))
    feels.add(hour.getDouble("temp_feels_like"))
  }
  return WeatherSnapshot(
    bundle.getString("name"),
    bundle.optString("geohash"),
    bundle.getLong("fetched"),
    bundle.optString("obsTime").let { if (it.isEmpty()) 0L else Instant.parse(it).toEpochMilli() },
    obs.optDouble("temp", Double.NaN),
    obs.optDouble("temp_feels_like", Double.NaN),
    number(obs, "rain_since_9am"),
    TempSeries(times.toLongArray(), temps.toDoubleArray(), feels.toDoubleArray()),
    HourlyExtras(hourTimes, hourRain, hourStorm, hourIcon, hourNight, hourMinMm, hourMaxMm),
    thunder.toLongArray(),
    parseDays(bundle.getJSONArray("daily"), nowMs)
  )
}


// Returns json's number under key, or NaN when json is null or the value is missing or null.
private fun number(json: JSONObject?, key: String): Double =
  if (json == null || json.isNull(key)) Double.NaN else json.optDouble(key, Double.NaN)


// Returns BoM's thunderstorm likelihood from forecast wording: 3 for "likely" or a storm icon, 2 for "chance"
// or "possible", 1 for "slight chance", 0 when thunder isn't mentioned.
fun stormLevel(text: String, stormIcon: Boolean): Int
{
  val lower = text.lowercase()

  return when
  {
    stormIcon || Regex("thunderstorms? (are |is )?likely|likely thunderstorm").containsMatchIn(lower) -> 3
    Regex("slight chance of (a )?thunderstorm").containsMatchIn(lower) -> 1
    lower.contains("thunderstorm") -> 2
    else -> 0
  }
}


// Parses BoM daily forecasts, skipping days that have already ended, up to DAYS_SHOWN days.
private fun parseDays(daily: JSONArray, nowMs: Long): List<DayForecast>
{
  val days = ArrayList<DayForecast>(DAYS_SHOWN)

  for (i in 0 until daily.length())
  {
    val day = daily.getJSONObject(i)
    val dateMs = Instant.parse(day.getString("date")).toEpochMilli()
    val rain = day.optJSONObject("rain")
    val amount = rain?.optJSONObject("amount")
    val text = day.optString("extended_text", "") + " " + day.optString("short_text", "")

    if (dateMs + 24 * HOUR_MS <= nowMs) continue
    if (days.size == DAYS_SHOWN) break
    days.add(DayForecast(
      dateMs,
      day.optDouble("temp_min", Double.NaN),
      day.optDouble("temp_max", Double.NaN),
      if (rain == null || rain.isNull("chance")) -1 else rain.getInt("chance"),
      amount?.optDouble("min", Double.NaN) ?: Double.NaN,
      amount?.optDouble("max", Double.NaN) ?: Double.NaN,
      if (day.isNull("short_text")) "" else day.getString("short_text"),
      stormLevel(text, day.optString("icon_descriptor") == "storm"),
      day.optString("icon_descriptor")
    ))
  }
  return days
}
