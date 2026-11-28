package com.masalik.yemen.game

/** Art-direction profiles, inspired by documented regional forms, not surveyed city geometry. */
data class CityArchitectureProfile(
    val minFloors: Int,
    val floorVariants: Int,
    val buildingWidthM: Float,
    val gypsumTrim: Boolean = false,
    val woodenScreens: Boolean = false,
    val courtyardWalls: Boolean = false
)

object CityArchitectureCatalog {
    fun profile(region: RegionId): CityArchitectureProfile = when (region) {
        RegionId.SANAA_HIGHLANDS -> CityArchitectureProfile(4, 3, 8.5f, gypsumTrim = true)
        RegionId.SHIBAM_HADRAMOUT -> CityArchitectureProfile(5, 3, 7.0f)
        RegionId.TIHAMA_PLAINS -> CityArchitectureProfile(1, 2, 11.0f, courtyardWalls = true)
        RegionId.COASTAL_ROAD -> CityArchitectureProfile(2, 3, 10.0f, woodenScreens = true)
        RegionId.TAIZ_HIGHLANDS -> CityArchitectureProfile(3, 3, 9.0f)
        RegionId.WADI_RAYHAN -> CityArchitectureProfile(2, 2, 7.5f, courtyardWalls = true)
        RegionId.MASALIK_DESERT -> CityArchitectureProfile(1, 2, 12f, courtyardWalls = true)
        RegionId.IBB_GREEN_PEAKS -> CityArchitectureProfile(2, 3, 8.0f)
        else -> CityArchitectureProfile(1, 2, 9.0f)
    }
}
