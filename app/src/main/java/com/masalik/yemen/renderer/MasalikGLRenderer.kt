package com.masalik.yemen.renderer

import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.masalik.yemen.game.YemenJourneys
import com.masalik.yemen.game.QuayHumps
import com.masalik.yemen.game.AtmosphereSnapshot
import com.masalik.yemen.game.BullbarStyle
import com.masalik.yemen.game.CameraMode
import com.masalik.yemen.game.DriverSeatSide
import com.masalik.yemen.game.HeadlightBeamMode
import com.masalik.yemen.game.LivingWorldSimulation
import com.masalik.yemen.game.PaintFinishType
import com.masalik.yemen.game.PedestrianAgent
import com.masalik.yemen.game.PedestrianStyle
import com.masalik.yemen.game.PickupAccessoryType
import com.masalik.yemen.game.RegionId
import com.masalik.yemen.game.ReplayCameraAngle
import com.masalik.yemen.game.RoadSample
import com.masalik.yemen.game.RoadSplineNetwork
import com.masalik.yemen.game.SteeringWheelStyle
import com.masalik.yemen.game.SurfaceType
import com.masalik.yemen.game.TrafficSignalPhase
import com.masalik.yemen.game.TrafficVehicleType
import com.masalik.yemen.game.VehicleCustomization
import com.masalik.yemen.game.VehicleFleetCatalog
import com.masalik.yemen.game.VehicleId
import com.masalik.yemen.game.VehicleTelemetry
import com.masalik.yemen.game.WeatherPreset
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class MasalikGLRenderer(
    private val assets: android.content.res.AssetManager,
    private val onFirstFrame: () -> Unit = {},
    private val onStepFrame: (dtSec: Float) -> RenderFrameSnapshot
) : GLSurfaceView.Renderer {

    private val canopies = mutableMapOf<Coachwork.Profile, Map<Coachwork.Material, GpuMesh>>()
    private val bodies = mutableMapOf<Pair<VehicleId, Boolean>, GpuMesh>()
    private var firstFrameReported = false
    private var frameQuality = com.masalik.yemen.game.GraphicsQuality.HIGH

    data class RenderFrameSnapshot(
        val graphicsQuality: com.masalik.yemen.game.GraphicsQuality = com.masalik.yemen.game.GraphicsQuality.HIGH,
        val targetFps: Int = 60,
        val telemetry: VehicleTelemetry,
        val customization: VehicleCustomization,
        val previewVehicleId: VehicleId,
        val previewCustomization: VehicleCustomization,
        val isLobbyOrGarage: Boolean,
        val showroomOrbitRad: Float,
        val cameraMode: CameraMode,
        val photoFovDeg: Float,
        val worldTimeHours: Float,
        val weather: WeatherPreset,
        val livingWorld: LivingWorldSimulation,
        val finishDistanceM: Float?,
        val objectiveLane: Float = 2.85f,
        val isStop: Boolean = false,
        val controls: com.masalik.yemen.game.HudCustomizationSettings = com.masalik.yemen.game.HudCustomizationSettings(),
        val blinkerOn: Boolean,
        val wheelStyle: SteeringWheelStyle = SteeringWheelStyle.EUROPEAN_LUXURY,
        val seatSide: DriverSeatSide = DriverSeatSide.LEFT_HAND,
        val rearMirrorEnabled: Boolean = true,
        val isReplayMode: Boolean = false,
        val replayCameraAngle: ReplayCameraAngle = ReplayCameraAngle.DRONE_ORBIT,
        val orbitYawRad: Float = 0.42f,
        val orbitPitchRad: Float = 0.24f
    )

    private var frameStartNs: Long = 0L
    private lateinit var shaders: ShaderLibrary
    private lateinit var textures: ProceduralTextures
    private lateinit var roundedMesh: GpuMesh
    private lateinit var humanMesh: GpuMesh
    private lateinit var palmMeshes: Map<JourneyScenery.Finish, GpuMesh>
    private lateinit var hillTreeMeshes: Map<JourneyScenery.Finish, GpuMesh>
    private lateinit var marketMeshes: Map<JourneyScenery.Finish, GpuMesh>
    private lateinit var sabirMeshes: Map<JourneyScenery.Finish, GpuMesh>
    private lateinit var fortMeshes: Map<JourneyScenery.Finish, GpuMesh>
    private lateinit var gateMeshes: Map<JourneyScenery.Finish, GpuMesh>
    private lateinit var coffeeMeshes: Map<JourneyScenery.Finish, GpuMesh>
    private lateinit var boatMeshes: Map<JourneyScenery.Finish, GpuMesh>
    private lateinit var humpMesh: GpuMesh
    private lateinit var cubeMesh: GpuMesh
    private lateinit var slopedBodyMesh: GpuMesh
    private lateinit var utilityCabinMesh: GpuMesh
    private lateinit var cabinMesh: GpuMesh
    private lateinit var wheelMesh: GpuMesh
    private lateinit var quadMesh: GpuMesh
    private lateinit var coneMesh: GpuMesh
    private lateinit var steerRingRound: GpuMesh
    private lateinit var steerRingFlat: GpuMesh

    private data class RoadFinish(val surface: SurfaceType, val boulevard: Boolean)
    private val roadMeshes = mutableMapOf<RoadFinish, DynamicMesh>()
    private lateinit var shoulderMesh: DynamicMesh
    private lateinit var sidewalkDynamicMesh: DynamicMesh
    private lateinit var terrainDynamicMesh: DynamicMesh
    private var skyVboId: Int = 0

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var aspectRatio = 16f / 9f
    private var screenWidth = 1920
    private var screenHeight = 1080
    private var lastFrameNs = System.nanoTime()
    private var lastStreamCenterDistM = -9999f
    private var weatherAnimTime = 0f

    private val fxEngine = ParticleAndMirrorEngine()

    private var currentCamX = 0f
    private var currentCamY = 4f
    private var currentCamZ = -8f
    private var smoothCamX = 0f
    private var smoothCamY = 4f
    private var smoothCamZ = -8f
    private var camInitialized = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        shaders = ShaderLibrary()
        bodies.clear()
        canopies.clear()
        palmMeshes = JourneyScenery.roadsideTree(true).mapValues { GpuMesh(it.value) }
        hillTreeMeshes = JourneyScenery.roadsideTree(false).mapValues { GpuMesh(it.value) }
        marketMeshes = JourneyScenery.taizMarket().mapValues { GpuMesh(it.value) }
        sabirMeshes = JourneyScenery.sabirLookout().mapValues { GpuMesh(it.value) }
        fortMeshes = JourneyScenery.taizHillFort().mapValues { GpuMesh(it.value) }
        gateMeshes = JourneyScenery.gate(RoadSplineNetwork.sampleAt(YemenJourneys.GATE_M).widthM).mapValues { GpuMesh(it.value) }
        coffeeMeshes = JourneyScenery.coffeeTerrace().mapValues { GpuMesh(it.value) }
        boatMeshes = JourneyScenery.dhow().mapValues { GpuMesh(it.value) }
        humpMesh = GpuMesh(JourneyScenery.quayHump(RoadSplineNetwork.sampleAt(QuayHumps.centers.first()).widthM))
        textures = ProceduralTextures(assets)
        roundedMesh = GpuMesh(VisualMeshData.roundedBox())
        humanMesh = GpuMesh(VisualMeshData.ellipsoid())
        cubeMesh = MeshPrimitives.createUnitCube()
        slopedBodyMesh = MeshPrimitives.createSlopedBody()
        cabinMesh = MeshPrimitives.createTrapezoidCabin()
        utilityCabinMesh = MeshPrimitives.createTrapezoidCabin(upright = true)
        wheelMesh = MeshPrimitives.createWheelCylinder(24)
        quadMesh = MeshPrimitives.createUnitQuadXZ()
        coneMesh = MeshPrimitives.createConePyramid(8)
        steerRingRound = MeshPrimitives.createSteeringRing(24, flatBottom = false)
        steerRingFlat = MeshPrimitives.createSteeringRing(24, flatBottom = true)

        roadMeshes.clear()
        shoulderMesh = DynamicMesh()
        sidewalkDynamicMesh = DynamicMesh()
        terrainDynamicMesh = DynamicMesh()

        val skyQuad = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        skyVboId = ids[0]
        val buf = ByteBuffer.allocateDirect(skyQuad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(skyQuad)
        buf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, skyVboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, skyQuad.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        fxEngine.initGlResources()
        lastFrameNs = System.nanoTime()
        lastStreamCenterDistM = -9999f
        camInitialized = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenWidth = width.coerceAtLeast(1)
        screenHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, screenWidth, screenHeight)
        aspectRatio = screenWidth.toFloat() / screenHeight.toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val frameSeconds = (now - lastFrameNs) / 1_000_000_000f
        val dt = frameSeconds.coerceIn(0f, 0.12f)
        lastFrameNs = now
        weatherAnimTime += dt

        val snap = onStepFrame(frameSeconds)
        frameQuality = snap.graphicsQuality
        val frameBudgetNs = 1_000_000_000L / snap.targetFps.coerceIn(30, 60)
        val sinceLastFrame = System.nanoTime() - frameStartNs
        if (sinceLastFrame < frameBudgetNs) {
            val pauseNs = frameBudgetNs - sinceLastFrame
            try { Thread.sleep(pauseNs / 1_000_000L, (pauseNs % 1_000_000L).toInt()) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        frameStartNs = System.nanoTime()

        val atmo = LivingWorldSimulation.sampleAtmosphere(snap.worldTimeHours, snap.weather)
        val t = snap.telemetry

        val centerDist = t.distanceAlongRoadM
        streamCorridor(centerDist)

        // Update 3D Drift Smoke, Desert Dust, Rain Spray, Exhaust Backfire Flames & Asphalt Skidmarks
        val playerSpec = VehicleFleetCatalog.specOf(t.vehicleId)
        if (snap.graphicsQuality != com.masalik.yemen.game.GraphicsQuality.LOW) fxEngine.stepParticlesAndSkids(dt, t, playerSpec, snap.weather, !snap.isLobbyOrGarage)

        // Pass A: Live Rear-View Mirror Render-to-Texture FBO Pass (Backwards-facing 3D camera!)
        val showLiveMirror = snap.graphicsQuality != com.masalik.yemen.game.GraphicsQuality.LOW && !snap.isLobbyOrGarage && !snap.isReplayMode && snap.rearMirrorEnabled &&
            (snap.cameraMode == CameraMode.COCKPIT || snap.cameraMode == CameraMode.HOOD || snap.cameraMode == CameraMode.CHASE_NEAR)
        if (showLiveMirror && fxEngine.beginRearMirrorPass()) {
            GLES30.glClearColor(atmo.skyR, atmo.skyG, atmo.skyB, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

            val fwdX = sin(t.headingRad); val fwdZ = cos(t.headingRad)
            val mEyeX = t.posX - fwdX * 0.45f
            val mEyeY = t.posY + 1.55f
            val mEyeZ = t.posZ - fwdZ * 0.45f
            Matrix.perspectiveM(projMatrix, 0, 56f, fxEngine.mirrorWidth.toFloat() / fxEngine.mirrorHeight.toFloat(), 0.2f, 260f)
            Matrix.setLookAtM(viewMatrix, 0, mEyeX, mEyeY, mEyeZ, mEyeX - fwdX * 32f, mEyeY - 0.8f, mEyeZ - fwdZ * 32f, 0f, 1f, 0f)
            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
            currentCamX = mEyeX; currentCamY = mEyeY; currentCamZ = mEyeZ

            renderWorldScenePass(snap, atmo, centerDist, isMirrorPass = true)
            fxEngine.endRearMirrorPass(screenWidth, screenHeight)
        }

        // Pass B: Primary Full-Resolution 3D Viewport Pass
        GLES30.glClearColor(atmo.skyR, atmo.skyG, atmo.skyB, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)


        val speedFovBoost = (t.speedKmh / 140f).coerceIn(0f, 1f) * 13f
        val fov = when {
            snap.isLobbyOrGarage -> 44f
            snap.isReplayMode && snap.replayCameraAngle == ReplayCameraAngle.DRONE_ORBIT -> 52f
            snap.isReplayMode && snap.replayCameraAngle == ReplayCameraAngle.TRACKSIDE -> 46f
            snap.cameraMode == CameraMode.FREE_PHOTO -> snap.photoFovDeg
            snap.cameraMode == CameraMode.CINEMATIC_ORBIT -> 54f + speedFovBoost * 0.55f
            snap.cameraMode == CameraMode.HOOD -> 64f + speedFovBoost * 0.65f
            snap.cameraMode == CameraMode.COCKPIT || (snap.isReplayMode && snap.replayCameraAngle == ReplayCameraAngle.COCKPIT) -> snap.controls.cockpitFov + speedFovBoost * .45f * snap.controls.cameraMotion
            else -> 58f + speedFovBoost
        }
        Matrix.perspectiveM(projMatrix, 0, fov, aspectRatio, 0.16f, 440f)
        updateCameraMatrix(snap, dt)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        renderWorldScenePass(snap, atmo, centerDist, isMirrorPass = false)

        // Composite Live Chrome-Framed Rear-View Mirror HUD directly on top of the 3D scene
        if (showLiveMirror) {
            fxEngine.drawMirrorOverlay(shaders, skyVboId, isCockpit = snap.cameraMode == CameraMode.COCKPIT)
        }
        if (!firstFrameReported) { firstFrameReported = true; onFirstFrame() }
    }

    private fun renderWorldScenePass(
        snap: RenderFrameSnapshot,
        atmo: AtmosphereSnapshot,
        centerDist: Float,
        isMirrorPass: Boolean
    ) {
        val t = snap.telemetry
        drawSkyBackdrop(atmo, t.regionId)
        GLES30.glUseProgram(shaders.worldProgram)
        GLES30.glUniform3f(shaders.uSunDir, atmo.sunDirX, atmo.sunDirY, atmo.sunDirZ)
        GLES30.glUniform3f(shaders.uSunColor, atmo.sunR, atmo.sunG, atmo.sunB)
        GLES30.glUniform1f(shaders.uSunIntensity, atmo.sunIntensity)
        GLES30.glUniform1f(shaders.uAmbientIntensity, atmo.ambientIntensity)
        GLES30.glUniform3f(shaders.uFogColor, atmo.horizonR, atmo.horizonG, atmo.horizonB)
        GLES30.glUniform1f(shaders.uFogDensity, atmo.fogDensity)
        GLES30.glUniform3f(shaders.uCameraPos, currentCamX, currentCamY, currentCamZ)
        GLES30.glUniform1f(shaders.uNightFactor, atmo.nightFactor)
        GLES30.glUniform1f(shaders.uWaterTime, -1f)

        val fwdX = sin(t.headingRad)
        val fwdZ = cos(t.headingRad)
        val beamPower = when {
            t.headlightBeamMode == HeadlightBeamMode.HIGH_BEAM -> 1.85f
            t.headlightBeamMode == HeadlightBeamMode.LOW_BEAM -> 1.10f
            atmo.nightFactor > 0.35f || t.inTunnel -> 1.05f
            else -> 0f
        }
        val activeHeadlightStrength = if (snap.isLobbyOrGarage || isMirrorPass) 0f else beamPower * (0.35f + 0.65f * atmo.nightFactor + if (t.inTunnel) 0.7f else 0f)
        GLES30.glUniform3f(shaders.uHeadlightPos, t.posX + fwdX * 2.1f, t.posY + 0.92f, t.posZ + fwdZ * 2.1f)
        GLES30.glUniform3f(shaders.uHeadlightDir, fwdX, -0.075f, fwdZ)
        GLES30.glUniform1f(shaders.uHeadlightStrength, activeHeadlightStrength.coerceAtMost(2.2f))

        val roadWetness = .04f + .82f * t.roadWetness
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(shaders.uTexture, 0)

        drawCorridorGeometry(t.regionId, roadWetness)

        // Persistent 3D Asphalt Skidmarks directly over road surface
        if (!isMirrorPass) {
            fxEngine.renderSkidmarks { sx, sy, sz, hRad, w, l, alpha ->
                Matrix.setIdentityM(modelMatrix, 0)
                Matrix.translateM(modelMatrix, 0, sx, sy, sz)
                Matrix.rotateM(modelMatrix, 0, hRad * 180f / PI.toFloat(), 0f, 1f, 0f)
                Matrix.scaleM(modelMatrix, 0, w, 1f, l)
                drawMeshWithCurrentMatrix(quadMesh, 0.06f, 0.06f, 0.07f, textures.whiteTex, 0f, 1f, 1f, 0f, 0f, 0f, 0f, 0.95f, alpha = alpha)
            }
        }

        drawYemeniCityEnvironment(centerDist, snap, atmo)
        if (!snap.isLobbyOrGarage) drawMarketStalls(centerDist, atmo)
        drawTownStreetGrid(centerDist)
        drawTaizLandmarks(centerDist)
        drawRoundaboutsAndCrossStreets(centerDist, atmo)
        drawSpeedRadars(centerDist, atmo)
        if (!snap.isLobbyOrGarage) {
            drawParkedCars(centerDist, snap.livingWorld)
            if (!isMirrorPass) drawYemeniPedestrians(centerDist, snap.livingWorld)
            drawAiTrafficAndRival(centerDist, snap, atmo)
        }

        if (!isMirrorPass) {
            val drawVehicleId = if (snap.isLobbyOrGarage) snap.previewVehicleId else t.vehicleId
            val drawCustom = if (snap.isLobbyOrGarage) snap.previewCustomization else snap.customization
            val showCockpit = !snap.isLobbyOrGarage &&
                ((!snap.isReplayMode && snap.cameraMode == CameraMode.COCKPIT) ||
                    (snap.isReplayMode && snap.replayCameraAngle == ReplayCameraAngle.COCKPIT))
            if (showCockpit) {
                drawCockpitInterior(t, drawCustom, snap.wheelStyle, snap.seatSide, atmo)
            } else {
                drawDetailedPlayerVehicle(t, drawVehicleId, drawCustom, snap.blinkerOn, atmo)
            }

            // 3D Drift Smoke, Desert Dust, Rain Spray & Exhaust Backfire Flames
            fxEngine.renderParticles { px, py, pz, sz, r, g, b, alpha, emR, emG, emB ->
                Matrix.setIdentityM(modelMatrix, 0)
                Matrix.translateM(modelMatrix, 0, px, py, pz)
                Matrix.scaleM(modelMatrix, 0, sz, sz, sz)
                drawMeshWithCurrentMatrix(cubeMesh, r, g, b, textures.whiteTex, 0f, 1f, 1f, emR, emG, emB, 0f, 0.9f, alpha = alpha)
            }

            if (snap.weather in listOf(WeatherPreset.LIGHT_RAIN, WeatherPreset.HEAVY_RAIN, WeatherPreset.SANDSTORM)) {
                drawWeatherParticles(snap.weather)
            }
        }
    }

    private fun drawSkyBackdrop(atmo: AtmosphereSnapshot, region: RegionId) {
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(shaders.skyProgram)
        GLES30.glUniform3f(shaders.uSkyTopColor, atmo.skyR, atmo.skyG, atmo.skyB)
        GLES30.glUniform3f(shaders.uSkyHorizonColor, atmo.horizonR, atmo.horizonG, atmo.horizonB)
        GLES30.glUniform3f(shaders.uSkySunDir, atmo.sunDirX, atmo.sunDirY, atmo.sunDirZ)
        GLES30.glUniform1f(shaders.uSkyNightFactor, atmo.nightFactor)
        GLES30.glUniform1f(shaders.uSkyTime, weatherAnimTime)
        GLES30.glUniformMatrix4fv(shaders.uSkyView, 1, false, viewMatrix, 0)
        GLES30.glUniform2f(shaders.uSkyLens, 1f / projMatrix[0], 1f / projMatrix[5])
        val relief = when (region) {
            RegionId.COASTAL_ROAD, RegionId.TIHAMA_PLAINS -> .035f
            RegionId.MASALIK_DESERT, RegionId.SHIBAM_HADRAMOUT -> .085f
            RegionId.NAQIL_SAHAB -> .32f
            else -> .20f
        }
        GLES30.glUniform1f(shaders.uSkyRelief, relief)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, skyVboId)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glDepthMask(true)
    }

    private fun updateCameraMatrix(snap: RenderFrameSnapshot, dt: Float) {
        val t = snap.telemetry
        val fwdX = sin(t.headingRad)
        val fwdZ = cos(t.headingRad)
        val rightX = cos(t.headingRad)
        val rightZ = -sin(t.headingRad)
        val steerApexLook = t.steeringInputNorm * 2.1f

        var eyeX: Float
        var eyeY: Float
        var eyeZ: Float
        var targetX: Float
        var targetY: Float
        var targetZ: Float
        var upX = 0f
        var upY = 1f
        var upZ = 0f

        if (snap.isLobbyOrGarage) {
            val radius = 9.5f
            val angle = t.headingRad + snap.showroomOrbitRad
            eyeX = t.posX + sin(angle) * radius
            eyeY = t.posY + 3.05f
            eyeZ = t.posZ + cos(angle) * radius
            targetX = t.posX + cos(angle) * 2.0f
            targetY = t.posY + 1.0f
            targetZ = t.posZ - sin(angle) * 2.0f
            camInitialized = false
        } else if (snap.isReplayMode) {
            when (snap.replayCameraAngle) {
                ReplayCameraAngle.DRONE_ORBIT -> {
                    val ang = t.headingRad + weatherAnimTime * 0.55f + snap.showroomOrbitRad
                    val dist = 9.2f + sin(weatherAnimTime * 0.7f) * 1.8f
                    eyeX = t.posX + sin(ang) * dist
                    eyeY = t.posY + 4.4f + cos(weatherAnimTime * 0.6f) * 1.1f
                    eyeZ = t.posZ + cos(ang) * dist
                    targetX = t.posX + fwdX * 1.8f
                    targetY = t.posY + 1.1f
                    targetZ = t.posZ + fwdZ * 1.8f
                }
                ReplayCameraAngle.LOW_CHASE -> {
                    eyeX = t.posX - fwdX * 5.6f + rightX * 1.15f
                    eyeY = t.posY + 1.15f
                    eyeZ = t.posZ - fwdZ * 5.6f + rightZ * 1.15f
                    targetX = t.posX + fwdX * 12f
                    targetY = t.posY + 0.95f
                    targetZ = t.posZ + fwdZ * 12f
                }
                ReplayCameraAngle.TRACKSIDE -> {
                    // Anchored roadside television broadcast camera panning smoothly with the car!
                    val anchorDist = ((t.distanceAlongRoadM / 38f).toInt() * 38f + 16f)
                    val s = RoadSplineNetwork.sampleAt(anchorDist)
                    eyeX = s.x + s.rightX * (s.widthM * 0.5f + 4.5f)
                    eyeY = s.y + 2.6f
                    eyeZ = s.z + s.rightZ * (s.widthM * 0.5f + 4.5f)
                    targetX = t.posX
                    targetY = t.posY + 1.05f
                    targetZ = t.posZ
                }
                ReplayCameraAngle.COCKPIT -> {
                    val seatSign = if (snap.seatSide == DriverSeatSide.LEFT_HAND) -1f else 1f
                    eyeX = t.posX + fwdX * 0.16f + rightX * (seatSign * 0.38f)
                    eyeY = t.posY + 1.40f
                    eyeZ = t.posZ + fwdZ * 0.16f + rightZ * (seatSign * 0.38f)
                    targetX = eyeX + fwdX * 26f
                    targetY = eyeY - 0.38f
                    targetZ = eyeZ + fwdZ * 26f
                }
            }
            camInitialized = false
        } else {
            when (snap.cameraMode) {
                CameraMode.CHASE_NEAR -> {
                    val speedBack = (t.speedKmh / 160f).coerceIn(0f, 1f) * 0.9f
                    val desiredX = t.posX - fwdX * (6.9f + speedBack) - rightX * (t.driftSlipAngleDeg * 0.035f)
                    val desiredY = t.posY + 2.65f
                    val desiredZ = t.posZ - fwdZ * (6.9f + speedBack) - rightZ * (t.driftSlipAngleDeg * 0.035f)
                    if (!camInitialized) {
                        smoothCamX = desiredX; smoothCamY = desiredY; smoothCamZ = desiredZ; camInitialized = true
                    } else {
                        val k = (dt * 11f).coerceAtMost(1f)
                        smoothCamX += (desiredX - smoothCamX) * k
                        smoothCamY += (desiredY - smoothCamY) * k
                        smoothCamZ += (desiredZ - smoothCamZ) * k
                    }
                    eyeX = smoothCamX; eyeY = smoothCamY; eyeZ = smoothCamZ
                    targetX = t.posX + fwdX * 16f + rightX * steerApexLook
                    targetY = t.posY + 1.25f
                    targetZ = t.posZ + fwdZ * 16f + rightZ * steerApexLook
                    // Subtle dynamic cornering camera horizon tilt
                    val bankTilt = (t.rollDeg * 0.0045f).coerceIn(-0.045f, 0.045f)
                    upX = rightX * bankTilt; upZ = rightZ * bankTilt
                }
                CameraMode.REVERSE -> {
                    val spec = VehicleFleetCatalog.specOf(t.vehicleId)
                    val mount = spec.bodyLengthM * .5f + .08f
                    eyeX = t.posX - fwdX * mount
                    eyeZ = t.posZ - fwdZ * mount
                    eyeY = t.posY + spec.bodyHeightM * .65f
                    targetX = eyeX - fwdX * 8f
                    targetZ = eyeZ - fwdZ * 8f
                    targetY = eyeY - 1.65f
                    camInitialized = false
                }
                CameraMode.CHASE_FAR -> {
                    eyeX = t.posX - fwdX * 10.6f
                    eyeY = t.posY + 4.1f
                    eyeZ = t.posZ - fwdZ * 10.6f
                    targetX = t.posX + fwdX * 20f + rightX * steerApexLook
                    targetY = t.posY + 1.35f
                    targetZ = t.posZ + fwdZ * 20f + rightZ * steerApexLook
                    camInitialized = false
                }
                CameraMode.COCKPIT -> {
                    // Authentic Left-Hand Drive vs Right-Hand Drive driver eye position with G-force shift!
                    val seatSign = if (snap.seatSide == DriverSeatSide.LEFT_HAND) -1f else 1f
                    val gLongShift = (-t.throttle * 0.055f + t.brake * 0.065f).coerceIn(-0.07f, 0.08f)
                    eyeX = t.posX + fwdX * (0.16f + snap.controls.seatDepthM + gLongShift * snap.controls.cameraMotion) + rightX * (seatSign * 0.38f)
                    eyeY = t.posY + 1.40f + snap.controls.seatHeightM + t.suspensionBounceM * .72f * snap.controls.cameraMotion
                    eyeZ = t.posZ + fwdZ * (0.16f + snap.controls.seatDepthM + gLongShift * snap.controls.cameraMotion) + rightZ * (seatSign * 0.38f)
                    targetX = eyeX + fwdX * 28f + rightX * (steerApexLook * 1.45f)
                    targetY = eyeY - 0.40f + t.pitchDeg * 0.032f
                    targetZ = eyeZ + fwdZ * 28f + rightZ * (steerApexLook * 1.45f)
                    val cockpitRoll = (t.rollDeg * 0.0085f * snap.controls.cameraMotion).coerceIn(-0.075f, 0.075f)
                    upX = rightX * cockpitRoll; upZ = rightZ * cockpitRoll
                    camInitialized = false
                }
                CameraMode.HOOD -> {
                    // Sculpted Hood-Mounted Sports Camera showing the metallic hood & dynamic pitch/roll!
                    eyeX = t.posX + fwdX * 0.62f
                    eyeY = t.posY + 1.42f + t.suspensionBounceM * 0.85f
                    eyeZ = t.posZ + fwdZ * 0.62f
                    targetX = eyeX + fwdX * 26f + rightX * (steerApexLook * 1.1f)
                    targetY = eyeY - 0.62f + t.pitchDeg * 0.035f
                    targetZ = eyeZ + fwdZ * 26f + rightZ * (steerApexLook * 1.1f)
                    val hoodRoll = (t.rollDeg * 0.006f).coerceIn(-0.055f, 0.055f)
                    upX = rightX * hoodRoll; upZ = rightZ * hoodRoll
                    camInitialized = false
                }
                CameraMode.CINEMATIC_ORBIT -> {
                    // Smooth 360° Touch-Draggable & Auto-Sweeping Cinematic Third-Person Orbit View!
                    val speedPull = (t.speedKmh / 160f).coerceIn(0f, 1f) * 1.45f
                    val radius = 7.25f + speedPull
                    val pitch = snap.orbitPitchRad.coerceIn(0.10f, 0.72f)
                    val horizR = radius * cos(pitch)
                    // Offset by PI so orbitYawRad = 0 sits cleanly behind the car
                    val worldAng = t.headingRad + PI.toFloat() + snap.orbitYawRad
                    val desiredX = t.posX + sin(worldAng) * horizR
                    val desiredY = t.posY + 1.18f + radius * sin(pitch)
                    val desiredZ = t.posZ + cos(worldAng) * horizR
                    if (!camInitialized) {
                        smoothCamX = desiredX; smoothCamY = desiredY; smoothCamZ = desiredZ; camInitialized = true
                    } else {
                        val k = (dt * 9.5f).coerceAtMost(1f)
                        smoothCamX += (desiredX - smoothCamX) * k
                        smoothCamY += (desiredY - smoothCamY) * k
                        smoothCamZ += (desiredZ - smoothCamZ) * k
                    }
                    eyeX = smoothCamX; eyeY = smoothCamY; eyeZ = smoothCamZ
                    targetX = t.posX + fwdX * 1.6f
                    targetY = t.posY + 1.10f
                    targetZ = t.posZ + fwdZ * 1.6f
                    val orbitTilt = (t.rollDeg * 0.0035f).coerceIn(-0.035f, 0.035f)
                    upX = rightX * orbitTilt; upZ = rightZ * orbitTilt
                }
                CameraMode.CINEMATIC_FRONT -> {
                    eyeX = t.posX + fwdX * 7.8f + rightX * 2.2f
                    eyeY = t.posY + 2.0f
                    eyeZ = t.posZ + fwdZ * 7.8f + rightZ * 2.2f
                    targetX = t.posX
                    targetY = t.posY + 1.05f
                    targetZ = t.posZ
                    camInitialized = false
                }
                CameraMode.FREE_PHOTO -> {
                    val r = 8.0f
                    val pitch = snap.orbitPitchRad.coerceIn(0.08f, 0.75f)
                    val horizR = r * cos(pitch)
                    val ang = t.headingRad + snap.showroomOrbitRad
                    eyeX = t.posX + sin(ang) * horizR
                    eyeY = t.posY + 1.15f + r * sin(pitch)
                    eyeZ = t.posZ + cos(ang) * horizR
                    targetX = t.posX
                    targetY = t.posY + 1.12f
                    targetZ = t.posZ
                    camInitialized = false
                }
            }
        }

        currentCamX = eyeX
        currentCamY = eyeY
        currentCamZ = eyeZ
        Matrix.setLookAtM(viewMatrix, 0, eyeX, eyeY, eyeZ, targetX, targetY, targetZ, upX, upY, upZ)
    }

    private data class Corridor(val center: Float, val roads: Map<RoadFinish, FloatArray>, val shoulders: FloatArray, val sidewalks: FloatArray, val terrain: FloatArray)
    private val EMPTY_VERTICES = floatArrayOf()
    private var pendingCorridor: java.util.concurrent.CompletableFuture<Corridor>? = null
    private fun streamCorridor(center: Float) {
        fun upload(c: Corridor) {
            for ((finish, mesh) in roadMeshes) if (finish !in c.roads) mesh.upload(EMPTY_VERTICES)
            for ((finish, vertices) in c.roads) roadMeshes.getOrPut(finish) { DynamicMesh() }.upload(vertices)
            shoulderMesh.upload(c.shoulders)
            sidewalkDynamicMesh.upload(c.sidewalks)
            terrainDynamicMesh.upload(c.terrain)
            lastStreamCenterDistM = c.center
        }
        if (abs(center - lastStreamCenterDistM) > 165f) {
            pendingCorridor = null
            upload(buildCorridor(center))
            return
        }
        val pending = pendingCorridor
        if (pending != null && pending.isDone) {
            val result = pending.join()
            pendingCorridor = null
            if (abs(result.center - center) < 100f) upload(result)
        }
        if (pendingCorridor == null && abs(center - lastStreamCenterDistM) > 50f)
            pendingCorridor = java.util.concurrent.CompletableFuture.supplyAsync { buildCorridor(center) }
    }

    private fun buildCorridor(centerDistM: Float): Corridor {
        val startM = kotlin.math.floor((centerDistM - 340f).coerceAtLeast(0f) / 5f) * 5f
        val endM = (centerDistM + 340f).coerceAtMost(RoadSplineNetwork.TOTAL_ROAD_LENGTH_M)
        val step = 5f
        val samples = mutableListOf<RoadSample>()
        var d = startM
        while (d <= endM) {
            samples.add(RoadSplineNetwork.sampleAt(d))
            d += step
        }
        check(samples.size >= 2)

        val roads = mutableMapOf<RoadFinish, MutableList<Float>>()
        val shoulderVerts = ArrayList<Float>()
        val sidewalkVerts = ArrayList<Float>(samples.size * 96)
        val terrainVerts = ArrayList<Float>(samples.size * 192)

        fun pushTri(
            dst: MutableList<Float>,
            ax: Float, ay: Float, az: Float, au: Float, av: Float,
            bx: Float, by: Float, bz: Float, bu: Float, bv: Float,
            cx: Float, cy: Float, cz: Float, cu: Float, cv: Float,
            nx: Float, ny: Float, nz: Float
        ) {
            dst.add(ax); dst.add(ay); dst.add(az); dst.add(nx); dst.add(ny); dst.add(nz); dst.add(au); dst.add(av)
            dst.add(bx); dst.add(by); dst.add(bz); dst.add(nx); dst.add(ny); dst.add(nz); dst.add(bu); dst.add(bv)
            dst.add(cx); dst.add(cy); dst.add(cz); dst.add(nx); dst.add(ny); dst.add(nz); dst.add(cu); dst.add(cv)
        }

        for (i in 0 until samples.lastIndex) {
            val s0 = samples[i]
            val s1 = samples[i + 1]
            val finish = RoadFinish(s0.surface, s0.isModernBoulevard && s0.surface == SurfaceType.ASPHALT)
            val roadVerts = roads.getOrPut(finish) { ArrayList() }
            val shoulder = if (s0.isUrbanStreet) sidewalkVerts else shoulderVerts
            val hw0 = s0.widthM * 0.5f
            val hw1 = s1.widthM * 0.5f
            val v0 = s0.distanceM * 0.08f
            val v1 = s1.distanceM * 0.08f

            val l0x = s0.x - s0.rightX * hw0; val l0z = s0.z - s0.rightZ * hw0
            val r0x = s0.x + s0.rightX * hw0; val r0z = s0.z + s0.rightZ * hw0
            val l1x = s1.x - s1.rightX * hw1; val l1z = s1.z - s1.rightZ * hw1
            val r1x = s1.x + s1.rightX * hw1; val r1z = s1.z + s1.rightZ * hw1

            pushTri(roadVerts, l0x, s0.y + 0.03f, l0z, 0f, v0, r0x, s0.y + 0.03f, r0z, 1f, v0, r1x, s1.y + 0.03f, r1z, 1f, v1, 0f, 1f, 0f)
            pushTri(roadVerts, l0x, s0.y + 0.03f, l0z, 0f, v0, r1x, s1.y + 0.03f, r1z, 1f, v1, l1x, s1.y + 0.03f, l1z, 0f, v1, 0f, 1f, 0f)

            val swLift0 = if (s0.isUrbanStreet) 0.19f else 0.02f
            val swLift1 = if (s1.isUrbanStreet) 0.19f else 0.02f
            val swW = 3.6f
            for (side in intArrayOf(-1, 1)) {
                val in0 = side * hw0
                val out0 = side * (hw0 + swW)
                val in1 = side * hw1
                val out1 = side * (hw1 + swW)
                val p0x = s0.x + s0.rightX * in0; val p0z = s0.z + s0.rightZ * in0
                val p1x = s0.x + s0.rightX * out0; val p1z = s0.z + s0.rightZ * out0
                val p2x = s1.x + s1.rightX * out1; val p2z = s1.z + s1.rightZ * out1
                val p3x = s1.x + s1.rightX * in1; val p3z = s1.z + s1.rightZ * in1
                pushTri(shoulder, p0x, s0.y + swLift0, p0z, 0f, v0, p1x, s0.y + swLift0, p1z, 1f, v0, p2x, s1.y + swLift1, p2z, 1f, v1, 0f, 1f, 0f)
                pushTri(shoulder, p0x, s0.y + swLift0, p0z, 0f, v0, p2x, s1.y + swLift1, p2z, 1f, v1, p3x, s1.y + swLift1, p3z, 0f, v1, 0f, 1f, 0f)
            }

            val offsets = floatArrayOf(hw0 + swW, hw0 + 18f, hw0 + 46f, hw0 + 88f)
            for (side in intArrayOf(-1, 1)) {
                for (band in 0 until offsets.lastIndex) {
                    val latA = side * offsets[band]
                    val latB = side * offsets[band + 1]
                    val ax0 = s0.x + s0.rightX * latA; val az0 = s0.z + s0.rightZ * latA
                    val bx0 = s0.x + s0.rightX * latB; val bz0 = s0.z + s0.rightZ * latB
                    val ax1 = s1.x + s1.rightX * latA; val az1 = s1.z + s1.rightZ * latA
                    val bx1 = s1.x + s1.rightX * latB; val bz1 = s1.z + s1.rightZ * latB

                    val ay0 = RoadSplineNetwork.terrainHeightAt(ax0, az0, s0, latA)
                    val by0 = RoadSplineNetwork.terrainHeightAt(bx0, bz0, s0, latB)
                    val ay1 = RoadSplineNetwork.terrainHeightAt(ax1, az1, s1, latA)
                    val by1 = RoadSplineNetwork.terrainHeightAt(bx1, bz1, s1, latB)

                    fun terrainTri(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
                                   cx: Float, cy: Float, cz: Float) {
                        val ux=bx-ax; val uy=by-ay; val uz=bz-az
                        val vx=cx-ax; val vy=cy-ay; val vz=cz-az
                        val nx=uy*vz-uz*vy; val ny=uz*vx-ux*vz; val nz=ux*vy-uy*vx
                        val sign=if (ny<0f) -1f else 1f
                        pushTri(terrainVerts,ax,ay,az,ax*.13f,az*.13f,bx,by,bz,bx*.13f,bz*.13f,
                            cx,cy,cz,cx*.13f,cz*.13f,nx*sign,ny*sign,nz*sign)
                    }
                    terrainTri(ax0,ay0,az0,bx0,by0,bz0,bx1,by1,bz1)
                    terrainTri(ax0,ay0,az0,bx1,by1,bz1,ax1,ay1,az1)
                }
            }
        }

        return Corridor(centerDistM, roads.mapValues { it.value.toFloatArray() }, shoulderVerts.toFloatArray(), sidewalkVerts.toFloatArray(), terrainVerts.toFloatArray())
    }

    private fun drawCorridorGeometry(regionId: RegionId, wetness: Float) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        GLES30.glUniformMatrix4fv(shaders.uModelMatrix, 1, false, modelMatrix, 0)
        GLES30.glUniformMatrix4fv(shaders.uMvpMatrix, 1, false, mvpMatrix, 0)
        GLES30.glUniform3f(shaders.uEmissive, 0f, 0f, 0f)

        GLES30.glUniform1f(shaders.uUseTexture, 1f)
        GLES30.glUniform1f(shaders.uMetallic, .025f)
        GLES30.glUniform4f(shaders.uBaseColor, .98f, .97f, .95f, 1f)
        for ((finish, mesh) in roadMeshes) {
            if (mesh.vertexCount == 0) continue
            val paved = finish.surface == SurfaceType.ASPHALT
            val tex = when {
                finish.boulevard -> textures.modernBoulevardTex
                paved -> textures.roadAsphaltTex
                else -> textures.naturalRoads.getValue(finish.surface)
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glUniform2f(shaders.uUvScale, 1f, if (paved) 1f else 1.6f)
            GLES30.glUniform1f(shaders.uRoughness, when (finish.surface) {
                SurfaceType.ASPHALT -> .52f
                SurfaceType.MUD -> .48f
                else -> .91f
            })
            GLES30.glUniform1f(shaders.uWetness, if (finish.surface == SurfaceType.MUD) maxOf(.48f, wetness) else wetness)
            GLES30.glUniform1f(shaders.uPuddleStrength, if (paved) ((wetness-.28f)*1.8f).coerceIn(0f,1f) else 0f)
            mesh.draw()
        }
        GLES30.glUniform1f(shaders.uPuddleStrength, 0f)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures.naturalRoads.getValue(SurfaceType.GRAVEL))
        GLES30.glUniform2f(shaders.uUvScale, 1f, 2.5f)
        GLES30.glUniform1f(shaders.uRoughness, .9f)
        GLES30.glUniform1f(shaders.uWetness, wetness*.4f)
        shoulderMesh.draw()

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures.sidewalkStoneTex)
        GLES30.glUniform1f(shaders.uUseTexture, 1f)
        GLES30.glUniform2f(shaders.uUvScale, 1f, 1.5f)
        GLES30.glUniform1f(shaders.uMetallic, 0.04f)
        GLES30.glUniform1f(shaders.uRoughness, 0.78f)
        GLES30.glUniform1f(shaders.uWetness, wetness * 0.5f)
        GLES30.glUniform4f(shaders.uBaseColor, 0.94f, 0.92f, 0.88f, 1f)
        sidewalkDynamicMesh.draw()

        val (tr, tg, tb) = when (regionId) {
            RegionId.TAIZ_HIGHLANDS -> Triple(0.46f, 0.42f, 0.28f)
            RegionId.IBB_GREEN_PEAKS, RegionId.WADI_RAYHAN -> Triple(0.24f, 0.47f, 0.23f)
            RegionId.NAQIL_SAHAB, RegionId.SANAA_HIGHLANDS -> Triple(0.50f, 0.42f, 0.34f)
            RegionId.MASALIK_DESERT -> Triple(0.84f, 0.68f, 0.41f)
            RegionId.SHIBAM_HADRAMOUT -> Triple(0.76f, 0.60f, 0.38f)
            RegionId.TIHAMA_PLAINS -> Triple(0.72f, 0.62f, 0.38f)
            RegionId.COASTAL_ROAD -> Triple(0.76f, 0.68f, 0.46f)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures.naturalRoads.getValue(SurfaceType.ROCK))
        GLES30.glUniform2f(shaders.uUvScale, 1f, 1f)
        GLES30.glUniform1f(shaders.uUseTexture, .52f)
        GLES30.glUniform1f(shaders.uMetallic, 0f)
        GLES30.glUniform1f(shaders.uRoughness, 0.94f)
        GLES30.glUniform1f(shaders.uWetness, 0f)
        GLES30.glUniform4f(shaders.uBaseColor, tr, tg, tb, 1f)
        terrainDynamicMesh.draw()
    }

    private fun drawYemeniCityEnvironment(
        centerDistM: Float,
        snap: RenderFrameSnapshot,
        atmo: AtmosphereSnapshot
    ) {
        val behind = if (snap.graphicsQuality == com.masalik.yemen.game.GraphicsQuality.LOW) 55f else 110f
        val ahead = if (snap.graphicsQuality == com.masalik.yemen.game.GraphicsQuality.LOW) 145f else 250f
        val minD = ((centerDistM - behind) / 22f).toInt().coerceAtLeast(1) * 22
        val maxD = ((centerDistM + ahead) / 22f).toInt() * 22
        val nightGlow = atmo.nightFactor

        var dInt = minD
        while (dInt <= maxD) {
            val d = dInt.toFloat()
            if (d >= RoadSplineNetwork.TOTAL_ROAD_LENGTH_M - 20f) break
            val sample = RoadSplineNetwork.sampleAt(d)
            if (snap.isLobbyOrGarage && abs(d - centerDistM) < 45f) { dInt += 22; continue }
            val hash = ((dInt * 2654435761L) ushr 16).toInt() and 0x7FFFFFFF

            if (sample.hasTunnel) {
                drawOrientedBox(sample.x, sample.y + 6.8f, sample.z, sample.headingRad, sample.widthM + 3.5f, 1.8f, 22.5f, 0.30f, 0.28f, 0.26f)
                for (side in intArrayOf(-1, 1)) {
                    val lat = side * (sample.widthM * 0.5f + 1.2f)
                    drawOrientedBox(sample.x + sample.rightX * lat, sample.y + 3.4f, sample.z + sample.rightZ * lat, sample.headingRad, 1.8f, 6.8f, 22.5f, 0.34f, 0.31f, 0.28f)
                }
                // Warm overhead tunnel LED ceiling strip
                drawOrientedBox(sample.x, sample.y + 5.85f, sample.z, sample.headingRad, 0.9f, 0.12f, 5.5f, 1f, 0.92f, 0.72f, emR = 1.2f, emG = 0.95f, emB = 0.55f)
            } else if (sample.isUrbanStreet && !com.masalik.yemen.game.CityStreetNetwork.isJunction(d, 15f)) {
                val isModern = sample.isModernBoulevard
                for (side in intArrayOf(-1, 1)) {
                    // Keep the working waterfront open to the sea.
                    if (side > 0 && abs(d - YemenJourneys.HARBOUR_M) < 115f) continue
                    if (abs(d - YemenJourneys.GATE_M) < 18f) continue
                    val bHash = hash + (if (side > 0) 37 else 11)
                    val profile = com.masalik.yemen.game.CityArchitectureCatalog.profile(sample.regionId)
                    val buildingW = profile.buildingWidthM + (bHash % 4)
                    val buildingD = 12.0f + (bHash % 6)
                    val floors = profile.minFloors + (bHash % profile.floorVariants)
                    val buildingH = floors * 3.5f
                    val lat = side * (sample.widthM * 0.5f + 3.9f + buildingW * 0.52f)
                    val bx = sample.x + sample.rightX * lat
                    val bz = sample.z + sample.rightZ * lat
                    val by = sample.y + buildingH * 0.5f

                    val isCoastal = sample.regionId == RegionId.COASTAL_ROAD || sample.regionId == RegionId.TIHAMA_PLAINS
                    val isMosqueLandmark = (dInt % 264 == 0) && (side == -1)
                    val facadeTex = when {
                        isMosqueLandmark -> textures.heritageMosqueTex
                        sample.regionId == RegionId.COASTAL_ROAD -> textures.adenHeritageTex
                        sample.regionId == RegionId.TIHAMA_PLAINS -> textures.zabidBrickTex
                        isModern && (bHash % 7 == 0) -> textures.modernTowerTex
                        sample.regionId == RegionId.SANAA_HIGHLANDS -> textures.sanaaFacadeTex
                        sample.regionId == RegionId.SHIBAM_HADRAMOUT -> textures.shibamMudTex
                        else -> if (bHash % 3 == 0) textures.sanaaFacadeTex else textures.taizStoneTex
                    }
                    val isGlassTower = facadeTex == textures.modernTowerTex || facadeTex == textures.adenCoastalTowerTex
                    val winGlow = nightGlow * (if (isGlassTower) 0.42f else if (isMosqueLandmark) 0.38f else 0.28f)
                    val tintR = if (isGlassTower) 0.98f else if (bHash % 4 == 0) 0.99f else 0.94f
                    val tintG = if (isGlassTower) 0.98f else if (bHash % 4 == 1) 0.95f else 0.92f
                    val tintB = if (isGlassTower) 1.00f else if (bHash % 4 == 2) 0.90f else 0.88f

                    drawOrientedBox(
                        bx, by, bz, sample.headingRad,
                        buildingW, buildingH, buildingD,
                        tintR, tintG, tintB,
                        texId = facadeTex,
                        uvScaleX = 1f, uvScaleY = when (sample.regionId) {
                            RegionId.SHIBAM_HADRAMOUT -> floors * 0.25f
                            else -> floors * 0.5f
                        },
                        emR = winGlow * 0.95f, emG = winGlow * 0.80f, emB = winGlow * (if (isGlassTower) 0.62f else 0.34f),
                        metallic = if (isGlassTower) 0.62f else 0.06f,
                        roughness = if (isGlassTower) 0.16f else 0.74f
                    )

                    if (abs(d - centerDistM) < 82f && snap.graphicsQuality != com.masalik.yemen.game.GraphicsQuality.LOW) {
                        val frontLat = side * (sample.widthM * 0.5f + 3.65f)
                        val faceX = sample.x + sample.rightX * frontLat
                        val faceZ = sample.z + sample.rightZ * frontLat
                        for (floor in 1..floors) {
                            drawOrientedBox(faceX, sample.y + floor * 3.5f - 0.12f, faceZ, sample.headingRad,
                                0.35f, 0.17f, buildingD * 0.96f, 0.73f, 0.70f, 0.64f, roughness = 0.90f)
                        }
                        // Recessed dark entrance and a stone step ground each building at human scale.
                        drawOrientedBox(faceX, sample.y + 1.12f, faceZ, sample.headingRad,
                            0.23f, 2.24f, 1.25f, 0.15f, 0.12f, 0.10f, roughness = 0.9f)
                        drawOrientedBox(faceX - sample.rightX * side * 0.25f, sample.y + 0.12f,
                            faceZ - sample.rightZ * side * 0.25f, sample.headingRad,
                            0.75f, 0.24f, 1.6f, 0.50f, 0.48f, 0.44f, roughness = 0.95f)
                    }

                    if (abs(d - centerDistM) < 95f && snap.graphicsQuality != com.masalik.yemen.game.GraphicsQuality.LOW) {
                        val streetLat = side * (sample.widthM * 0.5f + 3.60f)
                        val faceX = sample.x + sample.rightX * streetLat
                        val faceZ = sample.z + sample.rightZ * streetLat
                        if (profile.gypsumTrim) {
                            drawOrientedBox(faceX, sample.y + 1.38f, faceZ, sample.headingRad,
                                .29f, 2.76f, buildingD*.985f, .68f, .63f, .56f,
                                texId = textures.taizStoneTex, uvScaleY = .65f)
                            for (window in -1..1) {
                                val along = window * buildingD * .25f
                                val wx = faceX + sin(sample.headingRad) * along
                                val wz = faceZ + cos(sample.headingRad) * along
                                drawOrientedBox(wx, sample.y + .69f, wz, sample.headingRad,
                                    .44f, 1.37f, .95f, .27f, .19f, .12f)
                            }
                            // White gypsum floor bands and paired qamariya-scale window surrounds.
                            for (floor in 1 until floors) for (window in -1..1) {
                                val along = window * buildingD * 0.27f
                                val wx = faceX + sin(sample.headingRad) * along
                                val wz = faceZ + cos(sample.headingRad) * along
                                drawOrientedMesh(humanMesh, wx, sample.y + floor * 3.5f + 1.4f, wz,
                                    sample.headingRad, 0f, 0f, 0.28f, 1.45f, 1.3f, 0.91f, 0.89f, 0.82f)
                                drawOrientedMesh(humanMesh, wx - sample.rightX * side * 0.15f,
                                    sample.y + floor * 3.5f + 1.4f, wz - sample.rightZ * side * 0.15f,
                                    sample.headingRad, 0f, 0f, 0.20f, 1.12f, 0.96f, 0.12f, 0.22f, 0.26f)
                            }
                        }
                        if (profile.woodenScreens) {
                            for (floor in 1 until floors) {
                                drawOrientedBox(faceX, sample.y + floor * 3.5f + 1.1f, faceZ,
                                    sample.headingRad, 0.80f, 1.75f, buildingD * 0.65f, 0.30f, 0.23f, 0.17f)
                                for (slat in -3..3) {
                                    val along = slat * buildingD * 0.085f
                                    drawOrientedBox(faceX - sample.rightX * side * 0.42f + sin(sample.headingRad) * along,
                                        sample.y + floor * 3.5f + 1.1f,
                                        faceZ - sample.rightZ * side * 0.42f + cos(sample.headingRad) * along,
                                        sample.headingRad, 0.08f, 1.62f, 0.12f, 0.59f, 0.46f, 0.30f)
                                }
                            }
                        }
                        if (profile.courtyardWalls) {
                            drawOrientedBox(faceX, sample.y + 1.0f, faceZ, sample.headingRad,
                                0.65f, 2.0f, buildingD * 0.95f, 0.72f, 0.56f, 0.39f,
                                texId = textures.zabidBrickTex)
                        }
                    }

                    if (isMosqueLandmark) {
                        // Iconic Yemeni Mosque White Dome + Tall Illuminated Minaret with Golden Finial!
                        val domeBaseY = sample.y + buildingH
                        drawOrientedBox(bx, domeBaseY + 1.2f, bz, sample.headingRad, buildingW * 0.68f, 2.4f, buildingD * 0.68f, 0.96f, 0.95f, 0.91f, emR = nightGlow * 0.22f, emG = nightGlow * 0.20f, emB = nightGlow * 0.14f)
                        drawCone(bx, domeBaseY + 3.8f, bz, buildingW * 0.58f, 3.6f, 0.97f, 0.96f, 0.92f)
                        // Slender Yemeni Minaret Tower on corner
                        val minX = bx + sample.rightX * (side * -buildingW * 0.35f) + sin(sample.headingRad) * (buildingD * 0.35f)
                        val minZ = bz + sample.rightZ * (side * -buildingW * 0.35f) + cos(sample.headingRad) * (buildingD * 0.35f)
                        drawOrientedBox(minX, sample.y + buildingH + 5.5f, minZ, sample.headingRad, 2.1f, 11.5f, 2.1f, 0.95f, 0.93f, 0.88f, texId = textures.heritageMosqueTex, uvScaleX = 0.5f, uvScaleY = 2.0f, emR = nightGlow * 0.30f, emG = nightGlow * 0.26f, emB = nightGlow * 0.15f)
                        drawOrientedBox(minX, sample.y + buildingH + 10.6f, minZ, sample.headingRad, 2.75f, 0.55f, 2.75f, 0.12f, 0.62f, 0.44f, emR = nightGlow * 0.14f, emG = nightGlow * 0.65f, emB = nightGlow * 0.42f)
                        drawCone(minX, sample.y + buildingH + 13.0f, minZ, 1.9f, 3.6f, 0.98f, 0.97f, 0.93f)
                    } else {
                        // Rooftop architectural crown / modern stepped penthouse & LED cornice OR Sana'a parapet
                        drawOrientedBox(
                            bx, sample.y + buildingH + 0.55f, bz, sample.headingRad,
                            buildingW + 0.5f, 0.95f, buildingD + 0.5f,
                            if (isGlassTower) 0.16f else 0.95f,
                            if (isGlassTower) 0.21f else 0.93f,
                            if (isGlassTower) 0.28f else 0.88f,
                            emR = if (isGlassTower) nightGlow * 0.50f else 0f,
                            emG = if (isGlassTower) nightGlow * 0.36f else 0f,
                            emB = if (isGlassTower) nightGlow * (if (isCoastal) 0.48f else 0.12f) else 0f
                        )
                        // Stepped upper architectural tower crown for skyline variety
                        if (isGlassTower && bHash % 2 == 0) {
                            drawOrientedBox(
                                bx, sample.y + buildingH + 2.4f, bz, sample.headingRad,
                                buildingW * 0.62f, 3.2f, buildingD * 0.62f,
                                0.94f, 0.96f, 1.0f,
                                texId = facadeTex, uvScaleX = 0.8f, uvScaleY = 0.5f,
                                emR = winGlow * 0.9f, emG = winGlow * 0.8f, emB = winGlow * 0.65f,
                                metallic = 0.68f, roughness = 0.14f
                            )
                        } else if (!isGlassTower && bHash % 2 == 1) {
                            // Authentic Carved Wooden Yemeni Mashrabiya Balcony projecting toward street
                            val balLat = side * (sample.widthM * 0.5f + 4.05f)
                            drawOrientedBox(
                                sample.x + sample.rightX * balLat, sample.y + buildingH * 0.58f, sample.z + sample.rightZ * balLat,
                                sample.headingRad, 1.15f, 2.2f, buildingD * 0.42f,
                                0.42f, 0.25f, 0.14f, metallic = 0.08f, roughness = 0.72f
                            )
                        }
                    }

                    // Ground-level Modern Mall/Café Storefront OR Authentic Striped Souk Awning + Colorful Neon Sign Accent
                    if (bHash % 2 == 0 && !isMosqueLandmark) {
                        val shopLat = side * (sample.widthM * 0.5f + 3.65f)
                        drawOrientedBox(
                            sample.x + sample.rightX * shopLat, sample.y + 1.95f, sample.z + sample.rightZ * shopLat,
                            sample.headingRad, 1.4f, 3.5f, buildingD * 0.86f,
                            1f, 1f, 1f,
                            texId = if (isModern) textures.modernMallTex else textures.soukShopTex,
                            uvScaleX = 1f, uvScaleY = 1f,
                            emR = nightGlow * 0.48f, emG = nightGlow * 0.38f, emB = nightGlow * 0.18f,
                            metallic = if (isModern) 0.38f else 0.05f,
                            roughness = if (isModern) 0.22f else 0.72f
                        )
                        if (isModern) {
                            val (nr, ng, nb) = when (bHash % 4) {
                                0 -> Triple(0.98f, 0.68f, 0.12f)
                                1 -> Triple(0.08f, 0.82f, 0.68f)
                                2 -> Triple(0.22f, 0.64f, 0.98f)
                                else -> Triple(0.94f, 0.26f, 0.32f)
                            }
                            drawOrientedBox(
                                sample.x + sample.rightX * (shopLat - side * 0.72f), sample.y + 3.65f, sample.z + sample.rightZ * (shopLat - side * 0.72f),
                                sample.headingRad, 0.14f, 0.42f, buildingD * 0.76f,
                                nr, ng, nb, emR = nr * (0.35f + nightGlow * 0.85f), emG = ng * (0.35f + nightGlow * 0.85f), emB = nb * (0.35f + nightGlow * 0.85f)
                            )
                        }
                    }

                    // Sleek Curved-Arm Modern Boulevard Streetlamps on BOTH sides every 44m
                    if (dInt % 44 == 0 && (isModern || side == 1)) {
                        val poleLat = side * (sample.widthM * 0.5f + 0.85f)
                        val px = sample.x + sample.rightX * poleLat
                        val pz = sample.z + sample.rightZ * poleLat
                        drawOrientedBox(px, sample.y + 3.6f, pz, sample.headingRad, 0.18f, 7.2f, 0.18f, 0.26f, 0.30f, 0.35f, metallic = 0.55f, roughness = 0.32f)
                        drawOrientedBox(
                            px - sample.rightX * (side * 0.95f), sample.y + 7.15f, pz - sample.rightZ * (side * 0.95f),
                            sample.headingRad, 1.9f, 0.14f, 0.22f, 0.32f, 0.36f, 0.42f, metallic = 0.55f, roughness = 0.32f
                        )
                        val lampEm = 0.25f + nightGlow * 1.45f
                        drawOrientedBox(
                            px - sample.rightX * (side * 1.65f), sample.y + 6.95f, pz - sample.rightZ * (side * 1.65f),
                            sample.headingRad, 0.55f, 0.20f, 0.48f,
                            if (isModern) 0.95f else 1.0f, if (isModern) 0.96f else 0.88f, if (isModern) 1.0f else 0.58f,
                            emR = lampEm, emG = lampEm * (if (isModern) 0.92f else 0.78f), emB = lampEm * (if (isModern) 0.78f else 0.38f)
                        )
                    }

                    // Multi-Frond Ornamental Boulevard Palm Trees + Stone Planter Box lining Modern Yemeni Sidewalks
                    if (isModern && dInt % 44 == 22) {
                        val palmLat = side * (sample.widthM * 0.5f + 2.25f)
                        val px = sample.x + sample.rightX * palmLat
                        val pz = sample.z + sample.rightZ * palmLat
                        // Decorative curb planter + green hedge
                        drawOrientedBox(px, sample.y + 0.32f, pz, sample.headingRad, 1.25f, 0.48f, 1.25f, 0.76f, 0.72f, 0.65f)
                        drawOrientedBox(px, sample.y + 2.65f, pz, 0f, 0.34f, 5.1f, 0.34f, 0.46f, 0.33f, 0.20f)
                        // Lush layered palm fronds radiating in 4 directions
                        drawCone(px, sample.y + 5.55f, pz, 3.6f, 1.65f, 0.14f, 0.54f, 0.24f)
                        drawOrientedBox(px, sample.y + 5.25f, pz, sample.headingRad + 0.35f, 4.4f, 0.24f, 0.85f, 0.16f, 0.58f, 0.26f)
                        drawOrientedBox(px, sample.y + 5.25f, pz, sample.headingRad + 1.92f, 4.4f, 0.24f, 0.85f, 0.12f, 0.50f, 0.22f)
                    }
                }

                // Overhead Modern Green Highway Directional Gantry every 330m in modern boulevards
                if (isModern && dInt % 330 == 110) {
                    val hw = sample.widthM * 0.5f + 1.1f
                    for (side in intArrayOf(-1, 1)) {
                        drawOrientedBox(
                            sample.x + sample.rightX * (side * hw), sample.y + 3.6f, sample.z + sample.rightZ * (side * hw),
                            sample.headingRad, 0.34f, 7.2f, 0.34f, 0.62f, 0.66f, 0.70f, metallic = 0.6f, roughness = 0.3f
                        )
                    }
                    drawOrientedBox(
                        sample.x, sample.y + 6.5f, sample.z, sample.headingRad,
                        sample.widthM + 2.6f, 1.35f, 0.32f,
                        0.06f, 0.44f, 0.26f,
                        emR = 0.04f, emG = 0.24f + nightGlow * 0.22f, emB = 0.14f + nightGlow * 0.12f
                    )
                }
            } else {
                // Mountain / Wadi / Desert Scenery + Reflective Curve Chevron Warning Signs on sharp bends!
                val side = if (hash % 2 == 0) 1 else -1
                val lat = side * (sample.widthM * 0.5f + 5.5f + (hash % 7))
                val tx = sample.x + sample.rightX * lat
                val tz = sample.z + sample.rightZ * lat
                val ty = RoadSplineNetwork.terrainHeightAt(tx, tz, sample, lat)

                val tree = when (sample.regionId) {
                    RegionId.MASALIK_DESERT, RegionId.SHIBAM_HADRAMOUT,
                    RegionId.TIHAMA_PLAINS, RegionId.COASTAL_ROAD -> palmMeshes
                    else -> hillTreeMeshes
                }
                val scale = .76f + (hash%13)*.043f
                for ((m, mesh) in tree) drawOrientedMesh(mesh,tx,ty,tz,sample.headingRad+hash%11,
                    0f,0f,scale,scale,scale,m.r,m.g,m.b,roughness=.93f)

                // Reflective Curve Chevron Signs (>>>) on outside of sharp mountain switchbacks!
                if (abs(sample.curvatureRadPerM) > 0.0046f) {
                    val outerSide = if (sample.curvatureRadPerM > 0f) -1 else 1
                    val cLat = outerSide * (sample.widthM * 0.5f + 1.05f)
                    val cx = sample.x + sample.rightX * cLat
                    val cz = sample.z + sample.rightZ * cLat
                    drawOrientedBox(cx, sample.y + 0.8f, cz, sample.headingRad, 0.10f, 1.6f, 0.10f, 0.55f, 0.58f, 0.62f)
                    drawOrientedBox(
                        cx, sample.y + 1.65f, cz, sample.headingRad,
                        0.95f, 0.85f, 0.10f, 1f, 0.94f, 0.80f,
                        texId = textures.curveChevronTex,
                        emR = 0.28f + nightGlow * 0.42f, emG = 0.22f + nightGlow * 0.32f, emB = 0.02f
                    )
                }

                // Steel guardrail posts with amber night reflectors
                for (gSide in intArrayOf(-1, 1)) {
                    val gLat = gSide * (sample.widthM * 0.5f + 0.60f)
                    drawOrientedBox(
                        sample.x + sample.rightX * gLat, sample.y + 0.46f, sample.z + sample.rightZ * gLat,
                        sample.headingRad, 0.24f, 0.86f, 1.4f, 0.86f, 0.84f, 0.80f,
                        emR = nightGlow * 0.25f, emG = nightGlow * 0.15f
                    )
                }
            }

            if (sample.regionId == RegionId.COASTAL_ROAD) {
                val seaLat = sample.widthM * 0.5f + 46f
                GLES30.glUniform1f(shaders.uWaterTime, weatherAnimTime)
                drawOrientedBox(
                    sample.x + sample.rightX * seaLat, sample.y - 2.6f, sample.z + sample.rightZ * seaLat,
                    sample.headingRad, 80f, 0.4f, 24f,
                    0.035f, 0.30f, 0.40f, metallic = 0.42f, roughness = 0.19f
                )
                GLES30.glUniform1f(shaders.uWaterTime, -1f)
            }
            dInt += 22
        }

        drawJourneyScenery(centerDistM)

        // Service Stations (Fuel Canopies, Workshops & Car Washes)
        for (poi in RoadSplineNetwork.WORLD_POIS) {
            if (abs(poi.distanceM - centerDistM) > 220f) continue
            if (poi.type !in listOf("fuel", "workshop", "car_wash")) continue
            val s = RoadSplineNetwork.sampleAt(poi.distanceM)
            val px = s.x + s.rightX * poi.lateralOffsetM
            val pz = s.z + s.rightZ * poi.lateralOffsetM
            val (ar, ag, ab) = when (poi.type) {
                "fuel" -> Triple(0.95f, 0.60f, 0.10f)
                "workshop" -> Triple(0.18f, 0.56f, 0.88f)
                else -> Triple(0.10f, 0.72f, 0.52f)
            }
            drawOrientedBox(px, s.y + 4.6f, pz, s.headingRad, 6.2f, 0.7f, 9.5f, ar, ag, ab, emR = ar * 0.45f, emG = ag * 0.45f, emB = ab * 0.45f)
            drawOrientedBox(px, s.y + 2.2f, pz + 3.2f, s.headingRad, 0.5f, 4.4f, 0.5f, 0.85f, 0.85f, 0.88f)
            drawOrientedBox(px, s.y + 2.2f, pz - 3.2f, s.headingRad, 0.5f, 4.4f, 0.5f, 0.85f, 0.85f, 0.88f)
        }

        // Live Traffic Signal Gantries & Zebra Crosswalks
        for (sig in snap.livingWorld.trafficSignals) {
            if (abs(sig.def.distanceM - centerDistM) > 200f) continue
            val s = RoadSplineNetwork.sampleAt(sig.def.distanceM)

            val cwSample = RoadSplineNetwork.sampleAt(sig.def.distanceM + 2.5f)
            drawOrientedQuad(cwSample.x, cwSample.y + 0.06f, cwSample.z, cwSample.headingRad, cwSample.widthM - 0.6f, 3.8f, textures.crosswalkTex)

            val poleLat = s.widthM * 0.5f + 0.9f
            val px = s.x + s.rightX * poleLat
            val pz = s.z + s.rightZ * poleLat
            drawOrientedBox(px, s.y + 3.2f, pz, s.headingRad, 0.22f, 6.4f, 0.22f, 0.22f, 0.24f, 0.28f, metallic = 0.5f, roughness = 0.35f)
            drawOrientedBox(px - s.rightX * 2.4f, s.y + 6.1f, pz - s.rightZ * 2.4f, s.headingRad, 4.9f, 0.22f, 0.22f, 0.22f, 0.24f, 0.28f, metallic = 0.5f, roughness = 0.35f)

            val bx = px - s.rightX * 3.6f
            val bz = pz - s.rightZ * 3.6f
            drawOrientedBox(bx, s.y + 5.7f, bz, s.headingRad, 0.65f, 1.75f, 0.55f, 0.10f, 0.11f, 0.14f)

            val redOn = sig.phase == TrafficSignalPhase.RED
            val yelOn = sig.phase == TrafficSignalPhase.YELLOW
            val grnOn = sig.phase == TrafficSignalPhase.GREEN
            drawOrientedBox(bx, s.y + 6.22f, bz, s.headingRad, 0.42f, 0.38f, 0.62f, if (redOn) 1f else 0.22f, 0.06f, 0.06f, emR = if (redOn) 1.8f else 0f)
            drawOrientedBox(bx, s.y + 5.72f, bz, s.headingRad, 0.42f, 0.38f, 0.62f, if (yelOn) 1f else 0.22f, if (yelOn) 0.85f else 0.18f, 0.05f, emR = if (yelOn) 1.5f else 0f, emG = if (yelOn) 1.15f else 0f)
            drawOrientedBox(bx, s.y + 5.22f, bz, s.headingRad, 0.42f, 0.38f, 0.62f, 0.06f, if (grnOn) 1f else 0.20f, 0.20f, emG = if (grnOn) 1.8f else 0f)
        }

        // Mission Finish Archway Banner
        val finishM = snap.finishDistanceM
        if (finishM != null && abs(finishM - centerDistM) < 230f) {
            val s = RoadSplineNetwork.sampleAt(finishM)
            if (snap.isStop) {
                val x = s.x + s.rightX * snap.objectiveLane
                val z = s.z + s.rightZ * snap.objectiveLane
                for (side in intArrayOf(-1, 1)) {
                    drawOrientedBox(x + s.rightX * side * 2.4f, s.y + .10f, z + s.rightZ * side * 2.4f,
                        s.headingRad, .16f, .07f, 12f, .95f, .74f, .15f)
                    drawOrientedBox(x + sin(s.headingRad) * side * 6f, s.y + .10f, z + cos(s.headingRad) * side * 6f,
                        s.headingRad, 4.8f, .07f, .16f, .95f, .74f, .15f)
                }
                return
            }
            for (side in intArrayOf(-1, 1)) {
                val lat = side * (s.widthM * 0.5f + 0.8f)
                drawOrientedBox(s.x + s.rightX * lat, s.y + 3.5f, s.z + s.rightZ * lat, s.headingRad, 0.5f, 7f, 0.5f, 0.96f, 0.62f, 0.06f, emR = 0.55f, emG = 0.32f)
            }
            drawOrientedBox(s.x, s.y + 6.6f, s.z, s.headingRad, s.widthM + 2.2f, 1.1f, 0.7f, 0.96f, 0.62f, 0.06f, emR = 0.65f, emG = 0.40f)
        }
    }

    /** Iconic Yemeni Roundabouts (جولة القصر، العدين، المصباحي، كالتكس) with Central Monuments & Branching 4-Way Cross-Streets! */
    private fun drawMarketStalls(centerDistM: Float, atmo: AtmosphereSnapshot) {
        for (hub in RoadSplineNetwork.YEMEN_CITY_HUBS) {
            if (abs(hub.distanceM - centerDistM) > 160f) continue
            for (i in -2..2) {
                val d = hub.distanceM + i * 16f
                val s = RoadSplineNetwork.sampleAt(d)
                if (!s.isUrbanStreet || com.masalik.yemen.game.CityStreetNetwork.isJunction(d, 13f)) continue
                val lat = -(s.widthM * 0.5f + 2.8f)
                val x = s.x + s.rightX * lat; val z = s.z + s.rightZ * lat
                drawOrientedBox(x, s.y + 0.67f, z, s.headingRad, 1.05f, 1.1f, 2.8f, 0.38f, 0.24f, 0.13f)
                for (end in intArrayOf(-1, 1)) drawOrientedBox(x + sin(s.headingRad) * end * 1.4f,
                    s.y + 1.35f, z + cos(s.headingRad) * end * 1.4f, s.headingRad,
                    0.07f, 2.7f, 0.07f, 0.30f, 0.23f, 0.14f)
                drawOrientedBox(x, s.y + 2.7f, z, s.headingRad, 1.8f, 0.12f, 3.3f,
                    if (i % 2 == 0) 0.64f else 0.15f, 0.26f, 0.17f)
                for (crate in -1..1) {
                    val along = crate * 0.82f
                    drawOrientedBox(x + sin(s.headingRad) * along, s.y + 1.28f, z + cos(s.headingRad) * along, s.headingRad,
                        0.85f, 0.25f, 0.68f, 0.47f, 0.34f, 0.18f)
                    if (frameQuality != com.masalik.yemen.game.GraphicsQuality.LOW) for (fruit in -1..1) {
                        drawOrientedMesh(humanMesh, x + s.rightX * fruit * 0.20f + sin(s.headingRad) * along,
                            s.y + 1.46f, z + s.rightZ * fruit * 0.20f + cos(s.headingRad) * along,
                            s.headingRad, 0f, 0f, 0.25f, 0.21f, 0.25f,
                            if (crate == 0) 0.75f else 0.28f, if (crate == 0) 0.25f else 0.58f, 0.12f)
                    }
                }
                if (atmo.nightFactor > 0.25f) drawOrientedBox(x, s.y + 2.48f, z, s.headingRad,
                    0.12f, 0.18f, 0.14f, 1f, 0.78f, 0.41f, emR = 0.9f, emG = 0.55f, emB = 0.18f)
            }
        }
    }

    private fun drawJourneyScenery(center: Float) {
        fun model(meshes: Map<JourneyScenery.Finish, GpuMesh>, s: RoadSample, lane: Float,
                  y: Float, angle: Float = 0f, roll: Float = 0f, scale: Float = 1f) {
            for ((m, mesh) in meshes) drawOrientedMesh(mesh, s.x + s.rightX * lane, y,
                s.z + s.rightZ * lane, s.headingRad + angle, 0f, roll, scale, scale, scale,
                m.r, m.g, m.b, roughness = .86f)
        }
        if (abs(center - YemenJourneys.TAIZ_MARKET_M) < 215f) {
            val s = RoadSplineNetwork.sampleAt(YemenJourneys.TAIZ_MARKET_M)
            model(marketMeshes, s, 41.2f, s.y + .23f)
        }
        if (abs(center - YemenJourneys.SABIR_LOOKOUT_M) < 370f) {
            val s = RoadSplineNetwork.sampleAt(YemenJourneys.SABIR_LOOKOUT_M)
            model(sabirMeshes, s, s.widthM * .5f + 1.9f, s.y)
        }
        if (abs(center - 1620f) < 390f) {
            val s = RoadSplineNetwork.sampleAt(1620f)
            val lane = -74f
            val base = RoadSplineNetwork.terrainHeightAt(s.x+s.rightX*lane, s.z+s.rightZ*lane, s, lane)
            model(fortMeshes, s, lane, base, .24f)
        }
        if (abs(center - YemenJourneys.GATE_M) < 290f) {
            val s = RoadSplineNetwork.sampleAt(YemenJourneys.GATE_M)
            model(gateMeshes, s, 0f, s.y)
        }
        if (abs(center - YemenJourneys.FARM_M) < 245f) {
            for (i in -2..2) {
                val s = RoadSplineNetwork.sampleAt(YemenJourneys.FARM_M + i * 32f)
                val lane = -(s.widthM * .5f + 10f)
                val base = RoadSplineNetwork.terrainHeightAt(s.x+s.rightX*lane, s.z+s.rightZ*lane, s, lane)
                model(coffeeMeshes, s, lane, base)
            }
        }
        if (abs(center - YemenJourneys.HARBOUR_M) < 240f) {
            for (section in -3..3) {
                val edge = RoadSplineNetwork.sampleAt(YemenJourneys.HARBOUR_M + section*13f)
                val offset = edge.widthM*.5f+7f
                val x=edge.x+edge.rightX*offset
                val z=edge.z+edge.rightZ*offset
                drawOrientedBox(x,edge.y-.45f,z,edge.headingRad,.88f,1.8f,12.95f,
                    .61f,.55f,.45f,texId=textures.taizStoneTex,uvScaleX=1.7f)
                drawOrientedBox(x,edge.y+.54f,z,edge.headingRad,1f,.18f,13f,.77f,.71f,.60f)
            }
            for (i in -1..2) {
                val s = RoadSplineNetwork.sampleAt(YemenJourneys.HARBOUR_M + i * 24f)
                val phase = weatherAnimTime * .85f + i * 2.1f
                model(boatMeshes, s, 58f + 5f*i, s.y - 2.4f + .12f*sin(phase),
                    .28f*i, 2.8f*sin(phase), 1.05f + .09f*i)
            }
            for (d in QuayHumps.centers) {
                val s = RoadSplineNetwork.sampleAt(d)
                drawOrientedMesh(humpMesh,s.x,s.y+.036f,s.z,s.headingRad,
                    -kotlin.math.atan(s.gradePct/100f)*180f/PI.toFloat(),0f,1f,1f,1f,.80f,.59f,.24f)
            }
        }
    }

    private fun drawTaizLandmarks(center: Float) {
        fun box(d: Float, lane: Float, y: Float, w: Float, h: Float, l: Float, r: Float, g: Float, b: Float) {
            val s = RoadSplineNetwork.sampleAt(d)
            drawOrientedBox(s.x + s.rightX * lane, s.y + y, s.z + s.rightZ * lane, s.headingRad,
                w,h,l,r,g,b)
        }
        if (abs(center - 230f) < 240f) {
            val ramp = com.masalik.yemen.game.TaizDriveway
            for (i in 0..11) {
                val d = ramp.distance - ramp.length * .5f + (i+.5f) * ramp.length / 12f
                val h = ramp.height(d, ramp.lane)
                box(d,ramp.lane,.24f+h/2,ramp.width,h+.02f,ramp.length/12f,.66f,.63f,.54f)
            }
        }
    }

    private fun drawTownStreetGrid(centerDistM: Float) {
        val network = com.masalik.yemen.game.CityStreetNetwork
        for (district in network.districts) {
            if (abs(district.centerM - centerDistM) > 430f) continue
            for (junction in district.junctionsM) {
                val s = RoadSplineNetwork.sampleAt(junction)
                drawOrientedQuad(s.x, s.y + 0.23f, s.z, s.headingRad + PI.toFloat() * 0.5f,
                    network.SIDE_HALF_WIDTH_M * 2f, network.SIDE_STREET_OFFSET_M * 2f + 9.6f, textures.roadAsphaltTex)
            }
            // Parallel side lanes follow the same sampled terrain as the main route.
            var d = district.junctionsM.first()
            while (d < district.junctionsM.last()) {
                val a = RoadSplineNetwork.sampleAt(d)
                val b = RoadSplineNetwork.sampleAt((d + 8f).coerceAtMost(district.junctionsM.last()))
                val dx = b.x - a.x; val dz = b.z - a.z
                val length = kotlin.math.sqrt(dx * dx + dz * dz) + 0.7f
                for (side in intArrayOf(-1, 1)) {
                    val offset = side * network.SIDE_STREET_OFFSET_M
                    drawOrientedQuad((a.x + b.x) * 0.5f + a.rightX * offset,
                        (a.y + b.y) * 0.5f + 0.23f,
                        (a.z + b.z) * 0.5f + a.rightZ * offset, a.headingRad,
                        network.SIDE_HALF_WIDTH_M * 2f, length, textures.roadAsphaltTex)
                }
                d += 8f
            }
        }
    }

    private fun drawRoundaboutsAndCrossStreets(centerDistM: Float, atmo: AtmosphereSnapshot) {
        val nightGlow = atmo.nightFactor
        for (rb in RoadSplineNetwork.ROUNDABOUTS) {
            if (abs(rb.distanceM - centerDistM) > 220f) continue
            val s = RoadSplineNetwork.sampleAt(rb.distanceM)
            val crossHeading = s.headingRad + (PI.toFloat() * 0.5f)

            // Branching 4-Way Perpendicular Asphalt Cross-Street extending ±48m left & right!
            drawOrientedQuad(s.x, s.y + 0.045f, s.z, crossHeading, 11.4f, 96f, textures.modernBoulevardTex)
            // Flanking sidewalks along the branching cross-street
            for (side in intArrayOf(-1, 1)) {
                val off = side * 7.4f
                val fwdX = sin(s.headingRad); val fwdZ = cos(s.headingRad)
                drawOrientedBox(
                    s.x + fwdX * off, s.y + 0.14f, s.z + fwdZ * off,
                    crossHeading, 2.8f, 0.24f, 82f, 0.88f, 0.86f, 0.82f, texId = textures.sidewalkStoneTex, uvScaleX = 1f, uvScaleY = 12f
                )
            }
            // Crosswalks at the 4 mouths of the roundabout
            for (dir in intArrayOf(-1, 1)) {
                drawOrientedQuad(s.x + s.rightX * (dir * 13.5f), s.y + 0.065f, s.z + s.rightZ * (dir * 13.5f), crossHeading, 10.6f, 3.2f, textures.crosswalkTex)
            }

            // Central Landscaped Roundabout Stone Island & Glowing Fountain/Monument
            drawOrientedBox(s.x, s.y + 0.36f, s.z, s.headingRad, 6.2f, 0.62f, 6.2f, 0.82f, 0.78f, 0.72f, texId = textures.sidewalkStoneTex)
            drawOrientedBox(s.x, s.y + 0.68f, s.z, s.headingRad, 5.2f, 0.18f, 5.2f, 0.14f, 0.48f, 0.22f) // Green turf garden bed

            // Illuminated fountain/arch monument centerpiece
            val glow = 0.25f + nightGlow * 1.1f
            when (rb.monumentStyle) {
                0 -> { // Illuminated Yemeni Arch & Water Fountain
                    drawOrientedBox(s.x, s.y + 0.95f, s.z, s.headingRad, 3.2f, 0.45f, 3.2f, 0.12f, 0.62f, 0.88f, emR = 0.05f, emG = glow * 0.45f, emB = glow * 0.85f, metallic = 0.7f, roughness = 0.12f)
                    drawOrientedBox(s.x, s.y + 3.2f, s.z, s.headingRad, 0.85f, 4.4f, 0.85f, 0.92f, 0.84f, 0.68f, texId = textures.sanaaFacadeTex)
                    drawOrientedBox(s.x, s.y + 5.6f, s.z, s.headingRad, 1.45f, 0.55f, 1.45f, 0.96f, 0.72f, 0.18f, emR = glow, emG = glow * 0.72f, emB = glow * 0.15f)
                }
                1 -> { // Modern Palm Plaza & Illuminated Obelisk
                    drawCone(s.x, s.y + 3.6f, s.z, 2.2f, 5.8f, 0.86f, 0.88f, 0.92f)
                    drawOrientedBox(s.x, s.y + 6.5f, s.z, s.headingRad, 0.75f, 0.75f, 0.75f, 0.20f, 0.82f, 1.0f, emR = 0.1f, emG = glow * 0.85f, emB = glow * 1.15f)
                }
                else -> { // Sana'a Heritage Qamariya Clock Tower Monument
                    drawOrientedBox(s.x, s.y + 3.4f, s.z, s.headingRad, 2.4f, 5.8f, 2.4f, 0.92f, 0.82f, 0.66f, texId = textures.sanaaFacadeTex, emR = nightGlow * 0.35f, emG = nightGlow * 0.24f, emB = nightGlow * 0.08f)
                    drawOrientedBox(s.x, s.y + 6.5f, s.z, s.headingRad, 2.7f, 0.45f, 2.7f, 0.98f, 0.96f, 0.92f, emR = glow * 0.5f, emG = glow * 0.4f, emB = glow * 0.18f)
                }
            }
        }
    }

    /** 3D Roadside Speed Radar Cameras (رادار ساهر) with Overhead Speed-Limit Sign & Glowing Status Lens! */
    private fun drawSpeedRadars(centerDistM: Float, atmo: AtmosphereSnapshot) {
        val nightGlow = atmo.nightFactor
        for (rad in RoadSplineNetwork.SPEED_RADARS) {
            if (abs(rad.distanceM - centerDistM) > 190f) continue
            val s = RoadSplineNetwork.sampleAt(rad.distanceM)
            val poleLat = s.widthM * 0.5f + 1.15f
            val rx = s.x + s.rightX * poleLat
            val rz = s.z + s.rightZ * poleLat

            // Metallic radar pole + dual-lens Saher camera box
            drawOrientedBox(rx, s.y + 2.3f, rz, s.headingRad, 0.22f, 4.6f, 0.22f, 0.72f, 0.75f, 0.80f, metallic = 0.7f, roughness = 0.25f)
            drawOrientedBox(rx, s.y + 4.45f, rz, s.headingRad, 0.65f, 0.92f, 0.58f, 0.90f, 0.92f, 0.95f, metallic = 0.4f, roughness = 0.3f)
            // Radar flash lens + pulsing crimson/amber active sensor LED
            val pulse = if (((weatherAnimTime * 3.5f).toInt() % 2) == 0) 1.6f else 0.35f
            drawOrientedBox(rx - sin(s.headingRad) * 0.31f, s.y + 4.62f, rz - cos(s.headingRad) * 0.31f, s.headingRad, 0.32f, 0.26f, 0.06f, 0.12f, 0.14f, 0.18f, emR = pulse, emG = pulse * 0.15f)
            drawOrientedBox(rx - sin(s.headingRad) * 0.31f, s.y + 4.24f, rz - cos(s.headingRad) * 0.31f, s.headingRad, 0.36f, 0.20f, 0.06f, 1.0f, 0.96f, 0.88f, emR = 0.35f + nightGlow * 0.5f, emG = 0.35f + nightGlow * 0.5f, emB = 0.35f + nightGlow * 0.5f)
        }
    }

    private fun drawParkedCars(centerDistM: Float, livingWorld: LivingWorldSimulation) {
        for (car in livingWorld.parkedCars) {
            if (abs(car.distanceM - centerDistM) > (if (frameQuality == com.masalik.yemen.game.GraphicsQuality.LOW) 65f else 150f)) continue
            drawTrafficVehicleBody(
                car.worldX, car.worldY, car.worldZ, car.headingRad,
                car.type, car.colorR, car.colorG, car.colorB,
                braking = false, headlightsOn = false, wheelSpin = 0f
            )
        }
    }

    private fun drawYemeniPedestrians(centerDistM: Float, livingWorld: LivingWorldSimulation) {
        var drawn = 0
        val budget = when (frameQuality) {
            com.masalik.yemen.game.GraphicsQuality.LOW -> 8
            com.masalik.yemen.game.GraphicsQuality.MEDIUM -> 18
            else -> 36
        }
        for (ped in livingWorld.pedestrians.sortedBy { abs(it.distanceM - centerDistM) }) {
            if (abs(ped.distanceM - centerDistM) > 115f || drawn >= budget) continue
            drawPedestrianCharacter(ped); drawn++
        }
    }

    private fun drawPedestrianCharacter(ped: PedestrianAgent) {
        val swing = if (ped.isMoving) sin(ped.walkPhaseRad) * 0.22f else 0f
        val (robeR, robeG, robeB) = when (ped.style) {
            PedestrianStyle.THOBE_WHITE -> Triple(0.94f, 0.92f, 0.86f)
            PedestrianStyle.MAAWIS_COLORFUL -> Triple(0.90f, 0.88f, 0.82f)
            PedestrianStyle.SHAWL_ELDER -> Triple(0.86f, 0.80f, 0.68f)
            PedestrianStyle.ABAYA_CLASSIC -> Triple(0.11f, 0.12f, 0.15f)
        }
        val fwdX = sin(ped.headingRad); val fwdZ = cos(ped.headingRad)
        val rightX = cos(ped.headingRad); val rightZ = -sin(ped.headingRad)
        val (legR, legG, legB) = if (ped.style == PedestrianStyle.MAAWIS_COLORFUL) {
            Triple(ped.accentR, ped.accentG, ped.accentB)
        } else Triple(robeR * 0.9f, robeG * 0.9f, robeB * 0.9f)

        for (side in intArrayOf(-1, 1)) {
            val step = swing * side
            val hipX = ped.worldX + rightX * side * 0.11f
            val hipZ = ped.worldZ + rightZ * side * 0.11f
            val kneeX = hipX + fwdX * step * 0.65f
            val kneeZ = hipZ + fwdZ * step * 0.65f
            val footX = hipX + fwdX * step
            val footZ = hipZ + fwdZ * step
            val lift = if (ped.isMoving) maxOf(0f, cos(ped.walkPhaseRad + if (side > 0) PI.toFloat() else 0f)) * 0.09f else 0f
            drawLimbSegment(hipX, ped.worldY + 0.79f, hipZ, kneeX, ped.worldY + 0.43f + lift, kneeZ,
                0.17f, legR, legG, legB)
            drawLimbSegment(kneeX, ped.worldY + 0.43f + lift, kneeZ, footX, ped.worldY + 0.09f + lift, footZ,
                0.14f, legR, legG, legB)
            drawHumanPart(footX + fwdX * 0.06f, ped.worldY + 0.06f + lift, footZ + fwdZ * 0.06f,
                ped.headingRad, 0.17f, 0.10f, 0.28f, 0.13f, 0.11f, 0.10f)
        }
        if (ped.style == PedestrianStyle.MAAWIS_COLORFUL || ped.style == PedestrianStyle.ABAYA_CLASSIC || ped.style == PedestrianStyle.THOBE_WHITE) {
            val hemY = if (ped.style == PedestrianStyle.MAAWIS_COLORFUL) 0.61f else 0.48f
            drawHumanPart(ped.worldX, ped.worldY + hemY, ped.worldZ, ped.headingRad,
                0.47f, if (hemY > 0.5f) 0.48f else 0.80f, 0.31f, legR, legG, legB)
            if (ped.style == PedestrianStyle.MAAWIS_COLORFUL) for (stripe in 0..3) {
                drawHumanPart(ped.worldX, ped.worldY + 0.44f + stripe * 0.09f, ped.worldZ, ped.headingRad,
                    0.48f, 0.025f, 0.32f, ped.accentR * 0.6f, ped.accentG * 0.6f, ped.accentB * 0.6f)
            }
        }
        drawHumanPart(ped.worldX, ped.worldY + 1.02f, ped.worldZ, ped.headingRad, 0.48f, 0.76f, 0.29f, robeR, robeG, robeB)
        if (ped.style != PedestrianStyle.ABAYA_CLASSIC) {
            drawHumanPart(ped.worldX, ped.worldY + 0.82f, ped.worldZ, ped.headingRad, 0.46f, 0.11f, 0.28f, ped.accentR, ped.accentG, ped.accentB)
        }
        drawHumanPart(ped.worldX - rightX * 0.24f - fwdX * swing * 0.8f, ped.worldY + 1.0f, ped.worldZ - rightZ * 0.24f - fwdZ * swing * 0.8f, ped.headingRad, 0.12f, 0.54f, 0.13f, robeR * 0.95f, robeG * 0.95f, robeB * 0.95f)
        drawHumanPart(ped.worldX + rightX * 0.24f + fwdX * swing * 0.8f, ped.worldY + 1.0f, ped.worldZ + rightZ * 0.24f + fwdZ * swing * 0.8f, ped.headingRad, 0.12f, 0.54f, 0.13f, robeR * 0.95f, robeG * 0.95f, robeB * 0.95f)
        drawHumanPart(ped.worldX, ped.worldY + 1.43f, ped.worldZ, ped.headingRad, 0.25f, 0.26f, 0.25f, 0.76f, 0.56f, 0.42f)
        val (hatR, hatG, hatB) = when (ped.style) {
            PedestrianStyle.ABAYA_CLASSIC -> Triple(0.10f, 0.11f, 0.14f)
            PedestrianStyle.SHAWL_ELDER -> Triple(0.92f, 0.26f, 0.24f)
            else -> Triple(0.95f, 0.93f, 0.88f)
        }
        drawHumanPart(ped.worldX, ped.worldY + 1.57f, ped.worldZ, ped.headingRad, 0.30f, 0.12f, 0.30f, hatR, hatG, hatB)
    }

    private fun drawHumanPart(
        x: Float, y: Float, z: Float, heading: Float,
        sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float
    ) {
        drawOrientedMesh(humanMesh, x, y, z, heading, 0f, 0f, sx, sy, sz, r, g, b,
            metallic = 0f, roughness = 0.92f)
    }

    private fun drawAiTrafficAndRival(
        centerDistM: Float,
        snap: RenderFrameSnapshot,
        atmo: AtmosphereSnapshot
    ) {
        for (car in snap.livingWorld.trafficVehicles) {
            if (abs(car.distanceM - centerDistM) > (if (frameQuality == com.masalik.yemen.game.GraphicsQuality.LOW) 95f else 210f)) continue
            drawTrafficVehicleBody(
                x = car.worldX,
                y = car.worldY,
                z = car.worldZ,
                heading = car.headingRad,
                type = car.type,
                r = car.colorR,
                g = car.colorG,
                b = car.colorB,
                braking = car.braking,
                headlightsOn = car.headlightsOn,
                wheelSpin = car.wheelSpinRad,
                turnSignalDir = if (snap.blinkerOn) car.turnSignalDir else 0,
                steerAngleRad = car.steerAngleRad,
                pitchDeg = car.bodyPitchDeg,
                rollDeg = car.bodyRollDeg
            )
        }

        val rivalDist = snap.telemetry.aiRivalDistanceM
        if (rivalDist != null && abs(rivalDist - centerDistM) < 230f) {
            val rs = RoadSplineNetwork.sampleAt(rivalDist)
            val rx = rs.x - rs.rightX * 2.2f
            val rz = rs.z - rs.rightZ * 2.2f
            drawTrafficVehicleBody(
                x = rx, y = rs.y, z = rz, heading = rs.headingRad,
                type = TrafficVehicleType.SPORTS_COUPE,
                r = 0.96f, g = 0.68f, b = 0.08f,
                braking = false, headlightsOn = atmo.nightFactor > 0.25f,
                wheelSpin = weatherAnimTime * 22f,
                turnSignalDir = 0,
                steerAngleRad = (rs.curvatureRadPerM * 25f).coerceIn(-0.4f, 0.4f)
            )
        }
    }

    private fun drawTrafficVehicleBody(
        x: Float, y: Float, z: Float, heading: Float,
        type: TrafficVehicleType,
        r: Float, g: Float, b: Float,
        braking: Boolean,
        headlightsOn: Boolean,
        wheelSpin: Float,
        turnSignalDir: Int = 0,
        steerAngleRad: Float = 0f,
        pitchDeg: Float = 0f,
        rollDeg: Float = 0f
    ) {
        val (len, wid, bodyH, cabH) = when (type) {
            TrafficVehicleType.SEDAN -> Quad4(4.55f, 1.84f, 0.64f, 0.54f)
            TrafficVehicleType.SPORTS_COUPE -> Quad4(4.48f, 1.96f, 0.54f, 0.44f)
            TrafficVehicleType.SUV_LUXURY -> Quad4(4.98f, 2.02f, 0.88f, 0.72f)
            TrafficVehicleType.TAXI_YEMEN -> Quad4(4.50f, 1.82f, 0.64f, 0.55f)
            TrafficVehicleType.HATCHBACK -> Quad4(3.92f, 1.78f, 0.66f, 0.56f)
            TrafficVehicleType.PICKUP -> Quad4(5.08f, 1.92f, 0.78f, 0.68f)
            TrafficVehicleType.MINIBUS -> Quad4(5.40f, 2.00f, 1.12f, 0.84f)
            TrafficVehicleType.TRUCK -> Quad4(6.35f, 2.24f, 1.45f, 1.12f)
            TrafficVehicleType.POLICE -> Quad4(4.68f, 1.88f, 0.66f, 0.56f)
            TrafficVehicleType.AMBULANCE -> Quad4(5.55f, 2.04f, 1.16f, 0.86f)
        }
        val wheelRad = when (type) {
            TrafficVehicleType.SUV_LUXURY, TrafficVehicleType.PICKUP, TrafficVehicleType.TRUCK -> 0.43f
            TrafficVehicleType.SPORTS_COUPE -> 0.36f
            else -> 0.38f
        }
        val fwdX = sin(heading); val fwdZ = cos(heading)
        val rightX = cos(heading); val rightZ = -sin(heading)

        // Soft Ambient Occlusion Contact Shadow grounding the car onto the street
        drawContactShadow(x, y + 0.055f, z, heading, wid * 1.20f, len * 1.10f)

        val paintMet = when (type) {
            TrafficVehicleType.SPORTS_COUPE, TrafficVehicleType.SUV_LUXURY -> 0.76f
            TrafficVehicleType.TRUCK -> 0.32f
            else -> 0.64f
        }
        val paintRough = when (type) {
            TrafficVehicleType.SPORTS_COUPE, TrafficVehicleType.SUV_LUXURY -> 0.14f
            TrafficVehicleType.TRUCK -> 0.42f
            else -> 0.18f
        }

        // Sculpted metallic lower chassis with real dynamic suspension pitch & cornering body roll!
        val lowerY = y + wheelRad * 0.92f + bodyH * 0.5f
        drawOrientedMesh(
            roundedMesh,
            x, lowerY, z, heading, pitchDeg, rollDeg,
            wid, bodyH, len, r, g, b, metallic = paintMet, roughness = paintRough
        )
        // Lower dark bumper / side-skirt trim + front grille
        drawOrientedMesh(
            cubeMesh,
            x, y + wheelRad * 0.92f + 0.10f, z, heading, pitchDeg, rollDeg,
            wid * 1.015f, 0.18f, len * 0.97f, 0.11f, 0.12f, 0.15f, metallic = 0.42f, roughness = 0.44f
        )
        drawOrientedMesh(
            cubeMesh,
            x + fwdX * (len * 0.498f), lowerY, z + fwdZ * (len * 0.498f), heading, pitchDeg, rollDeg,
            wid * 0.54f, bodyH * 0.44f, 0.08f, 0.08f, 0.09f, 0.12f, metallic = 0.72f, roughness = 0.22f
        )

        val cabZOff = when (type) {
            TrafficVehicleType.PICKUP -> 0.56f
            TrafficVehicleType.TRUCK -> 1.70f
            TrafficVehicleType.HATCHBACK -> -0.26f
            TrafficVehicleType.SPORTS_COUPE -> -0.22f
            TrafficVehicleType.SUV_LUXURY -> -0.28f
            else -> -0.14f
        }
        val cabLen = when (type) {
            TrafficVehicleType.PICKUP -> 1.88f
            TrafficVehicleType.MINIBUS, TrafficVehicleType.AMBULANCE -> len * 0.88f
            TrafficVehicleType.SUV_LUXURY -> len * 0.68f
            TrafficVehicleType.HATCHBACK -> len * 0.66f
            TrafficVehicleType.SPORTS_COUPE -> len * 0.48f
            TrafficVehicleType.TRUCK -> 1.82f
            else -> len * 0.56f
        }
        // Aerodynamic sloped cabin glasshouse
        drawOrientedMesh(
            if (type in listOf(TrafficVehicleType.TRUCK, TrafficVehicleType.PICKUP, TrafficVehicleType.MINIBUS, TrafficVehicleType.AMBULANCE, TrafficVehicleType.SUV_LUXURY)) utilityCabinMesh else cabinMesh,
            x + fwdX * cabZOff, y + wheelRad * 0.92f + bodyH + cabH * 0.44f, z + fwdZ * cabZOff,
            heading, pitchDeg, rollDeg, wid * 0.90f, cabH, cabLen,
            0.07f, 0.13f, 0.21f, metallic = 0.90f, roughness = 0.07f
        )
        // Painted or contrasting gloss-black roof cap
        val roofY = y + wheelRad * 0.92f + bodyH + cabH * 0.90f
        val darkRoof = type == TrafficVehicleType.SPORTS_COUPE || type == TrafficVehicleType.HATCHBACK
        val whiteRoof = type == TrafficVehicleType.MINIBUS || type == TrafficVehicleType.AMBULANCE
        drawOrientedMesh(
            cubeMesh,
            x + fwdX * (cabZOff - 0.06f), roofY, z + fwdZ * (cabZOff - 0.06f),
            heading, pitchDeg, rollDeg, wid * 0.72f, 0.09f, cabLen * 0.64f,
            if (whiteRoof) 0.96f else if (darkRoof) 0.09f else r,
            if (whiteRoof) 0.96f else if (darkRoof) 0.10f else g,
            if (whiteRoof) 0.98f else if (darkRoof) 0.13f else b,
            metallic = paintMet, roughness = paintRough
        )

        // Body-colored pillars divide the glasshouse; handles and mirror housings provide scale.
        for (side in intArrayOf(-1, 1)) {
            val sx = x + rightX * (side * wid * 0.46f)
            val sz = z + rightZ * (side * wid * 0.46f)
            drawOrientedMesh(roundedMesh, sx, roofY - cabH * 0.48f, sz, heading, pitchDeg, rollDeg,
                0.055f, cabH * 0.92f, 0.085f, r * 0.50f, g * 0.50f, b * 0.50f, metallic = 0.3f)
            drawOrientedMesh(roundedMesh, sx - fwdX * 0.26f, lowerY + bodyH * 0.33f, sz - fwdZ * 0.26f,
                heading, pitchDeg, rollDeg, 0.065f, 0.055f, 0.25f, 0.64f, 0.66f, 0.68f, metallic = 0.8f)
            drawOrientedMesh(roundedMesh, x + rightX * (side * wid * 0.57f) + fwdX * (cabZOff + cabLen * 0.36f),
                roofY - cabH * 0.58f, z + rightZ * (side * wid * 0.57f) + fwdZ * (cabZOff + cabLen * 0.36f),
                heading, pitchDeg, rollDeg, 0.25f, 0.17f, 0.31f, r, g, b, metallic = 0.45f, roughness = 0.27f)
        }

        // Distinct Vehicle-Class Signature Details!
        when (type) {
            TrafficVehicleType.SPORTS_COUPE -> {
                // Carbon-Fiber GT Rear Wing & Twin Chrome Exhaust Tips
                drawOrientedMesh(
                    cubeMesh,
                    x - fwdX * (len * 0.44f), y + wheelRad * 0.92f + bodyH + 0.26f, z - fwdZ * (len * 0.44f),
                    heading, pitchDeg, rollDeg, wid * 0.94f, 0.07f, 0.26f,
                    0.10f, 0.11f, 0.14f, metallic = 0.72f, roughness = 0.18f
                )
                for (sx in intArrayOf(-1, 1)) {
                    drawOrientedMesh(
                        cubeMesh,
                        x - fwdX * (len * 0.44f) + rightX * (sx * wid * 0.30f), y + wheelRad * 0.92f + bodyH + 0.12f, z - fwdZ * (len * 0.44f) + rightZ * (sx * wid * 0.30f),
                        heading, pitchDeg, rollDeg, 0.07f, 0.24f, 0.16f, 0.12f, 0.13f, 0.16f
                    )
                }
            }
            TrafficVehicleType.SUV_LUXURY -> {
                // Chrome Roof Rails + Rear Door Mounted Spare Wheel
                for (sx in intArrayOf(-1, 1)) {
                    drawOrientedMesh(
                        cubeMesh,
                        x + fwdX * (cabZOff - 0.08f) + rightX * (sx * wid * 0.33f), roofY + 0.09f, z + fwdZ * (cabZOff - 0.08f) + rightZ * (sx * wid * 0.33f),
                        heading, pitchDeg, rollDeg, 0.07f, 0.07f, cabLen * 0.62f,
                        0.84f, 0.87f, 0.92f, metallic = 0.90f, roughness = 0.14f
                    )
                }
                drawOrientedMesh(
                    cubeMesh,
                    x - fwdX * (len * 0.52f), lowerY + 0.22f, z - fwdZ * (len * 0.52f),
                    heading, pitchDeg, rollDeg, 0.68f, 0.68f, 0.26f,
                    0.14f, 0.16f, 0.20f, metallic = 0.35f, roughness = 0.55f
                )
            }
            TrafficVehicleType.TAXI_YEMEN -> {
                // Authentic Yemeni Taxi Dark Side Checkered Trim + Illuminated Glowing Amber Roof Taxi Sign
                drawOrientedMesh(
                    cubeMesh,
                    x, lowerY + 0.04f, z, heading, pitchDeg, rollDeg,
                    wid * 1.016f, 0.12f, len * 0.86f, 0.12f, 0.13f, 0.16f
                )
                drawOrientedMesh(
                    cubeMesh,
                    x + fwdX * (cabZOff + 0.08f), roofY + 0.13f, z + fwdZ * (cabZOff + 0.08f),
                    heading, pitchDeg, rollDeg, 0.58f, 0.18f, 0.24f,
                    1.0f, 0.88f, 0.22f, emR = 1.45f, emG = 1.10f, emB = 0.18f
                )
            }
            TrafficVehicleType.PICKUP -> {
                // Steel Bed Roll-Bar & Timber Mountain Cargo Crate
                drawOrientedMesh(
                    cubeMesh,
                    x - fwdX * (len * 0.10f), y + wheelRad * 0.92f + bodyH + 0.42f, z - fwdZ * (len * 0.10f),
                    heading, pitchDeg, rollDeg, wid * 0.88f, 0.10f, 0.12f, 0.14f, 0.15f, 0.18f, metallic = 0.7f
                )
                drawOrientedMesh(
                    cubeMesh,
                    x - fwdX * (len * 0.26f), y + wheelRad * 0.92f + bodyH + 0.22f, z - fwdZ * (len * 0.26f),
                    heading, pitchDeg, rollDeg, wid * 0.78f, 0.44f, len * 0.32f, 0.62f, 0.44f, 0.26f, roughness = 0.75f
                )
            }
            TrafficVehicleType.MINIBUS -> {
                // Classic Yemeni Passenger Dabab Teal/Emerald Side Stripe
                drawOrientedMesh(
                    cubeMesh,
                    x, lowerY + 0.10f, z, heading, pitchDeg, rollDeg,
                    wid * 1.018f, 0.22f, len * 0.90f, 0.08f, 0.58f, 0.48f, metallic = 0.45f, roughness = 0.28f
                )
            }
            TrafficVehicleType.TRUCK -> {
                drawOrientedMesh(
                    cubeMesh,
                    x - fwdX * 1.1f, y + wheelRad * 0.92f + bodyH + 0.85f, z - fwdZ * 1.1f,
                    heading, pitchDeg, rollDeg, wid * 0.96f, 1.85f, 3.65f, 0.92f, 0.90f, 0.86f
                )
                // Glowing amber truck cab roof marker lights
                for (sx in intArrayOf(-1, 0, 1)) {
                    drawOrientedBox(
                        x + fwdX * (cabZOff + 0.85f) + rightX * (sx * 0.55f), roofY + 0.10f, z + fwdZ * (cabZOff + 0.85f) + rightZ * (sx * 0.55f),
                        heading, 0.14f, 0.10f, 0.10f, 1f, 0.72f, 0.10f, emR = 1.4f, emG = 0.85f, emB = 0.08f
                    )
                }
            }
            TrafficVehicleType.POLICE, TrafficVehicleType.AMBULANCE -> {
                val isPolice = type == TrafficVehicleType.POLICE
                val stripeR = if (isPolice) 0.06f else 0.92f
                val stripeG = if (isPolice) 0.18f else 0.10f
                val stripeB = if (isPolice) 0.52f else 0.12f
                drawOrientedMesh(
                    cubeMesh,
                    x, lowerY + 0.06f, z,
                    heading, pitchDeg, rollDeg, wid * 1.018f, 0.18f, len * 0.86f,
                    stripeR, stripeG, stripeB, metallic = 0.35f, roughness = 0.30f
                )
                val strobeLeftRed = ((weatherAnimTime * 7.2f).toInt() % 2) == 0
                val barY = roofY + 0.14f
                val barZ = cabZOff + (if (isPolice) 0.02f else cabLen * 0.26f)
                drawOrientedBox(
                    x + fwdX * barZ, barY - 0.03f, z + fwdZ * barZ,
                    heading, wid * 0.66f, 0.06f, 0.24f,
                    0.14f, 0.16f, 0.20f, metallic = 0.7f, roughness = 0.25f
                )
                val redEm = if (strobeLeftRed) 2.6f else 0.22f
                drawOrientedBox(
                    x + fwdX * barZ - rightX * (wid * 0.22f), barY + 0.05f, z + fwdZ * barZ - rightZ * (wid * 0.22f),
                    heading, wid * 0.26f, 0.12f, 0.20f,
                    1.0f, 0.08f, 0.10f, emR = redEm, emG = 0.04f, emB = 0.06f
                )
                val blueEm = if (!strobeLeftRed) 2.6f else 0.22f
                drawOrientedBox(
                    x + fwdX * barZ + rightX * (wid * 0.22f), barY + 0.05f, z + fwdZ * barZ + rightZ * (wid * 0.22f),
                    heading, wid * 0.26f, 0.12f, 0.20f,
                    0.08f, 0.42f, 1.0f, emR = 0.06f, emG = blueEm * 0.35f, emB = blueEm
                )
            }
            else -> Unit
        }

        if (type == TrafficVehicleType.PICKUP) {
            val bedX = x - fwdX * len * 0.27f; val bedZ = z - fwdZ * len * 0.27f
            drawOrientedBox(bedX, lowerY + bodyH * 0.51f, bedZ, heading,
                wid * 0.82f, 0.06f, len * 0.35f, 0.16f, 0.17f, 0.16f)
            for (side in intArrayOf(-1, 1)) {
                drawOrientedMesh(roundedMesh, bedX + rightX * side * wid * 0.43f,
                    lowerY + bodyH * 0.70f, bedZ + rightZ * side * wid * 0.43f,
                    heading, pitchDeg, rollDeg, 0.13f, 0.28f, len * 0.38f, r, g, b)
            }
        }
        if (type == TrafficVehicleType.MINIBUS) {
            for (side in intArrayOf(-1, 1)) for (panel in -2..2) {
                drawOrientedBox(x + rightX * side * wid * 0.46f + fwdX * panel * cabLen * 0.15f,
                    roofY - cabH * 0.5f, z + rightZ * side * wid * 0.46f + fwdZ * panel * cabLen * 0.15f,
                    heading, 0.06f, cabH, 0.07f, r, g, b)
            }
        }

        // 4 High-Res Alloy Wheels — Front wheels visibly steer into curves & lane changes!
        val wb = len * 0.32f
        val tr = wid * 0.48f
        for (sx in intArrayOf(-1, 1)) {
            drawWheel(
                x + rightX * (sx * tr) + fwdX * wb,
                y + wheelRad,
                z + rightZ * (sx * tr) + fwdZ * wb,
                heading, steerAngleRad, wheelSpin, wheelRad, 0.27f
            )
            drawWheel(
                x + rightX * (sx * tr) - fwdX * wb,
                y + wheelRad,
                z + rightZ * (sx * tr) - fwdZ * wb,
                heading, 0f, wheelSpin, wheelRad, 0.28f
            )
        }

        // Crisp LED Headlights, Tail/Brake Lights & AI Blinking Amber Turn Signals
        val hlEm = if (headlightsOn) 1.75f else 0.18f
        val blEm = if (braking) 2.0f else 0.32f
        val lightY = y + wheelRad * 0.92f + bodyH * 0.44f
        for (sx in intArrayOf(-1, 1)) {
            drawOrientedBox(
                x + fwdX * (len * 0.50f) + rightX * (sx * wid * 0.35f), lightY, z + fwdZ * (len * 0.50f) + rightZ * (sx * wid * 0.35f),
                heading, 0.38f, 0.15f, 0.08f, 1f, 0.97f, 0.90f, emR = hlEm, emG = hlEm * 0.95f, emB = hlEm * 0.82f
            )
            drawOrientedBox(
                x - fwdX * (len * 0.50f) + rightX * (sx * wid * 0.35f), lightY + 0.02f, z - fwdZ * (len * 0.50f) + rightZ * (sx * wid * 0.35f),
                heading, 0.42f, 0.15f, 0.08f, 0.98f, 0.10f, 0.08f, emR = blEm, emG = 0.02f, emB = 0.02f
            )
            if (turnSignalDir != 0 && sx == turnSignalDir) {
                drawOrientedBox(
                    x + fwdX * (len * 0.495f) + rightX * (sx * wid * 0.46f), lightY, z + fwdZ * (len * 0.495f) + rightZ * (sx * wid * 0.46f),
                    heading, 0.16f, 0.15f, 0.12f, 1f, 0.66f, 0.04f, emR = 2.0f, emG = 1.25f, emB = 0.05f
                )
                drawOrientedBox(
                    x - fwdX * (len * 0.495f) + rightX * (sx * wid * 0.46f), lightY + 0.02f, z - fwdZ * (len * 0.495f) + rightZ * (sx * wid * 0.46f),
                    heading, 0.16f, 0.15f, 0.12f, 1f, 0.66f, 0.04f, emR = 2.0f, emG = 1.25f, emB = 0.05f
                )
            }
        }
        // Full-width sports LED tail light bar on Sports Coupe & Luxury SUV
        if (type == TrafficVehicleType.SPORTS_COUPE || type == TrafficVehicleType.SUV_LUXURY) {
            drawOrientedBox(
                x - fwdX * (len * 0.502f), lightY + 0.02f, z - fwdZ * (len * 0.502f),
                heading, wid * 0.82f, 0.06f, 0.06f, 0.98f, 0.08f, 0.08f, emR = blEm * 0.85f, emG = 0.02f, emB = 0.02f
            )
        }
    }

    /** Procedural Player Vehicle with Metallic Clearcoat, Sloped Windshield, Side Mirrors, DRLs, Exhausts & Full 3D Suspension Pitch/Roll. */
    private fun drawDetailedPlayerVehicle(
        t: VehicleTelemetry,
        vehicleId: VehicleId,
        custom: VehicleCustomization,
        blinkerOn: Boolean,
        atmo: AtmosphereSnapshot
    ) {
        val spec = VehicleFleetCatalog.specOf(vehicleId)
        val parsed = runCatching { Color.parseColor(custom.paintColorHex) }.getOrDefault(Color.rgb(200, 114, 54))
        val mud = t.damage.dirtMudLevel * 0.45f
        val pr = (Color.red(parsed) / 255f) * (1f - mud) + 0.36f * mud
        val pg = (Color.green(parsed) / 255f) * (1f - mud) + 0.28f * mud
        val pb = (Color.blue(parsed) / 255f) * (1f - mud) + 0.18f * mud

        val (paintMet, paintRough) = when (custom.paintFinish) {
            PaintFinishType.METALLIC -> 0.72f to 0.16f
            PaintFinishType.PEARL -> 0.52f to 0.10f
            PaintFinishType.MATTE -> 0.12f to 0.70f
        }

        val len = spec.bodyLengthM
        val wid = spec.bodyWidthM
        val hgt = spec.bodyHeightM
        // Include subtle drift slip yaw so cornering looks authentically dynamic!
        val visualHeading = t.headingRad + (t.driftSlipAngleDeg * PI.toFloat() / 180f) * 0.35f
        val pitchDeg = t.pitchDeg
        val rollDeg = t.rollDeg
        val fwdX = sin(visualHeading); val fwdZ = cos(visualHeading)
        val rightX = cos(visualHeading); val rightZ = -sin(visualHeading)
        val baseY = t.posY + spec.wheelRadiusM * 0.90f + t.suspensionBounceM

        // 1. Soft Ground Contact Shadow
        drawContactShadow(t.posX, t.posY + 0.06f, t.posZ, visualHeading, wid * 1.24f, len * 1.12f)

        // 2. Sculpted Aerodynamic Lower Body + Dark Metallic Lower Valance/Skirt
        val lowerH = hgt * 0.43f
        val isPickup = vehicleId in listOf(VehicleId.SAQR, VehicleId.BADIYA, VehicleId.MUNQIDH, VehicleId.NIMR)
        val isTruck = vehicleId == VehicleId.JABAL
        val isBus = vehicleId in listOf(VehicleId.HAFILAT, VehicleId.QAFILA)
        val lowDetail = frameQuality == com.masalik.yemen.game.GraphicsQuality.LOW
        val bodyMesh = bodies.getOrPut(vehicleId to lowDetail) {
            val lod = if (lowDetail) "low" else "high"
            val bytes = assets.open("fleet/${vehicleId.name.lowercase()}_${lod}.mesh").use { it.readBytes() }
            val floats = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            GpuMesh(floats)
        }

        drawOrientedMesh(
            bodyMesh,
            t.posX, baseY + lowerH * 0.52f, t.posZ,
            visualHeading, pitchDeg, rollDeg,
            wid, lowerH, len, pr, pg, pb,
            metallic = paintMet, roughness = paintRough
        )
        // Lower dark carbon/steel rock-slider & rear diffuser skirt
        drawOrientedMesh(
            roundedMesh,
            t.posX, baseY + 0.12f, t.posZ,
            visualHeading, pitchDeg, rollDeg,
            wid * .88f, 0.15f, len * 0.91f, 0.10f, 0.11f, 0.13f,
            metallic = 0.38f, roughness = 0.45f
        )

        // 3. Sculpted Front Chrome/Dark Grille & Hood Crease
        drawOrientedMesh(
            roundedMesh,
            t.posX + fwdX * (len * 0.495f), baseY + lowerH * 0.46f, t.posZ + fwdZ * (len * 0.495f),
            visualHeading, pitchDeg, rollDeg,
            wid * 0.64f, lowerH * 0.50f, 0.12f, 0.08f, 0.09f, 0.11f,
            metallic = 0.65f, roughness = 0.24f
        )
        // Chrome Grille Horizontal Slats + Emblem Badge
        drawOrientedMesh(
            roundedMesh,
            t.posX + fwdX * (len * 0.505f), baseY + lowerH * 0.50f, t.posZ + fwdZ * (len * 0.505f),
            visualHeading, pitchDeg, rollDeg,
            wid * 0.58f, 0.06f, 0.06f, 0.85f, 0.88f, 0.92f,
            metallic = 0.92f, roughness = 0.10f
        )
        drawOrientedMesh(
            roundedMesh,
            t.posX + fwdX * (len * 0.512f), baseY + lowerH * 0.50f, t.posZ + fwdZ * (len * 0.512f),
            visualHeading, pitchDeg, rollDeg,
            0.22f, 0.16f, 0.06f, 0.96f, 0.72f, 0.16f,
            metallic = 0.90f, roughness = 0.12f
        )

        // 4. Sculpted Sloped Panoramic Cabin Greenhouse + Roof Cap + Side Wing Mirrors
        val cabH = hgt * 0.43f
        val cabLen = when {
            isBus -> len * 0.90f
            isPickup -> len * 0.42f
            isTruck -> len * 0.34f
            else -> len * 0.58f
        }
        val cabOffset = when {
            isPickup -> len * 0.12f
            isTruck -> len * 0.26f
            else -> -len * 0.07f
        }
        val profile = when {
            isBus || isTruck -> Coachwork.Profile.COMMERCIAL
            isPickup -> Coachwork.Profile.PICKUP
            vehicleId == VehicleId.BARQ -> Coachwork.Profile.COUPE
            vehicleId == VehicleId.SHAHIQ || vehicleId == VehicleId.RAHHAL -> Coachwork.Profile.WAGON
            else -> Coachwork.Profile.SALOON
        }
        val canopy = canopies.getOrPut(profile) { Coachwork.build(profile).mapValues { GpuMesh(it.value) } }
        for ((material, mesh) in canopy) {
            val paint = material == Coachwork.Material.PAINT
            val glass = material == Coachwork.Material.GLAZING
            val chrome = material == Coachwork.Material.BRIGHTWORK
            val r = when { paint -> pr; chrome -> .68f; glass -> .62f; else -> .045f }
            val g = when { paint -> pg; chrome -> .73f; glass -> .76f; else -> .05f }
            val b = when { paint -> pb; chrome -> .76f; glass -> .85f; else -> .055f }
            drawOrientedMesh(mesh,t.posX+fwdX*cabOffset,baseY+lowerH+cabH*.44f,t.posZ+fwdZ*cabOffset,
                visualHeading,pitchDeg,rollDeg,wid*.94f,cabH,cabLen,r,g,b,
                texId=if (glass) textures.autoGlassTex else textures.whiteTex,
                metallic=when { paint -> paintMet; chrome -> .86f; glass -> .58f; else -> .02f },
                roughness=when { paint -> paintRough; glass -> .10f; chrome -> .21f; else -> .81f })
        }
        // Left & Right Aerodynamic Side Mirrors
        for (sx in intArrayOf(-1, 1)) {
            drawOrientedMesh(
                roundedMesh,
                t.posX + fwdX * (cabOffset + cabLen * 0.28f) + rightX * (sx * (wid * 0.53f)),
                baseY + lowerH + 0.16f,
                t.posZ + fwdZ * (cabOffset + cabLen * 0.28f) + rightZ * (sx * (wid * 0.53f)),
                visualHeading, pitchDeg, rollDeg,
                0.24f, 0.14f, 0.18f, pr, pg, pb, metallic = paintMet, roughness = paintRough
            )
        }

        if (isPickup && custom.pickupAccessory == PickupAccessoryType.NONE) {
            val bedX = t.posX - fwdX * len * 0.26f; val bedZ = t.posZ - fwdZ * len * 0.26f
            drawOrientedBox(bedX, baseY + lowerH + 0.04f, bedZ, visualHeading,
                wid * 0.82f, 0.06f, len * 0.35f, 0.15f, 0.17f, 0.16f)
            for (side in intArrayOf(-1, 1)) drawOrientedMesh(roundedMesh,
                bedX + rightX * side * wid * 0.43f, baseY + lowerH + 0.17f,
                bedZ + rightZ * side * wid * 0.43f, visualHeading, pitchDeg, rollDeg,
                0.14f, 0.30f, len * 0.38f, pr, pg, pb, metallic = paintMet, roughness = paintRough)
        }
        // Door pulls follow the belt line, including the rear door on longer cabs.
        for (side in intArrayOf(-1, 1)) {
            val doors = if (isTruck || vehicleId == VehicleId.BARQ) 1 else 2
            for (i in 0 until doors) {
                val along = cabOffset + cabLen*(.12f-.37f*i)
                drawOrientedMesh(roundedMesh,t.posX+rightX*side*wid*.492f+fwdX*along,
                    baseY+lowerH*.85f,t.posZ+rightZ*side*wid*.492f+fwdZ*along,visualHeading,
                    pitchDeg,rollDeg,.045f,.052f,.17f,.42f,.44f,.45f,metallic=.67f,roughness=.28f)
            }
        }

        // 5. Dual Chrome Rear Exhaust Tips
        for (sx in intArrayOf(-1, 1)) {
            drawOrientedMesh(
                cubeMesh,
                t.posX - fwdX * (len * 0.505f) + rightX * (sx * wid * 0.30f),
                baseY + 0.14f,
                t.posZ - fwdZ * (len * 0.505f) + rightZ * (sx * wid * 0.30f),
                visualHeading, pitchDeg, rollDeg,
                0.15f, 0.11f, 0.18f, 0.80f, 0.84f, 0.88f, metallic = 0.92f, roughness = 0.12f
            )
        }

        // 6. Truck Cargo / Pickup Accessories / Bullbar / Roof Rack / Rescue Lightbar
        if (isTruck) {
            drawOrientedMesh(cubeMesh, t.posX - fwdX * (len * 0.18f), baseY + lowerH + 0.95f, t.posZ - fwdZ * (len * 0.18f), visualHeading, pitchDeg, rollDeg, wid * 0.95f, 1.95f, len * 0.56f, 0.90f, 0.88f, 0.82f)
        } else if (isPickup && custom.pickupAccessory != PickupAccessoryType.NONE) {
            val accH = if (custom.pickupAccessory == PickupAccessoryType.CANOPY_TENT) cabH else 0.65f
            drawOrientedMesh(
                cubeMesh,
                t.posX - fwdX * (len * 0.24f), baseY + lowerH + accH * 0.5f, t.posZ - fwdZ * (len * 0.24f),
                visualHeading, pitchDeg, rollDeg, wid * 0.86f, accH, len * 0.38f,
                if (custom.pickupAccessory == PickupAccessoryType.CANOPY_TENT) 0.78f else 0.18f,
                if (custom.pickupAccessory == PickupAccessoryType.CANOPY_TENT) 0.70f else 0.19f,
                if (custom.pickupAccessory == PickupAccessoryType.CANOPY_TENT) 0.54f else 0.22f
            )
        }
        if (custom.bullbarStyle != BullbarStyle.NONE) {
            drawOrientedMesh(cubeMesh, t.posX + fwdX * (len * 0.53f), baseY + 0.32f, t.posZ + fwdZ * (len * 0.53f), visualHeading, pitchDeg, rollDeg, wid * 0.94f, 0.34f, 0.24f, 0.14f, 0.15f, 0.18f, metallic = 0.72f, roughness = 0.25f)
        }
        if (custom.roofRackInstalled) {
            val roofY = baseY + lowerH + cabH + 0.04f
            drawOrientedMesh(cubeMesh, t.posX + fwdX * cabOffset, roofY, t.posZ + fwdZ * cabOffset, visualHeading, pitchDeg, rollDeg, wid * 0.70f, 0.10f, cabLen * 0.62f, 0.15f, 0.16f, 0.20f, metallic = 0.6f, roughness = 0.3f)
            if (custom.roofCargoBoxInstalled) {
                drawOrientedMesh(cabinMesh, t.posX + fwdX * cabOffset, roofY + 0.22f, t.posZ + fwdZ * cabOffset, visualHeading, pitchDeg, rollDeg, wid * 0.50f, 0.32f, cabLen * 0.54f, 0.14f, 0.16f, 0.20f, metallic = 0.7f, roughness = 0.18f)
            }
        }
        if (vehicleId == VehicleId.BARQ) {
            // Signature Carbon-Fiber GT Rear Wing & Twin Aerodynamic Struts for the Sports Car!
            drawOrientedMesh(
                cubeMesh,
                t.posX - fwdX * (len * 0.44f), baseY + lowerH + 0.30f, t.posZ - fwdZ * (len * 0.44f),
                visualHeading, pitchDeg, rollDeg, wid * 0.96f, 0.07f, 0.30f,
                0.09f, 0.10f, 0.13f, metallic = 0.78f, roughness = 0.16f
            )
            for (sx in intArrayOf(-1, 1)) {
                drawOrientedMesh(
                    cubeMesh,
                    t.posX - fwdX * (len * 0.44f) + rightX * (sx * wid * 0.31f), baseY + lowerH + 0.15f, t.posZ - fwdZ * (len * 0.44f) + rightZ * (sx * wid * 0.31f),
                    visualHeading, pitchDeg, rollDeg, 0.07f, 0.28f, 0.16f, 0.11f, 0.12f, 0.15f
                )
            }
        } else if (vehicleId in listOf(VehicleId.SHAHIQ, VehicleId.RAHHAL)) {
            val spareDiameter = spec.wheelRadiusM * 1.86f
            drawOrientedMesh(wheelMesh,t.posX-fwdX*len*.518f,baseY+lowerH*.73f,
                t.posZ-fwdZ*len*.518f,visualHeading+PI.toFloat()*.5f,pitchDeg,rollDeg,
                .26f,spareDiameter,spareDiameter,.86f,.86f,.85f,
                texId=textures.alloyRimTex,metallic=.21f,roughness=.63f)
        } else if (vehicleId in listOf(VehicleId.MADINA, VehicleId.ASIL, VehicleId.SAHIL)) {
            // Sleek Brushed-Chrome Beltline Accent Molding
            drawOrientedMesh(
                cubeMesh,
                t.posX, baseY + lowerH * 0.92f, t.posZ,
                visualHeading, pitchDeg, rollDeg, wid * 1.015f, 0.05f, len * 0.84f,
                0.86f, 0.89f, 0.94f, metallic = 0.94f, roughness = 0.10f
            )
        }
        if (vehicleId == VehicleId.MUNQIDH) {
            val roofY = baseY + lowerH + cabH + 0.16f
            val flashA = ((weatherAnimTime * 7f).toInt() % 2 == 0)
            drawOrientedBox(t.posX + fwdX * cabOffset - rightX * 0.38f, roofY, t.posZ + fwdZ * cabOffset - rightZ * 0.38f, visualHeading, 0.45f, 0.20f, 0.26f, 1f, 0.55f, 0.05f, emR = if (flashA) 1.9f else 0.1f, emG = if (flashA) 0.8f else 0.05f)
            drawOrientedBox(t.posX + fwdX * cabOffset + rightX * 0.38f, roofY, t.posZ + fwdZ * cabOffset + rightZ * 0.38f, visualHeading, 0.45f, 0.20f, 0.26f, 0.12f, 0.55f, 1f, emB = if (!flashA) 1.9f else 0.1f, emG = if (!flashA) 0.7f else 0.05f)
        }

        if (t.jobPayloadKg in 275f..300f && (isPickup || isTruck)) {
            for (side in listOf(-1,1)) {
                val x = t.posX - fwdX * len*.30f + rightX * side * wid*.20f
                val z = t.posZ - fwdZ * len*.30f + rightZ * side * wid*.20f
                drawOrientedMesh(roundedMesh,x,baseY+lowerH+.20f,z,visualHeading,pitchDeg,rollDeg,
                    wid*.36f,.46f,len*.26f,.57f,.42f,.25f)
                drawOrientedBox(x,baseY+lowerH+.44f,z,visualHeading,wid*.38f,.035f,.09f,.24f,.21f,.15f)
            }
        }
        // 7. 4 High-Detail 24-Segment Alloy Wheels with Red Brake Calipers & Steering Angle
        val wb = spec.wheelbaseM * 0.5f
        val tr = spec.trackWidthM * 0.5f
        for (sx in intArrayOf(-1, 1)) {
            drawWheel(
                t.posX + rightX * (sx * tr) + fwdX * wb,
                t.posY + spec.wheelRadiusM + (if (sx < 0) t.wheelHeights.frontLeft else t.wheelHeights.frontRight),
                t.posZ + rightZ * (sx * tr) + fwdZ * wb,
                visualHeading, t.frontSteerRad, t.wheelRotationRad, spec.wheelRadiusM, 0.33f
            )
            drawWheel(
                t.posX + rightX * (sx * tr) - fwdX * wb,
                t.posY + spec.wheelRadiusM + (if (sx < 0) t.wheelHeights.rearLeft else t.wheelHeights.rearRight),
                t.posZ + rightZ * (sx * tr) - fwdZ * wb,
                visualHeading, 0f, t.wheelRotationRad, spec.wheelRadiusM, 0.35f
            )
        }

        // 8. Signature LED DRLs, High/Low Beam Headlights, Full-Width Rear LED Taillight Bar & Blinkers
        val hlOn = t.headlightsOn || atmo.nightFactor > 0.32f || t.inTunnel
        val hlEm = when {
            t.headlightBeamMode == HeadlightBeamMode.HIGH_BEAM -> 2.4f
            hlOn -> 1.65f
            else -> 0.55f // Modern crisp white DRL signature always visible!
        }
        val blEm = if (t.brake > 0.08f || t.handbrake) 2.2f else 0.45f

        fun rear(mesh: GpuMesh, x: Float, height: Float, d: Float, w: Float, h: Float, depth: Float,
                 r: Float, g: Float, b: Float, tex: Int = textures.whiteTex, glow: Float = 0f) {
            drawOrientedMesh(mesh,t.posX+rightX*x-fwdX*d,baseY+height,t.posZ+rightZ*x-fwdZ*d,
                visualHeading,pitchDeg,rollDeg,w,h,depth,r,g,b,texId=tex,emR=glow,roughness=.36f)
        }
        val pickupTail = isPickup && custom.pickupAccessory == PickupAccessoryType.NONE
        if (pickupTail) {
            rear(roundedMesh,0f,lowerH+.085f,len*.466f,wid*.83f,.26f,.105f,pr,pg,pb)
            rear(roundedMesh,0f,lowerH+.15f,len*.483f,.24f,.047f,.045f,.09f,.10f,.11f)
        }
        rear(roundedMesh,0f,lowerH*.22f,len*.494f,wid*.91f,.145f,.19f,.14f,.15f,.16f)
        val plateX = if (profile==Coachwork.Profile.WAGON) -wid*.31f else 0f
        val plateY = if (profile==Coachwork.Profile.WAGON) lowerH*.30f else lowerH*.43f
        rear(roundedMesh,plateX,plateY,len*.507f,.47f,.148f,.052f,.10f,.105f,.11f)
        rear(cubeMesh,plateX,plateY,len*.515f,.422f,.115f,.023f,1f,1f,1f,textures.rearPlateTex)
        if (profile==Coachwork.Profile.COUPE) {
            rear(roundedMesh,0f,lowerH*.66f,len*.503f,wid*.81f,.032f,.042f,.58f,.025f,.02f,glow=blEm*.42f)
        }

        for (sx in intArrayOf(-1, 1)) {
            drawOrientedMesh(
                cubeMesh,
                t.posX + fwdX * (len * 0.505f) + rightX * (sx * wid * 0.36f),
                baseY + lowerH * 0.54f,
                t.posZ + fwdZ * (len * 0.505f) + rightZ * (sx * wid * 0.36f),
                visualHeading, pitchDeg, rollDeg,
                0.42f, 0.16f, 0.09f, 0.96f, 0.98f, 1.0f,
                emR = hlEm, emG = hlEm * 0.96f, emB = hlEm * 0.88f
            )
            val isBlinkerLit = blinkerOn && ((sx < 0 && t.leftIndicatorOn) || (sx > 0 && t.rightIndicatorOn))
            val vertical = isPickup || isTruck || isBus
            val lampX = sx*wid*(if (vertical) .398f else .355f)
            val lampWidth = wid*(if (vertical) .105f else .205f)
            val lampHeight = if (vertical) .27f else .12f
            val lampY = lowerH*.56f
            rear(roundedMesh,lampX,lampY,len*.491f,lampWidth+.035f,lampHeight+.035f,.14f,.16f,.04f,.045f)
            rear(roundedMesh,lampX,lampY,len*.504f,lampWidth,lampHeight,.055f,.42f,.028f,.025f)
            rear(roundedMesh,lampX,lampY+lampHeight*.20f,len*.51f,lampWidth*.87f,.034f,.033f,
                .75f,.04f,.028f,glow=blEm*.68f)
            rear(roundedMesh,lampX+sx*lampWidth*.30f,lampY-lampHeight*.18f,len*.51f,
                lampWidth*.24f,lampHeight*.27f,.034f,if(isBlinkerLit) 1f else .62f,
                if(isBlinkerLit) .62f else .33f,.12f,glow=if(isBlinkerLit) 1.6f else .02f)

        }
    }

    /** Procedural first-person cabin, with driver limbs and an open windshield aperture. */
    private fun drawCockpitInterior(
        t: VehicleTelemetry,
        custom: VehicleCustomization,
        wheelStyle: SteeringWheelStyle,
        seatSide: DriverSeatSide,
        atmo: AtmosphereSnapshot
    ) {
        val fwdX = sin(t.headingRad); val fwdZ = cos(t.headingRad)
        val rightX = cos(t.headingRad); val rightZ = -sin(t.headingRad)
        val seatSign = if (seatSide == DriverSeatSide.LEFT_HAND) -1f else 1f
        val parsed = runCatching { Color.parseColor(custom.paintColorHex) }.getOrDefault(Color.rgb(200, 114, 54))

        // Windshield aperture remains clear: frame only, no opaque glass plane across the view.
        drawOrientedBox(t.posX + fwdX * 0.86f, t.posY + 2.00f, t.posZ + fwdZ * 0.86f,
            t.headingRad, 1.84f, 0.12f, 0.15f, 0.12f, 0.13f, 0.15f)
        drawOrientedBox(t.posX - fwdX * 0.10f, t.posY + 0.48f, t.posZ - fwdZ * 0.10f,
            t.headingRad, 1.72f, 0.08f, 1.42f, 0.08f, 0.09f, 0.10f)
        // Door tops and inner trim make the driver's location legible without blocking the road.
        for (side in intArrayOf(-1, 1)) {
            drawOrientedBox(t.posX + rightX * side * 0.88f, t.posY + 1.05f,
                t.posZ + rightZ * side * 0.88f, t.headingRad,
                0.10f, 0.40f, 1.48f, 0.11f, 0.12f, 0.14f)
        }

        // 1. Sculpted Metallic Hood visible through windshield
        drawOrientedMesh(
            slopedBodyMesh,
            t.posX + fwdX * 1.65f, t.posY + 1.02f, t.posZ + fwdZ * 1.65f,
            t.headingRad, 0f, 0f, 1.74f, 0.24f, 1.65f,
            Color.red(parsed) / 255f, Color.green(parsed) / 255f, Color.blue(parsed) / 255f,
            metallic = 0.72f, roughness = 0.16f
        )

        val bus = t.vehicleId == VehicleId.HAFILAT || t.vehicleId == VehicleId.QAFILA
        val utility = t.vehicleId in listOf(VehicleId.SAQR, VehicleId.NIMR, VehicleId.JABAL, VehicleId.SHAHIQ, VehicleId.BADIYA)
        if (bus) {
            // Passenger grab pole and fare shelf visible from both seat positions.
            for (side in listOf(.71f,.82f)) drawOrientedBox(t.posX + rightX*side*seatSign, t.posY+1.42f,
                t.posZ + rightZ*side*seatSign, t.headingRad,.038f,1.15f,.038f,.86f,.62f,.13f)
        } else if (utility) {
            val grip = -seatSign*.48f
            drawOrientedBox(t.posX + fwdX*.76f + rightX*grip,t.posY+1.23f,t.posZ + fwdZ*.76f + rightZ*grip,
                t.headingRad,.42f,.055f,.065f,.40f,.37f,.30f)
        }
        // 2. Sculpted Soft-Touch Leather Dashboard + Wood/Carbon Trim Strip
        drawOrientedBox(t.posX + fwdX * 0.84f, t.posY + 1.04f, t.posZ + fwdZ * 0.84f, t.headingRad, 1.80f, 0.38f, 0.48f, 0.09f, 0.10f, 0.12f, metallic = 0.15f, roughness = 0.65f)
        val (trimR, trimG, trimB) = when (wheelStyle) {
            SteeringWheelStyle.GULF_YEMENI, SteeringWheelStyle.CLASSIC_HERITAGE -> Triple(0.42f, 0.22f, 0.10f) // Polished Walnut Wood
            SteeringWheelStyle.EUROPEAN_LUXURY -> Triple(0.72f, 0.76f, 0.82f) // Brushed Aluminum
            SteeringWheelStyle.JAPANESE_SPORT -> Triple(0.75f, 0.12f, 0.14f)  // Racing Red Carbon Accent
        }
        drawOrientedBox(
            t.posX + fwdX * 0.64f, t.posY + 1.12f, t.posZ + fwdZ * 0.64f,
            t.headingRad, 1.72f, 0.055f, 0.06f, trimR, trimG, trimB, metallic = 0.65f, roughness = 0.20f,
            emR = if (wheelStyle == SteeringWheelStyle.EUROPEAN_LUXURY) 0.15f else 0f,
            emG = if (wheelStyle == SteeringWheelStyle.EUROPEAN_LUXURY) 0.35f else 0f,
            emB = if (wheelStyle == SteeringWheelStyle.EUROPEAN_LUXURY) 0.55f else 0f
        )

        // 3. Glowing Virtual Cockpit Digital Cluster (directly ahead of LHD/RHD driver) + Center Infotainment Tablet
        val clusterX = t.posX + fwdX * 0.72f + rightX * (seatSign * 0.38f)
        val clusterZ = t.posZ + fwdZ * 0.72f + rightZ * (seatSign * 0.38f)
        drawOrientedBox(clusterX, t.posY + 1.18f, clusterZ, t.headingRad, 0.46f, 0.15f, 0.05f, 0.12f, 0.74f, 0.96f, emR = 0.22f, emG = 0.64f, emB = 0.90f)
        drawOrientedBox(t.posX + fwdX * 0.74f, t.posY + 1.22f, t.posZ + fwdZ * 0.74f, t.headingRad, 0.34f, 0.20f, 0.04f, 0.12f, 0.20f, 0.28f, emR = 0.38f, emG = 0.26f, emB = 0.08f)

        // 4. Windshield A-Pillars & Top Rear-View Mirror
        for (side in intArrayOf(-1, 1)) {
            drawOrientedBox(
                t.posX + fwdX * 0.86f + rightX * (side * 0.84f), t.posY + 1.56f, t.posZ + fwdZ * 0.86f + rightZ * (side * 0.84f),
                t.headingRad, 0.09f, 0.90f, 0.11f, 0.11f, 0.12f, 0.14f
            )
        }
        drawOrientedBox(t.posX + fwdX * 0.78f, t.posY + 1.78f, t.posZ + fwdZ * 0.78f, t.headingRad, 0.30f, 0.08f, 0.05f, 0.14f, 0.18f, 0.24f, metallic = 0.9f, roughness = 0.05f)

        // 5. Animated Sweeping Windshield Wipers!
        if (t.wipersOn || abs(t.wiperPhaseRad) > 0.02f) {
            val sweepDeg = 12f + (sin(t.wiperPhaseRad) * 0.5f + 0.5f) * 68f
            for (wOff in floatArrayOf(-0.32f, 0.22f)) {
                Matrix.setIdentityM(modelMatrix, 0)
                Matrix.translateM(modelMatrix, 0, t.posX + fwdX * 1.02f + rightX * wOff, t.posY + 1.22f, t.posZ + fwdZ * 1.02f + rightZ * wOff)
                Matrix.rotateM(modelMatrix, 0, t.headingRad * 180f / PI.toFloat(), 0f, 1f, 0f)
                Matrix.rotateM(modelMatrix, 0, -sweepDeg, 0f, 0f, 1f)
                Matrix.translateM(modelMatrix, 0, 0f, 0.28f, 0f)
                Matrix.scaleM(modelMatrix, 0, 0.025f, 0.56f, 0.025f)
                drawMeshWithCurrentMatrix(cubeMesh, 0.08f, 0.09f, 0.10f, textures.whiteTex, 0f, 1f, 1f, 0f, 0f, 0f, 0.2f, 0.5f)
            }
        }

        // 6. Sculpted 3D Rotating Steering Wheel matching Selected Style (Eastern / European / Sport / Classic)
        val swX = t.posX + fwdX * 0.58f + rightX * (seatSign * 0.38f)
        val swY = t.posY + 1.15f
        val swZ = t.posZ + fwdZ * 0.58f + rightZ * (seatSign * 0.38f)
        val ringMesh = if (wheelStyle == SteeringWheelStyle.EUROPEAN_LUXURY) steerRingFlat else steerRingRound
        val (rimR, rimG, rimB) = when (wheelStyle) {
            SteeringWheelStyle.GULF_YEMENI -> Triple(0.46f, 0.24f, 0.11f)
            SteeringWheelStyle.EUROPEAN_LUXURY -> Triple(0.14f, 0.16f, 0.20f)
            SteeringWheelStyle.JAPANESE_SPORT -> Triple(0.12f, 0.13f, 0.16f)
            SteeringWheelStyle.CLASSIC_HERITAGE -> Triple(0.56f, 0.30f, 0.12f)
        }
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, swX, swY, swZ)
        Matrix.rotateM(modelMatrix, 0, t.headingRad * 180f / PI.toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, 16f, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, -t.steeringWheelAngleDeg, 0f, 0f, 1f)
        Matrix.scaleM(modelMatrix, 0, 0.38f, 0.38f, 0.38f)
        drawMeshWithCurrentMatrix(ringMesh, rimR, rimG, rimB, textures.whiteTex, 0f, 1f, 1f, 0.05f * atmo.nightFactor, 0.04f * atmo.nightFactor, 0f, 0.05f, 0.80f)

        // Spokes & Center Boss
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, swX, swY, swZ)
        Matrix.rotateM(modelMatrix, 0, t.headingRad * 180f / PI.toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, 16f, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, -t.steeringWheelAngleDeg, 0f, 0f, 1f)
        Matrix.scaleM(modelMatrix, 0, 0.32f, 0.06f, 0.05f)
        drawMeshWithCurrentMatrix(cubeMesh, trimR, trimG, trimB, textures.whiteTex, 0f, 1f, 1f, 0f, 0f, 0f, 0.72f, 0.22f)
        drawFirstPersonDriver(t, seatSign, swX, swY, swZ)
    }

    private fun drawFirstPersonDriver(t: VehicleTelemetry, seatSign: Float, wheelX: Float, wheelY: Float, wheelZ: Float) {
        val fx = sin(t.headingRad); val fz = cos(t.headingRad)
        val rx = cos(t.headingRad); val rz = -sin(t.headingRad)
        val seatX = t.posX + rx * seatSign * 0.38f
        val seatZ = t.posZ + rz * seatSign * 0.38f
        // Knees and lap sit below eye level; no head is drawn at the first-person camera.
        drawOrientedMesh(humanMesh, seatX - fx * 0.16f, t.posY + 0.65f, seatZ - fz * 0.16f,
            t.headingRad, 0f, 0f, 0.48f, 0.28f, 0.56f, 0.79f, 0.77f, 0.68f, roughness = 0.95f)
        for (side in intArrayOf(-1, 1)) {
            drawOrientedMesh(humanMesh, seatX + rx * side * 0.13f + fx * 0.17f, t.posY + 0.62f,
                seatZ + rz * side * 0.13f + fz * 0.17f, t.headingRad, 0f, 0f,
                0.20f, 0.25f, 0.54f, 0.78f, 0.76f, 0.67f, roughness = 0.95f)
            // Hands track the same wheel rotation and tilt as the visible steering ring.
            val angle = (-t.steeringWheelAngleDeg * PI.toFloat() / 180f).coerceIn(-2.1f, 2.1f)
            val gripX = side * 0.14f
            val gripY = 0.10f
            val localX = gripX * cos(angle) - gripY * sin(angle)
            val localY = gripX * sin(angle) + gripY * cos(angle)
            val handX = wheelX + rx * localX + fx * localY * 0.276f
            val handY = wheelY + localY * 0.961f
            val handZ = wheelZ + rz * localX + fz * localY * 0.276f - fz * 0.035f
            val elbowX = seatX + rx * side * 0.23f + fx * 0.10f
            val elbowZ = seatZ + rz * side * 0.23f + fz * 0.10f
            drawLimbSegment(elbowX, t.posY + 0.94f, elbowZ, handX, handY, handZ, 0.095f, 0.76f, 0.57f, 0.43f)
            drawLimbSegment(seatX + rx * side * 0.20f - fx * 0.12f, t.posY + 1.12f,
                seatZ + rz * side * 0.20f - fz * 0.12f, elbowX, t.posY + 0.94f, elbowZ,
                0.13f, 0.84f, 0.82f, 0.73f)
            drawOrientedMesh(humanMesh, handX, handY, handZ, t.headingRad, 0f, 0f,
                0.105f, 0.12f, 0.10f, 0.76f, 0.57f, 0.43f, roughness = 0.86f)
        }
    }

    private fun drawLimbSegment(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        width: Float, r: Float, g: Float, b: Float) {
        val dx = bx - ax; val dy = by - ay; val dz = bz - az
        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.001f)
        val horizontal = kotlin.math.sqrt(dx * dx + dz * dz)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, (ax + bx) * 0.5f, (ay + by) * 0.5f, (az + bz) * 0.5f)
        Matrix.rotateM(modelMatrix, 0, kotlin.math.atan2(dx, dz) * 180f / PI.toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, kotlin.math.atan2(horizontal, dy) * 180f / PI.toFloat(), 1f, 0f, 0f)
        Matrix.scaleM(modelMatrix, 0, width, len + width * 0.5f, width)
        drawMeshWithCurrentMatrix(humanMesh, r, g, b, textures.whiteTex, 0f, 1f, 1f, 0f, 0f, 0f, 0f, 0.9f)
    }

    private fun drawWeatherParticles(weather: WeatherPreset) {
        val isSand = weather == WeatherPreset.SANDSTORM
        val count = if (frameQuality == com.masalik.yemen.game.GraphicsQuality.LOW) 12 else if (weather == WeatherPreset.HEAVY_RAIN) 68 else 36
        val r = if (isSand) 0.88f else 0.80f
        val g = if (isSand) 0.70f else 0.88f
        val b = if (isSand) 0.44f else 0.98f

        for (i in 0 until count) {
            val seed = i * 19.37f
            val px = currentCamX + sin(seed + weatherAnimTime * (if (isSand) 3.6f else 0.3f)) * 16f
            val pz = currentCamZ + cos(seed * 1.3f + weatherAnimTime * 0.4f) * 16f
            val fall = ((weatherAnimTime * (if (isSand) 4.2f else 20f) + i * 0.7f) % 10f)
            val py = currentCamY + 5f - fall
            drawOrientedBox(
                px, py, pz, 0f,
                if (isSand) 0.16f else 0.025f,
                if (isSand) 0.14f else 0.78f,
                if (isSand) 0.16f else 0.025f,
                r, g, b
            )
        }
    }

    private fun drawContactShadow(x: Float, y: Float, z: Float, headingRad: Float, sx: Float, sz: Float) {
        GLES30.glDepthMask(false)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, y, z)
        Matrix.rotateM(modelMatrix, 0, headingRad * 180f / PI.toFloat(), 0f, 1f, 0f)
        Matrix.scaleM(modelMatrix, 0, sx, 1f, sz)
        drawMeshWithCurrentMatrix(quadMesh, 1f, 1f, 1f, textures.contactShadowTex, 1f, 1f, 1f, 0f, 0f, 0f, 0f, 1f, alpha = 0.85f)
        GLES30.glDepthMask(true)
    }

    private fun drawWheel(
        x: Float, y: Float, z: Float,
        headingRad: Float, steerRad: Float, spinRad: Float,
        radius: Float, width: Float
    ) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, y, z)
        Matrix.rotateM(modelMatrix, 0, (headingRad + steerRad) * 180f / PI.toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, spinRad * 180f / PI.toFloat(), 1f, 0f, 0f)
        Matrix.scaleM(modelMatrix, 0, width, radius * 2f, radius * 2f)
        drawMeshWithCurrentMatrix(wheelMesh, 1f, 1f, 1f, textures.alloyRimTex, 1f, 1f, 1f, 0f, 0f, 0f, 0.52f, 0.28f)
    }

    private fun drawOrientedMesh(
        mesh: GpuMesh,
        x: Float, y: Float, z: Float,
        headingRad: Float,
        pitchDeg: Float,
        rollDeg: Float,
        sx: Float, sy: Float, sz: Float,
        r: Float, g: Float, b: Float,
        texId: Int = textures.whiteTex,
        uvScaleX: Float = 1f,
        uvScaleY: Float = 1f,
        emR: Float = 0f,
        emG: Float = 0f,
        emB: Float = 0f,
        metallic: Float = 0.05f,
        roughness: Float = 0.72f
    ) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, y, z)
        Matrix.rotateM(modelMatrix, 0, headingRad * 180f / PI.toFloat(), 0f, 1f, 0f)
        if (abs(pitchDeg) > 0.01f) Matrix.rotateM(modelMatrix, 0, pitchDeg, 1f, 0f, 0f)
        if (abs(rollDeg) > 0.01f) Matrix.rotateM(modelMatrix, 0, rollDeg, 0f, 0f, 1f)
        Matrix.scaleM(modelMatrix, 0, sx, sy, sz)
        val useTex = if (texId != textures.whiteTex) 1f else 0f
        drawMeshWithCurrentMatrix(mesh, r, g, b, texId, useTex, uvScaleX, uvScaleY, emR, emG, emB, metallic, roughness)
    }

    private fun drawOrientedBox(
        x: Float, y: Float, z: Float,
        headingRad: Float,
        sx: Float, sy: Float, sz: Float,
        r: Float, g: Float, b: Float,
        texId: Int = textures.whiteTex,
        uvScaleX: Float = 1f,
        uvScaleY: Float = 1f,
        emR: Float = 0f,
        emG: Float = 0f,
        emB: Float = 0f,
        metallic: Float = 0.05f,
        roughness: Float = 0.74f
    ) {
        drawOrientedMesh(cubeMesh, x, y, z, headingRad, 0f, 0f, sx, sy, sz, r, g, b, texId, uvScaleX, uvScaleY, emR, emG, emB, metallic, roughness)
    }

    private fun drawOrientedQuad(x: Float, y: Float, z: Float, headingRad: Float, sx: Float, sz: Float, texId: Int) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, y, z)
        Matrix.rotateM(modelMatrix, 0, headingRad * 180f / PI.toFloat(), 0f, 1f, 0f)
        Matrix.scaleM(modelMatrix, 0, sx, 1f, sz)
        drawMeshWithCurrentMatrix(quadMesh, 1f, 1f, 1f, texId, 1f, 1f, 1f, 0f, 0f, 0f, 0.04f, 0.65f)
    }

    private fun drawCone(x: Float, y: Float, z: Float, width: Float, height: Float, r: Float, g: Float, b: Float) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, y, z)
        Matrix.scaleM(modelMatrix, 0, width, height, width)
        drawMeshWithCurrentMatrix(coneMesh, r, g, b, textures.whiteTex, 0f, 1f, 1f, 0f, 0f, 0f, 0f, 0.85f)
    }

    private fun drawMeshWithCurrentMatrix(
        mesh: GpuMesh,
        r: Float, g: Float, b: Float,
        texId: Int,
        useTex: Float,
        uvScaleX: Float,
        uvScaleY: Float,
        emR: Float, emG: Float, emB: Float,
        metallic: Float,
        roughness: Float,
        alpha: Float = 1f
    ) {
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        GLES30.glUniformMatrix4fv(shaders.uModelMatrix, 1, false, modelMatrix, 0)
        GLES30.glUniformMatrix4fv(shaders.uMvpMatrix, 1, false, mvpMatrix, 0)
        GLES30.glUniform4f(shaders.uBaseColor, r, g, b, alpha)
        GLES30.glUniform3f(shaders.uEmissive, emR, emG, emB)
        GLES30.glUniform1f(shaders.uMetallic, metallic)
        GLES30.glUniform1f(shaders.uRoughness, roughness)
        GLES30.glUniform1f(shaders.uWetness, 0f)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glUniform1f(shaders.uUseTexture, useTex)
        GLES30.glUniform2f(shaders.uUvScale, uvScaleX, uvScaleY)
        mesh.draw()
    }

    private data class Quad4(val a: Float, val b: Float, val c: Float, val d: Float)
}
