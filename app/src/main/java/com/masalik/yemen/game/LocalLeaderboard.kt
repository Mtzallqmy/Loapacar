package com.masalik.yemen.game

/** Ranking is confined to this device's stored recent trips. No remote claims. */
object LocalLeaderboard {
    fun topTrips(trips: List<DrivingTripLog>): List<DrivingTripLog> = trips
        .filter { it.distanceKm.isFinite() && it.distanceKm >= 0f }
        .sortedWith(compareByDescending<DrivingTripLog> { it.distanceKm }.thenBy { it.timestampIso })
        .take(5)
}
