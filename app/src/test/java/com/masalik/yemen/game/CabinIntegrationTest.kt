package com.masalik.yemen.game

import com.masalik.yemen.renderer.CabinGeometry
import com.masalik.yemen.renderer.CabinLayout
import org.junit.Assert.*
import org.junit.Test

class CabinIntegrationTest {
    @Test fun everyVehicleHasAViewThroughItsOwnWindshield() {
        val layouts = VehicleId.entries.map { CabinLayout.of(it) }.toSet()
        assertEquals(CabinLayout.entries.toSet(), layouts)
        for (layout in layouts) {
            assertTrue(layout.seatY in layout.cowl..layout.roof)
            assertTrue(layout.seatZ + .14f < layout.glassTopZ)
            assertTrue(layout.driverX + layout.clusterWidth / 2 < layout.width / 2)
            val mesh = CabinGeometry.bake(layout, -1)
            assertTrue(mesh.size >= 5)
            assertTrue(mesh.values.all { data -> data.size % 24 == 0 && data.all { it.isFinite() } })
        }
    }

    @Test fun rainTriggersAutomaticWipersAndOffParksTheBlade() {
        val car = VehiclePhysicsEngine()
        car.weather = WeatherPreset.HEAVY_RAIN
        val tick = 1f / 60f
        repeat(16) { car.step(tick, ControlInputs(brake = .7f), null) }
        assertTrue(car.wiperPhaseRad > .3f)
        assertTrue(car.telemetry().wipersOn)
        car.wiperMode = WiperMode.OFF
        car.step(tick, ControlInputs(brake = .7f), null)
        assertTrue(car.wiperPhaseRad > .3f)
        repeat(180) { car.step(tick, ControlInputs(brake = .7f), null) }
        assertEquals(0f, car.wiperPhaseRad, 0f)
        assertFalse(car.telemetry().wipersOn)
        car.wiperMode = WiperMode.ON
        car.weather = WeatherPreset.CLEAR
        car.step(tick, ControlInputs(brake = .7f), null)
        assertTrue(car.telemetry().wipersOn)
    }
}
