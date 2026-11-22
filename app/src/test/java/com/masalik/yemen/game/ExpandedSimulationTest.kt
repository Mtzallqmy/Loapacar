package com.masalik.yemen.game

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ExpandedSimulationTest {
    @Test fun coastalAndZabidProfilesAreLowerThanTowerCities() {
        val z = CityArchitectureCatalog.profile(RegionId.TIHAMA_PLAINS)
        val a = CityArchitectureCatalog.profile(RegionId.COASTAL_ROAD)
        val s = CityArchitectureCatalog.profile(RegionId.SHIBAM_HADRAMOUT)
        assertTrue(z.minFloors + z.floorVariants <= s.minFloors)
        assertTrue(a.woodenScreens)
        assertTrue(CityArchitectureCatalog.profile(RegionId.SANAA_HIGHLANDS).gypsumTrim)
    }
    @Test fun streetGridHasContinuousJunctionsAndClosedBoundaries() {
        assertTrue(CityStreetNetwork.districts.isNotEmpty())
        for (district in CityStreetNetwork.districts) {
            for (junction in district.junctionsM) {
                for (lateral in -38..38) {
                    val p = CityStreetNetwork.constrain(junction, lateral.toFloat(), 6f)
                    assertFalse("junction $junction at $lateral", p.hitBoundary)
                }
            }
            val side = CityStreetNetwork.constrain(district.centerM + 60f, 34f, 6f)
            assertFalse(side.hitBoundary)
            val block = CityStreetNetwork.constrain(district.centerM + 60f, 20f, 6f)
            assertTrue(block.hitBoundary)
            assertTrue(abs(block.lateralM) <= 6f || abs(block.lateralM) >= 29.19f)
        }
    }
    @Test fun finiteStreetConstraintsStayOnSomeRoad() {
        for (district in CityStreetNetwork.districts) for (d in -175..175 step 5) for (l in -50..50 step 5) {
            val p = CityStreetNetwork.constrain(district.centerM + d, l.toFloat(), 6f)
            assertTrue(p.distanceM.isFinite() && p.lateralM.isFinite())
            assertFalse("center=${district.centerM} d=$d l=$l corrected=$p", CityStreetNetwork.constrain(p.distanceM, p.lateralM, 6f).hitBoundary)
        }
    }
    @Test fun lowGripRetainsMoreLateralInertia() {
        val loose = SurfaceDynamics.lateralResponse(6f, 0f, 0.4f, 0.02f)
        val dry = SurfaceDynamics.lateralResponse(6f, 0f, 1.2f, 0.02f)
        assertTrue(loose > dry)
        assertTrue(loose < 6f)
    }
    @Test fun yawIsTractionLimitedAtHighSpeed() {
        val dry = abs(SurfaceDynamics.yawRate(30f, 0.5f, 2.7f, 1.2f, false))
        val wet = abs(SurfaceDynamics.yawRate(30f, 0.5f, 2.7f, 0.6f, false))
        assertTrue(wet < dry)
        assertTrue(wet * 30f <= 0.6f * 9.81f + 0.001f)
        assertEquals(0f, SurfaceDynamics.yawRate(0f, 0.5f, 2.7f, 1f, false), 0f)
    }
    @Test fun engineVoicesDifferByVehicleClassAndStayNormalized() {
        assertNotEquals(EngineVoiceCatalog.voice(VehicleId.JABAL), EngineVoiceCatalog.voice(VehicleId.BARQ))
        for (id in VehicleId.entries) {
            val v = EngineVoiceCatalog.voice(id)
            assertEquals(1f, v.fundamental + v.second + v.third + v.fourth + v.roughness, 0.001f)
        }
    }
    @Test fun trafficAndPedestriansStayFiniteDuringWeatherCycle() {
        val world = LivingWorldSimulation()
        repeat(600) {
            world.update(1f/60f, 180f, 2.8f, 8f, 0.7f, WeatherPreset.HEAVY_RAIN)
            for (car in world.trafficVehicles) {
                assertTrue(car.speedMs.isFinite() && car.speedMs >= 0f)
                assertTrue(car.worldX.isFinite() && car.worldY.isFinite() && car.worldZ.isFinite())
            }
        }
        assertTrue(world.trafficVehicles.any { it.headlightsOn })
    }
    @Test fun vehicleCanRunOnSideStreetWithoutBeingClampedToMainRoad() {
        val district = CityStreetNetwork.districts.first()
        val engine = VehiclePhysicsEngine()
        engine.steeringAssistEnabled = false
        engine.resetToRoad(district.centerM - 60f, 34f)
        repeat(240) { engine.step(1f/60f, ControlInputs(throttle = 0.35f), null) }
        assertTrue(engine.distanceAlongRoadM > district.centerM - 59f)
        assertTrue(engine.lateralOffsetM > 29f)
        assertTrue(engine.posX.isFinite() && engine.posY.isFinite() && engine.posZ.isFinite())
    }
    @Test fun fullFleetRunsFiniteAcrossWeatherAndSurfaceChoices() {
        for (id in VehicleId.entries) for (weather in WeatherPreset.entries) {
            val engine = VehiclePhysicsEngine()
            engine.selectVehicle(id, VehicleFleetCatalog.defaultCustomizations().getValue(id))
            engine.weather = weather
            engine.resetToRoad(if (weather == WeatherPreset.SANDSTORM) 17000f else 6400f)
            repeat(180) { engine.step(1f/60f, ControlInputs(throttle = 0.5f, steer = 0.1f), null) }
            assertTrue(engine.forwardSpeedMs.isFinite() && engine.lateralSpeedMs.isFinite())
            assertTrue(engine.posX.isFinite() && engine.posY.isFinite() && engine.posZ.isFinite())
        }
    }
}
