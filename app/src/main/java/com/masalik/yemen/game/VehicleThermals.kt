package com.masalik.yemen.game

import kotlin.math.abs

/**
 * Calibrated simulation targets, not manufacturer thermodynamic specifications.
 * Temperatures evolve from absorbed work (joules) and airflow over time.
 */
internal class VehicleThermals {
    var coolantC = 78f
        private set
    var brakeC = 28f
        private set

    val enginePowerFraction: Float get() = (1f - (coolantC - 106f).coerceAtLeast(0f) / 48f).coerceIn(.48f, 1f)
    val brakeFraction: Float get() = (1f - (brakeC - 360f).coerceAtLeast(0f) / 740f).coerceIn(.55f, 1f)

    fun reset() {
        coolantC = 78f
        brakeC = 28f
    }

    fun step(dt: Float, ambient: Float, speedMs: Float, throttle: Float, shaftPowerW: Float,
             brakeForceN: Float, massKg: Float, engineRunning: Boolean, wetness: Float) {
        val speed = abs(speedMs)
        val size = (massKg / 1600f).coerceIn(.65f, 4f)
        val intake = if (engineRunning) 3800f + throttle * 3600f + abs(shaftPowerW) * .48f else 0f
        val fan = if (engineRunning && coolantC >= 94f) 220f else 0f
        val cooling = (coolantC - ambient) * (42f + speed * 5f + fan)
        coolantC = (coolantC + (intake - cooling) * dt / (55_000f * size)).coerceIn(ambient, 150f)
        val heat = abs(brakeForceN) * speed * .85f
        val brakeCooling = (brakeC - ambient) * (18f + speed * 4f + wetness * 27f)
        brakeC = (brakeC + (heat - brakeCooling) * dt / (6400f * size)).coerceIn(ambient, 1000f)
    }
}
