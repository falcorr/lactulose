// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// repository.kt
// Joins the network sources to the on-device cache: weather per place and the outage list each live in one
// small file, written only after a successful download so a failed refresh never loses the last good data.
// All functions block, so call them off the main thread.
package com.falcorr.lactulose.data

import android.content.Context
import com.falcorr.lactulose.core.Place
import com.falcorr.lactulose.core.WeatherSnapshot
import com.falcorr.lactulose.io.AtomicStore
import com.falcorr.lactulose.io.FetchResult
import com.falcorr.lactulose.io.SecureFetch
import java.io.File


class Repository(private val context: Context)
{
  // The only hosts the app contacts for data.
  private val fetch = SecureFetch(BOM_HOSTS + OUTAGE_HOSTS)

  // Station table, read from the app's assets only when observations are first downloaded.
  private val stations by lazy { context.assets.open("stations.csv").use { StationTable(it) } }
  private val bom = BomSource(fetch) { stations }
  private val outageStore = AtomicStore(File(context.filesDir, "outages.json"))


  // Cache file for a place key (a fixed place's geohash, or "phone").
  private fun weatherStore(key: String) = AtomicStore(File(context.filesDir, "weather-" + key + ".json"))


  // Returns the cached snapshot for key, or null if none is cached or it can't be read.
  fun cachedWeather(key: String, nowMs: Long): WeatherSnapshot? =
    weatherStore(key).read().let { if (it.ok) try { parseBundle(it.text, nowMs) } catch (e: Exception) { null } else null }


  // Downloads place's weather, caches it under key, and returns the parsed snapshot or the error.
  fun refreshWeather(place: Place, key: String, nowMs: Long): FetchResult<WeatherSnapshot>
  {
    val result = bom.download(place, nowMs)
    val snapshot = if (result.ok) try { parseBundle(result.value!!, nowMs) } catch (e: Exception) { null } else null

    if (!result.ok) return FetchResult(false, error = result.error)
    if (snapshot == null) return FetchResult(false, error = "BoM data for " + place.geohash + " could not be parsed")
    weatherStore(key).write(result.value!!)
    return FetchResult(true, snapshot)
  }


  // Forgets cached outages and their history, for when the outage point changes.
  fun clearOutages()
  {
    outageStore.write(outagesToJson(emptyList(), 0L))
  }


  // Returns the cached outages with their fetch time, or null.
  fun cachedOutages(): Pair<Long, List<Outage>>? =
    outageStore.read().let { if (it.ok) try { outagesFromJson(it.text) } catch (e: Exception) { null } else null }


  // Downloads outages at latDeg, lonDeg, merges them with the kept history, caches them, and returns them or
  // the error.
  fun refreshOutages(nowMs: Long, latDeg: Double, lonDeg: Double): FetchResult<List<Outage>>
  {
    val result = fetchOutages(fetch, latDeg, lonDeg, nowMs, cachedOutages()?.second ?: emptyList())

    if (result.ok) outageStore.write(outagesToJson(result.value!!, nowMs))
    return result
  }
}
