// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// places.kt
// The places offered in the location drop-down. The point checked for power outages is personal, so it is never
// in the code: it is entered on the phone and kept only there, taken from the phone's position moved 50 to 100 m
// in a random direction, or from the drop-down place.
package com.falcorr.lactulose.core


// A forecast place: display name, BoM geohash (6 characters) and coordinates in degrees.
class Place(val name: String, val geohash: String, val lat: Double, val lon: Double)


// Fixed NSW places, with geohashes and coordinates from BoM's location service (20/10/2026).
val FIXED_PLACES = listOf(
  Place("Dubbo", "r638sy", -32.2202, 148.5736),
  Place("Branxton", "r65whn", -32.6596, 151.3532),
  Place("Sydney", "r3gx2s", -33.8571, 151.1993)
)


// Metres per degree of latitude, near enough everywhere for offsets of a few hundred metres.
private const val METRES_PER_DEGREE = 111_320.0

// The range of the random offset applied to the phone's position for the outage point (user, 20/10/2026).
const val MIN_BLUR_M = 50.0
const val MAX_BLUR_M = 100.0

// The directions the offset may take, as compass bearings in degrees clockwise from north: 110° (east-south-east)
// through south to 270° (west), as the user set it (20/10/2026); never northward.
const val MIN_BLUR_BEARING = 110.0
const val MAX_BLUR_BEARING = 270.0


// Returns the point metres away from (lat, lon) in the direction bearingRad (0 = north, clockwise).
fun offsetPoint(lat: Double, lon: Double, metres: Double, bearingRad: Double): Pair<Double, Double> =
  Pair(lat + metres * Math.cos(bearingRad) / METRES_PER_DEGREE,
    lon + metres * Math.sin(bearingRad) / (METRES_PER_DEGREE * Math.cos(Math.toRadians(lat))))


// Returns (lat, lon) moved MIN_BLUR_M to MAX_BLUR_M in a random direction between MIN_BLUR_BEARING and
// MAX_BLUR_BEARING, so the stored outage point is never the phone's exact position.
fun blurPoint(lat: Double, lon: Double, random: kotlin.random.Random = kotlin.random.Random.Default): Pair<Double, Double> =
  offsetPoint(lat, lon, MIN_BLUR_M + random.nextDouble() * (MAX_BLUR_M - MIN_BLUR_M),
    Math.toRadians(MIN_BLUR_BEARING + random.nextDouble() * (MAX_BLUR_BEARING - MIN_BLUR_BEARING)))
