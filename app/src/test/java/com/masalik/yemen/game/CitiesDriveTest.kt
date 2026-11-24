package com.masalik.yemen.game

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CitiesDriveTest {
    @Test fun followsTerracesAndStopsAtSelectedDestination() {
        val car=VehiclePhysicsEngine()
        car.resetToRoad(1300f,SabirRoad.offset(1300f)+1.65f)
        val world=LivingWorldSimulation().apply { trafficVehicles.clear(); pedestrians.clear() }
        val target=DriveDestinations.all.first { it.label=="استراحة المدرجات" }.point
        val route=DriverRoute(MapPoint(car.distanceAlongRoadM,car.lateralOffsetM),target)
        val driver=AutoDriver()
        repeat(4800) {
            val cmd=driver.step(car,world,AutoDriveStyle.MOUNTAIN,route)
            car.step(1f/60f,cmd.pedals,null)
        }
        assertTrue("final pose d=${car.distanceAlongRoadM} l=${car.lateralOffsetM}",
            target.distance(MapPoint(car.distanceAlongRoadM,car.lateralOffsetM))<2.5f)
        assertTrue(abs(car.forwardSpeedMs)<.25f)
    }

    @Test fun fullAdenParkingJourneyUsesRealPhysicsFromDepartureToArrival() {
        val trip=YemenJourneys.byId.getValue("journey_aden_quay")
        val run=MissionRun(trip.mission)
        val car=VehiclePhysicsEngine()
        car.selectVehicle(VehicleId.SAHIL, VehicleFleetCatalog.defaultCustomizations().getValue(VehicleId.SAHIL))
        car.resetToRoad(trip.mission.startDistanceM)
        val world=LivingWorldSimulation()
        val driver=AutoDriver()
        var route: DriverRoute?=null
        var stage=-1
        val dt=1f/60f
        for (tick in 1..18000) {
            val goal=run.target ?: break
            if (stage != run.stage) {
                stage=run.stage
                route=DriverRoute(MapPoint(car.distanceAlongRoadM,car.lateralOffsetM),MapPoint(goal.distance,goal.lane))
            }
            world.update(dt,car.distanceAlongRoadM,car.lateralOffsetM,car.forwardSpeedMs,.1f,car.weather)
            val command=driver.step(car,world,AutoDriveStyle.SCENIC,route,goal.isStop)
            val secure=goal.isStop && abs(car.forwardSpeedMs)<.12f &&
                route!!.goal.distance(MapPoint(car.distanceAlongRoadM,car.lateralOffsetM)) < 1.2f
            val t=car.step(dt,command.pedals.copy(handbrake=secure),world)
            run.advance(dt,t,car.headingOffsetFromRoadRad,car.lastCollisionImpulse)
        }
        assertTrue("stage=${run.stage} distance=${car.distanceAlongRoadM} lat=${car.lateralOffsetM} speed=${car.forwardSpeedMs}",run.finished)
        assertNull(run.failure)
        println("Aden quayside parking completed in ${run.elapsed} simulated seconds with traffic")
    }
}
