package com.masalik.yemen.game

import kotlin.math.abs

/** Small driveable procedural town grids, in longitudinal/lateral coordinates along the main route.
 * These are fictional street layouts, not geographic reproductions of the named city. */
object CityStreetNetwork {
    const val SIDE_STREET_OFFSET_M = 34f
    const val SIDE_HALF_WIDTH_M = 4.8f
    data class District(val centerM: Float, val junctionsM: List<Float>)
    data class ConstrainedPosition(val distanceM: Float, val lateralM: Float, val hitBoundary: Boolean)

    val districts: List<District> = RoadSplineNetwork.YEMEN_CITY_HUBS.mapNotNull { hub ->
        if (!RoadSplineNetwork.sampleAt(hub.distanceM).isUrbanStreet) null
        else {
            val center = hub.distanceM.coerceIn(230f, RoadSplineNetwork.TOTAL_ROAD_LENGTH_M - 230f)
            val junctions = when (hub.id) {
                "city_taiz_sabir" -> listOf(-140f,-64f,0f,140f)
                "city_sanaa_old" -> listOf(-140f,-74f,0f,65f,140f)
                "city_aden_mokha_coast" -> listOf(-160f,0f,155f)
                "city_shibam_hadramout" -> listOf(-130f,-52f,32f,122f)
                "city_mahwit_terraces" -> listOf(-135f,-52f,30f,145f)
                "city_marib_desert" -> listOf(-150f,-76f,0f,82f,150f)
                "city_ibb_green" -> listOf(-145f,-58f,0f,138f)
                else -> listOf(-140f,0f,140f)
            }
            District(center, junctions.map { center+it })
        }
    }

    fun districtAt(distanceM: Float): District? = districts.minByOrNull { abs(it.centerM - distanceM) }
        ?.takeIf { abs(it.centerM - distanceM) <= 180f }

    fun isJunction(distanceM: Float, clearanceM: Float = 7f): Boolean =
        districts.any { district -> district.junctionsM.any { abs(it - distanceM) < clearanceM } }

    fun constrain(distanceM: Float, lateralM: Float, mainHalfWidthM: Float): ConstrainedPosition {
        if (abs(lateralM) <= mainHalfWidthM) return ConstrainedPosition(distanceM, lateralM, false)
        if (distanceM in SabirRoad.start..SabirRoad.end) {
            val center = SabirRoad.offset(distanceM)
            val limited = lateralM.coerceIn(center-SabirRoad.halfWidth, center+SabirRoad.halfWidth)
            val main = lateralM.coerceIn(-mainHalfWidthM, mainHalfWidthM)
            if (abs(limited-lateralM) <= abs(main-lateralM))
                return ConstrainedPosition(distanceM, limited, abs(limited-lateralM) > .002f)
        }
        val district = districtAt(distanceM)
        if (district == null) return ConstrainedPosition(distanceM, lateralM.coerceIn(-mainHalfWidthM, mainHalfWidthM), true)
        val maxLat = SIDE_STREET_OFFSET_M + SIDE_HALF_WIDTH_M
        val nearestJunction = district.junctionsM.minByOrNull { abs(it - distanceM) }!!
        val onCross = abs(nearestJunction - distanceM) <= SIDE_HALF_WIDTH_M + 0.002f
        val onSide = abs(abs(lateralM) - SIDE_STREET_OFFSET_M) <= SIDE_HALF_WIDTH_M + 0.002f &&
            distanceM >= district.junctionsM.first() - SIDE_HALF_WIDTH_M && distanceM <= district.junctionsM.last() + SIDE_HALF_WIDTH_M
        if ((onCross || onSide) && abs(lateralM) <= maxLat + 0.002f) return ConstrainedPosition(distanceM, lateralM, false)
        // Project to the closest legal road, retaining smooth continuous coordinates at junctions.
        val candidates = listOf(
            distanceM to lateralM.coerceIn(-mainHalfWidthM, mainHalfWidthM),
            distanceM.coerceIn(district.junctionsM.first() - SIDE_HALF_WIDTH_M, district.junctionsM.last() + SIDE_HALF_WIDTH_M) to
                (if (lateralM < 0f) lateralM.coerceIn(-maxLat, -SIDE_STREET_OFFSET_M + SIDE_HALF_WIDTH_M)
                 else lateralM.coerceIn(SIDE_STREET_OFFSET_M - SIDE_HALF_WIDTH_M, maxLat)),
            distanceM.coerceIn(nearestJunction - SIDE_HALF_WIDTH_M, nearestJunction + SIDE_HALF_WIDTH_M) to lateralM.coerceIn(-maxLat, maxLat)
        )
        val best = candidates.minByOrNull { (d,l) -> (d-distanceM)*(d-distanceM)+(l-lateralM)*(l-lateralM) }!!
        return ConstrainedPosition(best.first, best.second, true)
    }
}
