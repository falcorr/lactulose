// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// screenshottest.kt
// Renders the main screen on a Samsung S10-sized screen and saves PNGs for review: with live BoM and
// Essential Energy data when run with -Plive (network needed), otherwise with sample data. Also renders a
// sheet of every weather icon.
package com.falcorr.lactulose

import android.graphics.Bitmap
import android.graphics.Canvas
import com.falcorr.lactulose.core.DayForecast
import com.falcorr.lactulose.core.HALF_HOUR_MS
import com.falcorr.lactulose.core.HOUR_MS
import com.falcorr.lactulose.core.HourlyExtras
import com.falcorr.lactulose.core.TempSeries
import com.falcorr.lactulose.core.WeatherSnapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone


// Where screenshots are written, relative to the app module.
private const val SHOT_DIR = "build/screenshots"


@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h869dp-xxhdpi")
class ScreenshotTest
{
  // Builds a plausible spring day: half-hourly observations for the past 3 h, then hourly forecasts.
  private fun sampleSnapshot(nowMs: Long): WeatherSnapshot
  {
    val zone = TimeZone.getTimeZone("Australia/Sydney")
    val start = nowMs - nowMs % HALF_HOUR_MS - 3 * HOUR_MS
    val times = ArrayList<Long>()
    var t = start
    var hourOfDay = 0.0
    val days = ArrayList<DayForecast>()
    val summaries = listOf("Partly cloudy. Slight chance of a shower.", "Shower or two.", "Mostly sunny.",
      "Sunny.", "Possible thunderstorm.", "Cloudy.", "Showers easing.")

    while (t <= nowMs + 48 * HOUR_MS)
    {
      times.add(t)
      t += if (t < nowMs) HALF_HOUR_MS else HOUR_MS
    }
    val temps = DoubleArray(times.size)
    val feels = DoubleArray(times.size)
    for (i in times.indices)
    {
      hourOfDay = ((times[i] + zone.getOffset(times[i])) % (24 * HOUR_MS)).toDouble() / HOUR_MS
      temps[i] = 19.5 + 6.0 * Math.cos((hourOfDay - 14.0) / 24.0 * 2 * Math.PI)
      feels[i] = temps[i] - 2.5 - 1.0 * Math.sin(hourOfDay / 3.0)
    }
    for (d in 0 until 7)
    {
      days.add(DayForecast(nowMs + d * 24 * HOUR_MS, if (d == 0) Double.NaN else 14.0 + d % 3, 22.0 + (d * 3) % 7,
        listOf(40, 70, 10, 5, 60, 30, 50)[d], 0.0, listOf(3.0, 8.0, Double.NaN, Double.NaN, 15.0, 1.0, 4.0)[d],
        summaries[d], if (d == 4) 2 else if (d == 1) 1 else 0,
        listOf("partly_cloudy", "shower", "mostly_sunny", "sunny", "storm", "cloudy", "light_rain")[d]))
    }
    val hourTimes = times.filter { it >= nowMs - nowMs % HOUR_MS }.toLongArray()
    val rain = IntArray(hourTimes.size) { listOf(0, 5, 10, 20, 40, 60, 30, 15, 5)[it % 9] }
    val storm = BooleanArray(hourTimes.size) { it in 5..6 }

    return WeatherSnapshot("Sydney (sample data)", "r3gx2s", nowMs, nowMs - 10 * 60_000L, temps[6], feels[6], 0.4,
      TempSeries(times.toLongArray(), temps, feels), HourlyExtras(hourTimes, rain, storm,
        Array(hourTimes.size) { "mostly_sunny" }, BooleanArray(hourTimes.size), DoubleArray(hourTimes.size) { 0.0 },
        DoubleArray(hourTimes.size) { if (rain[it] >= 40) 2.0 else if (rain[it] >= 20) 1.0 else Double.NaN }),
      longArrayOf(nowMs - 2 * HOUR_MS), days)
  }


  @Test
  fun rendersMainScreen()
  {
    val live = System.getProperty("live") == "true"
    val activity: MainActivity
    val deadline = System.currentTimeMillis() + 120_000L

    TimeZone.setDefault(TimeZone.getTimeZone("Australia/Sydney"))
    // A public outage point (BoM's point for Dubbo, as in places.kt), stored where the activity's own preferences live.
    org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("MainActivity", 0).edit()
      .putString("outageLat", "-32.2202").putString("outageLon", "148.5736").putString("outageName", "Dubbo").commit()
    activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    if (!live) activity.show(sampleSnapshot(System.currentTimeMillis()))
    // Live: let the real downloads finish and their results reach the main thread.
    while (live && System.currentTimeMillis() < deadline && !(loaded(activity, "snapshot") && outagesDone(activity)))
    {
      Thread.sleep(500)
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    save(activity.window.decorView, 2280, if (live) "main-live.png" else "main.png")
    save(activity.window.decorView, 0, if (live) "main-live-full.png" else "main-full.png")
    (MainActivity::class.java.getDeclaredField("valuesButton").apply { isAccessible = true }.get(activity)
      as android.widget.Button).performClick()
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    save(activity.window.decorView, 2280, if (live) "main-live-values.png" else "main-values.png")
  }


  @Test
  fun rendersIconSheet()
  {
    val context = org.robolectric.RuntimeEnvironment.getApplication()
    val kinds = listOf("sunny", "clear", "mostly_sunny", "partly_cloudy", "cloudy", "hazy", "dusty", "fog", "windy",
      "light_shower", "shower", "heavy_shower", "light_rain", "rain", "storm", "frost", "snow", "cyclone")
    val cell = 180
    val columns = 6
    val rows = (kinds.size * 2 + columns - 1) / columns
    val bitmap = Bitmap.createBitmap(cell * columns, cell * rows, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
      color = 0xFFB0B8C4.toInt()
      textSize = 22f
      textAlign = android.graphics.Paint.Align.CENTER
    }
    var index = 0

    canvas.drawColor(0xFF2B3038.toInt())
    for (night in listOf(false, true)) for (kind in kinds)
    {
      if (night && kind !in listOf("sunny", "clear", "mostly_sunny", "partly_cloudy", "light_shower", "shower")) continue
      val icon = com.falcorr.lactulose.ui.WeatherIcon(context, 40)
      val x = (index % columns) * cell
      val y = (index / columns) * cell

      icon.setKind(kind, night)
      icon.measure(0, 0)
      icon.layout(0, 0, icon.measuredWidth, icon.measuredHeight)
      canvas.save()
      canvas.translate(x + (cell - icon.measuredWidth) / 2f, y + 12f)
      icon.draw(canvas)
      canvas.restore()
      canvas.drawText(kind + if (night) " (night)" else "", x + cell / 2f, y + cell - 18f, label)
      index++
    }
    File(SHOT_DIR).mkdirs()
    File(SHOT_DIR, "icons.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }


  // Reads a private field of the activity by name and reports whether it is non-null.
  private fun loaded(activity: MainActivity, field: String): Boolean =
    MainActivity::class.java.getDeclaredField(field).apply { isAccessible = true }.get(activity) != null


  // Reports whether the outage download has finished (its status is no longer the loading text).
  private fun outagesDone(activity: MainActivity): Boolean =
    MainActivity::class.java.getDeclaredField("outageStatus").apply { isAccessible = true }.get(activity) != "Loading…"


  // Lays out root 1080 px wide and heightPx tall (0 means as tall as its content), draws it, and saves a PNG.
  private fun save(root: android.view.View, heightPx: Int, name: String)
  {
    val content = root.findViewById<android.view.ViewGroup>(android.R.id.content)
    val spec = android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY)
    val height: Int
    val bitmap: Bitmap
    val canvas: Canvas

    content.measure(spec, android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
    height = if (heightPx > 0) heightPx else maxOf(content.measuredHeight, 600)
    root.measure(spec, android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
    root.layout(0, 0, 1080, height)
    bitmap = Bitmap.createBitmap(1080, height, Bitmap.Config.ARGB_8888)
    canvas = Canvas(bitmap)
    canvas.drawColor(0xFF2B3038.toInt())
    if (heightPx > 0) root.draw(canvas) else
    {
      // Full-length capture draws the scroll view's content directly so nothing is clipped.
      val column = (content.getChildAt(0) as android.view.ViewGroup).getChildAt(0)

      column.measure(spec, android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
      column.layout(0, 0, 1080, column.measuredHeight)
      column.draw(canvas)
    }
    File(SHOT_DIR).mkdirs()
    File(SHOT_DIR, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
