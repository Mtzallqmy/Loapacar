package com.masalik.yemen.data

import com.masalik.yemen.game.DrivingTripLog
import com.masalik.yemen.game.MissionsCatalog
import com.masalik.yemen.game.RoadSplineNetwork
import com.masalik.yemen.game.SaveGameSchema
import kotlinx.serialization.json.*

/** The portable document is independent of the on-device Room schema. */
internal data class SaveBackup(val profile: SaveGameSchema, val trips: List<DrivingTripLog>?, val journeyUnavailable: Boolean)

internal object SaveBackupCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    const val MAX_CHARS = 8_000_000

    fun encode(profile: SaveGameSchema, trips: List<DrivingTripLog>): String =
        JsonObject(json.encodeToJsonElement(SaveGameSchema.serializer(), profile).jsonObject +
            mapOf("schemaVersion" to JsonPrimitive(4),
                "tripHistory" to json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(DrivingTripLog.serializer()), trips)))
            .toString().also { require(it.length <= MAX_CHARS) { "Backup too large" } }

    fun decode(raw: String): SaveBackup {
        require(raw.length <= MAX_CHARS) { "Backup too large" }
        val document = json.parseToJsonElement(raw).jsonObject
        require("schemaVersion" in document || "selectedVehicleId" in document)
        val decoded = json.decodeFromJsonElement(SaveGameSchema.serializer(), document)
        require(decoded.schemaVersion in 1..4 && decoded.selectedVehicleId in decoded.unlockedVehicles)
        require(decoded.masalikCoins >= 0 && decoded.driverXp >= 0 && decoded.missionCompletions.values.all { it >= 0 })
        require(decoded.playerDistanceM.isFinite() && decoded.worldTimeHours.isFinite() &&
            decoded.totalCareerDistanceM.isFinite() && decoded.totalCareerDistanceM >= 0)
        require(decoded.missionBestTimesSec.values.all { it.isFinite() && it >= 0f })
        require(decoded.fuelByVehicle.values.all { it.isFinite() && it >= 0 })
        val progress = decoded.missionRun
        progress?.let {
            require(it.stage >= 0 && it.elapsed.isFinite() && it.elapsed >= 0f && it.lateralM.isFinite() &&
                it.headingOffsetRad.isFinite() && it.cargoCondition in 0f..100f)
        }
        val missing = progress != null && MissionsCatalog.ALL_OFFLINE_MISSIONS.none { it.id == progress.id }
        val trips = document["tripHistory"]?.let {
            json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(DrivingTripLog.serializer()), it)
        }
        trips?.forEach {
            require(it.timestampIso.isNotBlank() && it.distanceKm.isFinite() && it.distanceKm >= 0 &&
                it.maxSpeedKmh.isFinite() && it.maxSpeedKmh >= 0 && it.durationSec >= 0)
        }
        return SaveBackup(decoded.copy(
            schemaVersion = 3, missionRun = if (missing) null else progress,
            playerDistanceM = decoded.playerDistanceM.coerceIn(0f, RoadSplineNetwork.TOTAL_ROAD_LENGTH_M),
            worldTimeHours = decoded.worldTimeHours.coerceIn(0f, 24f),
            hudSettings = decoded.hudSettings.copy(
                targetFps = decoded.hudSettings.targetFps.coerceIn(30, 60),
                tiltSensitivity = decoded.hudSettings.tiltSensitivity.coerceIn(.5f, 2f)
            )
        ), trips, missing)
    }
}
