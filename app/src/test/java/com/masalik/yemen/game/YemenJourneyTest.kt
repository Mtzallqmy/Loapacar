package com.masalik.yemen.game

import com.masalik.yemen.renderer.JourneyScenery
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class YemenJourneyTest {
    @Test fun stopsAreReachableAndResumeWithTheSamePayload() {
        for (journey in YemenJourneys.all) {
            val run = MissionRun(journey.mission)
            val car = VehiclePhysicsEngine()
            for (goal in journey.stops) {
                assertEquals(journey.mission.id, goal, run.target)
                val road = RoadSplineNetwork.sampleAt(goal.distance)
                assertFalse(CityStreetNetwork.constrain(goal.distance, goal.lane, road.widthM*.5f).hitBoundary)
                val heading = if (goal.reverseRoute) Math.PI.toFloat() else 0f
                car.resetToRoad(goal.distance, goal.lane, heading)
                val telemetry = car.telemetry().copy(handbrake=true, forwardSpeedMs=if(goal.isStop) 0f else 2f)
                run.advance(.016f, telemetry, heading, 0f)
                if (goal.isStop) {
                    if (goal.kind != ObjectiveKind.PARK) {
                        assertTrue(run.waitingForAction)
                        run.useEquipment()
                    }
                    repeat(125) { run.advance(.017f, telemetry, heading, 0f) }
                }
                if (!run.finished) {
                    val resumed=MissionRun(journey.mission,run.snapshot(goal.lane, null, heading))
                    assertEquals(run.target,resumed.target)
                    assertEquals(run.payloadKg,resumed.payloadKg,.001f)
                    assertEquals(heading, resumed.snapshot(goal.lane, null, heading).headingOffsetRad, 0f)
                }
            }
            assertTrue(run.finished)
        }
    }

    @Test fun sabirRoutesRequireDifferentStreetsAndClimbToTheSameLookout() {
        val market = YemenJourneys.taizRoutes.first()
        val main = YemenJourneys.taizRoutes.last()
        assertEquals(market.stops.first(), main.stops.first())
        assertEquals(market.stops.last(), main.stops.last())
        assertTrue(abs(market.stops[1].lane-main.stops[1].lane) > 25f)
        val base = RoadSplineNetwork.sampleAt(YemenJourneys.TAIZ_MARKET_M)
        val peak = RoadSplineNetwork.sampleAt(YemenJourneys.SABIR_LOOKOUT_M)
        assertTrue(peak.y - base.y > 100f)
    }

    @Test fun quayMeshMatchesTheHeightSampledByTheWheels() {
        val width=RoadSplineNetwork.sampleAt(QuayHumps.centers.first()).widthM
        val mesh=JourneyScenery.quayHump(width)
        for(i in mesh.indices step 8) {
            val h=QuayHumps.height(QuayHumps.centers.first()+mesh[i+2],mesh[i],width/2)
            assertEquals(h,mesh[i+1],.001f)
        }
        assertEquals(QuayHumps.rise,QuayHumps.height(QuayHumps.centers.last(),0f,width/2),.001f)
        assertEquals(0f,QuayHumps.height(QuayHumps.centers.first(),width, width/2),0f)
    }

    @Test fun sceneryContainsFiniteTrianglesWithUnitNormals() {
        for (model in listOf(JourneyScenery.gate(12f), JourneyScenery.coffeeTerrace(), JourneyScenery.dhow(),
            JourneyScenery.taizMarket(), JourneyScenery.sabirLookout(), JourneyScenery.taizHillFort())) {
            for (mesh in model.values) {
                assertEquals(0,mesh.size%24)
                assertTrue(mesh.all { it.isFinite() })
                for (i in mesh.indices step 8) {
                    val norm=(3..5).sumOf { (mesh[i+it]*mesh[i+it]).toDouble() }
                    assertTrue(abs(norm-1)<.001)
                }
            }
        }
    }
}
