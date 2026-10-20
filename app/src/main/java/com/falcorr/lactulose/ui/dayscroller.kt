// 20/10/2026 created by Claude Opus 5.5
// dayscroller.kt
// Scrolling frame for the daily list: as tall as its first seven rows, so later days (up to three weeks) are
// reached by touch scrolling inside the page. When a scroll comes to rest close to the top or bottom it settles
// onto that end, so the list sticks slightly at its extremes.
package com.falcorr.lactulose.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView


// Distance from an end, in dp, within which a resting scroll settles onto that end.
private const val SNAP_DP = 28

// How long after the last scroll movement the list counts as at rest.
private const val REST_MS = 120L


@SuppressLint("ViewConstructor")
class DayScroller(context: Context, private val list: DailyList) : ScrollView(context)
{
  // Screen density for dp conversion.
  private val density = resources.displayMetrics.density

  // Settles onto the nearer end when the list has come to rest near it.
  private val settle = Runnable {
    val max = maxOf(0, list.height - height)

    if (scrollY in 1 until (SNAP_DP * density).toInt()) smoothScrollTo(0, 0)
    else if (max - scrollY in 1 until (SNAP_DP * density).toInt()) smoothScrollTo(0, max)
  }


  init
  {
    isVerticalScrollBarEnabled = true
    overScrollMode = View.OVER_SCROLL_NEVER
    addView(list)
    setOnScrollChangeListener { _, _, _, _, _ ->
      removeCallbacks(settle)
      postDelayed(settle, REST_MS)
    }
  }


  // Measures as tall as the list's first DAY_ROWS rows (or the whole list if shorter).
  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int)
  {
    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    setMeasuredDimension(measuredWidth, minOf(list.measuredHeight, list.visibleRowsHeight(DAY_ROWS)))
  }


  // Keeps the page from taking the gesture while this list can scroll.
  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean
  {
    if (event.actionMasked == MotionEvent.ACTION_DOWN && list.height > height)
    {
      parent?.requestDisallowInterceptTouchEvent(true)
    }
    return super.onTouchEvent(event)
  }


  override fun onInterceptTouchEvent(event: MotionEvent): Boolean
  {
    if (event.actionMasked == MotionEvent.ACTION_DOWN && list.height > height)
    {
      parent?.requestDisallowInterceptTouchEvent(true)
    }
    return super.onInterceptTouchEvent(event)
  }
}
