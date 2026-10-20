// 20/10/2026 created by Claude Opus 5.5
// 20/10/2026 last modified by Claude Opus 5.5
// weathericon.kt
// Small weather icon drawn in code from BoM's icon descriptor (sunny, mostly_sunny, partly_cloudy, cloudy,
// windy, light_rain, rain, shower, storm, fog, frost, snow and others), with a moon instead of the sun at night.
package com.falcorr.lactulose.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View


// Icon colours, as ARGB.
private const val COLOUR_SUN = 0xFFFFC94D.toInt()
private const val COLOUR_MOON = 0xFFE8E3C8.toInt()
private const val COLOUR_CLOUD = 0xFFD8DEE6.toInt()
private const val COLOUR_CLOUD_DARK = 0xFF8E99A8.toInt()
private const val COLOUR_DROP = 0xFF81D4FA.toInt()
private const val COLOUR_BOLT = 0xFFFFE082.toInt()
private const val COLOUR_WIND = 0xFFB0BEC5.toInt()
private const val COLOUR_HAZE = 0xFFC8B89A.toInt()
private const val COLOUR_ICE = 0xFFE1F5FE.toInt()
private const val COLOUR_CYCLONE = 0xFFFF8A80.toInt()


@SuppressLint("ViewConstructor")
class WeatherIcon(context: Context, private val sizeDp: Int) : View(context)
{
  // Screen density for dp conversion.
  private val density = resources.displayMetrics.density

  // BoM icon descriptor shown, and whether it is night (a moon replaces the sun).
  private var kind = ""
  private var night = false

  // Reused drawing objects.
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
  }
  private val path = Path()
  private val cutout = Path()
  private val oval = RectF()


  // Shows the icon for BoM descriptor newKind ("" draws nothing), at night if isNight.
  fun setKind(newKind: String, isNight: Boolean = false)
  {
    kind = newKind
    night = isNight
    contentDescription = newKind.replace('_', ' ')
    invalidate()
  }


  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int)
  {
    val size = (sizeDp * density).toInt()

    setMeasuredDimension(size, size)
  }


  override fun onDraw(canvas: Canvas)
  {
    val s = minOf(width, height).toFloat()

    stroke.strokeWidth = s * 0.06f
    when (kind)
    {
      "" -> {}
      "sunny", "clear" -> sky(canvas, s * 0.5f, s * 0.5f, s * 0.22f)
      "mostly_sunny" ->
      {
        sky(canvas, s * 0.42f, s * 0.42f, s * 0.2f)
        cloud(canvas, s * 0.66f, s * 0.72f, s * 0.48f, COLOUR_CLOUD)
      }
      "partly_cloudy" ->
      {
        sky(canvas, s * 0.36f, s * 0.36f, s * 0.18f)
        cloud(canvas, s * 0.58f, s * 0.66f, s * 0.72f, COLOUR_CLOUD)
      }
      "cloudy" ->
      {
        cloud(canvas, s * 0.62f, s * 0.48f, s * 0.6f, COLOUR_CLOUD_DARK)
        cloud(canvas, s * 0.44f, s * 0.68f, s * 0.72f, COLOUR_CLOUD)
      }
      "hazy", "dusty" ->
      {
        sky(canvas, s * 0.5f, s * 0.4f, s * 0.18f)
        lines(canvas, s, s * 0.68f, 3, if (kind == "dusty") COLOUR_HAZE else COLOUR_WIND)
      }
      "fog" ->
      {
        cloud(canvas, s * 0.5f, s * 0.46f, s * 0.72f, COLOUR_CLOUD_DARK)
        lines(canvas, s, s * 0.7f, 3, COLOUR_WIND)
      }
      "windy" -> wind(canvas, s)
      "light_shower", "shower" ->
      {
        sky(canvas, s * 0.36f, s * 0.3f, s * 0.16f)
        cloud(canvas, s * 0.56f, s * 0.54f, s * 0.7f, COLOUR_CLOUD)
        drops(canvas, s, if (kind == "shower") 2 else 1, false)
      }
      "light_rain" ->
      {
        cloud(canvas, s * 0.5f, s * 0.46f, s * 0.78f, COLOUR_CLOUD)
        drops(canvas, s, 2, false)
      }
      "rain", "heavy_shower" ->
      {
        cloud(canvas, s * 0.5f, s * 0.46f, s * 0.78f, COLOUR_CLOUD_DARK)
        drops(canvas, s, 4, true)
      }
      "storm" ->
      {
        cloud(canvas, s * 0.5f, s * 0.42f, s * 0.8f, COLOUR_CLOUD_DARK)
        bolt(canvas, s)
        drops(canvas, s, 2, false, 0.38f)
      }
      "frost" -> flake(canvas, s * 0.5f, s * 0.5f, s * 0.32f)
      "snow" ->
      {
        cloud(canvas, s * 0.5f, s * 0.42f, s * 0.78f, COLOUR_CLOUD)
        flake(canvas, s * 0.32f, s * 0.82f, s * 0.1f)
        flake(canvas, s * 0.68f, s * 0.82f, s * 0.1f)
      }
      "cyclone", "tropical_cyclone" -> cyclone(canvas, s)
      else -> cloud(canvas, s * 0.5f, s * 0.55f, s * 0.78f, COLOUR_CLOUD_DARK)
    }
  }


  // Draws the sun, or at night the moon, centred on cx, cy with disc radius r.
  private fun sky(canvas: Canvas, cx: Float, cy: Float, r: Float)
  {
    var angle = 0.0

    if (night)
    {
      path.rewind()
      cutout.rewind()
      path.addCircle(cx, cy, r * 1.25f, Path.Direction.CW)
      cutout.addCircle(cx + r * 0.7f, cy - r * 0.45f, r * 1.05f, Path.Direction.CW)
      path.op(cutout, Path.Op.DIFFERENCE)
      fill.color = COLOUR_MOON
      canvas.drawPath(path, fill)
      return
    }
    fill.color = COLOUR_SUN
    stroke.color = COLOUR_SUN
    canvas.drawCircle(cx, cy, r, fill)
    for (i in 0 until 8)
    {
      angle = i * Math.PI / 4
      canvas.drawLine(cx + (r * 1.4f * Math.cos(angle)).toFloat(), cy + (r * 1.4f * Math.sin(angle)).toFloat(),
        cx + (r * 1.85f * Math.cos(angle)).toFloat(), cy + (r * 1.85f * Math.sin(angle)).toFloat(), stroke)
    }
  }


  // Draws a filled cloud of width w whose flat base sits a little below cy.
  private fun cloud(canvas: Canvas, cx: Float, cy: Float, w: Float, colour: Int)
  {
    path.rewind()
    oval.set(cx - w * 0.5f, cy - w * 0.02f, cx + w * 0.5f, cy + w * 0.26f)
    path.addRoundRect(oval, w * 0.14f, w * 0.14f, Path.Direction.CW)
    path.addCircle(cx - w * 0.2f, cy + w * 0.04f, w * 0.2f, Path.Direction.CW)
    path.addCircle(cx + w * 0.06f, cy - w * 0.04f, w * 0.27f, Path.Direction.CW)
    path.addCircle(cx + w * 0.3f, cy + w * 0.08f, w * 0.17f, Path.Direction.CW)
    fill.color = colour
    canvas.drawPath(path, fill)
  }


  // Draws count slanted rain drops under a cloud, gap apart as a fraction of the icon (wider to leave room for a
  // bolt); long ones for steady rain.
  private fun drops(canvas: Canvas, s: Float, count: Int, long: Boolean, gap: Float = 0.18f)
  {
    val top = s * 0.78f
    val length = if (long) s * 0.17f else s * 0.11f
    var x = 0f

    stroke.color = COLOUR_DROP
    for (i in 0 until count)
    {
      x = s * (0.5f + (i - (count - 1) / 2f) * gap)
      canvas.drawLine(x, top, x - length * 0.35f, top + length, stroke)
    }
  }


  // Draws a lightning bolt below the cloud.
  private fun bolt(canvas: Canvas, s: Float)
  {
    path.rewind()
    path.moveTo(s * 0.56f, s * 0.6f)
    path.lineTo(s * 0.4f, s * 0.82f)
    path.lineTo(s * 0.5f, s * 0.82f)
    path.lineTo(s * 0.42f, s * 0.99f)
    path.lineTo(s * 0.64f, s * 0.74f)
    path.lineTo(s * 0.53f, s * 0.74f)
    path.lineTo(s * 0.6f, s * 0.6f)
    path.close()
    fill.color = COLOUR_BOLT
    canvas.drawPath(path, fill)
  }


  // Draws count horizontal lines starting at y, for fog or haze.
  private fun lines(canvas: Canvas, s: Float, y: Float, count: Int, colour: Int)
  {
    stroke.color = colour
    for (i in 0 until count) canvas.drawLine(s * (0.18f + i * 0.06f), y + i * s * 0.12f, s * (0.82f - i * 0.04f),
      y + i * s * 0.12f, stroke)
  }


  // Draws three gust lines with curled ends.
  private fun wind(canvas: Canvas, s: Float)
  {
    stroke.color = COLOUR_WIND
    for (i in 0 until 3)
    {
      val y = s * (0.3f + i * 0.2f)
      val end = s * (if (i == 1) 0.82f else 0.66f)

      path.rewind()
      path.moveTo(s * 0.12f, y)
      path.lineTo(end, y)
      path.cubicTo(end + s * 0.14f, y, end + s * 0.12f, y - s * 0.16f, end, y - s * 0.12f)
      canvas.drawPath(path, stroke)
    }
  }


  // Draws a six-armed ice flake of arm length r.
  private fun flake(canvas: Canvas, cx: Float, cy: Float, r: Float)
  {
    var angle = 0.0

    stroke.color = COLOUR_ICE
    for (i in 0 until 3)
    {
      angle = i * Math.PI / 3 + Math.PI / 2
      canvas.drawLine(cx - (r * Math.cos(angle)).toFloat(), cy - (r * Math.sin(angle)).toFloat(),
        cx + (r * Math.cos(angle)).toFloat(), cy + (r * Math.sin(angle)).toFloat(), stroke)
    }
  }


  // Draws a cyclone symbol: a ring with two opposite tails.
  private fun cyclone(canvas: Canvas, s: Float)
  {
    stroke.color = COLOUR_CYCLONE
    oval.set(s * 0.3f, s * 0.3f, s * 0.7f, s * 0.7f)
    canvas.drawOval(oval, stroke)
    oval.set(s * 0.1f, s * 0.1f, s * 0.9f, s * 0.9f)
    canvas.drawArc(oval, 180f, 70f, false, stroke)
    canvas.drawArc(oval, 0f, 70f, false, stroke)
  }
}
