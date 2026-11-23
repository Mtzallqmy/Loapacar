package com.masalik.yemen.game

import org.junit.Assert.*
import org.junit.Test

class AutoDriverTest {
    private val driver = AutoDriver()
    private val car = VehiclePhysicsEngine()
    private val world = LivingWorldSimulation().apply {
        trafficVehicles.clear()
        pedestrians.clear()
    }

    @Test fun declinesOpposingLaneAndReverseGear() {
        car.resetToRoad(500f,-2.65f)
        assertFalse(driver.canEngage(car))
        car.resetToRoad(500f,2.65f)
        car.autoShifter = AutomaticShifterState.R
        assertFalse(driver.canEngage(car))
        car.autoShifter = AutomaticShifterState.D
        assertTrue(driver.canEngage(car))
    }

    @Test fun brakesForSignalThenContinuesOnGreen() {
        val signal = world.trafficSignals.first()
        car.resetToRoad(signal.def.distanceM - car.spec.bodyLengthM / 2f - 2f, 2.65f)
        signal.phase = TrafficSignalPhase.RED
        val stopped = driver.step(car,world,AutoDriveStyle.SCENIC)
        assertEquals(AutoDriveStatus.WAITING,stopped.status)
        assertTrue(stopped.pedals.brake > .5f)
        signal.phase = TrafficSignalPhase.GREEN
        assertTrue(driver.step(car,world,AutoDriveStyle.SCENIC).pedals.throttle > 0f)
    }

    @Test fun followsAnEmptyStretchThroughTheRegularPhysicsLoop() {
        car.resetToRoad(1800f,2.5f)
        var maxOffsetError = 0f
        repeat(1440) {
            car.step(1f / 60f,driver.step(car,world,AutoDriveStyle.SCENIC).pedals,null)
            maxOffsetError = maxOf(maxOffsetError,kotlin.math.abs(car.lateralOffsetM - 2.65f))
        }
        assertTrue(car.distanceAlongRoadM > 1960f)
        assertTrue("lane deviation $maxOffsetError",maxOffsetError < 1.15f)
    }

    @Test fun holdsPositionBeforeEndBoundary() {
        car.resetToRoad(RoadSplineNetwork.TOTAL_ROAD_LENGTH_M - 27f,2.65f)
        val command = driver.step(car,world,AutoDriveStyle.TOURING)
        assertEquals(AutoDriveStatus.ARRIVING,command.status)
        assertEquals(0f,command.pedals.throttle,0f)
        assertTrue(command.pedals.brake > .5f)
    }
}
