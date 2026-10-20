// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// geohash.kt
// Encodes latitude and longitude as a geohash, the location key BoM's forecast service uses, and finds a cell's centre.
package com.falcorr.lactulose.core

// Geohash base-32 alphabet.
private const val GEOHASH_CHARS = "0123456789bcdefghjkmnpqrstuvwxyz"


// Returns the geohash of length precision for latDeg and lonDeg; 6 characters is about 1.2 x 0.6 km.
fun geohash(latDeg: Double, lonDeg: Double, precision: Int): String
{
  val out = StringBuilder(precision)
  var latLow = -90.0
  var latHigh = 90.0
  var lonLow = -180.0
  var lonHigh = 180.0
  var evenBit = true
  var bits = 0
  var value = 0
  var mid = 0.0

  require(precision in 1..12) { "Geohash precision must be 1 to 12, got " + precision }
  while (out.length < precision)
  {
    if (evenBit)
    {
      mid = (lonLow + lonHigh) / 2
      if (lonDeg >= mid) { value = value * 2 + 1; lonLow = mid } else { value *= 2; lonHigh = mid }
    }
    else
    {
      mid = (latLow + latHigh) / 2
      if (latDeg >= mid) { value = value * 2 + 1; latLow = mid } else { value *= 2; latHigh = mid }
    }
    evenBit = !evenBit
    bits += 1
    if (bits == 5)
    {
      out.append(GEOHASH_CHARS[value])
      bits = 0
      value = 0
    }
  }
  return out.toString()
}


// Returns the centre (lat, lon) of the cell named by hash, or null if it has a character geohashes don't use.
fun geohashCentre(hash: String): Pair<Double, Double>?
{
  var latLow = -90.0
  var latHigh = 90.0
  var lonLow = -180.0
  var lonHigh = 180.0
  var evenBit = true

  for (c in hash)
  {
    val value = GEOHASH_CHARS.indexOf(c)

    if (value < 0) return null
    for (bit in 4 downTo 0)
    {
      val on = (value shr bit) and 1 == 1

      if (evenBit) { if (on) lonLow = (lonLow + lonHigh) / 2 else lonHigh = (lonLow + lonHigh) / 2 }
      else { if (on) latLow = (latLow + latHigh) / 2 else latHigh = (latLow + latHigh) / 2 }
      evenBit = !evenBit
    }
  }
  return Pair((latLow + latHigh) / 2, (lonLow + lonHigh) / 2)
}
