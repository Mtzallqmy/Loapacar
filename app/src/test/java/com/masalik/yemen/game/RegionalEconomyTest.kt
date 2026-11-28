package com.masalik.yemen.game

import org.junit.Assert.*
import org.junit.Test

class RegionalEconomyTest {
    @Test fun quoteRespondsToDemandAndSurvivesCheckpoint() {
        val mission = YemenJourneys.byId.getValue("journey_marib_market").mission
        val peak = RegionalEconomy.quote(mission, SaveGameSchema(worldTimeHours = 8f))
        val off = RegionalEconomy.quote(mission, SaveGameSchema(worldTimeHours = 13f))
        assertTrue(peak > off)
        val run = MissionRun(mission, quoteCoins = peak)
        val (_, checkpoint) = run.segmentStart()
        assertEquals(peak, MissionRun(mission, checkpoint, quoteCoins = off).quotedRewardCoins)
        assertEquals(peak, RegionalEconomy.earned(mission, peak, 100f))
        assertTrue(RegionalEconomy.earned(mission, peak, 80f) < peak)
    }
}
