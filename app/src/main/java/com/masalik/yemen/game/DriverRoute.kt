package com.masalik.yemen.game

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

data class DriveDestination(val label: String, val point: MapPoint)
object DriveDestinations {
    val all = listOf(DriveDestination("مخزن بن تعز",MapPoint(221f,34f)),
        DriveDestination("استراحة المدرجات",MapPoint(1380f,SabirRoad.offset(1380f)+1.65f)),
        DriveDestination("مطلّ صبر",MapPoint(2350f,2.65f)),DriveDestination("سوق إب",MapPoint(3400f,34f)),
        DriveDestination("باب اليمن",MapPoint(15465f,2.65f)),DriveDestination("ساحة شبام",MapPoint(18695f,2.65f)),
        DriveDestination("رصيف عدن",MapPoint(21640f,2.65f)),
        DriveDestination("قرية المحويت", MapPoint(7175f, 2.65f)),
        DriveDestination("سوق مأرب", MapPoint(16812f, 2.65f)),
        DriveDestination("محطة الحديدة", MapPoint(20520f, 2.65f)))
}

/** Sample the same streets shown by GPS into right-hand lanes. Progress is local to the last fix. */
internal class DriverRoute(start: MapPoint, val goal: MapPoint, deliveryActive: Boolean=false) {
    private val streets=StreetNavigation.route(start,goal,deliveryActive)
    val reachable get() = streets.isNotEmpty()
    private val samples = buildList {
        val center = streets.drop(1).dropLast(1).fold(mutableListOf<MapPoint>()) { dst,p ->
            if (dst.lastOrNull() != p) dst.add(p)
            dst
        }
        add(start)
        for ((a,b) in center.zipWithNext()) {
            val length=a.distance(b)
            if (length < .01f) continue
            val fd=(b.d-a.d)/length
            val fl=(b.l-a.l)/length
            val steps=ceil(length/3f).toInt().coerceAtLeast(1)
            for (j in 1..steps) {
                val t=j.toFloat()/steps
                val d=a.d+(b.d-a.d)*t
                val main=abs(a.l)+abs(b.l)<.01f
                var side=if (main) 2.65f else 1.65f
                if (main) for (round in RoadSplineNetwork.ROUNDABOUTS)
                    side+=2.1f*((22f-abs(d-round.distanceM))/12f).coerceIn(0f,1f)
                add(MapPoint(d-fl*side,a.l+(b.l-a.l)*t+fd*side))
            }
        }
        add(goal)
    }
    private val remaining=FloatArray(samples.size)
    private var cursor=0
    init { for (i in samples.lastIndex-1 downTo 0) remaining[i]=remaining[i+1]+samples[i].distance(samples[i+1]) }
    data class Guide(val aim: MapPoint, val remaining: Float, val error: Float, val tight: Boolean)
    fun guide(position: MapPoint, look: Float): Guide {
        var nearest=cursor
        var error=position.distance(samples[cursor])
        for(i in cursor+1..minOf(cursor+9,samples.lastIndex)) {
            val gap=position.distance(samples[i])
            if(gap < error) { nearest=i; error=gap }
        }
        cursor=nearest
        var ahead=cursor
        while(ahead<samples.lastIndex && remaining[cursor]-remaining[ahead]<look) ahead++
        val after=samples[minOf(ahead+4,samples.lastIndex)]
        val aim=samples[ahead]
        val x=aim.d-position.d; val y=aim.l-position.l
        val u=after.d-aim.d; val v=after.l-aim.l
        val tight=hypot(u,v)>.5f && (u*x+v*y)<.88f*hypot(u,v)*hypot(x,y)
        return Guide(aim,remaining[cursor]+error,error,tight)
    }
}
