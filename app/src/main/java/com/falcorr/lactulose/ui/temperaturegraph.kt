// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// temperaturegraph.kt
// Custom view drawing temperature and feels-like lines from 3 h before now to 21 h after on a fixed
// -5 to 50 °C axis, with hourly rain-chance bars, storm marks, a current-time line and a draggable 6.5 h
// selection window with its start and end values just above the axis. Lines are slightly darker at and below the dose floor, and each hour's values can be printed
// along them. Draws without allocating so dragging stays smooth.
package com.falcorr.lactulose.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.falcorr.lactulose.core.GRAPH_AFTER_MS
import com.falcorr.lactulose.core.GRAPH_BEFORE_MS
import com.falcorr.lactulose.core.HALF_HOUR_MS
import com.falcorr.lactulose.core.HOUR_MS
import com.falcorr.lactulose.core.HourlyExtras
import com.falcorr.lactulose.core.TempSeries
import com.falcorr.lactulose.core.WINDOW_MS
import com.falcorr.lactulose.core.floorLocal
import com.falcorr.lactulose.core.snapWindowStart
import com.falcorr.lactulose.core.DEFAULT_SNAP_MIN
import com.falcorr.lactulose.core.MINUTE_MS
import java.util.TimeZone


// Fixed temperature axis in degrees Celsius, with minor marks every 2.5 and labels every 5.
private const val AXIS_MIN_C = -5.0
private const val AXIS_MAX_C = 50.0
private const val AXIS_MARK_C = 2.5
private const val AXIS_LABEL_C = 5.0

// Colours, as ARGB.
const val COLOUR_TEMP = 0xFFFFB74D.toInt()
const val COLOUR_FEELS = 0xFF4FC3F7.toInt()
private const val COLOUR_TEMP_LOW = 0xFFC48A3A.toInt()
private const val COLOUR_FEELS_LOW = 0xFF3B94BC.toInt()
private const val COLOUR_HALO = 0xFF2B3038.toInt()
private const val COLOUR_EDGE_TEMP = 0xFFFFFFFF.toInt()
private const val COLOUR_GRID_MINOR = 0x1FFFFFFF
private const val COLOUR_GRID_MAJOR = 0x40FFFFFF
private const val COLOUR_LABEL = 0xFFB0B8C4.toInt()
private const val COLOUR_LABEL_MAIN = 0xFFFFFFFF.toInt()
private const val COLOUR_NOW = 0xFFD0D0D0.toInt()
private const val COLOUR_WINDOW_FILL = 0x33FFB6C8
private const val COLOUR_WINDOW_PAST = 0x33FFE4EC
private const val COLOUR_LOCK = 0xE6FFD6E0.toInt()
private const val COLOUR_WINDOW_EDGE = 0x80FFB6C8.toInt()
private const val COLOUR_RAIN_BAR = 0x9981D4FA.toInt()
private const val COLOUR_RAIN_LABEL = 0xE681D4FA.toInt()
private const val COLOUR_EDGE_FEELS = 0xFFA6E0FB.toInt()
private const val COLOUR_FLOOR_LINE = 0xB0FFFFFF.toInt()
private const val COLOUR_ZERO_LINE = 0x80FFFFFF.toInt()
const val COLOUR_STORM = 0xFFFFE082.toInt()

// Rain-chance bars are proportional to the plot: 100% reaches the top. Then the storm mark glyph.
private const val RAIN_BAR_SCALE = 1f
const val STORM_MARK = "ϟ"

// Range of whole degrees with a prebuilt label for hourly values.
private const val VALUE_LABEL_MIN = -20
private const val VALUE_LABEL_MAX = 60


@SuppressLint("ViewConstructor")
class TemperatureGraph(context: Context) : View(context)
{
  // Screen density for converting dp and sp to pixels.
  private val density = resources.displayMetrics.density
  private val scaledDensity = density * resources.configuration.fontScale

  // Margins around the plot area in pixels, leaving room for axis labels.
  private val leftMargin = 20 * density
  private val rightMargin = 4 * density
  private val topMargin = 5 * density
  private val bottomMargin = 22 * density

  // Reused drawing objects so onDraw allocates nothing.
  private val gridPaint = Paint().apply { strokeWidth = density * 0.6f }
  private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = COLOUR_LABEL
    textSize = 8.5f * scaledDensity
  }
  private val tempPaint = linePaint(COLOUR_TEMP)
  private val feelsPaint = linePaint(COLOUR_FEELS)
  private val tempLowPaint = linePaint(COLOUR_TEMP_LOW)
  private val feelsLowPaint = linePaint(COLOUR_FEELS_LOW)
  private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = 8f * scaledDensity
    textAlign = Paint.Align.CENTER
    isFakeBoldText = true
    setShadowLayer(2.5f * density, 0f, 0f, COLOUR_HALO)
  }
  private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = 8f * scaledDensity
    setShadowLayer(2.5f * density, 0f, 0f, COLOUR_HALO)
  }
  private val percentLabels = Array(101) { it.toString() + "%" }

  // Each forecast hour's rain amount label such as "1mm" or "0–2mm", or "" when none is forecast; set by setData.
  private var rainMmLabels = arrayOf<String>()
  private val valueLabels = Array(VALUE_LABEL_MAX - VALUE_LABEL_MIN + 1) { (it + VALUE_LABEL_MIN).toString() }
  private val nowPaint = Paint().apply {
    color = COLOUR_NOW
    strokeWidth = density * 0.8f
  }
  private val windowFill = Paint().apply { color = COLOUR_WINDOW_FILL }
  private val windowPastFill = Paint().apply { color = COLOUR_WINDOW_PAST }
  private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = COLOUR_LOCK
    strokeWidth = 1.4f * density
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
  }

  // The lock button's area in the window's top-left corner, set when drawn, and whether a touch began on it.
  private val lockRect = android.graphics.RectF()
  private var lockTouched = false
  private val windowEdge = Paint().apply {
    color = COLOUR_WINDOW_EDGE
    strokeWidth = density
  }
  private val rainPaint = Paint().apply { color = COLOUR_RAIN_BAR }
  private val rainLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = COLOUR_RAIN_LABEL
    textSize = 6.5f * scaledDensity
    textAlign = Paint.Align.CENTER
    setShadowLayer(2f * density, 0f, 0f, COLOUR_HALO)
  }
  private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 0.7f * density }
  private val stormPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = COLOUR_STORM
    textSize = 10f * scaledDensity
    textAlign = Paint.Align.CENTER
    isFakeBoldText = true
  }
  private val linePath = Path()
  private val hourLabels24 = Array(24) { it.toString() }
  private val hourLabels12 = Array(24) {
    when (it) { 0 -> "12am"; 6 -> "6am"; 12 -> "12pm"; 18 -> "6pm"; else -> (it % 12).toString() }
  }

  // Hour labels matching the device's 12 or 24 hour clock setting.
  private val hourLabels = if (android.text.format.DateFormat.is24HourFormat(context)) hourLabels24 else hourLabels12
  private val axisLabels = Array(12) { (AXIS_MIN_C + it * AXIS_LABEL_C).toInt().toString() }

  // Data shown and the current time; setData and setNow update them.
  private var series = TempSeries(LongArray(0), DoubleArray(0), DoubleArray(0))
  private var hourly = HourlyExtras(LongArray(0), IntArray(0), BooleanArray(0))
  private var thunderTimes = LongArray(0)

  // Dose floor temperature, drawn as a brighter line; setFloor updates it.
  private var floorC = 15.0

  // Whether each hour's temperature and feels-like are printed along the lines; setShowValues updates it.
  var showValues = false
    private set
  private var nowMs = 0L
  private var zone: TimeZone = TimeZone.getDefault()

  // Selection window start in epoch ms, and the finger's offset from the window's left edge while dragging.
  var windowStartMs = 0L
    private set
  private var grabOffsetPx = 0f

  // Whether the window is locked to start no earlier than the first snap time at or after now (the default);
  // unlocked it may move back over the graph's past 3 h (user, 20/10/2026).
  var locked = true
    private set

  // Step the window start snaps to, in ms; setSnapMinutes changes it.
  private var snapMs = DEFAULT_SNAP_MIN * MINUTE_MS

  // Called with the new window start whenever the selection moves.
  var onWindowChanged: (Long) -> Unit = {}


  // Builds an anti-aliased stroke paint for a data line.
  private fun linePaint(colour: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = colour
    style = Paint.Style.STROKE
    strokeWidth = density * 1.6f
    strokeJoin = Paint.Join.ROUND
  }


  // Sets the dose floor temperature line and redraws.
  fun setFloor(celsius: Double)
  {
    floorC = celsius
    invalidate()
  }


  // Sets the window's snap step in minutes, re-snapping the current window.
  fun setSnapMinutes(minutes: Int)
  {
    snapMs = minutes * MINUTE_MS
    setNow(nowMs)
  }


  // Turns printing of each hour's values along the lines on or off, and redraws.
  fun setShowValues(show: Boolean)
  {
    showValues = show
    invalidate()
  }


  // Replaces the plotted series, hourly rain and storm forecasts, and observed thunder times, then redraws.
  fun setData(newSeries: TempSeries, newHourly: HourlyExtras, newThunder: LongArray)
  {
    series = newSeries
    hourly = newHourly
    rainMmLabels = Array(newHourly.times.size) { rainMmLabel(newHourly.rainMinMm[it], newHourly.rainMaxMm[it]) }
    thunderTimes = newThunder
    invalidate()
  }


  // Moves the current time to timeMs and places the window at windowStart, snapped and clamped so it never starts
  // before the first snap time at or after now (a window left behind by time moves on), then redraws. Pass -1 to
  // start it at that first snap time.
  fun setNow(timeMs: Long, windowStart: Long = windowStartMs)
  {
    val previous = windowStartMs

    nowMs = timeMs
    zone = TimeZone.getDefault()
    windowStartMs = snapWindowStart(windowStart, nowMs, zone, snapMs, !locked)
    if (windowStartMs != previous) onWindowChanged(windowStartMs)
    invalidate()
  }


  // Locks or unlocks the window. Locking while it starts before the first snap time at or after now moves it there
  // at once.
  fun setLocked(lock: Boolean)
  {
    locked = lock
    setNow(nowMs)
  }


  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int)
  {
    val width = MeasureSpec.getSize(widthMeasureSpec)

    setMeasuredDimension(width, (199 * density).toInt())
  }


  // Maps an epoch-ms time to an x pixel.
  private fun xOf(timeMs: Long): Float =
    leftMargin + (timeMs - (nowMs - GRAPH_BEFORE_MS)).toFloat() / (GRAPH_BEFORE_MS + GRAPH_AFTER_MS) *
      (width - leftMargin - rightMargin)


  // Maps degrees Celsius to a y pixel.
  private fun yOf(celsius: Double): Float =
    (topMargin + (AXIS_MAX_C - celsius) / (AXIS_MAX_C - AXIS_MIN_C) * (height - topMargin - bottomMargin)).toFloat()


  // Maps an x pixel back to an epoch-ms time.
  private fun timeOf(x: Float): Long =
    nowMs - GRAPH_BEFORE_MS + ((x - leftMargin) / (width - leftMargin - rightMargin) *
      (GRAPH_BEFORE_MS + GRAPH_AFTER_MS)).toLong()


  override fun onDraw(canvas: Canvas)
  {
    val right = width - rightMargin
    val bottom = height - bottomMargin
    val rangeStart = nowMs - GRAPH_BEFORE_MS
    val rangeEnd = nowMs + GRAPH_AFTER_MS
    var celsius = AXIS_MIN_C
    var tick = floorLocal(rangeStart, HALF_HOUR_MS, zone) + HALF_HOUR_MS
    var x = 0f
    var y = 0f
    var hour = 0
    var halfWidth = 0f

    // Temperature marks every 2.5 °C, labelled every 5 °C.
    while (celsius <= AXIS_MAX_C)
    {
      y = yOf(celsius)
      gridPaint.color = if (celsius % AXIS_LABEL_C == 0.0) COLOUR_GRID_MAJOR else COLOUR_GRID_MINOR
      canvas.drawLine(leftMargin, y, right, y, gridPaint)
      if (celsius % AXIS_LABEL_C == 0.0)
      {
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(axisLabels[((celsius - AXIS_MIN_C) / AXIS_LABEL_C).toInt()], leftMargin - 3 * density,
          y + labelPaint.textSize * 0.35f, labelPaint)
      }
      celsius += AXIS_MARK_C
    }

    // Brighter lines at 0 °C and, brighter still, at the dose floor.
    gridPaint.color = COLOUR_ZERO_LINE
    canvas.drawLine(leftMargin, yOf(0.0), right, yOf(0.0), gridPaint)
    gridPaint.color = COLOUR_FLOOR_LINE
    canvas.drawLine(leftMargin, yOf(floorC), right, yOf(floorC), gridPaint)

    // Hour ticks with labels, and shorter unlabelled half-hour ticks.
    labelPaint.textAlign = Paint.Align.CENTER
    gridPaint.color = COLOUR_LABEL
    while (tick < rangeEnd)
    {
      x = xOf(tick)
      if (floorLocal(tick, HOUR_MS, zone) == tick)
      {
        canvas.drawLine(x, bottom, x, bottom + 3 * density, gridPaint)
        hour = (Math.floorMod(tick + zone.getOffset(tick), 24 * HOUR_MS) / HOUR_MS).toInt()
        // Every hour is labelled (user, 20/10/2026); a label near either edge is moved in so it isn't cut off.
        // 6am, 12pm, 6pm and 12am are brighter, with am or pm written out, and sit a row lower to have room.
        labelPaint.color = if (hour % 6 == 0) COLOUR_LABEL_MAIN else COLOUR_LABEL
        halfWidth = labelPaint.measureText(hourLabels[hour]) / 2
        canvas.drawText(hourLabels[hour], x.coerceIn(halfWidth, width - halfWidth),
          height - (if (hour % 6 == 0) 1.5f else 10.5f) * density, labelPaint)
        labelPaint.color = COLOUR_LABEL
      }
      else
      {
        canvas.drawLine(x, bottom, x, bottom + 1.5f * density, gridPaint)
      }
      tick += HALF_HOUR_MS
    }

    canvas.save()
    canvas.clipRect(leftMargin, 0f, right, bottom)
    drawRainAndStorms(canvas, bottom)
    y = yOf(floorC)
    canvas.save()
    canvas.clipRect(leftMargin, 0f, right, y)
    drawLine(canvas, series.feels, feelsPaint, rangeStart, rangeEnd)
    drawLine(canvas, series.temps, tempPaint, rangeStart, rangeEnd)
    canvas.restore()
    canvas.save()
    canvas.clipRect(leftMargin, y, right, bottom)
    drawLine(canvas, series.feels, feelsLowPaint, rangeStart, rangeEnd)
    drawLine(canvas, series.temps, tempLowPaint, rangeStart, rangeEnd)
    canvas.restore()
    if (showValues) drawValues(canvas, rangeStart, rangeEnd)

    // Selection window drawn over the lines so they stay visible through its faint fill.
    // The part of the window before now is a slightly brighter pink at the same transparency.
    x = xOf(windowStartMs)
    if (windowStartMs < nowMs) canvas.drawRect(x, topMargin, xOf(minOf(nowMs, windowStartMs + WINDOW_MS)), bottom, windowPastFill)
    canvas.drawRect(xOf(maxOf(nowMs, windowStartMs)), topMargin, xOf(windowStartMs + WINDOW_MS), bottom, windowFill)
    canvas.drawLine(x, topMargin, x, bottom, windowEdge)
    canvas.drawLine(xOf(windowStartMs + WINDOW_MS), topMargin, xOf(windowStartMs + WINDOW_MS), bottom, windowEdge)
    canvas.restore()
    drawWindowEdgeValues(canvas, bottom)
    drawLock(canvas, x)

    x = xOf(nowMs)
    canvas.drawLine(x, 0f, x, bottom, nowPaint)
  }


  // Reports whether a rain label centred at x just above barTop stays inside the plot and clear of the window's
  // start and end values printed just above the axis.
  private fun rainLabelFits(x: Float, barTop: Float, bottom: Float): Boolean
  {
    val halfWidth = 7 * density
    val edgeWidth = 24 * density
    val start = xOf(windowStartMs)
    val end = xOf(windowStartMs + WINDOW_MS)

    if (x - halfWidth < leftMargin || x + halfWidth > width - rightMargin) return false
    if (barTop - 2 * rainLabelPaint.textSize - 3 * density > bottom - 2 * edgePaint.textSize - 4 * density) return true
    return !(x + halfWidth > start && x - halfWidth < start + edgeWidth) && !(x + halfWidth > end - edgeWidth &&
      x - halfWidth < end)
  }


  // Formats an hour's rain amount from BoM's min and max, e.g. "1mm" or "0–2mm"; "" when no amount above zero is given.
  private fun rainMmLabel(minMm: Double, maxMm: Double): String
  {
    if (maxMm.isNaN() || maxMm <= 0) return if (!minMm.isNaN() && minMm > 0) trim(minMm) + "mm" else ""
    if (minMm.isNaN() || minMm == maxMm) return trim(maxMm) + "mm"
    return trim(minMm) + "–" + trim(maxMm) + "mm"
  }


  // Draws each forecast hour's rain chance as a bar from the bottom labelled with its chance and amount, and a storm mark at the top of hours
  // forecast as stormy and at times thunder was observed.
  private fun drawRainAndStorms(canvas: Canvas, bottom: Float)
  {
    val plotHeight = bottom - topMargin
    val markY = topMargin + stormPaint.textSize
    var left = 0f
    var right = 0f
    var top = 0f

    for (i in hourly.times.indices)
    {
      left = xOf(hourly.times[i]) + density * 0.5f
      right = xOf(hourly.times[i] + HOUR_MS) - density * 0.5f
      if (hourly.rainChance[i] > 0)
      {
        top = bottom - plotHeight * RAIN_BAR_SCALE * hourly.rainChance[i] / 100f
        canvas.drawRect(left, top, right, bottom, rainPaint)
        // Chance, and the amount above it when BoM gives one, just above the bar's top. Left out where it would be
        // cut off at the plot's edge or sit under the window's start and end values.
        if (rainLabelFits((left + right) / 2, top, bottom))
        {
          canvas.drawText(percentLabels[hourly.rainChance[i].coerceIn(0, 100)], (left + right) / 2,
            top - 1.5f * density, rainLabelPaint)
          if (rainMmLabels.getOrElse(i) { "" }.isNotEmpty()) canvas.drawText(rainMmLabels[i], (left + right) / 2,
            top - 2.5f * density - rainLabelPaint.textSize, rainLabelPaint)
        }
      }
      if (hourly.storm[i]) canvas.drawText(STORM_MARK, (left + right) / 2, markY, stormPaint)
    }
    for (time in thunderTimes) canvas.drawText(STORM_MARK, xOf(time), markY, stormPaint)
  }


  // Prints the temperature (white) over the feels-like (light blue) at the window's start and end, just above the x
  // axis, inside the window's edges.
  private fun drawWindowEdgeValues(canvas: Canvas, bottom: Float)
  {
    val end = windowStartMs + WINDOW_MS
    val inset = 2.5f * density
    val lowerY = bottom - 2 * density
    val upperY = lowerY - edgePaint.textSize - 1 * density

    edgePaint.textAlign = Paint.Align.LEFT
    drawEdgePair(canvas, windowStartMs, xOf(windowStartMs) + inset, upperY, lowerY)
    edgePaint.textAlign = Paint.Align.RIGHT
    drawEdgePair(canvas, end, xOf(end) - inset, upperY, lowerY)
  }


  // Prints one time's temperature at upperY and feels-like at lowerY, one decimal each, skipping unknown values.
  private fun drawEdgePair(canvas: Canvas, timeMs: Long, x: Float, upperY: Float, lowerY: Float)
  {
    val temp = series.valueAt(series.temps, timeMs)
    val feels = series.valueAt(series.feels, timeMs)

    edgePaint.color = COLOUR_EDGE_TEMP
    if (!temp.isNaN()) canvas.drawText(String.format(java.util.Locale.getDefault(), "%.1f°", temp), x, upperY, edgePaint)
    edgePaint.color = COLOUR_EDGE_FEELS
    if (!feels.isNaN()) canvas.drawText(String.format(java.util.Locale.getDefault(), "%.1f°", feels), x, lowerY, edgePaint)
  }


  // Prints each whole hour's temperature and feels-like, rounded, in its line's colour: the higher value just
  // above its line and the lower just below its own, so the two never overlap.
  private fun drawValues(canvas: Canvas, rangeStart: Long, rangeEnd: Long)
  {
    var hour = floorLocal(rangeStart, HOUR_MS, zone) + HOUR_MS
    var temp = 0.0
    var feels = 0.0
    var x = 0f

    while (hour < rangeEnd)
    {
      // Hours too close to either edge would print cut-off values, so they are skipped.
      if (xOf(hour) < leftMargin + 6 * density || xOf(hour) > width - rightMargin - 6 * density)
      {
        hour += HOUR_MS
        continue
      }
      temp = series.valueAt(series.temps, hour)
      feels = series.valueAt(series.feels, hour)
      x = xOf(hour)
      if (!temp.isNaN()) drawValue(canvas, x, temp, feels.isNaN() || temp >= feels, COLOUR_TEMP, COLOUR_TEMP_LOW)
      if (!feels.isNaN()) drawValue(canvas, x, feels, !temp.isNaN() && feels > temp, COLOUR_FEELS, COLOUR_FEELS_LOW)
      hour += HOUR_MS
    }
  }


  // Prints one rounded value at x, above its line point when above is true and below it otherwise, joined to the
  // point by a short line, using the darker colour at and below the floor.
  private fun drawValue(canvas: Canvas, x: Float, celsius: Double, above: Boolean, colour: Int, lowColour: Int)
  {
    val rounded = Math.round(celsius).toInt().coerceIn(VALUE_LABEL_MIN, VALUE_LABEL_MAX)
    val y = yOf(celsius)

    valuePaint.color = if (celsius <= floorC) lowColour else colour
    tickPaint.color = valuePaint.color
    // A short thin line joins the value to the exact point it belongs to on the line.
    if (above)
    {
      canvas.drawLine(x, y - 1 * density, x, y - 6 * density, tickPaint)
      canvas.drawText(valueLabels[rounded - VALUE_LABEL_MIN], x, y - 7.5f * density, valuePaint)
    }
    else
    {
      canvas.drawLine(x, y + 1 * density, x, y + 6 * density, tickPaint)
      canvas.drawText(valueLabels[rounded - VALUE_LABEL_MIN], x, y + 6.5f * density + valuePaint.textSize, valuePaint)
    }
  }


  // Draws one series as a polyline through every sample inside the visible range plus one either side,
  // so the line reaches the plot edges.
  private fun drawLine(canvas: Canvas, values: DoubleArray, paint: Paint, rangeStart: Long, rangeEnd: Long)
  {
    val times = series.times
    var started = false

    linePath.rewind()
    for (i in times.indices)
    {
      if ((i + 1 < times.size && times[i + 1] < rangeStart) || (i > 0 && times[i - 1] > rangeEnd)) continue
      if (values[i].isNaN()) continue
      if (started) linePath.lineTo(xOf(times[i]), yOf(values[i]))
      else linePath.moveTo(xOf(times[i]), yOf(values[i]))
      started = true
    }
    canvas.drawPath(linePath, paint)
  }


  // Draws the small lock button in the window's top-left corner (window left edge at x): closed when locked, with
  // its shackle raised and swung open when unlocked. Records its touch area, a little larger than the icon.
  private fun drawLock(canvas: Canvas, x: Float)
  {
    val size = 9f * density
    val left = x + 4f * density
    val top = topMargin + 5f * density
    val bodyTop = top + size * 0.45f
    val shackleLeft = left + size * 0.2f
    val shackleRight = left + size * 0.8f
    val lift = if (locked) 0f else size * 0.25f

    lockPaint.style = Paint.Style.FILL
    canvas.drawRoundRect(left, bodyTop, left + size, top + size, 1.2f * density, 1.2f * density, lockPaint)
    lockPaint.style = Paint.Style.STROKE
    linePath.rewind()
    linePath.moveTo(shackleLeft, bodyTop - (if (locked) 0f else lift))
    linePath.cubicTo(shackleLeft, top - lift - size * 0.2f, shackleRight, top - lift - size * 0.2f, shackleRight, bodyTop - lift)
    if (locked) linePath.lineTo(shackleRight, bodyTop)
    canvas.drawPath(linePath, lockPaint)
    lockRect.set(left - 6f * density, top - 8f * density, left + size + 8f * density, top + size + 6f * density)
  }


  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean
  {
    val windowLeft = xOf(windowStartMs)
    val windowRight = xOf(windowStartMs + WINDOW_MS)
    var start = 0L

    // A tap on the lock button toggles the lock instead of moving the window.
    if (event.actionMasked == MotionEvent.ACTION_DOWN) lockTouched = lockRect.contains(event.x, event.y)
    if (lockTouched)
    {
      if (event.actionMasked == MotionEvent.ACTION_UP && lockRect.contains(event.x, event.y)) setLocked(!locked)
      if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
      return true
    }
    when (event.actionMasked)
    {
      MotionEvent.ACTION_DOWN ->
      {
        // Grabbing inside the window keeps the finger's place on it; tapping elsewhere centres it there.
        grabOffsetPx = if (event.x in windowLeft..windowRight) event.x - windowLeft else (windowRight - windowLeft) / 2
        parent?.requestDisallowInterceptTouchEvent(true)
      }
      MotionEvent.ACTION_MOVE -> {}
      else -> return true
    }
    start = snapWindowStart(timeOf(event.x - grabOffsetPx), nowMs, zone, snapMs, !locked)
    if (start != windowStartMs)
    {
      windowStartMs = start
      onWindowChanged(start)
      invalidate()
    }
    return true
  }
}
