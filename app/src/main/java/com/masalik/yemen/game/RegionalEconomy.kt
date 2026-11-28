package com.masalik.yemen.game

import kotlin.math.roundToInt

/** Local contract quotes in fictional game currency, never dependent on a server. */
internal object RegionalEconomy {
    fun quote(mission: MissionDefinition, save: SaveGameSchema): Int {
        if (!mission.id.startsWith("journey_")) return mission.rewardCoins
        val freight = mission.category == GameModeCategory.CARGO
        val passenger = mission.category == GameModeCategory.PASSENGER
        if (!freight && !passenger) return mission.rewardCoins
        val peak = save.worldTimeHours in 6f..10f || save.worldTimeHours in 16f..20f
        val demand = if (peak) 1.16f else .98f
        val exposure = when (mission.weatherPreset) {
            WeatherPreset.HEAVY_RAIN, WeatherPreset.SANDSTORM, WeatherPreset.MOUNTAIN_FOG -> 1.24f
            WeatherPreset.LIGHT_RAIN -> 1.12f
            else -> 1f
        }
        val regional = when (mission.regionId) {
            RegionId.MASALIK_DESERT, RegionId.SHIBAM_HADRAMOUT -> 1.10f
            RegionId.IBB_GREEN_PEAKS, RegionId.TAIZ_HIGHLANDS -> 1.05f
            else -> 1f
        }
        val repeatSupply = (1f - .025f * (save.missionCompletions[mission.id] ?: 0)).coerceAtLeast(.85f)
        return (mission.rewardCoins * demand * exposure * regional * repeatSupply / 10f).roundToInt() * 10
    }

    fun earned(mission: MissionDefinition, quoteCoins: Int, condition: Float): Int {
        val factor = if (mission.category == GameModeCategory.CARGO || mission.category == GameModeCategory.PASSENGER)
            .75f + .25f * (condition / 100f).coerceIn(0f, 1f) else 1f
        return (quoteCoins * factor).roundToInt()
    }
}
