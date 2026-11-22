package com.masalik.yemen.game

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** Bounded, frame-rate-independent approximations, not a calibrated tire solver. */
object SurfaceDynamics {
    fun lateralResponse(current: Float, target: Float, grip: Float, dt: Float): Float {
        val response = 2.0f + grip.coerceIn(0.25f, 1.5f) * 7f
        return current + (target - current) * (1f - exp(-response * dt.coerceIn(0f, 0.1f)))
    }
    fun yawRate(speed: Float, steerRad: Float, wheelbase: Float, grip: Float, arcade: Boolean): Float {
        if (abs(speed) < 0.1f) return 0f
        val requested = speed / wheelbase.coerceAtLeast(1f) * sin(steerRad)
        if (arcade) return requested
        val tractionLimit = grip.coerceIn(0.25f, 1.5f) * 9.81f / abs(speed).coerceAtLeast(2f)
        return requested.coerceIn(-tractionLimit, tractionLimit)
    }
}

data class EngineVoice(val fundamental: Float, val second: Float, val third: Float, val fourth: Float, val roughness: Float)
object EngineVoiceCatalog {
    fun voice(id: VehicleId): EngineVoice = when (id) {
        VehicleId.JABAL, VehicleId.HAFILAT, VehicleId.QAFILA -> EngineVoice(.55f,.16f,.13f,.08f,.08f)
        VehicleId.SAQR, VehicleId.NIMR, VehicleId.BADIYA -> EngineVoice(.46f,.22f,.17f,.09f,.06f)
        VehicleId.BARQ -> EngineVoice(.27f,.31f,.25f,.15f,.02f)
        VehicleId.MADINA, VehicleId.SAHIL -> EngineVoice(.40f,.35f,.18f,.05f,.02f)
        else -> EngineVoice(.51f,.25f,.15f,.06f,.03f)
    }
}
