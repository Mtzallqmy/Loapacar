package com.masalik.yemen.game

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class TireSolver {
    companion object { init { System.loadLibrary("mycar_contacts") } }
    private val input = FloatArray(18)
    private val output = FloatArray(7)
    private external fun solve(input: FloatArray, output: FloatArray)

    fun step(spec: VehicleSpec, road: RoadSample, lateral: Float, yaw: Float, grip: Float,
             mass: Float, speed: Float, steer: Float, acceleration: Float, drive: Float,
             braking: Float, fourWheel: Boolean, assists: ElectronicAssistsState): FloatArray {
        input[0]=mass; input[1]=spec.wheelbaseM; input[2]=spec.trackWidthM
        input[3]=spec.bodyHeightM * .35f
        input[4]=speed; input[5]=steer; input[6]=acceleration
        input[7]=drive; input[8]=braking
        for (wheel in 0..3) {
            val side = if (wheel % 2 == 0) -.5f else .5f
            val axle = if (wheel < 2) .5f else -.5f
            val offset = lateral + axle * spec.wheelbaseM * sin(yaw) + side * spec.trackWidthM * cos(yaw)
            val shoulder = road.surface == SurfaceType.ASPHALT && !road.isUrbanStreet && abs(offset) > road.widthM*.5f
            input[9+wheel]=if (shoulder) grip*.58f else grip
        }
        input[13]=if(fourWheel) 1f else 0f
        input[14]=if(assists.absEnabled) 1f else 0f
        input[15]=if(assists.tcsEnabled) 1f else 0f
        input[16]=if(assists.escEnabled) 1f else 0f
        input[17]=grip
        solve(input,output)
        return output
    }
}
