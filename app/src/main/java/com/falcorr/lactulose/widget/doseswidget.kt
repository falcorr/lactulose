// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// doseswidget.kt
// The 2x1 home-screen widget (user, 20/10/2026): the by-temperature and by-feel lactulose doses for the window
// starting at the first snap time at or after now, for the place chosen in the app. It recalculates every 15
// minutes, downloading weather only once the cached weather is 15 minutes old, and on a tap, which downloads only
// when the app's shared once-a-minute manual refresh limit allows. The small print shows when the weather was
// last downloaded, and the sync icon spins only while a download is actually running.
package com.falcorr.lactulose.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.falcorr.lactulose.KEY_PLACE
import com.falcorr.lactulose.KEY_SNAP_MIN
import com.falcorr.lactulose.PREFS_NAME
import com.falcorr.lactulose.R
import com.falcorr.lactulose.WEATHER_REFRESH_MS
import com.falcorr.lactulose.claimManualRefresh
import com.falcorr.lactulose.core.DEFAULT_SNAP_MIN
import com.falcorr.lactulose.core.FIXED_PLACES
import com.falcorr.lactulose.core.MINUTE_MS
import com.falcorr.lactulose.core.Place
import com.falcorr.lactulose.core.WINDOW_MS
import com.falcorr.lactulose.core.WeatherSnapshot
import com.falcorr.lactulose.core.earliestWindowStart
import com.falcorr.lactulose.core.formatMl
import com.falcorr.lactulose.core.geohashCentre
import com.falcorr.lactulose.data.Repository
import com.falcorr.lactulose.loadDoseRule
import java.util.TimeZone


// Broadcast action sent when the widget is tapped.
private const val ACTION_TAP = "com.falcorr.lactulose.widget.TAP"

// Cache key the app uses for phone-location weather.
private const val PHONE_KEY = "phone"

// Small-print colours: normal, and red when the last download failed.
private const val COLOUR_SMALL = 0xFF9AA4B2.toInt()
private const val COLOUR_FAILED = 0xFFEF5350.toInt()


class DosesWidget : AppWidgetProvider()
{
  companion object
  {
    // Recalculates every widget from cached weather without downloading, e.g. after the app downloads or the dose
    // settings change.
    fun refreshAll(context: Context)
    {
      val app = context.applicationContext

      if (ids(app).isEmpty()) return
      Thread { update(app, download = false, manual = false) }.start()
    }


    // Returns the ids of all doses widgets on home screens.
    private fun ids(context: Context): IntArray =
      AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, DosesWidget::class.java))


    // Works out the doses and shows them on every widget. download lets it fetch weather when due (cache 15 minutes
    // old, or manual and the shared limit allows); the sync icon spins only while that fetch runs.
    private fun update(context: Context, download: Boolean, manual: Boolean)
    {
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      val repository = Repository(context)
      val now = System.currentTimeMillis()
      val index = prefs.getInt(KEY_PLACE, 0).coerceIn(0, FIXED_PLACES.size)
      val key = if (index < FIXED_PLACES.size) FIXED_PLACES[index].geohash else PHONE_KEY
      var snapshot = repository.cachedWeather(key, now)
      val place = placeFor(index, snapshot)
      var failed = false

      val due = download && place != null &&
        (if (manual) claimManualRefresh(prefs, now) else snapshot == null || now - snapshot.fetchedMs >= WEATHER_REFRESH_MS)
      if (due)
      {
        show(context, snapshot, now, syncing = true, failed = false)
        val result = repository.refreshWeather(place!!, key, now)

        if (result.ok) snapshot = result.value else failed = true
      }
      show(context, snapshot, now, syncing = false, failed = failed)
    }


    // Returns the place to download for drop-down index, or for phone location the centre of the last downloaded
    // geohash cell (the widget doesn't ask for location itself), or null when there is none yet.
    private fun placeFor(index: Int, snapshot: WeatherSnapshot?): Place?
    {
      if (index < FIXED_PLACES.size) return FIXED_PLACES[index]
      val centre = snapshot?.let { geohashCentre(it.geohash) } ?: return null

      return Place("", snapshot.geohash, centre.first, centre.second)
    }


    // Draws the doses from snapshot (or dashes) on every widget, with the download time or a failure note.
    private fun show(context: Context, snapshot: WeatherSnapshot?, nowMs: Long, syncing: Boolean, failed: Boolean) =
      AppWidgetManager.getInstance(context).updateAppWidget(ComponentName(context, DosesWidget::class.java),
        views(context, snapshot, nowMs, syncing, failed))


    // Builds the widget's views: the doses from snapshot (or dashes), the download time or a failure note, and the
    // sync icon or, while syncing, the spinner.
    internal fun views(context: Context, snapshot: WeatherSnapshot?, nowMs: Long, syncing: Boolean, failed: Boolean):
      RemoteViews
    {
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      val rule = loadDoseRule(prefs)
      val zone = TimeZone.getDefault()
      val start = earliestWindowStart(nowMs, zone, prefs.getInt(KEY_SNAP_MIN, DEFAULT_SNAP_MIN) * MINUTE_MS)
      val views = RemoteViews(context.packageName, R.layout.widget_doses)
      val time = android.text.format.DateFormat.getTimeFormat(context)
      val small = when
      {
        failed -> context.getString(R.string.widget_failed, time.format(nowMs).replace(" ", "").lowercase())
        snapshot == null -> context.getString(R.string.widget_no_data)
        else -> context.getString(R.string.widget_updated, time.format(snapshot.fetchedMs).replace(" ", "").lowercase())
      }

      views.setTextViewText(R.id.widgetTime, small)
      views.setTextColor(R.id.widgetTime, if (failed) COLOUR_FAILED else COLOUR_SMALL)
      views.setTextViewText(R.id.widgetTemp,
        formatMl(snapshot?.let { rule.doseByTemp(it.series, start, start + WINDOW_MS) } ?: Double.NaN))
      views.setTextViewText(R.id.widgetFeel,
        formatMl(snapshot?.let { rule.doseByFeel(it.series, start, start + WINDOW_MS) } ?: Double.NaN))
      views.setViewVisibility(R.id.widgetSync, if (syncing) View.GONE else View.VISIBLE)
      views.setViewVisibility(R.id.widgetSpin, if (syncing) View.VISIBLE else View.GONE)
      views.setOnClickPendingIntent(R.id.widgetRoot, PendingIntent.getBroadcast(context, 0,
        Intent(context, DosesWidget::class.java).setAction(ACTION_TAP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
      return views
    }


    // Returns the pending broadcast the 15-minute alarm sends to update the widgets.
    private fun alarmIntent(context: Context): PendingIntent =
      PendingIntent.getBroadcast(context, 1, Intent(context, DosesWidget::class.java)
        .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids(context)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }


  // System or alarm update: keeps the 15-minute alarm set (it is inexact, so Android may batch it to save
  // battery) and updates the widgets, downloading if the weather is 15 minutes old.
  override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray)
  {
    val alarms = context.getSystemService(AlarmManager::class.java)

    alarms?.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
      AlarmManager.INTERVAL_FIFTEEN_MINUTES, alarmIntent(context))
    runInBackground(context, manual = false)
  }


  // Handles a tap; everything else goes to the standard widget handling.
  override fun onReceive(context: Context, intent: Intent)
  {
    if (intent.action == ACTION_TAP) runInBackground(context, manual = true)
    else super.onReceive(context, intent)
  }


  // The last widget was removed: stops the alarm.
  override fun onDisabled(context: Context)
  {
    context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context))
  }


  // Runs an update off the main thread, keeping this broadcast alive until it finishes.
  private fun runInBackground(context: Context, manual: Boolean)
  {
    val pending = goAsync()
    val app = context.applicationContext

    Thread {
      try { update(app, download = true, manual = manual) }
      catch (e: Throwable)
      {
        // A failed update leaves the widget showing what it had.
      }
      finally { pending.finish() }
    }.start()
  }
}
