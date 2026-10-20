// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// outages.kt
// Reads Essential Energy's current, future and cancelled outage maps (KML) as streams and keeps only outages
// whose area contains a given point, so the multi-megabyte future map is never held in memory. Essential
// Energy publishes no history, so outages seen earlier are kept on the phone for 7 days after they end.
package com.falcorr.lactulose.data

import android.util.Xml
import com.falcorr.lactulose.io.FetchResult
import com.falcorr.lactulose.io.SecureFetch
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter


// Essential Energy host and its outage maps: outages happening now, and planned ones.
val OUTAGE_HOSTS = setOf("www.essentialenergy.com.au")
private const val CURRENT_URL = "https://www.essentialenergy.com.au/Assets/kmz/current.kml"
private const val FUTURE_URL = "https://www.essentialenergy.com.au/Assets/kmz/future.kml"
private const val CANCELLED_URL = "https://www.essentialenergy.com.au/Assets/kmz/cancelled.kml"

// Size caps for the two maps (the future map is about 3 MB uncompressed).
private const val CURRENT_MAX_BYTES = 5_000_000L
private const val FUTURE_MAX_BYTES = 20_000_000L

// How far ahead planned outages are listed, and how long ended outages stay listed.
const val OUTAGE_AHEAD_MS = 14 * 24 * 3_600_000L
const val OUTAGE_KEEP_MS = 7 * 24 * 3_600_000L

// Outage states.
const val OUTAGE_PAST = 0
const val OUTAGE_NOW = 1
const val OUTAGE_PLANNED = 2

// Outage times are NSW local time, e.g. 20/10/2026 14:00:00.
private val OUTAGE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
private val OUTAGE_ZONE = ZoneId.of("Australia/Sydney")


// One outage covering the point: Essential Energy's id, state (OUTAGE_PAST, OUTAGE_NOW or OUTAGE_PLANNED),
// off and estimated-on times in epoch ms (0 if not given), customers affected (-1 if not given) and reason.
class Outage(val id: String, val state: Int, val offMs: Long, val onMs: Long, val customers: Int, val reason: String)


// Downloads the three maps and merges them with previous, the list kept from earlier downloads, returning
// outages covering latDeg, lonDeg sorted for display: happening now, then planned within OUTAGE_AHEAD_MS, then
// ones that ended within OUTAGE_KEEP_MS, most recent first. Each map is tried twice. Essential Energy's planned
// map is sometimes briefly missing (HTTP 404), so then the last known planned outages are kept and the result
// is still ok, with error describing what couldn't be checked.
fun fetchOutages(fetch: SecureFetch, latDeg: Double, lonDeg: Double, nowMs: Long, previous: List<Outage>):
  FetchResult<List<Outage>>
{
  val current = twice { fetch.stream(CURRENT_URL, CURRENT_MAX_BYTES) { parseKml(it, OUTAGE_NOW, latDeg, lonDeg) } }
  val future = twice { fetch.stream(FUTURE_URL, FUTURE_MAX_BYTES) { parseKml(it, OUTAGE_PLANNED, latDeg, lonDeg) } }
  val cancelled = twice { fetch.stream(CANCELLED_URL, CURRENT_MAX_BYTES) { parseKml(it, OUTAGE_PAST, latDeg, lonDeg) } }
  val planned = future.value ?: previous.filter { it.state == OUTAGE_PLANNED && it.offMs > nowMs }

  if (!current.ok) return FetchResult(false, error = current.error)
  return FetchResult(true, mergeOutages(previous, current.value!!, planned,
    cancelled.value?.map { it.id }?.toSet() ?: emptySet(), nowMs),
    if (future.ok) "" else "Planned outages couldn't be checked (" + future.error + "), showing the last known.")
}


// Runs attempt, and once more after a short random pause if it fails.
private fun <T> twice(attempt: () -> FetchResult<T>): FetchResult<T>
{
  val first = attempt()

  if (first.ok) return first
  Thread.sleep(2_000L + kotlin.random.Random.nextLong(4_000L))
  return attempt()
}


// Combines fresh current and planned outages with previously seen ones. A previously seen outage that is no
// longer listed and has started is kept as past (an early end is recorded as nowMs) until OUTAGE_KEEP_MS after
// it ended; cancelled ones and planned ones that vanished before starting are dropped.
fun mergeOutages(previous: List<Outage>, current: List<Outage>, future: List<Outage>, cancelledIds: Set<String>,
  nowMs: Long): List<Outage>
{
  val fresh = current + future.filter { it.offMs <= nowMs + OUTAGE_AHEAD_MS && it.id !in current.map { c -> c.id } }
  val freshIds = fresh.map { it.id }.toSet()
  val past = ArrayList<Outage>()

  for (old in previous)
  {
    if (old.id in freshIds || old.id in cancelledIds) continue
    if (old.state != OUTAGE_NOW && old.offMs > nowMs) continue
    val endMs = if (old.state == OUTAGE_NOW && (old.onMs <= 0 || old.onMs > nowMs)) nowMs else old.onMs

    if (maxOf(endMs, old.offMs) >= nowMs - OUTAGE_KEEP_MS)
    {
      past.add(Outage(old.id, OUTAGE_PAST, old.offMs, endMs, old.customers, old.reason))
    }
  }
  return current.sortedBy { it.offMs } + fresh.filter { it.state == OUTAGE_PLANNED }.sortedBy { it.offMs } +
    past.sortedByDescending { it.offMs }
}


// Streams one KML map and returns the placemarks with a polygon containing the point, all given state.
fun parseKml(input: InputStream, state: Int, latDeg: Double, lonDeg: Double): List<Outage>
{
  val parser = Xml.newPullParser()
  val found = ArrayList<Outage>()
  var description = ""
  var id = ""
  var inPolygon = false
  var covers = false
  var tag = ""

  parser.setInput(input, null)
  while (parser.next() != XmlPullParser.END_DOCUMENT)
  {
    when (parser.eventType)
    {
      XmlPullParser.START_TAG ->
      {
        tag = parser.name
        when (tag)
        {
          "Placemark" -> { description = ""; covers = false; id = parser.getAttributeValue(null, "id") ?: "" }
          "Polygon" -> inPolygon = true
          "description" -> description = parser.nextText()
          "coordinates" -> if (inPolygon && !covers) covers = ringContains(parser.nextText(), latDeg, lonDeg)
        }
      }
      XmlPullParser.END_TAG -> when (parser.name)
      {
        "Polygon" -> inPolygon = false
        "Placemark" -> if (covers) found.add(outageFrom(id, description, state))
      }
    }
  }
  return found
}


// Ray-casting test of whether the "lon,lat lon,lat ..." ring contains the point.
fun ringContains(ring: String, latDeg: Double, lonDeg: Double): Boolean
{
  val points = ring.trim().split(Regex("\\s+"))
  var inside = false
  var prevLon = 0.0
  var prevLat = 0.0
  var lon = 0.0
  var lat = 0.0
  var comma = 0

  for (i in points.indices)
  {
    comma = points[i].indexOf(',')
    if (comma < 0) continue
    lon = points[i].substring(0, comma).toDouble()
    lat = points[i].substring(comma + 1).substringBefore(',').toDouble()
    if (i > 0 && (lat > latDeg) != (prevLat > latDeg) &&
      lonDeg < (prevLon - lon) * (latDeg - lat) / (prevLat - lat) + lon) inside = !inside
    prevLon = lon
    prevLat = lat
  }
  return inside
}


// Builds an outage from a placemark's HTML description, e.g. "<span>Time Off:</span>20/10/2026 10:00:00".
private fun outageFrom(id: String, description: String, state: Int): Outage =
  Outage(id, state, timeField(description, "Time Off:"), timeField(description, "Est. Time On:"),
    field(description, "No. of Customers affected:").toIntOrNull() ?: -1, field(description, "Reason:"))


// Returns the text after label's closing span, up to the next tag, or "".
private fun field(description: String, label: String): String
{
  val start = description.indexOf(label + "</span>")

  if (start < 0) return ""
  return description.substring(start + label.length + 7).substringBefore('<').trim()
}


// Returns label's NSW local time as epoch ms, or 0 when missing or unreadable.
private fun timeField(description: String, label: String): Long =
  try
  {
    LocalDateTime.parse(field(description, label), OUTAGE_TIME).atZone(OUTAGE_ZONE).toInstant().toEpochMilli()
  }
  catch (e: Exception)
  {
    0L
  }


// Encodes outages as JSON text for the cache.
fun outagesToJson(outages: List<Outage>, fetchedMs: Long): String
{
  val list = JSONArray()

  for (o in outages) list.put(JSONObject().put("id", o.id).put("state", o.state).put("off", o.offMs).put("on", o.onMs)
    .put("customers", o.customers).put("reason", o.reason))
  return JSONObject().put("fetched", fetchedMs).put("outages", list).toString()
}


// Decodes cached outages; returns the fetch time and the list.
fun outagesFromJson(text: String): Pair<Long, List<Outage>>
{
  val root = JSONObject(text)
  val list = root.getJSONArray("outages")

  return Pair(root.getLong("fetched"), (0 until list.length()).map {
    val o = list.getJSONObject(it)

    Outage(o.getString("id"), o.getInt("state"), o.getLong("off"), o.getLong("on"), o.getInt("customers"), o.getString("reason"))
  })
}
