package com.masalik.yemen.game

import kotlin.math.PI

/** Fictional fleet calibrations. Ratios are dimensionless, height in metres. */
internal class Powertrain(
    val finalDrive: Float, val frontWeight: Float, val cgHeight: Float, val frontDriven: Boolean,
    val peakTorqueFraction: Float, val coastDrag: Float, private val gears: FloatArray
) {
    fun ratio(gear: Int, count: Int): Float {
        if (gear <= 0) return if (gear == 0) 0f else 3.35f
        val index = (gear.coerceIn(1, count)-1) * gears.lastIndex.toFloat() / (count-1).coerceAtLeast(1)
        val lo = index.toInt()
        return gears[lo] + (gears[(lo+1).coerceAtMost(gears.lastIndex)] - gears[lo]) * (index-lo)
    }

    fun torqueFraction(rpm: Float, redline: Float): Float {
        val speed = (rpm/redline).coerceIn(0f,1f)
        return if (speed < peakTorqueFraction) .58f + .42f * speed / peakTorqueFraction
        else 1f - .32f * (speed-peakTorqueFraction)/(1f-peakTorqueFraction)
    }

    fun nextGear(current: Int, count: Int, inputRpm: Float, redline: Float, throttle: Float): Int {
        val gear = current.coerceIn(1,count)
        val shaft = inputRpm * ratio(gear,count)
        val up = redline * (.47f + .40f * throttle)
        if (shaft > up && gear < count) return gear + 1
        if (gear > 1 && shaft < redline*(.22f + .095f*throttle) &&
            inputRpm*ratio(gear-1,count) < up*.95f) return gear-1
        return gear
    }

    fun topSpeedMs(spec: VehicleSpec, gearMultiplier: Float, powerMultiplier: Float): Float {
        val wheel = spec.wheelRadiusM * 2f * PI.toFloat() / 60f
        val gearing = spec.redlineRpm * wheel / (finalDrive * ratio(spec.forwardGearsCount,spec.forwardGearsCount) * gearMultiplier)
        val area = spec.bodyWidthM*spec.bodyHeightM*.85f
        val drag = .5f*1.225f*area*spec.dragCoefficient
        // Fixed iterations only on vehicle or configuration change.
        var low = 0f
        var high = gearing
        repeat(15) {
            val v = (low+high)*.5f
            if ((drag*v*v + spec.rollingResistance)*v < spec.maxPowerHp*745.7f*.86f*powerMultiplier) low=v else high=v
        }
        return high
    }

    companion object {
        private val sedan = Powertrain(3.65f,.60f,.52f,true,.55f,62f,floatArrayOf(3.82f,2.16f,1.51f,1.16f,.89f,.70f))
        private val pickup = Powertrain(3.91f,.55f,.78f,false,.43f,115f,floatArrayOf(4.15f,2.30f,1.48f,1f,.79f))
        private val bus = Powertrain(4.56f,.52f,1.02f,false,.39f,152f,floatArrayOf(4.65f,2.62f,1.69f,1.24f,.97f,.77f))
        private val suv = Powertrain(3.91f,.54f,.73f,false,.50f,92f,floatArrayOf(3.88f,2.29f,1.53f,1.13f,.86f,.68f))
        fun forVehicle(id: VehicleId) = when(id) {
            VehicleId.SAQR, VehicleId.NIMR, VehicleId.BADIYA -> pickup
            VehicleId.HAFILAT, VehicleId.QAFILA, VehicleId.JABAL -> bus
            VehicleId.MADINA, VehicleId.SAHIL -> sedan
            else -> suv
        }
    }
}
