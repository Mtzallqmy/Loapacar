package com.masalik.yemen.game

import org.junit.Assert.*
import org.junit.Test

class ParkingLessonTest {
    @Test fun parkingLessonAcceptsRealPedalInputsAtBothRenderRates() {
        val mission = MissionsCatalog.ALL_OFFLINE_MISSIONS.first { it.id == MissionsCatalog.PARKING_LESSON_ID }
        fun drive(ticksPerFrame: Int): VehicleTelemetry {
            val run = MissionRun(mission)
            val car = VehiclePhysicsEngine()
            val id = mission.recommendedVehicleId
            car.selectVehicle(id, VehicleFleetCatalog.defaultCustomizations().getValue(id))
            car.resetToRoad(mission.startDistanceM, run.target!!.lane)
            var telemetry = car.telemetry()
            var braking = false
            repeat(1200 / ticksPerFrame) {
                repeat(ticksPerFrame) {
                    if (car.distanceAlongRoadM >= mission.finishDistanceM - 1f) braking = true
                    val pedals = if (braking) ControlInputs(brake = 1f, handbrake = true)
                        else ControlInputs(throttle = if (car.forwardSpeedMs < 2.4f) .5f else .08f)
                    telemetry = car.step(1f / 60f, pedals, null)
                    run.advance(1f / 60f, telemetry, car.headingOffsetFromRoadRad, car.lastCollisionImpulse)
                }
            }
            assertTrue("stopped at ${telemetry.distanceAlongRoadM}, lateral ${telemetry.lateralOffsetM}",run.finished)
            assertTrue(telemetry.speedKmh < .1f)
            return telemetry
        }
        val at30 = drive(2)
        val at60 = drive(1)
        assertEquals(at60.distanceAlongRoadM, at30.distanceAlongRoadM, .001f)
        assertEquals(at60.fuelLiters, at30.fuelLiters, .001f)
    }

    @Test fun readingRestoredParkedPosePreservesHeadingFuelAndDistance() {
        val car = VehiclePhysicsEngine()
        car.resetToRoad(230f, 34f, Math.PI.toFloat())
        val start = car.telemetry()
        repeat(100) { assertEquals(start, car.telemetry()) }
        assertEquals(34f, start.lateralOffsetM, 0f)
        assertEquals(230f, start.distanceAlongRoadM, 0f)
        val expected = RoadSplineNetwork.sampleAt(230f)
        assertEquals(expected.x + expected.rightX * 34f, start.posX, .001f)
        assertEquals(-1f, kotlin.math.cos(start.headingRad - expected.headingRad), .001f)
    }
}
