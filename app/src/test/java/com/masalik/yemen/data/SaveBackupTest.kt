package com.masalik.yemen.data

import com.masalik.yemen.game.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SaveBackupTest {
    @Test fun legacyBackupPreservesJourneyAndProfile() {
        val old = SaveGameSchema(playerNameAr = "سائق تعز", masalikCoins = 45200,
            missionRun = MissionProgress("journey_taiz_market", elapsed = 27f))
        val restored = SaveBackupCodec.decode(Json.encodeToString(SaveGameSchema.serializer(), old))
        assertEquals(old, restored.profile)
        assertNull(restored.trips)
    }

    @Test fun newBackupIncludesEveryTripAndKeepsTheProfile() {
        val log = DrivingTripLog(42, "2026-11-28T12:00:00Z", VehicleId.SAQR,
            RegionId.TAIZ_HIGHLANDS, "تعز", 4.2f, 51f, 420)
        val original = SaveGameSchema(driverXp = 1550)
        val restored = SaveBackupCodec.decode(SaveBackupCodec.encode(original, listOf(log)))
        assertEquals(original, restored.profile)
        assertEquals(listOf(log), restored.trips)
        assertFalse(restored.journeyUnavailable)
    }

    @Test fun anUnknownLegacyJourneyDoesNotDiscardTheProfile() {
        val save = SaveGameSchema(playerNameAr = "معتز", missionRun = MissionProgress("retired_journey"))
        val restored = SaveBackupCodec.decode(SaveBackupCodec.encode(save, emptyList()))
        assertTrue(restored.journeyUnavailable)
        assertNull(restored.profile.missionRun)
        assertEquals(save.playerNameAr, restored.profile.playerNameAr)
    }

    @Test fun invalidDocumentRejectedBeforeWriting() {
        assertThrows(Exception::class.java) { SaveBackupCodec.decode("broken") }
        assertThrows(Exception::class.java) { SaveBackupCodec.decode("{}") }
        assertThrows(Exception::class.java) { SaveBackupCodec.decode("{\"schemaVersion\":4,\"masalikCoins\":-1}") }
    }
}
