package com.masalik.yemen.game

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class AutoDriveStyle(val labelAr: String, val townKmh: Float, val openKmh: Float) {
    SCENIC("جولة هادئة", 24f, 40f),
    TOURING("سفر", 34f, 58f),
    MOUNTAIN("طريق جبلي", 20f, 32f)
}

enum class AutoDriveStatus(val labelAr: String) {
    CRUISING("يتبع المسار"),
    FOLLOWING("يراعي المركبة الأمامية"),
    HOLDING("يثبّت السيارة"),
    WAITING("ينتظر عند الإشارة أو المعبر"),
    BLOCKED("المسار غير متاح · تولَّ القيادة"),
    ARRIVING("الوصول إلى الوجهة")
}

internal data class AutoDriveCommand(val pedals: ControlInputs, val status: AutoDriveStatus)

internal class AutoDriver {
    fun canEngage(car: VehiclePhysicsEngine): Boolean {
        val sample = RoadSplineNetwork.sampleAt(car.distanceAlongRoadM)
        return car.engineRunning && car.transmissionMode == "auto" &&
            car.autoShifter == AutomaticShifterState.D && car.forwardSpeedMs >= -3.5f &&
            abs(car.lateralOffsetM) < sample.widthM * .5f - .8f &&
            car.lateralOffsetM > .25f && abs(car.headingOffsetFromRoadRad) < .75f &&
            car.distanceAlongRoadM < RoadSplineNetwork.TOTAL_ROAD_LENGTH_M - 29f
    }

    fun step(car: VehiclePhysicsEngine, world: LivingWorldSimulation, style: AutoDriveStyle, route: DriverRoute? = null, stopAtGoal: Boolean = true): AutoDriveCommand {
        if (route?.reachable == false) return AutoDriveCommand(ControlInputs(brake=.88f),AutoDriveStatus.BLOCKED)
        if (car.forwardSpeedMs < -.08f) {
            return AutoDriveCommand(ControlInputs(brake = .85f), AutoDriveStatus.HOLDING)
        }
        val distance = car.distanceAlongRoadM
        val sample = RoadSplineNetwork.sampleAt(distance)
        val speed = car.forwardSpeedMs.coerceAtLeast(0f)
        val lookAhead = (6f + speed * .66f).coerceIn(6f, 18f)
        val guide = route?.guide(MapPoint(distance,car.lateralOffsetM),lookAhead)
        if (guide != null && guide.error > 7.5f)
            return AutoDriveCommand(ControlInputs(brake=.88f),AutoDriveStatus.BLOCKED)
        val lead = RoadSplineNetwork.sampleAt(guide?.aim?.d ?: (distance+lookAhead))
        val lane = guide?.aim?.l ?: laneAt(distance + lookAhead)
        val dx = lead.x + lead.rightX * lane - car.posX
        val dz = lead.z + lead.rightZ * lane - car.posZ
        val carHeading = sample.headingRad + car.headingOffsetFromRoadRad
        val side = dx * cos(carHeading) - dz * sin(carHeading)
        val forward = dx*sin(carHeading)+dz*cos(carHeading)
        if (guide != null && forward < -2.5f && guide.remaining > 5f)
            return AutoDriveCommand(ControlInputs(brake=.88f),AutoDriveStatus.BLOCKED)
        val direction = if (cos(car.headingOffsetFromRoadRad) >= 0f) 1f else -1f
        val steerAngle = atan(2f * car.spec.wheelbaseM * side / (dx * dx + dz * dz).coerceAtLeast(1f))
        val steering = (steerAngle / car.steeringLimitRad(speed * 3.6f)).coerceIn(-1f, 1f)

        val grip = when (sample.surface) {
            SurfaceType.ASPHALT -> 1f
            SurfaceType.DIRT, SurfaceType.GRAVEL, SurfaceType.ROCK -> .76f
            SurfaceType.SAND, SurfaceType.MUD -> .52f
        } * when (car.weather) {
            WeatherPreset.HEAVY_RAIN, WeatherPreset.SANDSTORM -> .66f
            WeatherPreset.LIGHT_RAIN, WeatherPreset.MOUNTAIN_FOG -> .79f
            else -> 1f
        }
        val turn = maxOf(abs(sample.curvatureRadPerM), abs(lead.curvatureRadPerM), .001f)
        var target = minOf(
            (if (sample.isUrbanStreet) style.townKmh else style.openKmh) / 3.6f * sqrt(grip),
            sqrt(1.8f * grip / turn)
        )
        if (guide?.tight == true || abs(car.headingOffsetFromRoadRad) in .45f..2.6f) target=minOf(target,2.6f)
        if (sample.gradePct*direction < -3.5f) target *= .78f
        val brakeRate = 1.6f + grip
        val halfLength = car.spec.bodyLengthM * .5f
        var status = AutoDriveStatus.CRUISING
        fun limit(gap: Float, leadSpeed: Float, reason: AutoDriveStatus) {
            val room = gap.coerceAtLeast(0f)
            // Solve for speed given both stopping distance and a time gap before braking.
            val headway = if (reason == AutoDriveStatus.FOLLOWING) 1.6f else .55f
            val reaction = brakeRate * headway
            val permitted = (sqrt(reaction * reaction + 2f * brakeRate * room + leadSpeed * leadSpeed) - reaction)
                .coerceAtLeast(0f)
            if (permitted <= target) {
                target = permitted
                status = reason
            }
        }

        if (guide != null && stopAtGoal) {
            limit(guide.remaining-.55f,0f,AutoDriveStatus.ARRIVING)
            if (route!!.goal.distance(MapPoint(distance,car.lateralOffsetM)) < 1.1f)
                return AutoDriveCommand(ControlInputs(brake=.9f),AutoDriveStatus.ARRIVING)
        } else if (guide == null)
            limit(RoadSplineNetwork.TOTAL_ROAD_LENGTH_M - 28f - distance, 0f, AutoDriveStatus.ARRIVING)
        for (signal in world.trafficSignals) {
            val gap = (signal.def.distanceM - distance)*direction
            if (abs(car.lateralOffsetM)<sample.widthM*.55f && gap in 0f..85f && (signal.phase != TrafficSignalPhase.GREEN || signal.pedestriansCrossing)) {
                limit(gap - halfLength - 3f, 0f, AutoDriveStatus.WAITING)
            }
        }
        for (pedestrian in world.pedestrians) {
            val gap = (pedestrian.distanceM - distance)*direction
            if (pedestrian.isCrosswalkPedestrian && gap in -1f..50f &&
                abs(pedestrian.lateralOffsetM - (if (guide != null) lane else laneAt(pedestrian.distanceM))) < 2.4f) {
                limit(gap - halfLength - 3f, 0f, AutoDriveStatus.WAITING)
            }
        }
        for (other in world.trafficVehicles) {
            val gap = (other.distanceM - distance)*direction
            if (gap in 0f..75f && abs(other.laneOffsetM - (if (guide != null) lane else laneAt(other.distanceM))) < 2f) {
                limit(gap - halfLength - 5.5f, (other.speedMs * other.direction * direction).coerceAtLeast(0f),
                    AutoDriveStatus.FOLLOWING)
            }
        }

        val error = target - speed
        val inputs = if (target < .18f && speed < .45f) {
            ControlInputs(brake = .75f, steer = steering)
        } else {
            ControlInputs(
                throttle = (error * .24f).coerceIn(0f, .62f),
                brake = ((-error - .18f) * .33f).coerceIn(0f, .90f),
                steer = steering
            )
        }
        return AutoDriveCommand(inputs, status)
    }

    private fun laneAt(distance: Float): Float {
        for (junction in RoadSplineNetwork.ROUNDABOUTS) {
            val weight = ((22f - abs(distance - junction.distanceM)) / 12f).coerceIn(0f, 1f)
            if (weight > 0f) return 2.65f + 2.1f * weight
        }
        return 2.65f
    }
}
