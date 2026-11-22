package com.masalik.yemen.game

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class DrivingAcceptanceTest {
    private fun mission(id: String) = MissionsCatalog.ALL_OFFLINE_MISSIONS.first { it.id == id }

    @Test fun everyMissionCanBeCompletedInOrderUsingItsOwnRules() {
        for (m in MissionsCatalog.ALL_OFFLINE_MISSIONS) {
            val run = MissionRun(m)
            for (goal in run.objectives) {
                val t = VehicleTelemetry(distanceAlongRoadM=goal.distance,lateralOffsetM=goal.lane,
                    speedKmh=if(goal.isStop) 0f else 10f, forwardSpeedMs=if(goal.isStop) 0f else 3f,
                    handbrake=goal.isStop)
                repeat(if(goal.isStop) 125 else 1) {
                    run.advance(1f/60f,t,if(goal.reverseRoute) Math.PI.toFloat() else 0f,0f)
                    run.useEquipment()
                }
            }
            assertTrue(m.id,run.finished)
            assertNull(run.failure)
        }
    }
    @Test fun cargoRequiresCorrectStreetParkingAndDriverAction() {
        val r = MissionRun(mission("story_2"))
        val t = VehicleTelemetry(distanceAlongRoadM=230f,lateralOffsetM=34f,handbrake=true)
        fun waitAt(tick: VehicleTelemetry) = repeat(140) { r.advance(1f/60f,tick,0f,0f) }
        waitAt(t.copy(lateralOffsetM=2.85f)); r.useEquipment()
        waitAt(t.copy(speedKmh=2f)); waitAt(t.copy(handbrake=false))
        assertFalse(r.waitingForAction)
        waitAt(t); assertEquals(0,r.stage); assertTrue(r.waitingForAction)
        r.useEquipment(); waitAt(t)
        val restored = MissionRun(r.mission,r.snapshot(34f,null))
        assertEquals(1,restored.stage); assertEquals(280f,restored.payloadKg,0f)
        restored.advance(.016f,t,0f,30f)
        assertNotNull(restored.failure)
    }
    @Test fun gateCannotBeSkippedAndAReverseCrossingDoesNotCount() {
        val r = MissionRun(mission("story_1"))
        r.advance(.016f,VehicleTelemetry(distanceAlongRoadM=r.mission.finishDistanceM,forwardSpeedMs=10f),0f,0f)
        r.advance(.016f,VehicleTelemetry(distanceAlongRoadM=r.target!!.distance,forwardSpeedMs=10f),Math.PI.toFloat(),0f)
        assertEquals(0,r.stage)
    }
    @Test fun navigationEntersSideStreetsAndReroutesFromBothEnds() {
        val goal = MapPoint(230f,34f)
        for (start in listOf(MapPoint(60f,2f),MapPoint(420f,-3f),MapPoint(300f,-34f))) {
            val path=StreetNavigation.route(start,goal)
            assertEquals(goal,path.last()); assertEquals(start,path.first())
            assertTrue(path.any { it.l==34f })
        }
    }
    @Test fun diagonalDrivewayAndNativeContactsRespondIndependently() {
        val spec=VehicleFleetCatalog.specOf(VehicleId.SAQR)
        val suspension=WheelSuspension()
        val road=RoadSplineNetwork.sampleAt(TaizDriveway.distance+spec.wheelbaseM*.5f)
        repeat(150){ suspension.step(1f/60f,spec,road,TaizDriveway.lane-spec.trackWidthM*.5f,0f,spec.baseMassKg,1f) }
        assertTrue(suspension.contacts.rearRight > suspension.contacts.rearLeft+.16f)
        val tires=TireSolver()
        val s=road.copy(isUrbanStreet=false, surface=SurfaceType.ASPHALT)
        val forces=tires.step(spec,s,s.widthM*.5f,0f,1f,spec.baseMassKg,18f,0f,0f,0f,40000f,true,ElectronicAssistsState()).copyOf()
        assertTrue(forces[1]>0f && abs(forces[2])>80f && forces[3]==1f)
    }
    @Test fun manualClutchInterruptsDriveAndAbruptReleaseStalls() {
        val car=VehiclePhysicsEngine(); car.resetToRoad(250f); car.transmissionMode="manual"; car.assistedClutch=false
        repeat(60){ car.step(1f/60f,ControlInputs(throttle=.8f,clutch=1f,brake=1f),null) }
        assertTrue(abs(car.forwardSpeedMs)<.2f)
        car.step(1f/60f,ControlInputs(clutch=0f),null)
        assertFalse(car.engineRunning)
    }
    @Test fun replaySnapshotsDoNotMutateAfterLiveWorldAdvances() {
        val live=LivingWorldSimulation()
        live.update(.02f,180f,2f,4f,0f)
        val snap=live.captureNearby(180f)
        val car=snap.cars.first()
        val before=car.distanceM
        repeat(180){ live.update(1f/60,180f,2f,4f,0f) }
        val replay=LivingWorldSimulation()
        replay.showRecorded(snap)
        assertEquals(before,replay.trafficVehicles.first().distanceM,0f)
    }
}
