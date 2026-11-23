package com.masalik.yemen.game

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

class VehicleMeasurementsTest {
    @Test fun compareMarketAccelerationAndBrakingFromThirty() {
        val step = 1f / 60f
        val distances = mutableMapOf<Pair<VehicleId, Float>, Float>()
        for ((id, payload) in listOf(VehicleId.MADINA to 0f, VehicleId.SAQR to 0f, VehicleId.SAQR to 280f)) {
            val vehicle = VehiclePhysicsEngine()
            vehicle.selectVehicle(id, VehicleFleetCatalog.defaultCustomizations().getValue(id))
            vehicle.resetToRoad(256f, CityStreetNetwork.SIDE_STREET_OFFSET_M)
            vehicle.jobPayloadKg = payload
            var ticks = 0
            while (vehicle.forwardSpeedMs < 30f / 3.6f && ticks < 1200) {
                vehicle.step(step, ControlInputs(throttle = 1f), null)
                ticks++
            }
            assertTrue("$id reaches 30 km/h", ticks < 1200)
            val brakeAt = vehicle.totalDistanceDrivenM
            val entrySpeed = vehicle.forwardSpeedMs * 3.6f
            var brakingTicks = 0
            while (abs(vehicle.forwardSpeedMs) > .05f && brakingTicks < 600) {
                vehicle.step(step, ControlInputs(brake = 1f), null)
                brakingTicks++
            }
            val distance = vehicle.totalDistanceDrivenM - brakeAt
            assertTrue("$id stops under service brake", brakingTicks < 600 && distance > 0f)
            distances[id to payload] = distance
            println(String.format(Locale.ROOT,
                "%s payload=%.0f kg acceleration=%.3f s brake_entry=%.2f km/h stopping_distance=%.2f m",
                id, payload, ticks * step, entrySpeed, distance))
        }
        val empty = distances.getValue(VehicleId.SAQR to 0f)
        val loaded = distances.getValue(VehicleId.SAQR to 280f)
        assertTrue("The loaded pickup should need additional braking distance on the test strip",
            loaded - empty in .12f..1.2f)
    }
}
