package com.masalik.yemen.game

import kotlin.math.abs

/** Distances and offsets in the game's road coordinates. The two forks meet on grade. */
object SabirRoad {
    const val start = 620f
    const val end = 2220f
    const val halfWidth = 4.3f
    private val bends = listOf(MapPoint(start,0f),MapPoint(745f,-22f),MapPoint(910f,-52f),
        MapPoint(1080f,-41f),MapPoint(1240f,-67f),MapPoint(1410f,-64f),MapPoint(1570f,-35f),
        MapPoint(1770f,-58f),MapPoint(1930f,-48f),MapPoint(2070f,-24f),MapPoint(end,0f))

    fun offset(d: Float): Float {
        if (d <= start || d >= end) return 0f
        val i = bends.indexOfFirst { it.d >= d }
        val a=bends[i-1]; val b=bends[i]
        val t=(d-a.d)/(b.d-a.d)
        return a.l+(b.l-a.l)*t*t*(3f-2f*t)
    }
    val points = (start.toInt()..end.toInt() step 10).map { MapPoint(it.toFloat(), offset(it.toFloat())) }

    fun overlaps(d: Float, lane: Float, margin: Float = 0f) =
        d in start..end && abs(lane-offset(d)) < halfWidth+margin
}
