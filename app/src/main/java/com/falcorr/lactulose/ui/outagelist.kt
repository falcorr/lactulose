// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// outagelist.kt
// Text block listing Essential Energy outages at the fixed point: current ones with their estimated end, planned
// ones in the next 14 days, and ones that ended in the last 7 days, each with times and reason.
package com.falcorr.lactulose.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.widget.TextView
import com.falcorr.lactulose.data.OUTAGE_NOW
import com.falcorr.lactulose.data.OUTAGE_PAST
import com.falcorr.lactulose.data.Outage
import java.text.SimpleDateFormat
import java.util.Locale


// Colour for outages happening now.
private const val COLOUR_OUTAGE_NOW = 0xFFFF8A80.toInt()


class OutageList(context: Context) : TextView(context)
{
  // Formats outage times, e.g. "Tue 20 2:00 PM", following the phone's 12/24 h setting.
  private val dayFormat = SimpleDateFormat("EEE d ", Locale.getDefault())
  private val timeFormat = android.text.format.DateFormat.getTimeFormat(context)


  init
  {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    setTextColor(COLOUR_DIM)
  }


  // Shows title, then each outage on its own line, or the status (loading, none, or an error) when empty, and
  // then note (such as a part that couldn't be checked) when not empty.
  fun setOutages(title: String, outages: List<Outage>, status: String, note: String = "")
  {
    val out = SpannableStringBuilder(title)
    var start = 0

    if (outages.isEmpty()) out.append("\n" + status)
    for (o in outages)
    {
      out.append("\n")
      start = out.length
      when (o.state)
      {
        OUTAGE_NOW -> out.append("Now, off since " + whenText(o.offMs) + ", est. back on " + whenText(o.onMs))
        OUTAGE_PAST -> out.append("Past " + whenText(o.offMs) + " to " + whenText(o.onMs))
        else -> out.append("Planned " + whenText(o.offMs) + " to " + whenText(o.onMs))
      }
      out.setSpan(ForegroundColorSpan(when (o.state) { OUTAGE_NOW -> COLOUR_OUTAGE_NOW; OUTAGE_PAST -> COLOUR_DIM
        else -> COLOUR_TEXT }), start, out.length, 0)
      out.append(" · " + o.reason.ifEmpty { "no reason given" })
      if (o.customers >= 0) out.append(" · " + o.customers + " customers")
    }
    if (note.isNotEmpty()) out.append("\n" + note)
    text = out
  }


  // Formats a time as day and clock time, or "unknown" when not given.
  private fun whenText(timeMs: Long): String =
    if (timeMs <= 0) "unknown" else dayFormat.format(timeMs) + timeFormat.format(timeMs)
}
