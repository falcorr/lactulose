// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// phonelocation.kt
// Gets one location fix using the least power: a recent cached fix if there is one, else the network
// (Wi-Fi and cell) provider, and GPS only when the network provider is unavailable.
package com.falcorr.lactulose.io

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper


// A cached fix younger than this is used without switching on any radio, and how long to wait for a new fix.
private const val CACHED_FIX_MS = 15 * 60_000L
private const val FIX_TIMEOUT_MS = 30_000L


class PhoneLocation(context: Context)
{
  // System location service and a main-thread handler for callbacks and the timeout.
  private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
  private val mainHandler = Handler(Looper.getMainLooper())


  // Calls done on the main thread with latitude and longitude, or with an error message (lat and lon NaN).
  // The caller must hold a location permission.
  @SuppressLint("MissingPermission")
  fun request(done: (Double, Double, String) -> Unit)
  {
    val provider = when
    {
      manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
      manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
      else -> ""
    }
    val cached = try { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
      ?: manager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) } catch (e: Exception) { null }
    var finished = false
    val finish = { location: Location? ->
      if (!finished)
      {
        finished = true
        if (location == null) done(Double.NaN, Double.NaN, "No location fix from " + provider)
        else done(location.latitude, location.longitude, "")
      }
    }

    if (cached != null && System.currentTimeMillis() - cached.time < CACHED_FIX_MS)
    {
      done(cached.latitude, cached.longitude, "")
      return
    }
    if (provider.isEmpty())
    {
      done(Double.NaN, Double.NaN, "Location is switched off on this phone")
      return
    }
    try
    {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
      {
        val cancel = CancellationSignal()

        manager.getCurrentLocation(provider, cancel, { mainHandler.post(it) }) { finish(it) }
        mainHandler.postDelayed({ cancel.cancel(); finish(cached) }, FIX_TIMEOUT_MS)
      }
      else
      {
        val listener = object : LocationListener
        {
          override fun onLocationChanged(location: Location) = finish(location)
          @Deprecated("Required on API 28 and 29") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
          override fun onProviderEnabled(p: String) {}
          override fun onProviderDisabled(p: String) {}
        }

        @Suppress("DEPRECATION") manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        mainHandler.postDelayed({ manager.removeUpdates(listener); finish(cached) }, FIX_TIMEOUT_MS)
      }
    }
    catch (e: Exception)
    {
      finished = true
      done(Double.NaN, Double.NaN, "Location request failed: " + e.message)
    }
  }
}
