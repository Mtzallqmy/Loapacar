package com.masalik.yemen.audio

import com.masalik.yemen.game.TrafficVehicleAgent
import com.masalik.yemen.game.TrafficVehicleType
import com.masalik.yemen.game.VehicleTelemetry
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class TrafficAudioField(val gain: Float = 0f, val pan: Float = 0f, val pitchHz: Float = 55f) {
    companion object {
        fun sample(player: VehicleTelemetry, cars: List<TrafficVehicleAgent>): TrafficAudioField {
            var nearest: TrafficVehicleAgent? = null
            var gap = 38f
            for (car in cars) {
                val d = hypot(car.worldX - player.posX, car.worldZ - player.posZ)
                if (d < gap) { nearest = car; gap = d }
            }
            val car = nearest ?: return TrafficAudioField()
            val dx = car.worldX - player.posX
            val dz = car.worldZ - player.posZ
            val side = dx * cos(player.headingRad) - dz * sin(player.headingRad)
            val along = dx * sin(player.headingRad) + dz * cos(player.headingRad)
            val closing = (player.forwardSpeedMs - car.speedMs * car.direction) * if (along >= 0) 1f else -1f
            val base = when (car.type) {
                TrafficVehicleType.TRUCK, TrafficVehicleType.MINIBUS -> 42f
                TrafficVehicleType.SPORTS_COUPE -> 78f
                else -> 55f
            }
            val pitch = (base + abs(car.speedMs) * 4.2f) * (343f / (343f - closing.coerceIn(-50f, 50f)))
            return TrafficAudioField((1f - gap / 38f).let { it * it }, (side / gap.coerceAtLeast(1f)).coerceIn(-1f, 1f), pitch)
        }
    }
}
