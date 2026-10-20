// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// coretest.kt
// Unit tests for interpolation, window averages, window snapping and clamping, and the lactulose dose.
package com.falcorr.lactulose

import com.falcorr.lactulose.core.HOUR_MS
import com.falcorr.lactulose.core.MINUTE_MS
import com.falcorr.lactulose.core.TempSeries
import com.falcorr.lactulose.core.WINDOW_MS
import com.falcorr.lactulose.core.floorLocal
import com.falcorr.lactulose.core.DoseRule
import com.falcorr.lactulose.core.DEFAULT_DOSE_POINTS
import com.falcorr.lactulose.core.formatMl
import com.falcorr.lactulose.core.latestWindowStart
import com.falcorr.lactulose.core.snapWindowStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone


class CoreTest
{
  // Sydney (+10/+11) and Eucla-style +8:45 zones, and a fixed base time of 2026-10-20 00:00 UTC.
  private val sydney = TimeZone.getTimeZone("Australia/Sydney")
  private val eucla = TimeZone.getTimeZone("Australia/Eucla")
  private val base = 1_792_368_000_000L

  // Three hourly samples rising 10 → 20 → 20 °C, feels-like 2 °C lower.
  private val series = TempSeries(longArrayOf(base, base + HOUR_MS, base + 2 * HOUR_MS),
    doubleArrayOf(10.0, 20.0, 20.0), doubleArrayOf(8.0, 18.0, 18.0))


  @Test
  fun interpolatesBetweenAndAtSamples()
  {
    assertEquals(15.0, series.valueAt(series.temps, base + HOUR_MS / 2), 1e-9)
    assertEquals(20.0, series.valueAt(series.temps, base + HOUR_MS), 1e-9)
    assertTrue(series.valueAt(series.temps, base - 1).isNaN())
    assertTrue(series.valueAt(series.temps, base + 2 * HOUR_MS + 1).isNaN())
  }


  @Test
  fun averagesExactlyOverPiecewiseLines()
  {
    // First hour averages 15, second hour 20, so two hours average 17.5.
    assertEquals(17.5, series.average(series.temps, base, base + 2 * HOUR_MS), 1e-9)
    assertEquals(15.5, series.average(series.feels, base, base + 2 * HOUR_MS), 1e-9)
    // Window inside one segment: 12.5 to 17.5 averages 15.
    assertEquals(15.0, series.average(series.temps, base + HOUR_MS / 4, base + 3 * HOUR_MS / 4), 1e-9)
    assertTrue(series.average(series.temps, base, base + 3 * HOUR_MS).isNaN())
    assertTrue(series.average(series.temps, base, base).isNaN())
  }


  @Test
  fun snapsToLocalQuarterHoursAndClamps()
  {
    val now = base + 7 * MINUTE_MS

    // Requests before the first quarter at or after now give that quarter, never now itself.
    assertEquals(base + 15 * MINUTE_MS, snapWindowStart(now - HOUR_MS, now, sydney, 15 * MINUTE_MS))
    assertEquals(base + 15 * MINUTE_MS, snapWindowStart(now, now, sydney, 15 * MINUTE_MS))
    assertEquals(base, snapWindowStart(base - HOUR_MS, base, sydney, 15 * MINUTE_MS))
    // The user's example: at 4:24pm with 30-minute steps the earliest start is 4:30pm.
    assertEquals(base + 30 * MINUTE_MS, snapWindowStart(base + 24 * MINUTE_MS, base + 24 * MINUTE_MS, sydney))
    assertEquals(base + 15 * MINUTE_MS, snapWindowStart(now + MINUTE_MS, now, sydney, 15 * MINUTE_MS))
    assertEquals(base + 15 * MINUTE_MS, snapWindowStart(base + 12 * MINUTE_MS, now, sydney, 15 * MINUTE_MS))
    assertEquals(base + 30 * MINUTE_MS, snapWindowStart(base + 23 * MINUTE_MS, now, sydney, 15 * MINUTE_MS))
    // The window may not end after now + 21 h.
    assertEquals(latestWindowStart(now, sydney, 15 * MINUTE_MS), snapWindowStart(now + 30 * HOUR_MS, now, sydney, 15 * MINUTE_MS))
    assertTrue(latestWindowStart(now, sydney, 15 * MINUTE_MS) + WINDOW_MS <= now + 21 * HOUR_MS)
    assertTrue(latestWindowStart(now, sydney, 15 * MINUTE_MS) + WINDOW_MS > now + 21 * HOUR_MS - 15 * MINUTE_MS)
    // Unlocked, the window may start back to the first quarter within the graph's past 3 h, but no further.
    assertEquals(base - HOUR_MS, snapWindowStart(base - HOUR_MS, now, sydney, 15 * MINUTE_MS, allowPast = true))
    assertEquals(base - 3 * HOUR_MS + 15 * MINUTE_MS, snapWindowStart(now - 5 * HOUR_MS, now, sydney, 15 * MINUTE_MS, true))
    // The default step is 30 minutes.
    assertEquals(base + 30 * MINUTE_MS, snapWindowStart(base + 20 * MINUTE_MS, now, sydney))
    assertEquals(base + 30 * MINUTE_MS, snapWindowStart(base + 12 * MINUTE_MS + 30 * MINUTE_MS, now, sydney))
  }


  @Test
  fun honoursQuarterHourOffsets()
  {
    // Eucla is UTC+8:45, so local hour starts fall at :15 past the UTC hour.
    assertEquals(base + 15 * MINUTE_MS, floorLocal(base + 20 * MINUTE_MS, HOUR_MS, eucla))
  }


  @Test
  fun lactuloseMatchesUserPoints()
  {
    val points = mapOf(-5.0 to "0.30mL", 10.0 to "0.30mL", 15.0 to "0.30mL", 20.0 to "0.40mL", 25.0 to "0.50mL",
      30.0 to "0.60mL", 35.0 to "0.70mL", 40.0 to "0.80mL", 45.0 to "0.90mL", 50.0 to "1.00mL")

    val rule = DoseRule()

    for ((celsius, expected) in points) assertEquals("at " + celsius, expected, formatMl(rule.doseMl(celsius)))
    assertEquals("0.43mL", formatMl(rule.doseMl(21.3)))
    assertEquals("0.35mL", formatMl(rule.doseMl(17.5)))
    assertEquals("0.43mL", formatMl(rule.doseMl(21.25)))
    assertEquals("–mL", formatMl(rule.doseMl(Double.NaN)))
    // A changed floor keeps the points but never gives less than the floor dose.
    assertEquals("0.90mL", formatMl(DoseRule(19.0, 0.9).doseMl(23.5)))
    assertEquals("0.90mL", formatMl(DoseRule(19.0, 0.9).doseMl(45.0)))
    assertEquals("1.00mL", formatMl(DoseRule(19.0, 0.9).doseMl(50.0)))
    // A higher floor with a small floor dose: below the floor temperature the floor temperature counts, and the
    // floor dose is only a minimum, so the points still decide (0.4 mL at 20 °C).
    assertEquals(0.4, DoseRule(20.0, 0.2).doseMl(10.0), 1e-9)
    assertEquals(0.45, DoseRule(20.0, 0.2).doseMl(22.5), 1e-9)
    assertEquals(0.5, DoseRule(20.0, 0.2).doseMl(25.0), 1e-9)
    // Floors below zero and the setting limits: the points extended down, never below the floor dose.
    assertEquals(0.1, DoseRule(-15.0, 0.1).doseMl(-20.0), 1e-9)
    assertEquals(0.2, DoseRule(-15.0, 0.1).doseMl(10.0), 1e-9)
    assertEquals(3.0, DoseRule(50.0, 3.0).doseMl(50.0), 1e-9)
    assertEquals(3.0, DoseRule(-15.0, 3.0).doseMl(50.0), 1e-9)
  }


  @Test
  fun clampedAverageCountsColdTimeAsFloor()
  {
    // 10 to 20 °C over one hour crosses 15 °C halfway: half the time at 15, half averaging 17.5.
    val ramp = TempSeries(longArrayOf(base, base + HOUR_MS), doubleArrayOf(10.0, 20.0), doubleArrayOf(20.0, 10.0))

    assertEquals(16.25, ramp.averageClamped(ramp.temps, base, base + HOUR_MS, 15.0), 1e-9)
    assertEquals(16.25, ramp.averageClamped(ramp.feels, base, base + HOUR_MS, 15.0), 1e-9)
    assertEquals(15.0, ramp.averageClamped(ramp.temps, base, base + HOUR_MS / 4, 15.0), 1e-9)
    assertEquals(series.average(series.temps, base, base + 2 * HOUR_MS),
      series.averageClamped(series.temps, base, base + 2 * HOUR_MS, -5.0), 1e-9)
    // The user's example: 3 h at 10 °C then 3.5 h at 30 °C gives 0.46 mL (0.3 for 3 h, 0.6 for 3.5 h).
    val steps = TempSeries(longArrayOf(base, base + 3 * HOUR_MS, base + 3 * HOUR_MS + 1, base + WINDOW_MS),
      doubleArrayOf(10.0, 10.0, 30.0, 30.0), doubleArrayOf(10.0, 10.0, 30.0, 30.0))

    assertEquals("0.46mL", formatMl(DoseRule().doseByTemp(steps, base, base + WINDOW_MS)))
  }


  @Test
  fun feelsDoseIsTheTempDoseOffsetByTheGap()
  {
    val base = 1_800_000_000_000L
    // Flat series over the window: temperature T and feels-like F throughout.
    val flat = { t: Double, f: Double -> TempSeries(longArrayOf(base, base + WINDOW_MS),
      doubleArrayOf(t, t), doubleArrayOf(f, f)) }
    val rule = DoseRule()

    // Above the floor: 0.8 mL by temp at 40 °C, and 5 °C colder by feel is 0.1 mL less.
    assertEquals(0.8, rule.doseByTemp(flat(40.0, 35.0), base, base + WINDOW_MS), 1e-9)
    assertEquals(0.7, rule.doseByFeel(flat(40.0, 35.0), base, base + WINDOW_MS), 1e-9)
    // Feels-like warmer than the temperature raises the by-feel dose.
    assertEquals(0.84, rule.doseByFeel(flat(40.0, 42.0), base, base + WINDOW_MS), 1e-9)
    // Temperature 10 °C, feels 2 °C: by temp the floor 0.3 mL; by feel 8 °C of points lower, 0.14 mL, below the
    // floor dose because the floor doesn't apply to it.
    assertEquals(0.3, rule.doseByTemp(flat(10.0, 2.0), base, base + WINDOW_MS), 1e-9)
    assertEquals(0.14, rule.doseByFeel(flat(10.0, 2.0), base, base + WINDOW_MS), 1e-9)
    // Floor 10 °C at 0.1 mL: the points give 0.2 mL at 10 °C, so by temp 0.2 mL, and by feel 3 °C lower 0.14 mL.
    assertEquals(0.2, DoseRule(10.0, 0.1).doseByTemp(flat(5.0, 2.0), base, base + WINDOW_MS), 1e-9)
    assertEquals(0.14, DoseRule(10.0, 0.1).doseByFeel(flat(5.0, 2.0), base, base + WINDOW_MS), 1e-9)
    // Nonlinear points are followed between and beyond them.
    val curve = DoseRule(15.0, 0.3, listOf(Pair(15.0, 0.3), Pair(25.0, 0.4), Pair(35.0, 1.0)))
    assertEquals(0.35, curve.doseMl(20.0), 1e-9)
    assertEquals(0.7, curve.doseMl(30.0), 1e-9)
    assertEquals(1.3, curve.doseMl(40.0), 1e-9)
    // ... and the gap is measured along them: 30 °C feeling 20 °C drops 0.7 to 0.35 mL.
    assertEquals(0.35, curve.doseByFeel(flat(30.0, 20.0), base, base + WINDOW_MS), 1e-9)
    // The user's example floor (19 °C, 0.9 mL): by temp the floor dose; by feel 2.3 °C (0.046 mL) less.
    assertEquals(0.9, DoseRule(19.0, 0.9).doseByTemp(flat(23.5, 21.2), base, base + WINDOW_MS), 1e-9)
    assertEquals(0.854, DoseRule(19.0, 0.9).doseByFeel(flat(23.5, 21.2), base, base + WINDOW_MS), 1e-9)
    // Never below zero, even far below the points.
    assertEquals(0.0, DoseRule(-15.0, 0.1).doseByFeel(flat(-14.0, -30.0), base, base + WINDOW_MS), 1e-9)
  }


  @Test
  fun floorDoseMovesBothDosesTogether()
  {
    val base = 1_800_000_000_000L
    // The user's screenshots (20/10/2026): points 20..50 °C, floor 15 °C, a window at temperature 17.1 °C and
    // feels-like 14.9 °C. Raising the floor dose from 0.30 to 0.35 mL raised the by-temp dose but left the
    // by-feel dose at 0.30 mL. The by-feel dose must stay the by-temp dose less 2.2 °C of points (0.044 mL).
    val user = (20..50 step 5).map { Pair(it.toDouble(), it * 0.02) }
    val window = TempSeries(longArrayOf(base, base + WINDOW_MS), doubleArrayOf(17.1, 17.1), doubleArrayOf(14.9, 14.9))
    val low = DoseRule(15.0, 0.3, user)
    val high = DoseRule(15.0, 0.35, user)

    assertEquals(0.342, low.doseByTemp(window, base, base + WINDOW_MS), 1e-9)
    assertEquals(0.298, low.doseByFeel(window, base, base + WINDOW_MS), 1e-9)
    assertEquals(0.35, high.doseByTemp(window, base, base + WINDOW_MS), 1e-9)
    assertEquals(0.306, high.doseByFeel(window, base, base + WINDOW_MS), 1e-9)
    // The same for many varied windows, floors and floor doses: whenever the by-feel dose is above zero, the gap
    // between the two doses doesn't depend on the floor dose, so both move together.
    val random = java.util.Random(20261020L)
    var times: LongArray
    var temps: DoubleArray
    var feels: DoubleArray

    repeat(500) {
      times = LongArray(8) { base + it * HOUR_MS }
      temps = DoubleArray(8) { -5.0 + 45.0 * random.nextDouble() }
      feels = DoubleArray(8) { temps[it] - 6.0 + 9.0 * random.nextDouble() }
      val series = TempSeries(times, temps, feels)
      val floorC = 5.0 + 20.0 * random.nextDouble()
      val rules = listOf(0.1, 0.3, 0.6, 1.2).map { DoseRule(floorC, it, DEFAULT_DOSE_POINTS) }
      val gaps = rules.map { it.doseByTemp(series, base, base + WINDOW_MS) - it.doseByFeel(series, base, base + WINDOW_MS) }
      val lowest = (0 until 8).minOf { rules[0].feelsDoseMl(temps[it], feels[it]) }

      if (lowest > 0.02) for (gap in gaps) assertEquals(gaps[0], gap, 1e-9)
    }
  }


  @Test
  fun dosePointsRoundTripAndValidate()
  {
    val text = "15 0.3\n25=0.45, 35 1.0\n-15 0.1"
    val points = com.falcorr.lactulose.core.parseDosePoints(text)!!

    assertEquals(listOf(Pair(-15.0, 0.1), Pair(15.0, 0.3), Pair(25.0, 0.45), Pair(35.0, 1.0)), points)
    assertEquals(points, com.falcorr.lactulose.core.parseDosePoints(com.falcorr.lactulose.core.formatDosePoints(points)))
    assertEquals(null, com.falcorr.lactulose.core.parseDosePoints("15 0.3"))
    assertEquals(null, com.falcorr.lactulose.core.parseDosePoints("15 0.3\n15 0.4"))
    assertEquals(null, com.falcorr.lactulose.core.parseDosePoints("15 0.3\n60 0.4"))
    assertEquals(null, com.falcorr.lactulose.core.parseDosePoints("15 0.3\n20 4"))
  }


  @Test
  fun offsetAddsToBothDoses()
  {
    val series = TempSeries(longArrayOf(base, base + 10 * HOUR_MS), doubleArrayOf(20.0, 20.0), doubleArrayOf(18.0, 18.0))
    val plain = DoseRule()
    val offset = DoseRule(offsetMl = 0.25)

    // A 0.25 mL offset adds exactly 0.25 mL to the by-temperature and by-feel window doses.
    assertEquals(plain.doseByTemp(series, base, base + WINDOW_MS) + 0.25, offset.doseByTemp(series, base, base + WINDOW_MS), 1e-9)
    assertEquals(plain.doseByFeel(series, base, base + WINDOW_MS) + 0.25, offset.doseByFeel(series, base, base + WINDOW_MS), 1e-9)
    assertEquals("0.65mL", formatMl(offset.doseByTemp(series, base, base + WINDOW_MS)))
  }
}
