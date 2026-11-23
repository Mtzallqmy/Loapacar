package com.masalik.yemen.game

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Contact heights in meters relative to the road at the chassis center. */
data class WheelContacts(val frontLeft: Float = 0f, val frontRight: Float = 0f,
                         val rearLeft: Float = 0f, val rearRight: Float = 0f) {
    val mean get() = (frontLeft + frontRight + rearLeft + rearRight) * .25f
    val across get() = (frontRight + rearRight - frontLeft - rearLeft) * .5f
    val along get() = (frontLeft + frontRight - rearLeft - rearRight) * .5f
}

/** The curb ramp in the Taiz service lane is shared by rendering and tire contact. */
object TaizDriveway {
    const val distance = 200f
    const val lane = 36.1f
    const val length = 5.4f
    const val width = 1.55f
    const val rise = .26f
    fun height(d: Float, l: Float): Float {
        val edge = minOf((length / 2 - abs(d - distance)) / 1.1f,
            (width / 2 - abs(l - lane)) / .26f)
        return rise * edge.coerceIn(0f, 1f)
    }
}
class WheelSuspension {
    var contacts = WheelContacts()
        private set
    var heave = 0f
        private set
    private var velocity = 0f
    fun reset() { heave = 0f; velocity = 0f; contacts = WheelContacts() }
    fun step(dt: Float, spec: VehicleSpec, center: RoadSample, lateral: Float, yaw: Float, mass: Float, springUpgrade: Float) {
        val forward = cos(yaw)
        val right = sin(yaw)
        fun contact(axle: Int, side: Int): Float {
            val f = axle * spec.wheelbaseM * .5f
            val s = side * spec.trackWidthM * .5f
            val d = center.distanceM + forward * f - right * s
            val l = lateral + right * f + forward * s
            val road = RoadSplineNetwork.sampleAt(d)
            val shoulder = abs(l) > road.widthM * .5f
            val rough = when (road.surface) {
                SurfaceType.ASPHALT -> if (shoulder && !road.isUrbanStreet) .021f else .001f
                SurfaceType.MUD, SurfaceType.ROCK -> .048f
                else -> .024f
            }
            return road.y - center.y + TaizDriveway.height(d,l) + QuayHumps.height(d, l, road.widthM * .5f) +
                rough * (.65f * sin(d * 1.85f + l) + .35f * sin(d * 3.72f - 2f * l))
        }
        contacts = WheelContacts(contact(1,-1),contact(1,1),contact(-1,-1),contact(-1,1))
        val spring = spec.baseMassKg * 110f * springUpgrade
        val damping = 1.45f * sqrt(spring * mass)
        val loadSag = (mass - spec.baseMassKg) * 9.81f / spring
        val force = spring * (contacts.mean - loadSag - heave) - damping * velocity
        velocity += force / mass * dt
        heave = (heave + velocity * dt).coerceIn(-.38f, .5f)
    }
}
