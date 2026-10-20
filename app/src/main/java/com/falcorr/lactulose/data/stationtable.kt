// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// stationtable.kt
// Open BoM stations with WMO numbers (assets/stations.csv, made by tools/makestations.py), used to find the
// half-hourly observation feed for a station BoM names or for the nearest stations to a place.
package com.falcorr.lactulose.data

import java.io.InputStream


// One station: BoM site number, WMO number, state, and coordinates in degrees.
class Station(val bomId: String, val wmo: String, val state: String, val lat: Double, val lon: Double)


class StationTable(csv: InputStream)
{
  // All stations, read once.
  private val stations: List<Station> = csv.bufferedReader().useLines { lines ->
    lines.mapNotNull { line ->
      val cells = line.split(',')

      if (cells.size == 5) Station(cells[0], cells[1], cells[2], cells[3].toDouble(), cells[4].toDouble()) else null
    }.toList()
  }


  // Returns the station with BoM site number bomId, or null.
  fun byId(bomId: String): Station? = stations.firstOrNull { it.bomId == bomId }


  // Returns the count stations nearest latDeg, lonDeg, closest first (flat-earth distance is enough here).
  fun nearest(latDeg: Double, lonDeg: Double, count: Int): List<Station>
  {
    val lonScale = Math.cos(Math.toRadians(latDeg))

    return stations.sortedBy {
      val dLat = it.lat - latDeg
      val dLon = (it.lon - lonDeg) * lonScale

      dLat * dLat + dLon * dLon
    }.take(count)
  }
}
