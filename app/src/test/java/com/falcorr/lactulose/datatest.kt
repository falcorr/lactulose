// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// datatest.kt
// Tests for geohash encoding, BoM bundle parsing, outage area tests and KML parsing, the station table, and
// (live only) downloading the latest release.
package com.falcorr.lactulose

import com.falcorr.lactulose.core.geohash
import com.falcorr.lactulose.data.OUTAGE_NOW
import com.falcorr.lactulose.data.OUTAGE_PAST
import com.falcorr.lactulose.data.OUTAGE_PLANNED
import com.falcorr.lactulose.data.Outage
import com.falcorr.lactulose.data.StationTable
import com.falcorr.lactulose.data.mergeOutages
import com.falcorr.lactulose.data.outagesFromJson
import com.falcorr.lactulose.data.outagesToJson
import com.falcorr.lactulose.data.parseBundle
import com.falcorr.lactulose.data.parseKml
import com.falcorr.lactulose.data.ringContains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant


@RunWith(RobolectricTestRunner::class)
class DataTest
{
  // An arbitrary square ring in far-west NSW, in KML "lon,lat" order.
  private val square = "141.4,-31.9 141.5,-31.9 141.5,-31.8 141.4,-31.8 141.4,-31.9"


  @Test
  fun geohashMatchesBom()
  {
    // BoM's own geohashes for Dubbo, Branxton and Sydney start with these six characters.
    assertEquals("r638sy", geohash(-32.2202, 148.5736, 6))
    assertEquals("r65whn", geohash(-32.6596, 151.3532, 6))
    assertEquals("r3gx2s", geohash(-33.8571, 151.1993, 6))
  }


  @Test
  fun ringContainsPoint()
  {
    assertTrue(ringContains(square, -31.85, 141.45))
    assertFalse(ringContains(square, -32.0, 141.45))
    assertFalse(ringContains(square, -31.85, 141.55))
  }


  @Test
  fun parsesKmlPlacemarksCoveringPoint()
  {
    val kml = """<?xml version="1.0" encoding="UTF-8"?><kml xmlns="http://earth.google.com/kml/2.1"><Document>
      <Placemark id="A"><description><![CDATA[<h2>A</h2><div><span>Time Off:</span>20/10/2026 10:00:00</div>
      <div><span>Est. Time On:</span>20/10/2026 14:00:00</div><div><span>No. of Customers affected:</span>4</div>
      <div><span>Reason:</span>Critical repairs</div>]]></description><MultiGeometry><Polygon><outerBoundaryIs>
      <LinearRing><coordinates>$square</coordinates></LinearRing></outerBoundaryIs></Polygon>
      <Point><coordinates>141.47,-31.85</coordinates></Point></MultiGeometry></Placemark>
      <Placemark id="B"><description><![CDATA[<span>Time Off:</span>21/10/2026 08:00:00]]></description>
      <MultiGeometry><Polygon><outerBoundaryIs><LinearRing><coordinates>150,-30 151,-30 151,-31 150,-30</coordinates>
      </LinearRing></outerBoundaryIs></Polygon></MultiGeometry></Placemark></Document></kml>"""
    val found = parseKml(kml.byteInputStream(), OUTAGE_NOW, -31.85, 141.45)
    val decoded = outagesFromJson(outagesToJson(found, 5L))

    assertEquals(1, found.size)
    assertEquals("A", found[0].id)
    assertEquals(Instant.parse("2026-10-19T23:00:00Z").toEpochMilli(), found[0].offMs)
    assertEquals(Instant.parse("2026-10-20T03:00:00Z").toEpochMilli(), found[0].onMs)
    assertEquals(4, found[0].customers)
    assertEquals("Critical repairs", found[0].reason)
    assertEquals(5L, decoded.first)
    assertEquals("Critical repairs", decoded.second[0].reason)
  }


  @Test
  fun parsesBundleJoiningObservationsAndForecasts()
  {
    val t0 = Instant.parse("2026-10-20T01:00:00Z").toEpochMilli()
    val bundle = """{"fetched":$t0,"name":"Dubbo","geohash":"r638sy","obsTime":"2026-10-20T01:30:00Z",
      "obs":{"temp":21.5,"temp_feels_like":19.0},
      "station":[[${t0 + 1_800_000},21.5,19.0,"Thunderstorm"],[$t0,20.0,18.0,"-"],[${t0 - 1_800_000},null,null,"-"]],
      "hourly":[{"time":"2026-10-20T01:00:00Z","temp":20,"temp_feels_like":18,"rain":{"chance":10},"icon_descriptor":"sunny"},
        {"time":"2026-10-20T02:00:00Z","temp":23,"temp_feels_like":21,"rain":{"chance":35},"icon_descriptor":"storm"}],
      "daily":[{"date":"2026-10-19T13:00:00Z","temp_min":null,"temp_max":27,"rain":{"chance":95,"amount":{"min":15,"max":30}},
        "short_text":"Showers.","extended_text":"The chance of a thunderstorm.","icon_descriptor":"shower"},
        {"date":"2026-10-18T13:00:00Z","temp_min":1,"temp_max":2,"rain":{"chance":null},"short_text":null}]}"""
    val data = parseBundle(bundle, t0)

    // Observations at 01:00 and 01:30, then only the forecast hour after the last observation.
    assertEquals(3, data.series.times.size)
    assertEquals(23.0, data.series.temps[2], 1e-9)
    assertEquals(35, data.hourly.rainChance[1])
    assertTrue(data.hourly.storm[1])
    assertEquals(1, data.thunderObsTimes.size)
    assertEquals(1, data.days.size)
    assertTrue(data.days[0].minC.isNaN())
    assertEquals(95, data.days[0].rainChance)
    assertEquals(2, data.days[0].stormLevel)
    assertEquals(21.5, data.nowTemp, 1e-9)
  }


  @Test
  fun keepsAWeekOfOutageHistory()
  {
    val now = 100L * 24 * 3_600_000L
    val day = 24 * 3_600_000L
    val previous = listOf(
      Outage("ended", OUTAGE_NOW, now - 2 * day, now + day, 4, "Repairs"),
      Outage("old", OUTAGE_PAST, now - 9 * day, now - 8 * day, 4, "Old"),
      Outage("happened", OUTAGE_PLANNED, now - 3 * day, now - 3 * day + 3_600_000L, 9, "Maintenance"),
      Outage("cancelled", OUTAGE_PLANNED, now - day, now - day + 1, 1, "Maintenance"),
      Outage("vanished", OUTAGE_PLANNED, now + day, now + day + 1, 1, "Maintenance"))
    val merged = mergeOutages(previous, listOf(Outage("live", OUTAGE_NOW, now - 1, now + 1, 2, "Fault")),
      listOf(Outage("soon", OUTAGE_PLANNED, now + day, now + day + 1, 3, "Upgrade"),
        Outage("far", OUTAGE_PLANNED, now + 20 * day, now + 20 * day + 1, 3, "Upgrade")), setOf("cancelled"), now)

    assertEquals(listOf("live", "soon", "ended", "happened"), merged.map { it.id })
    assertEquals(OUTAGE_PAST, merged[2].state)
    // An outage that ended before its estimate is recorded as ending when it was last missing.
    assertEquals(now, merged[2].onMs)
  }


  @Test
  fun readsThunderstormWording()
  {
    assertEquals(1, com.falcorr.lactulose.data.stormLevel("Slight chance of a thunderstorm in the afternoon.", false))
    assertEquals(2, com.falcorr.lactulose.data.stormLevel("The chance of a thunderstorm.", false))
    assertEquals(2, com.falcorr.lactulose.data.stormLevel("Possible thunderstorm.", false))
    assertEquals(3, com.falcorr.lactulose.data.stormLevel("Thunderstorms likely.", false))
    assertEquals(3, com.falcorr.lactulose.data.stormLevel("Sunny.", true))
    assertEquals(0, com.falcorr.lactulose.data.stormLevel("Sunny.", false))
  }


  @Test
  fun stationTableFindsNearest()
  {
    val table = StationTable("065070,95719,NSW,-32.2206,148.5753\n066214,94768,NSW,-33.8593,151.2048\n".byteInputStream())

    assertEquals("95719", table.byId("065070")?.wmo)
    assertEquals("94768", table.nearest(-33.9, 151.2, 1)[0].wmo)
  }


  @Test
  fun downloadsLatestReleaseApk()
  {
    // Live only: fetches the latest release through GitHub's API and its redirect, as the Update button does.
    if (System.getProperty("live") != "true") return
    val github = com.falcorr.lactulose.update.GithubRelease(
      com.falcorr.lactulose.io.SecureFetch(com.falcorr.lactulose.update.GITHUB_HOSTS), "")
    val release = github.latest()
    val file = java.io.File.createTempFile("release", ".apk")

    assertTrue(release.error, release.ok)
    val fetched = github.download(release.value!!, file)

    assertTrue(fetched.error, fetched.ok)
    assertTrue(file.length() > 50_000)
    assertEquals("PK", String(file.readBytes().copyOf(2)))
    file.delete()
  }


  @Test
  fun blursPointBy50To100Metres()
  {
    val random = kotlin.random.Random(7)
    val bearings = HashSet<Int>()

    // BoM's Dubbo point, moved many times: always 50 to 100 m away (great-circle distance), at bearings from 110°
    // through south to 270°, spread across that whole arc.
    for (i in 0 until 500)
    {
      val moved = com.falcorr.lactulose.core.blurPoint(-32.2202, 148.5736, random)
      val dLat = Math.toRadians(moved.first + 32.2202)
      val dLon = Math.toRadians(moved.second - 148.5736)
      val a = Math.pow(Math.sin(dLat / 2), 2.0) +
        Math.cos(Math.toRadians(-32.2202)) * Math.cos(Math.toRadians(moved.first)) * Math.pow(Math.sin(dLon / 2), 2.0)
      val metres = 2 * 6_371_000.0 * Math.asin(Math.sqrt(a))

      assertTrue("moved $metres m", metres in 49.5..100.5)
      val bearing = (Math.toDegrees(Math.atan2(dLon * Math.cos(Math.toRadians(-32.2202)), dLat)) + 360) % 360

      assertTrue("bearing $bearing", bearing in 109.9..270.1)
      bearings.add(((bearing - 110) / 16).toInt())
    }
    assertEquals(10, bearings.size)
  }


  @Test
  fun decodesGeohashCentreAndSharesRefreshLimit()
  {
    val centre = com.falcorr.lactulose.core.geohashCentre("r638sy")!!
    val prefs = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("limitTest", 0)

    // The centre of BoM's Dubbo cell lies in that cell, within about 700 m of BoM's point.
    assertEquals("r638sy", geohash(centre.first, centre.second, 6))
    assertTrue(Math.abs(centre.first + 32.2202) < 0.006 && Math.abs(centre.second - 148.5736) < 0.006)
    assertEquals(null, com.falcorr.lactulose.core.geohashCentre("r6a"))
    // One manual refresh per minute plus up to 8 s, whether from the app or the widget.
    assertTrue(claimManualRefresh(prefs, 1_000_000L))
    assertFalse(claimManualRefresh(prefs, 1_000_000L + 59_000L))
    assertTrue(claimManualRefresh(prefs, 1_000_000L + 68_001L))
  }
}
