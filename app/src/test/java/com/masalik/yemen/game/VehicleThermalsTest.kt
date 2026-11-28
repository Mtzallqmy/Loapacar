package com.masalik.yemen.game

import org.junit.Assert.*
import org.junit.Test

class VehicleThermalsTest {
    @Test fun sustainedMountainBrakingHeatsAndFadesThenRecovers() {
        val car = VehicleThermals()
        repeat(60 * 180) { car.step(1f/60, 28f, 16f, .05f, 1000f, 4100f, 1800f, true, 0f) }
        assertTrue(car.brakeC > 360f)
        val hot = car.brakeFraction
        assertTrue(hot < 1f)
        repeat(60 * 180) { car.step(1f/60, 28f, 16f, 0f, 1000f, 0f, 1800f, true, 0f) }
        assertTrue(car.brakeFraction > hot)
        assertEquals(1f, car.brakeFraction, .01f)
    }

    @Test fun lowSpeedHeavyAscentEventuallyDeratesAndFanRecovers() {
        val car = VehicleThermals()
        repeat(60 * 180) { car.step(1f/60, 37f, 6f, 1f, 105000f, 0f, 1800f, true, 0f) }
        assertTrue(car.coolantC > 106f)
        assertTrue(car.enginePowerFraction < 1f)
        val hot = car.coolantC
        repeat(60 * 240) { car.step(1f/60, 28f, 15f, 0f, 0f, 0f, 1800f, true, 0f) }
        assertTrue(car.coolantC < hot)
        assertEquals(1f, car.enginePowerFraction, .01f)
    }
}
