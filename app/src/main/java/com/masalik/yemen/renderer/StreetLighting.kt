package com.masalik.yemen.renderer

import com.masalik.yemen.game.RoadSplineNetwork

/** The sources match the lamps placed by the streetscape renderer at 44 m intervals. */
internal class StreetLighting {
    val positions = FloatArray(16)
    private val distances = FloatArray(4)
    var count = 0
        private set

    fun sample(centerM: Float, cameraX: Float, cameraZ: Float, nightFactor: Float) {
        count = 0
        if (nightFactor < .1f) return
        distances.fill(Float.POSITIVE_INFINITY)
        val first = ((centerM - 130f) / 44f).toInt().coerceAtLeast(1)
        val last = ((centerM + 130f) / 44f).toInt()
        for (i in first..last) {
            val d = i * 44f
            if (d >= RoadSplineNetwork.TOTAL_ROAD_LENGTH_M) break
            val sample = RoadSplineNetwork.sampleAt(d)
            if (!sample.isUrbanStreet) continue
            for (side in intArrayOf(-1, 1)) {
                if (side == -1 && !sample.isModernBoulevard) continue
                val lateral = side * (sample.widthM * .5f + .85f - 1.65f)
                val x = sample.x + sample.rightX * lateral
                val z = sample.z + sample.rightZ * lateral
                val gap = (x-cameraX)*(x-cameraX) + (z-cameraZ)*(z-cameraZ)
                if (gap >= distances.last()) continue
                var index = distances.lastIndex
                while (index > 0 && gap < distances[index-1]) {
                    distances[index] = distances[index-1]
                    positions.copyInto(positions, index*4, (index-1)*4, index*4)
                    index--
                }
                distances[index] = gap
                positions[index*4] = x
                positions[index*4+1] = sample.y + 6.95f
                positions[index*4+2] = z
                positions[index*4+3] = if (sample.isModernBoulevard) 1f else 0f
                count = (count+1).coerceAtMost(4)
            }
        }
    }
}
