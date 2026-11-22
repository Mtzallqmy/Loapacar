package com.masalik.yemen.game

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.hypot

data class MapPoint(val d: Float, val l: Float) {
    fun distance(p: MapPoint) = hypot(d-p.d,l-p.l)
}
data class MapStreet(val a: MapPoint, val b: MapPoint) {
    fun project(p: MapPoint): MapPoint {
        val x = b.d-a.d
        val y = b.l-a.l
        val t = (((p.d-a.d)*x + (p.l-a.l)*y)/(x*x+y*y)).coerceIn(0f,1f)
        return MapPoint(a.d+x*t,a.l+y*t)
    }
}
/** The cross streets, service lanes and regional road share the collision network's coordinates. */
object StreetNavigation {
    val streets: List<MapStreet> by lazy {
        val main = sortedSetOf(0f, RoadSplineNetwork.TOTAL_ROAD_LENGTH_M)
        buildList {
            for (district in CityStreetNetwork.districts) {
                main.addAll(district.junctionsM)
                for (side in listOf(-CityStreetNetwork.SIDE_STREET_OFFSET_M,CityStreetNetwork.SIDE_STREET_OFFSET_M)) {
                    district.junctionsM.forEach { add(MapStreet(MapPoint(it,0f),MapPoint(it,side))) }
                    district.junctionsM.zipWithNext { a,b -> add(MapStreet(MapPoint(a,side),MapPoint(b,side))) }
                }
            }
            main.zipWithNext { a,b -> add(MapStreet(MapPoint(a,0f),MapPoint(b,0f))) }
        }
    }
    fun route(start: MapPoint, goal: MapPoint): List<MapPoint> {
        val source = streets.minBy { it.project(start).distance(start) }
        val dest = streets.minBy { it.project(goal).distance(goal) }
        val from = source.project(start)
        val to = dest.project(goal)
        if (source == dest) return listOf(start,from,to,goal)
        val edges = mutableMapOf<MapPoint,MutableList<MapPoint>>()
        fun link(a: MapPoint,b: MapPoint) {
            edges.getOrPut(a) { mutableListOf() }.add(b)
            edges.getOrPut(b) { mutableListOf() }.add(a)
        }
        streets.forEach { link(it.a,it.b) }
        link(from,source.a); link(from,source.b); link(to,dest.a); link(to,dest.b)
        val cost = mutableMapOf(from to 0f)
        val prev = mutableMapOf<MapPoint,MapPoint>()
        val queue = PriorityQueue<Pair<MapPoint,Float>>(compareBy { it.second })
        queue.add(from to 0f)
        while (!queue.isEmpty()) {
            val (p,g) = queue.remove()
            if (p == to) break
            if (g > cost.getValue(p)) continue
            for (q in edges[p].orEmpty()) {
                val next = g + p.distance(q)
                if (next < (cost[q] ?: Float.POSITIVE_INFINITY)) {
                    cost[q]=next; prev[q]=p; queue.add(q to next)
                }
            }
        }
        val reverse = mutableListOf(goal,to)
        var point = to
        while (point != from) { point = prev[point] ?: return emptyList(); reverse.add(point) }
        reverse.add(start)
        return reverse.asReversed()
    }
}
