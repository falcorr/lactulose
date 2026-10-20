// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// mainactivity.kt
// The app's only screen: place picker and update button, current temperatures, the selection window's
// averages and lactulose dose, the 24 h graph, daily rows and Essential Energy outages. Views are built in
// code to keep start-up fast; downloads run on one background thread and happen only when data is stale.
package com.falcorr.lactulose

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.falcorr.lactulose.core.FIXED_PLACES
import com.falcorr.lactulose.core.HOUR_MS
import com.falcorr.lactulose.core.MINUTE_MS
import com.falcorr.lactulose.core.DoseRule
import com.falcorr.lactulose.core.Place
import com.falcorr.lactulose.core.WINDOW_MS
import com.falcorr.lactulose.core.WeatherSnapshot
import com.falcorr.lactulose.core.DEFAULT_FLOOR_C
import com.falcorr.lactulose.core.DEFAULT_FLOOR_ML
import com.falcorr.lactulose.core.MAX_FLOOR_C
import com.falcorr.lactulose.core.MAX_FLOOR_ML
import com.falcorr.lactulose.core.MIN_FLOOR_C
import com.falcorr.lactulose.core.MIN_FLOOR_ML
import com.falcorr.lactulose.core.DEFAULT_SNAP_MIN
import com.falcorr.lactulose.core.MAX_SNAP_MIN
import com.falcorr.lactulose.core.MIN_SNAP_MIN
import com.falcorr.lactulose.core.formatMl
import com.falcorr.lactulose.core.DEFAULT_DOSE_POINTS
import com.falcorr.lactulose.core.formatDosePoints
import com.falcorr.lactulose.core.parseDosePoints
import com.falcorr.lactulose.core.geohash
import com.falcorr.lactulose.data.Outage
import com.falcorr.lactulose.data.Repository
import com.falcorr.lactulose.io.PhoneLocation
import com.falcorr.lactulose.ui.COLOUR_DIM
import com.falcorr.lactulose.ui.COLOUR_FEELS
import com.falcorr.lactulose.ui.COLOUR_STORM
import com.falcorr.lactulose.ui.COLOUR_TEMP
import com.falcorr.lactulose.ui.COLOUR_TEXT
import com.falcorr.lactulose.ui.DailyList
import com.falcorr.lactulose.ui.DayScroller
import com.falcorr.lactulose.ui.OutageList
import com.falcorr.lactulose.ui.STORM_MARK
import com.falcorr.lactulose.ui.TemperatureGraph
import com.falcorr.lactulose.ui.WeatherIcon
import com.falcorr.lactulose.ui.degrees
import com.falcorr.lactulose.ui.rainRange
import com.falcorr.lactulose.ui.stormLabel
import com.falcorr.lactulose.ui.trim
import com.falcorr.lactulose.update.ApkUpdater
import java.util.Locale
import java.util.concurrent.Executors


// Saved-state key for the selection window start, kept only while Android keeps the app alive.
private const val KEY_WINDOW_START = "windowStart"

// Preference key for the chosen place index; the last index means phone location.
private const val KEY_PLACE = "place"

// Preference keys for the dose floor temperature and the dose it corresponds to.
private const val KEY_FLOOR_C = "floorC"
private const val KEY_FLOOR_ML = "floorMl"
private const val KEY_DOSE_POINTS = "dosePoints"

// Preference keys for the outage point and its display name, entered on the phone and never in the code.
private const val KEY_OUTAGE_LAT = "outageLat"
private const val KEY_OUTAGE_LON = "outageLon"
private const val KEY_OUTAGE_NAME = "outageName"

// Preference key for the window's snap step in minutes.
private const val KEY_SNAP_MIN = "snapMinutes"

// Preference key for whether each hour's values are printed on the graph.
private const val KEY_SHOW_VALUES = "showValues"

// Lactulose box border and fill, as ARGB.
private const val COLOUR_BOX_EDGE = 0x66FFB6C8
private const val COLOUR_BOX_FILL = 0x14FFB6C8
private const val COLOUR_WINDOW_TEXT = 0xFFFFC4D2.toInt()
private const val COLOUR_RAIN_TEXT = 0xFF81D4FA.toInt()

// Weather is refetched when older than this; outages less often, as the planned-outage map is large.
// Each period gets a fresh random extra of up to REFRESH_JITTER_MS after every download, so requests don't
// arrive on a fixed schedule.
private const val WEATHER_REFRESH_MS = 30 * MINUTE_MS
private const val OUTAGE_REFRESH_MS = 60 * MINUTE_MS
private const val REFRESH_JITTER_MS = 7 * MINUTE_MS
private const val MANUAL_REFRESH_GAP_MS = 60_000L
private const val MANUAL_REFRESH_JITTER_MS = 8_000L

// Thunder observed this recently counts as current.
private const val THUNDER_NOW_MS = HOUR_MS

// Request codes for the folder pickers and the location permission.
private const val PICK_INTERNAL = 1
private const val PICK_SD = 2
private const val ASK_LOCATION = 3
private const val ASK_LOCATION_OUTAGES = 4


class MainActivity : Activity()
{
  // Main-thread handler for the once-a-minute redraw and staleness check.
  private val mainHandler = Handler(Looper.getMainLooper())
  private val minuteTick = object : Runnable
  {
    override fun run()
    {
      val now = System.currentTimeMillis()

      graph.setNow(now)
      refreshIfStale()
      mainHandler.postDelayed(this, MINUTE_MS - now % MINUTE_MS)
    }
  }

  // One background thread for disk and network, so work never overlaps.
  private val worker = Executors.newSingleThreadExecutor()

  // Separate background thread for outages, so a slow weather download or update never holds them up.
  private val outageWorker = Executors.newSingleThreadExecutor()
  private lateinit var repository: Repository
  private lateinit var updater: ApkUpdater

  // Views updated after creation.
  private lateinit var placePicker: Spinner
  private lateinit var updateButton: Button
  private lateinit var refreshButton: Button
  private lateinit var valuesButton: Button
  private lateinit var nowIcon: WeatherIcon
  private lateinit var todayText: TextView
  private lateinit var statusText: TextView
  private lateinit var nowText: TextView
  private lateinit var rangeText: TextView
  private lateinit var averageText: TextView
  private lateinit var lactuloseBox: LinearLayout
  private lateinit var doseTempText: TextView
  private lateinit var doseFeelText: TextView
  private lateinit var graph: TemperatureGraph
  private lateinit var dailyList: DailyList
  private lateinit var outageList: OutageList
  private lateinit var versionText: TextView

  // Manual refresh state: when it was last used, and whether the next loads must download regardless of age.
  private var lastManualRefreshMs = 0L
  private var forceRefresh = false

  // Current random extras added to the refresh periods.
  private var weatherJitterMs = kotlin.random.Random.nextLong(REFRESH_JITTER_MS)
  private var outageJitterMs = kotlin.random.Random.nextLong(REFRESH_JITTER_MS)

  // Dose rule, from the user's settings.
  private var doseRule = DoseRule()

  // Chosen place index, data shown, and whether a download is under way.
  private var placeIndex = 0
  private var snapshot: WeatherSnapshot? = null
  private var outages: List<Outage> = emptyList()
  private var outagesFetchedMs = 0L
  private var outageStatus = "Loading…"

  // When the outages shown were downloaded (0 if never), unlike outagesFetchedMs which also paces retries.
  private var outagesUpdatedMs = 0L

  // What the last successful outage download couldn't check, or "".
  private var outageNote = ""
  private var weatherBusy = false
  private var outagesBusy = false
  private var weatherError = ""

  // Formats clock times following the phone's 12/24 h setting.
  private val timeFormat by lazy { android.text.format.DateFormat.getTimeFormat(this) }


  override fun onCreate(savedInstanceState: Bundle?)
  {
    super.onCreate(savedInstanceState)
    repository = Repository(applicationContext)
    updater = ApkUpdater(applicationContext)
    placeIndex = getPreferences(MODE_PRIVATE).getInt(KEY_PLACE, 0).coerceIn(0, FIXED_PLACES.size)
    doseRule = getPreferences(MODE_PRIVATE).let {
      DoseRule(it.getFloat(KEY_FLOOR_C, DEFAULT_FLOOR_C.toFloat()).toDouble(),
        it.getFloat(KEY_FLOOR_ML, DEFAULT_FLOOR_ML.toFloat()).toDouble(),
        parseDosePoints(it.getString(KEY_DOSE_POINTS, "") ?: "") ?: DEFAULT_DOSE_POINTS)
    }
    setContentView(buildContent())
    graph.onWindowChanged = { showAverages() }
    graph.setFloor(doseRule.floorC)
    graph.setSnapMinutes(getPreferences(MODE_PRIVATE).getInt(KEY_SNAP_MIN, DEFAULT_SNAP_MIN))
    setShowValues(getPreferences(MODE_PRIVATE).getBoolean(KEY_SHOW_VALUES, false))
    graph.setNow(System.currentTimeMillis(), savedInstanceState?.getLong(KEY_WINDOW_START, -1) ?: -1)
    showStatus()
    showAverages()
    showOutages()
  }


  override fun onResume()
  {
    super.onResume()
    minuteTick.run()
  }


  override fun onPause()
  {
    super.onPause()
    mainHandler.removeCallbacks(minuteTick)
  }


  override fun onDestroy()
  {
    super.onDestroy()
    worker.shutdown()
    outageWorker.shutdown()
  }


  override fun onSaveInstanceState(outState: Bundle)
  {
    super.onSaveInstanceState(outState)
    outState.putLong(KEY_WINDOW_START, graph.windowStartMs)
  }


  // Builds the whole screen and returns its root view.
  private fun buildContent(): ScrollView
  {
    val density = resources.displayMetrics.density
    val pad = (8 * density).toInt()
    val scroll = ScrollView(this)
    val column = LinearLayout(this)
    val header = LinearLayout(this)
    val tempsRow = LinearLayout(this)
    val temps = LinearLayout(this)
    val nowRow = LinearLayout(this)
    val names = FIXED_PLACES.map { it.name } + getString(R.string.phone_location)

    scroll.fitsSystemWindows = true
    column.orientation = LinearLayout.VERTICAL
    column.setPadding(pad, pad / 2, pad, pad)

    placePicker = Spinner(this)
    placePicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
    placePicker.setSelection(placeIndex, false)
    placePicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener
    {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onPlaceChosen(position)
      override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
    updateButton = Button(this)
    updateButton.text = getString(R.string.update)
    updateButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    updateButton.minHeight = 0
    updateButton.minimumHeight = 0
    updateButton.setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
    updateButton.setOnClickListener { onUpdateTapped() }
    updateButton.setOnLongClickListener { showUpdateSetup(); true }
    refreshButton = smallButton(R.string.refresh, pad)
    refreshButton.setOnClickListener { onRefreshTapped() }
    valuesButton = smallButton(R.string.hour_values, pad)
    valuesButton.setOnClickListener { setShowValues(!graph.showValues) }
    header.gravity = Gravity.CENTER_VERTICAL
    header.addView(placePicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    header.addView(valuesButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
      LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = pad / 2 })
    header.addView(refreshButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
      LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = pad / 2 })
    header.addView(updateButton)
    column.addView(header)

    statusText = text(11f, COLOUR_DIM, false)
    column.addView(statusText)
    nowText = text(14f, COLOUR_TEXT, true)
    nowText.setPadding(0, pad / 2, 0, 0)
    nowIcon = WeatherIcon(this, 30)
    nowRow.gravity = Gravity.BOTTOM
    nowRow.addView(nowText)
    nowRow.addView(nowIcon, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
      LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = pad / 2; bottomMargin = pad / 4 })
    todayText = text(11f, COLOUR_TEXT, false)
    todayText.setPadding(0, pad / 4, 0, 0)
    temps.orientation = LinearLayout.VERTICAL
    temps.addView(nowRow)
    temps.addView(todayText)
    lactuloseBox = buildLactuloseBox(pad)
    tempsRow.addView(temps, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
      LinearLayout.LayoutParams.WRAP_CONTENT))
    tempsRow.addView(lactuloseBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
      topMargin = pad / 2
      leftMargin = pad
    })
    column.addView(tempsRow)
    graph = TemperatureGraph(this)
    column.addView(graph)
    outageList = OutageList(this)
    outageList.setOnLongClickListener { showOutageSettings(); true }
    outageList.setPadding(0, pad, 0, 0)
    column.addView(outageList)
    dailyList = DailyList(this)
    dailyList.setPadding(0, pad, 0, 0)
    column.addView(DayScroller(this, dailyList))
    versionText = text(10f, COLOUR_DIM, false)
    versionText.gravity = Gravity.END
    versionText.setPadding(0, pad, 0, 0)
    // The update time is left out when Android doesn't know it.
    versionText.text = if (updater.lastUpdatedMs() <= 0L) "v" + updater.installedVersionName()
    else getString(R.string.version_line, updater.installedVersionName(),
      java.text.SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(updater.lastUpdatedMs()) + " " +
        timeFormat.format(updater.lastUpdatedMs()).replace(" ", "").lowercase())
    column.addView(versionText)

    scroll.addView(column)
    return scroll
  }


  // Builds the bordered lactulose box: the selection window's times, its average temperature and feels-like,
  // and the doses by each. Tapping it opens the dose settings.
  private fun buildLactuloseBox(pad: Int): LinearLayout
  {
    val box = LinearLayout(this)
    val border = android.graphics.drawable.GradientDrawable()

    border.setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), COLOUR_BOX_EDGE)
    border.cornerRadius = pad / 2f
    border.setColor(COLOUR_BOX_FILL)
    box.background = border
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(pad, pad / 2, pad, pad / 2)
    rangeText = text(12f, COLOUR_WINDOW_TEXT, true)
    averageText = text(13f, COLOUR_TEXT, true)
    doseTempText = text(15f, COLOUR_TEMP, true)
    doseFeelText = text(15f, COLOUR_FEELS, true)
    box.addView(rangeText)
    box.addView(averageText)
    box.addView(doseTempText)
    box.addView(doseFeelText)
    box.setOnClickListener { showDoseSettings() }
    return box
  }


  // Opens the dose settings: the floor temperature and the dose it corresponds to. Saved values replace the
  // rule at once and the doses are recalculated.
  private fun showDoseSettings()
  {
    val pad = (16 * resources.displayMetrics.density).toInt()
    val form = LinearLayout(this)
    val floorField = android.widget.EditText(this)
    val doseField = android.widget.EditText(this)
    val pointsField = android.widget.EditText(this)
    val snapField = android.widget.EditText(this)
    val scroll = ScrollView(this)

    form.orientation = LinearLayout.VERTICAL
    form.setPadding(pad, pad / 2, pad, 0)
    for ((field, value, label) in listOf(Triple(floorField, doseRule.floorC, R.string.floor_temp_label),
      Triple(doseField, doseRule.floorMl, R.string.floor_dose_label)))
    {
      form.addView(TextView(this).apply { text = getString(label) })
      field.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
      field.setText(String.format(Locale.ROOT, if (field === doseField) "%.2f" else "%.1f", value))
      form.addView(field)
    }
    form.addView(TextView(this).apply { text = getString(R.string.dose_points_label) })
    pointsField.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
    pointsField.setText(formatDosePoints(doseRule.points))
    form.addView(pointsField)
    form.addView(TextView(this).apply { text = getString(R.string.snap_label) })
    snapField.inputType = android.text.InputType.TYPE_CLASS_NUMBER
    snapField.setText(getPreferences(MODE_PRIVATE).getInt(KEY_SNAP_MIN, DEFAULT_SNAP_MIN).toString())
    form.addView(snapField)
    scroll.addView(form)
    android.app.AlertDialog.Builder(this)
      .setTitle(R.string.dose_settings)
      .setMessage(R.string.dose_settings_help)
      .setView(scroll)
      .setNegativeButton(android.R.string.cancel, null)
      .setNeutralButton(R.string.reset) { _, _ ->
        saveDoseRule(DoseRule())
        saveSnap(DEFAULT_SNAP_MIN)
      }
      .setPositiveButton(android.R.string.ok) { _, _ ->
        val floorC = floorField.text.toString().toDoubleOrNull()
        val floorMl = doseField.text.toString().toDoubleOrNull()
        val points = parseDosePoints(pointsField.text.toString())
        val snap = snapField.text.toString().toIntOrNull()

        if (snap == null || snap !in MIN_SNAP_MIN..MAX_SNAP_MIN) toast(getString(R.string.snap_invalid))
        else saveSnap(snap)
        if (floorC == null || floorMl == null || floorC !in MIN_FLOOR_C..MAX_FLOOR_C || floorMl !in MIN_FLOOR_ML..MAX_FLOOR_ML ||
          points == null)
        {
          toast(getString(R.string.dose_settings_invalid))
        }
        else saveDoseRule(DoseRule(floorC, floorMl, points))
      }
      .show()
  }


  // Stores the window's snap step in minutes and applies it.
  private fun saveSnap(minutes: Int)
  {
    getPreferences(MODE_PRIVATE).edit().putInt(KEY_SNAP_MIN, minutes).apply()
    graph.setSnapMinutes(minutes)
  }


  // Stores rule in preferences (a tiny write, only when changed) and shows the recalculated doses.
  private fun saveDoseRule(rule: DoseRule)
  {
    doseRule = rule
    graph.setFloor(rule.floorC)
    getPreferences(MODE_PRIVATE).edit().putFloat(KEY_FLOOR_C, rule.floorC.toFloat())
      .putFloat(KEY_FLOOR_ML, rule.floorMl.toFloat()).putString(KEY_DOSE_POINTS, formatDosePoints(rule.points)).apply()
    showAverages()
  }


  // Creates a compact header button labelled with string resource label.
  private fun smallButton(label: Int, pad: Int): Button = Button(this).apply {
    text = getString(label)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    minHeight = 0
    minimumHeight = 0
    minWidth = 0
    minimumWidth = 0
    setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
  }


  // Shows or hides each hour's values on the graph, marks the button as on or off, and remembers the choice.
  private fun setShowValues(show: Boolean)
  {
    graph.setShowValues(show)
    valuesButton.setTextColor(if (show) COLOUR_TEMP else COLOUR_DIM)
    getPreferences(MODE_PRIVATE).edit().putBoolean(KEY_SHOW_VALUES, show).apply()
  }


  // Creates a TextView with the given size in sp, colour and weight.
  private fun text(sizeSp: Float, colour: Int, bold: Boolean): TextView = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(colour)
    paint.isFakeBoldText = bold
  }


  // Refresh button: downloads weather and outages now, whatever their age, then stays greyed out for a minute plus
  // a few random seconds so repeated taps can't hammer the sources.
  private fun onRefreshTapped()
  {
    val now = System.currentTimeMillis()

    if (now - lastManualRefreshMs < MANUAL_REFRESH_GAP_MS || weatherBusy || outagesBusy) return
    lastManualRefreshMs = now
    refreshButton.isEnabled = false
    refreshButton.alpha = 0.4f
    mainHandler.postDelayed({
      refreshButton.isEnabled = true
      refreshButton.alpha = 1f
    }, MANUAL_REFRESH_GAP_MS + kotlin.random.Random.nextLong(MANUAL_REFRESH_JITTER_MS))
    forceRefresh = true
    weatherError = ""
    outagesFetchedMs = 0L
    loadWeather()
    loadOutages()
  }


  // Remembers a newly chosen place and shows its data, downloading it if the cache is stale.
  private fun onPlaceChosen(position: Int)
  {
    if (position == placeIndex && snapshot != null) return
    placeIndex = position
    getPreferences(MODE_PRIVATE).edit().putInt(KEY_PLACE, position).apply()
    snapshot = null
    weatherError = ""
    weatherBusy = false
    refreshIfStale()
  }


  // Starts whatever loads are due: cached data first, then downloads when older than their refresh period.
  private fun refreshIfStale()
  {
    val now = System.currentTimeMillis()

    if (!weatherBusy && (snapshot == null || now - snapshot!!.fetchedMs > WEATHER_REFRESH_MS + weatherJitterMs) &&
      weatherError.isEmpty()) loadWeather()
    if (!outagesBusy && now - outagesFetchedMs > OUTAGE_REFRESH_MS + outageJitterMs) loadOutages()
  }


  // Loads the chosen place's cached weather, then downloads fresh data if it's stale. Phone location first gets
  // a fix and redownloads when the phone has moved to a different geohash.
  private fun loadWeather()
  {
    val phone = placeIndex == FIXED_PLACES.size

    weatherBusy = true
    if (!phone)
    {
      loadWeatherFor(FIXED_PLACES[placeIndex], FIXED_PLACES[placeIndex].geohash)
      return
    }
    if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
    {
      requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
        ASK_LOCATION)
      return
    }
    statusText.text = getString(R.string.locating)
    PhoneLocation(this).request { lat, lon, error ->
      if (error.isNotEmpty()) failWeather(error)
      else loadWeatherFor(Place("", geohash(lat, lon, 6), lat, lon), "phone")
    }
  }


  // Records a weather failure. It is shown, and the next attempt waits for the next minute tick after
  // WEATHER_REFRESH_MS so a lasting fault never causes repeated downloads.
  private fun failWeather(error: String)
  {
    weatherBusy = false
    weatherError = error
    showStatus()
    mainHandler.postDelayed({ weatherError = "" }, WEATHER_REFRESH_MS)
  }


  // On the worker: shows place's cache under key straight away, and downloads when the cache is stale or for
  // another geohash. Results come back to the main thread.
  private fun loadWeatherFor(place: Place, key: String)
  {
    val chosen = placeIndex

    worker.execute { try { downloadWeather(place, key, chosen) } catch (e: Throwable) {
      // Any unexpected failure is shown instead of leaving the screen waiting.
      mainHandler.post { if (chosen == placeIndex) failWeather(e.toString()) else weatherBusy = false }
    } }
  }


  // On the worker: the body of loadWeatherFor; chosen is the place index it was started for.
  private fun downloadWeather(place: Place, key: String, chosen: Int)
  {
    run {
      val now = System.currentTimeMillis()
      val cached = repository.cachedWeather(key, now)
      val stale = forceRefresh || cached == null || cached.geohash != place.geohash || now - cached.fetchedMs > WEATHER_REFRESH_MS + weatherJitterMs

      if (cached != null) mainHandler.post { if (chosen == placeIndex) show(cached) }
      if (!stale)
      {
        mainHandler.post { weatherBusy = false }
        return
      }
      mainHandler.post { if (chosen == placeIndex) statusText.text = getString(R.string.downloading) }
      val result = repository.refreshWeather(place, key, now)

      mainHandler.post {
        if (chosen != placeIndex)
        {
          weatherBusy = false
          return@post
        }
        weatherJitterMs = kotlin.random.Random.nextLong(REFRESH_JITTER_MS)
        forceRefresh = outagesBusy && forceRefresh
        if (result.ok)
        {
          weatherBusy = false
          weatherError = ""
          show(result.value!!)
        }
        else failWeather(result.error)
      }
    }
  }


  // On the worker: shows cached outages, then downloads them if stale. Nothing is downloaded until an outage
  // point has been set.
  private fun loadOutages()
  {
    if (outagePoint() == null)
    {
      outageStatus = getString(R.string.outage_not_set)
      showOutages()
      return
    }
    outagesBusy = true
    outageWorker.execute { try { downloadOutages() } catch (e: Throwable) {
      // Any unexpected failure is shown instead of leaving the list stuck on loading.
      mainHandler.post {
        outagesBusy = false
        outagesFetchedMs = System.currentTimeMillis() - OUTAGE_REFRESH_MS + 10 * MINUTE_MS
        outageStatus = getString(R.string.outages_failed, e.toString())
        showOutages()
      }
    } }
  }


  // On the outage worker: the body of loadOutages.
  private fun downloadOutages()
  {
    run {
      val now = System.currentTimeMillis()
      val cached = repository.cachedOutages()

      if (cached != null) mainHandler.post {
        outages = cached.second
        outagesFetchedMs = cached.first
        outagesUpdatedMs = cached.first
        showOutages()
      }
      if (!forceRefresh && cached != null && now - cached.first <= OUTAGE_REFRESH_MS + outageJitterMs)
      {
        mainHandler.post { outagesBusy = false }
        return
      }
      val point = outagePoint() ?: return
      val result = repository.refreshOutages(now, point.first, point.second)

      mainHandler.post {
        outagesBusy = false
        outageJitterMs = kotlin.random.Random.nextLong(REFRESH_JITTER_MS)
        if (result.ok)
        {
          outages = result.value!!
          outagesFetchedMs = now
          outagesUpdatedMs = now
          outageStatus = getString(R.string.no_outages)
          outageNote = result.error
        }
        else
        {
          // A failed download is retried once the refresh period has passed, not immediately.
          if (cached == null) outagesFetchedMs = 0L
          outagesFetchedMs = maxOf(outagesFetchedMs, now - OUTAGE_REFRESH_MS + 10 * MINUTE_MS)
          outageStatus = result.error
        }
        showOutages()
      }
    }
  }


  // Handles the answer to a location request: for weather it loads or reports the refusal; for the outage point
  // it says what to do next, since the settings dialog stays open.
  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray)
  {
    val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }

    if (requestCode == ASK_LOCATION_OUTAGES)
    {
      toast(getString(if (granted) R.string.outage_allow_location else R.string.location_denied))
      return
    }
    if (requestCode != ASK_LOCATION) return
    weatherBusy = false
    if (granted) loadWeather()
    else failWeather(getString(R.string.location_denied))
  }


  // Shows a snapshot on every part of the screen.
  fun show(data: WeatherSnapshot)
  {
    snapshot = data
    graph.setData(data.series, data.hourly, data.thunderObsTimes)
    dailyList.setDays(data.days)
    showStatus()
    showAverages()
  }


  // Shows data times, the place name for phone location, recent thunder, the last error if any, and the
  // current temperature and feels-like.
  private fun showStatus()
  {
    val data = snapshot
    val out = SpannableStringBuilder()
    val lastThunder = data?.thunderObsTimes?.maxOrNull() ?: 0L
    val now = SpannableStringBuilder(getString(R.string.now_label) + "\n")

    if (data != null)
    {
      if (placeIndex == FIXED_PLACES.size) out.append(data.place + " · ")
      out.append(getString(R.string.status_line, timeFormat.format(data.obsTimeMs), timeFormat.format(data.fetchedMs)))
      if (System.currentTimeMillis() - lastThunder < THUNDER_NOW_MS)
      {
        appendColoured(out, "  " + STORM_MARK + " " + getString(R.string.thunder_observed, timeFormat.format(lastThunder)),
          COLOUR_STORM)
      }
    }
    if (weatherError.isNotEmpty()) out.append((if (out.isNotEmpty()) "\n" else "") + weatherError)
    statusText.text = out
    appendColoured(now, getString(R.string.temp_label) + " " + oneDecimal(data?.nowTemp ?: Double.NaN), COLOUR_TEMP)
    now.append("\n")
    appendColoured(now, getString(R.string.feels_label) + " " + oneDecimal(data?.nowFeels ?: Double.NaN), COLOUR_FEELS)
    nowText.text = now
    showToday(data)
  }


  // Shows the current hour's forecast icon and today's range, rain and storm likelihood under the current temps.
  private fun showToday(data: WeatherSnapshot?)
  {
    val hour = data?.hourly?.indexAt(System.currentTimeMillis()) ?: -1
    val today = data?.days?.firstOrNull()
    val line = SpannableStringBuilder()

    nowIcon.setKind(if (hour < 0) today?.icon ?: "" else data!!.hourly.icon[hour], hour >= 0 && data!!.hourly.night[hour])
    if (today != null)
    {
      line.append(getString(R.string.today_label) + " " + degrees(today.minC) + " / " + degrees(today.maxC) + "\n")
      appendColoured(line, (if (today.rainChance < 0) "–" else today.rainChance.toString()) + "% " + rainRange(today),
        COLOUR_RAIN_TEXT)
      if (today.stormLevel > 0) line.append("  ").append(stormLabel(today.stormLevel, today.rainChance))
    }
    if (data != null && !data.rainSince9amMm.isNaN())
    {
      line.append("\n")
      appendColoured(line, getString(R.string.rain_since_9am, trim(data.rainSince9amMm)), COLOUR_RAIN_TEXT)
    }
    todayText.text = line
  }


  // Refreshes the selection window: its times, its average temperature and feels-like, and the lactulose box,
  // whose doses use averages where any time below the floor counts as the floor.
  private fun showAverages()
  {
    val start = graph.windowStartMs
    val end = start + WINDOW_MS
    val data = snapshot
    val series = data?.series
    val temp = series?.average(series.temps, start, end) ?: Double.NaN
    val feels = series?.average(series.feels, start, end) ?: Double.NaN
    val doseByTemp = if (series == null) Double.NaN else doseRule.doseByTemp(series, start, end)
    val doseByFeel = if (series == null) Double.NaN else doseRule.doseByFeel(series, start, end)
    val line = SpannableStringBuilder()

    rangeText.text = getString(R.string.range_line, timeFormat.format(start), timeFormat.format(end))
    appendColoured(line, getString(R.string.temp_label) + " " + oneDecimal(temp), COLOUR_TEMP)
    line.append("  ")
    appendColoured(line, getString(R.string.feels_label) + " " + oneDecimal(feels), COLOUR_FEELS)
    averageText.text = line
    doseTempText.text = getString(R.string.dose_by_temp, formatMl(doseByTemp))
    doseFeelText.text = getString(R.string.dose_by_feel, formatMl(doseByFeel))
  }


  // Returns the outage point set on this phone, or null when none has been set.
  private fun outagePoint(): Pair<Double, Double>?
  {
    val prefs = getPreferences(MODE_PRIVATE)
    val lat = prefs.getString(KEY_OUTAGE_LAT, "")?.toDoubleOrNull()
    val lon = prefs.getString(KEY_OUTAGE_LON, "")?.toDoubleOrNull()

    return if (lat == null || lon == null) null else Pair(lat, lon)
  }


  // Long press on the outage list: asks for the point to check (typed as "lat, lon" or taken from the phone's
  // location) and the name to show for it. Stored only on this phone.
  private fun showOutageSettings()
  {
    val pad = (16 * resources.displayMetrics.density).toInt()
    val form = LinearLayout(this)
    val nameField = android.widget.EditText(this)
    val pointField = android.widget.EditText(this)
    val usePhone = Button(this)
    val point = outagePoint()

    form.orientation = LinearLayout.VERTICAL
    form.setPadding(pad, pad / 2, pad, 0)
    form.addView(TextView(this).apply { text = getString(R.string.outage_name_label) })
    nameField.setText(getPreferences(MODE_PRIVATE).getString(KEY_OUTAGE_NAME, ""))
    form.addView(nameField)
    form.addView(TextView(this).apply { text = getString(R.string.outage_point_label) })
    pointField.setText(if (point == null) "" else point.first.toString() + ", " + point.second.toString())
    form.addView(pointField)
    usePhone.text = getString(R.string.outage_use_phone)
    usePhone.setOnClickListener {
      if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
      {
        requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION,
          android.Manifest.permission.ACCESS_FINE_LOCATION), ASK_LOCATION_OUTAGES)
        toast(getString(R.string.outage_allow_location))
      }
      else PhoneLocation(this).request { lat, lon, error ->
        if (error.isNotEmpty()) toast(error)
        else pointField.setText(String.format(Locale.ROOT, "%.6f, %.6f", lat, lon))
      }
    }
    form.addView(usePhone)
    android.app.AlertDialog.Builder(this)
      .setTitle(R.string.outage_settings)
      .setMessage(R.string.outage_settings_help)
      .setView(form)
      .setNegativeButton(android.R.string.cancel, null)
      .setNeutralButton(R.string.outage_clear) { _, _ -> saveOutagePoint(null, "") }
      .setPositiveButton(android.R.string.ok) { _, _ ->
        val parts = pointField.text.toString().split(',', ' ').filter { it.isNotBlank() }
        val lat = parts.getOrNull(0)?.toDoubleOrNull()
        val lon = parts.getOrNull(1)?.toDoubleOrNull()

        if (parts.size != 2 || lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0)
        {
          toast(getString(R.string.outage_point_invalid))
        }
        else saveOutagePoint(Pair(lat, lon), nameField.text.toString().trim())
      }
      .show()
  }


  // Stores the outage point (null clears it) and name on this phone, forgets the old point's outages, and reloads.
  private fun saveOutagePoint(point: Pair<Double, Double>?, name: String)
  {
    val prefs = getPreferences(MODE_PRIVATE).edit()

    if (point == null) prefs.remove(KEY_OUTAGE_LAT).remove(KEY_OUTAGE_LON)
    else prefs.putString(KEY_OUTAGE_LAT, point.first.toString()).putString(KEY_OUTAGE_LON, point.second.toString())
    prefs.putString(KEY_OUTAGE_NAME, name).apply()
    outageWorker.execute { repository.clearOutages() }
    outages = emptyList()
    outagesUpdatedMs = 0L
    outagesFetchedMs = 0L
    outageNote = ""
    outageStatus = getString(R.string.loading)
    loadOutages()
  }


  // Shows the outage list with its title and, when it has been fetched, the fetch time.
  private fun showOutages()
  {
    val updated = timeFormat.format(outagesUpdatedMs).replace(" ", "").lowercase()
    val name = getPreferences(MODE_PRIVATE).getString(KEY_OUTAGE_NAME, "") ?: ""
    val title = (if (name.isEmpty()) getString(R.string.outage_title) else getString(R.string.outage_title_at, name)) +
      if (outagesUpdatedMs > 0) " " + getString(R.string.updated, updated) else ""

    outageList.setOutages(title, outages, outageStatus, outageNote)
  }


  // Appends text to line in colour.
  private fun appendColoured(line: SpannableStringBuilder, text: String, colour: Int)
  {
    val start = line.length

    line.append(text)
    line.setSpan(ForegroundColorSpan(colour), start, line.length, 0)
  }


  // Formats degrees Celsius to one decimal, e.g. "21.3°", or "–" when unknown.
  private fun oneDecimal(celsius: Double): String =
    if (celsius.isNaN()) "–" else String.format(Locale.getDefault(), "%.1f°", celsius)


  // Update button: checks the latest GitHub release and the granted update folders. The app installs nothing
  // itself (so it needs no permission to install apps): a newer release is opened in the browser, which downloads
  // it for Android's own installer, and a newer folder copy is named so it can be opened from the Files app.
  private fun onUpdateTapped()
  {
    updateButton.isEnabled = false
    worker.execute {
      val github = updater.findNewerOnGithub()
      val local = updater.findNewer()

      mainHandler.post {
        updateButton.isEnabled = true
        when
        {
          github.first != null -> openInBrowser(github.first!!.apkUrl, github.first!!.versionName)
          local != null -> toast(getString(R.string.update_in_folder, local.versionName, local.fileName))
          updater.folders().isEmpty() && github.second.isNotEmpty() ->
            toast(github.second + "\n" + getString(R.string.update_setup_hint))
          else -> toast(getString(R.string.no_update, updater.installedVersionName()))
        }
      }
    }
  }


  // Hands the APK address to the browser to download; opening the download then installs it with Android's
  // package installer.
  private fun openInBrowser(url: String, versionName: String)
  {
    try
    {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
      toast(getString(R.string.update_downloading, versionName))
    }
    catch (e: android.content.ActivityNotFoundException)
    {
      toast(getString(R.string.update_no_browser, url))
    }
  }


  // Long press on Update: choose update folders, enter the GitHub token, or forget the folders.
  private fun showUpdateSetup()
  {
    val choices = arrayOf(getString(R.string.choose_folders), getString(R.string.github_token),
      getString(R.string.forget_folders))

    android.app.AlertDialog.Builder(this)
      .setTitle(R.string.update_setup)
      .setItems(choices) { _, which ->
        when (which)
        {
          0 -> pickFolder(PICK_INTERNAL, "content://com.android.externalstorage.documents/document/primary%3ADownload%2Flactulose")
          1 -> showTokenDialog()
          else -> { updater.clearFolders(); toast(getString(R.string.folders_forgotten)) }
        }
      }
      .show()
  }


  // Asks for the read-only GitHub token needed while falcorr/lactulose is private; empty clears it.
  private fun showTokenDialog()
  {
    val field = android.widget.EditText(this)

    field.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    field.setText(updater.githubToken)
    android.app.AlertDialog.Builder(this)
      .setTitle(R.string.github_token)
      .setMessage(R.string.github_token_help)
      .setView(field)
      .setNegativeButton(android.R.string.cancel, null)
      .setPositiveButton(android.R.string.ok) { _, _ -> updater.githubToken = field.text.toString() }
      .show()
  }


  // Opens the system folder picker starting at initialUri (read access only).
  private fun pickFolder(requestCode: Int, initialUri: String)
  {
    val pick = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)

    pick.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(initialUri))
    toast(getString(if (requestCode == PICK_INTERNAL) R.string.pick_downloads else R.string.pick_sd))
    startActivityForResult(pick, requestCode)
  }


  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
  {
    val tree = data?.data
    val sd = sdCardId()

    if (requestCode != PICK_INTERNAL && requestCode != PICK_SD) return
    if (resultCode == RESULT_OK && tree != null) updater.addFolder(tree)
    if (requestCode == PICK_INTERNAL && sd.isNotEmpty())
    {
      pickFolder(PICK_SD, "content://com.android.externalstorage.documents/document/" + sd + "%3Alactulose")
      return
    }
    if (updater.folders().isNotEmpty()) toast(getString(R.string.folders_saved, updater.folders().size))
  }


  // Returns the storage id of a mounted removable SD card, e.g. "1A2B-3C4D", or "" when there is none.
  private fun sdCardId(): String
  {
    val storage = getSystemService(StorageManager::class.java)

    return storage.storageVolumes.firstOrNull { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
      ?.uuid ?: ""
  }


  // Shows a short silent message.
  private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
