package com.masalik.yemen.game

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ExpansionIntegrityTest {
    @Test fun everyVehicleHasUniqueSpecAndDefaults() {
        val fleet = VehicleFleetCatalog.ALL_VEHICLES
        assertEquals(13, fleet.size)
        assertEquals(VehicleId.entries.toSet(), fleet.map { it.id }.toSet())
        assertEquals(fleet.size, fleet.map { it.id }.distinct().size)
        fleet.forEach {
            assertTrue(it.bodyLengthM > it.wheelbaseM)
            assertTrue(it.bodyHeightM > 0f && it.baseMassKg > 0f)
            assertTrue(it.defaultDrivetrain in it.supportedDrivetrains)
            assertTrue(VehicleFleetCatalog.defaultFuelMap().containsKey(it.id))
            assertTrue(VehicleFleetCatalog.defaultCustomizations().containsKey(it.id))
        }
    }

    @Test fun missionsHaveUniqueIdsAndValidRoutes() {
        val missions = MissionsCatalog.ALL_OFFLINE_MISSIONS
        assertEquals(78, missions.size)
        assertEquals(missions.size, missions.map { it.id }.distinct().size)
        missions.forEach {
            assertTrue(it.startDistanceM >= 0f)
            assertTrue(it.finishDistanceM > it.startDistanceM)
            assertTrue(it.finishDistanceM <= RoadSplineNetwork.TOTAL_ROAD_LENGTH_M)
            assertTrue(it.targetTimeSec > 0)
            assertEquals(it.regionId, RoadSplineNetwork.getRegionAt(it.startDistanceM).id)
        }
    }

    @Test fun destinationsRemainInsideTheirRegions() {
        val cities = RoadSplineNetwork.YEMEN_CITY_HUBS
        assertEquals(cities.size, cities.map { it.id }.distinct().size)
        assertEquals(12, cities.size)
        cities.forEach { assertEquals(it.regionId, RoadSplineNetwork.getRegionAt(it.distanceM).id) }
    }

    @Test fun roadSamplesAreFiniteAndBounded() {
        for (d in 0..22000 step 25) {
            val p = RoadSplineNetwork.sampleAt(d.toFloat())
            assertTrue(p.x.isFinite() && p.y.isFinite() && p.z.isFinite())
            assertTrue(p.headingRad.isFinite() && p.widthM > 0f)
        }
    }

    @Test fun olderHudSaveGetsNewDefaults() {
        val hud = Json.decodeFromString<HudCustomizationSettings>("{}")
        assertEquals(60, hud.targetFps)
        assertEquals(1f, hud.tiltSensitivity, 0.001f)
        assertFalse(hud.invertTilt)
    }

    @Test fun backupRoundTripPreservesSettingsAndProgress() {
        val original = SaveGameSchema(masalikCoins = 1234,
            missionBestTimesSec = mapOf("story_1" to 40f),
            hudSettings = HudCustomizationSettings(targetFps = 30, tiltSensitivity = 1.5f, invertTilt = true))
        val restored = Json.decodeFromString<SaveGameSchema>(Json.encodeToString(original))
        assertEquals(original, restored)
    }

    @Test fun localBoardRanksOnlyValidRecentTrips() {
        fun trip(km: Float) = DrivingTripLog(vehicleId = VehicleId.SAHIL,
            regionId = RegionId.COASTAL_ROAD, regionNameAr = "coast", distanceKm = km,
            maxSpeedKmh = 50f, durationSec = 120, timestampIso = "2026-11-21")
        val rows = LocalLeaderboard.topTrips(listOf(trip(1f), trip(8f), trip(Float.NaN), trip(-1f), trip(4f)))
        assertEquals(listOf(8f, 4f, 1f), rows.map { it.distanceKm })
        assertTrue(LocalLeaderboard.topTrips(emptyList()).isEmpty())
        assertEquals(5, LocalLeaderboard.topTrips((1..10).map { trip(it.toFloat()) }).size)
    }
}
