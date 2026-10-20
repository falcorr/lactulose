// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// selectionwindow.kt
// Time rules for the graph: the visible range, local clock alignment, and where the fixed-width selection
// window may start (snapped to a configurable local step, 30 minutes by default, starting at the first step at or
// after now,
// ending by now + 21 h).
package com.falcorr.lactulose.core

import java.util.TimeZone

// One minute, quarter-hour, half-hour and hour in milliseconds.
const val MINUTE_MS = 60_000L
const val QUARTER_HOUR_MS = 15 * MINUTE_MS
const val HALF_HOUR_MS = 30 * MINUTE_MS
const val HOUR_MS = 60 * MINUTE_MS

// Graph range relative to now, and the selection window's fixed width.
const val GRAPH_BEFORE_MS = 3 * HOUR_MS
const val GRAPH_AFTER_MS = 21 * HOUR_MS
const val WINDOW_MS = 6 * HOUR_MS + HALF_HOUR_MS

// Default and allowed window snap steps, in minutes (user, 20/10/2026).
const val DEFAULT_SNAP_MIN = 30
const val MIN_SNAP_MIN = 1
const val MAX_SNAP_MIN = 120


// Returns the largest local-clock multiple of stepMs at or below timeMs, e.g. the start of the local hour.
// Local offsets such as +8:45 or +10:30 are honoured because the offset is applied before flooring.
fun floorLocal(timeMs: Long, stepMs: Long, zone: TimeZone): Long
{
  val offset = zone.getOffset(timeMs).toLong()

  return Math.floorDiv(timeMs + offset, stepMs) * stepMs - offset
}


// Returns the latest allowed window start so the window ends no later than nowMs + 21 h, snapped down
// to a local multiple of stepMs.
fun latestWindowStart(nowMs: Long, zone: TimeZone, stepMs: Long = DEFAULT_SNAP_MIN * MINUTE_MS): Long =
  floorLocal(nowMs + GRAPH_AFTER_MS - WINDOW_MS, stepMs, zone)


// Returns the earliest allowed window start: the first local multiple of stepMs at or after nowMs (user, 20/10/2026).
fun earliestWindowStart(nowMs: Long, zone: TimeZone, stepMs: Long = DEFAULT_SNAP_MIN * MINUTE_MS): Long
{
  val floor = floorLocal(nowMs, stepMs, zone)

  return if (floor == nowMs) nowMs else floor + stepMs
}


// Returns the allowed window start nearest requestedMs: the nearest local multiple of stepMs, kept between
// earliestWindowStart and latestWindowStart.
fun snapWindowStart(requestedMs: Long, nowMs: Long, zone: TimeZone, stepMs: Long = DEFAULT_SNAP_MIN * MINUTE_MS): Long
{
  val snapped = floorLocal(requestedMs + stepMs / 2, stepMs, zone)

  return minOf(maxOf(snapped, earliestWindowStart(nowMs, zone, stepMs)), latestWindowStart(nowMs, zone, stepMs))
}
