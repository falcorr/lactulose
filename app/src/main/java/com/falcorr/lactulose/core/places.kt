// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// places.kt
// The places offered in the location drop-down. The point checked for power outages is personal, so it is never
// in the code: it is entered on the phone and kept only there.
package com.falcorr.lactulose.core


// A forecast place: display name, BoM geohash (6 characters) and coordinates in degrees.
class Place(val name: String, val geohash: String, val lat: Double, val lon: Double)


// Fixed NSW places, with geohashes and coordinates from BoM's location service (20/10/2026).
val FIXED_PLACES = listOf(
  Place("Dubbo", "r638sy", -32.2202, 148.5736),
  Place("Branxton", "r65whn", -32.6596, 151.3532),
  Place("Sydney", "r3gx2s", -33.8571, 151.1993)
)
