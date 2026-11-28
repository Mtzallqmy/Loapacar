package com.masalik.yemen.game
import org.junit.Assert.*
import org.junit.Test

class FleetPowertrainTest {
    @Test fun everyVehicleHasIndependentTransmissionAndFiniteLimits() {
        val profiles = VehicleId.entries.map { Powertrain.forVehicle(it) }
        assertEquals(VehicleId.entries.size, profiles.toSet().size)
        for (id in VehicleId.entries) {
            val s = VehicleFleetCatalog.specOf(id)
            val p = Powertrain.forVehicle(id)
            val ratios = (1..s.forwardGearsCount).map { p.ratio(it, s.forwardGearsCount) }
            assertTrue(ratios.zipWithNext().all { (a,b) -> a >= b && b > 0 })
            assertTrue(p.topSpeedMs(s,1f,1f).let { it.isFinite() && it > 10f })
        }
    }
}
