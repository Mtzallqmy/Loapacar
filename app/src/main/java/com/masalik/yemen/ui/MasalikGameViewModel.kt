package com.masalik.yemen.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.masalik.yemen.audio.MasalikAudioEngine
import com.masalik.yemen.data.MasalikDatabase
import com.masalik.yemen.data.MasalikRepository
import com.masalik.yemen.game.AudioMixerSettings
import com.masalik.yemen.game.AutomaticShifterState
import com.masalik.yemen.game.CameraMode
import com.masalik.yemen.game.AutoDriver
import com.masalik.yemen.game.AutoDriveStatus
import com.masalik.yemen.game.AutoDriveStyle
import com.masalik.yemen.game.ControlInputs
import com.masalik.yemen.game.DrivingRealismMode
import com.masalik.yemen.game.DrivingTripLog
import com.masalik.yemen.game.EngineeringTestResult
import com.masalik.yemen.game.GraphicsQuality
import com.masalik.yemen.game.HudCustomizationSettings
import com.masalik.yemen.game.LivingWorldSimulation
import com.masalik.yemen.game.MissionRun
import com.masalik.yemen.game.MissionDefinition
import com.masalik.yemen.game.MissionsCatalog
import com.masalik.yemen.game.NearbyTrafficSignalInfo
import com.masalik.yemen.game.PhoneDatabaseStats
import com.masalik.yemen.game.PhotoColorFilter
import com.masalik.yemen.game.RadioStationId
import com.masalik.yemen.game.RadarAlertInfo
import com.masalik.yemen.game.ReplayCameraAngle
import com.masalik.yemen.game.ReplayFrame
import com.masalik.yemen.game.ReplayRecorder
import com.masalik.yemen.game.RoadSplineNetwork
import com.masalik.yemen.game.SaveGameSchema
import com.masalik.yemen.game.TimeOfDayPreset
import com.masalik.yemen.game.TouchControlMode
import com.masalik.yemen.game.VehicleCustomization
import com.masalik.yemen.game.VehicleFleetCatalog
import com.masalik.yemen.game.VehicleId
import com.masalik.yemen.game.VehiclePhysicsEngine
import com.masalik.yemen.game.VehicleTelemetry
import com.masalik.yemen.game.WeatherPreset
import com.masalik.yemen.game.YemenCityHub
import com.masalik.yemen.input.GyroSteeringController
import com.masalik.yemen.renderer.MasalikGLRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

enum class ActiveModal {
    MENU, DRIVING, PAUSE, WORLD_MAP, GARAGE, MISSIONS, CONTROL_STUDIO, SETTINGS, PHOTO, REPLAY
}

data class MasalikUiState(
    val profileReady: Boolean = false,
    val saveState: SaveGameSchema = SaveGameSchema(),
    val activeModal: ActiveModal = ActiveModal.MENU,
    val telemetry: VehicleTelemetry = VehicleTelemetry(),
    val nearbySignal: NearbyTrafficSignalInfo? = null,
    val nearbyRadar: RadarAlertInfo? = null,
    val radarFlashAlpha: Float = 0f,
    val cameraMode: CameraMode = CameraMode.CHASE_NEAR,
    val cleanCinematicHud: Boolean = false,
    val previewVehicleId: VehicleId = VehicleId.SHAHIQ,
    val activeMission: MissionDefinition? = null,
    val autoDriveStyle: AutoDriveStyle = AutoDriveStyle.SCENIC,
    val autoDriveStatus: AutoDriveStatus? = null,
    val missionTimeRemainingSec: Int = 0,
    val missionAction: String? = null,
    val missionFraction: Float = 0f,
    val objectiveAr: String = "",
    val objectiveM: Float = 0f,
    val objectiveLane: Float = 2.85f,
    val missionCondition: Float = 100f,
    val missionBannerAr: String? = null,
    val radioOpen: Boolean = false,
    val displayClockStr: String = "09:00",
    val activeTimePhase: TimeOfDayPreset = TimeOfDayPreset.MORNING,
    val photoFilter: PhotoColorFilter = PhotoColorFilter.NATURAL,
    val photoFovDeg: Float = 58f,
    val replayProgress: Float = 0f,
    val replaySpeed: Float = 0.5f,
    val replayPlaying: Boolean = true,
    val replayCameraAngle: ReplayCameraAngle = ReplayCameraAngle.DRONE_ORBIT,
    val replayDurationSec: Float = 0f,
    val fps: Int = 60,
    val dbStats: PhoneDatabaseStats = PhoneDatabaseStats(),
    val diagnosticResults: List<EngineeringTestResult> = emptyList(),
    val toastMessageAr: String? = null
)

class MasalikGameViewModel(application: Application) : AndroidViewModel(application) {

    private val database = MasalikDatabase.getInstance(application)
    private val repository = MasalikRepository(application, database)

    val physics = VehiclePhysicsEngine()
    val livingWorld = LivingWorldSimulation()
    val audioEngine = MasalikAudioEngine(application.assets)
    val gyroController = GyroSteeringController(application)
    private val replayWorld by lazy { LivingWorldSimulation() }
    val replayRecorder = ReplayRecorder()

    private val _uiState = MutableStateFlow(MasalikUiState())
    val uiState: StateFlow<MasalikUiState> = _uiState.asStateFlow()

    // Lock-free volatile touch inputs read directly by the 60fps GL render thread
    @Volatile var inputSteerNorm: Float = 0f
    @Volatile var inputThrottle: Float = 0f
    @Volatile var inputBrake: Float = 0f
    @Volatile var inputClutch = 0f
    @Volatile var inputHandbrake: Boolean = false
    @Volatile var showroomOrbitRad: Float = 0.65f
    @Volatile var drivingOrbitYawRad: Float = 0.42f
    @Volatile var drivingOrbitPitchRad: Float = 0.24f
    @Volatile var drivingOrbitIdleSec: Float = 0f

    private var worldTimeHours: Float = 9.0f
    private var run: MissionRun? = null
    private val autoDriver = AutoDriver()
    private var autoDriveStatus: AutoDriveStatus? = null
    private var autoDriveHoldSec = 0f
    private var fixedRemainder = 0.0
    private var lastTelemetry = physics.telemetry()
    private var previousTelemetry = lastTelemetry

    private fun snapToCurrentPose() {
        lastTelemetry = physics.telemetry()
        previousTelemetry = lastTelemetry
        fixedRemainder = 0.0
    }
    private val pendingSaves = kotlinx.coroutines.channels.Channel<SaveGameSchema>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var missionElapsedSec: Float = 0f
    private var uiSyncAccumulator: Float = 0f
    private var autoSaveAccumulator: Float = 0f
    private var resumeFrame = true
    private val frameTimes = FloatArray(600)
    private var timingCount = 0
    private var fpsFrameCount: Int = 0
    private var fpsAccumulator: Float = 0f
    private var currentFps: Int = 60
    private var blinkerTimer: Float = 0f
    private var blinkerPhase: Boolean = false
    private var radarFlashAlpha: Float = 0f

    private var tripStartDistM: Float = 0f
    private var tripMaxSpeedKmh: Float = 0f
    private var tripStartTimeMs: Long = System.currentTimeMillis()

    init {
        viewModelScope.launch { for (snapshot in pendingSaves) repository.persistSaveGame(snapshot) }
        viewModelScope.launch {
            val loaded = repository.loadSaveGame()
            worldTimeHours = loaded.worldTimeHours
            applySaveToEngines(loaded)
            val stats = repository.getDatabaseStats(loaded)
            _uiState.update {
                it.copy(
                    profileReady = true,
                    saveState = loaded,
                    previewVehicleId = loaded.selectedVehicleId,
                    radioOpen = loaded.radioSettings.isOpen,
                    displayClockStr = LivingWorldSimulation.formatWorldTime(worldTimeHours),
                    activeTimePhase = LivingWorldSimulation.hoursToPreset(worldTimeHours),
                    dbStats = stats
                )
            }
        }
    }

    @Synchronized private fun applySaveToEngines(save: SaveGameSchema) {
        val custom = save.customizations[save.selectedVehicleId]
            ?: VehicleFleetCatalog.defaultCustomizations().getValue(save.selectedVehicleId)
        physics.selectVehicle(
            id = save.selectedVehicleId,
            custom = custom,
            savedFuel = save.fuelByVehicle[save.selectedVehicleId],
            savedDamage = save.damageByVehicle[save.selectedVehicleId]
        )
        physics.realismMode = save.drivingRealism
        gyroController.sensitivity = save.hudSettings.tiltSensitivity
        gyroController.inverted = save.hudSettings.invertTilt
        physics.weather = save.weather
        physics.wheelRotationRangeDeg = save.hudSettings.smartWheel.rotationRangeDeg
        physics.steeringWheelStyle = save.hudSettings.smartWheel.wheelStyle
        physics.steeringAssistEnabled = save.hudSettings.smartWheel.steeringAssist
        val progress = save.missionRun
        val mission = MissionsCatalog.ALL_OFFLINE_MISSIONS.firstOrNull { it.id == progress?.id }
        run = mission?.let { MissionRun(it, progress) }
        missionElapsedSec = run?.elapsed ?: 0f
        physics.resetToRoad(save.playerDistanceM, progress?.lateralM ?: 2.85f, progress?.headingOffsetRad ?: 0f)
        physics.configureAiRival(mission?.hasAiRival == true, (progress?.rivalDistance ?: (save.playerDistanceM + 10f)) - 10f,
            mission?.aiRivalSpeedMs ?: 18f)
        snapToCurrentPose()
        _uiState.update { it.copy(activeMission = mission) }

        audioEngine.mixer = save.audioMixer
        audioEngine.radioSettings = save.radioSettings
        audioEngine.vehicleId = physics.vehicleId
        audioEngine.engineBaseFreqHz = physics.spec.engineBaseFreqHz
        audioEngine.weather = save.weather
    }

    @Synchronized fun onResumeLifecycle() {
        resumeFrame = true
        audioEngine.start()
        if (_uiState.value.saveState.hudSettings.controlMode == TouchControlMode.TILT) {
            gyroController.start()
        }
    }

    @Synchronized fun onPauseLifecycle() {
        stopAutoDrive()
        gyroController.stop()
        audioEngine.stop()
        inputThrottle = 0f
        inputBrake = 0f
        inputSteerNorm = 0f
        inputClutch = 0f
        if (_uiState.value.profileReady) saveSnapshotNow()
    }

    override fun onCleared() {
        super.onCleared()
        gyroController.stop()
        audioEngine.stop()
    }

    /** Called every frame on the OpenGL thread (~60fps) to step simulation & build render snapshot. */
    @Synchronized fun stepSimulationFrame(frameSeconds: Float): MasalikGLRenderer.RenderFrameSnapshot {
        val firstAfterResume = resumeFrame
        resumeFrame = false
        val dt = if (firstAfterResume) 0f else frameSeconds.coerceIn(0f, .12f)
        val state = _uiState.value
        val modal = state.activeModal
        val isDriving = modal == ActiveModal.DRIVING
        val isReplay = modal == ActiveModal.REPLAY
        val isLobbyOrGarage = modal == ActiveModal.MENU || modal == ActiveModal.GARAGE

        if (!firstAfterResume && frameSeconds > 0f) {
            fpsFrameCount++
            fpsAccumulator += frameSeconds
            if (fpsAccumulator >= .7f) {
                currentFps = (fpsFrameCount / fpsAccumulator).toInt()
                fpsFrameCount = 0
                fpsAccumulator = 0f
            }
            if (isDriving) {
                frameTimes[timingCount++] = frameSeconds * 1000f
                if (timingCount == frameTimes.size) {
                    frameTimes.sort()
                    android.util.Log.i("MyCarFrameTimes",
                        "n=600 ms p50=${frameTimes[299]} p95=${frameTimes[569]} p99=${frameTimes[593]} max=${frameTimes.last()} " +
                        "vehicle=${physics.vehicleId} quality=${state.saveState.graphicsQuality} weather=${physics.weather}")
                    timingCount = 0
                }
            }
        }

        blinkerTimer += dt
        if (blinkerTimer >= 0.42f) {
            blinkerTimer = 0f
            blinkerPhase = !blinkerPhase
        }

        if (radarFlashAlpha > 0f) {
            radarFlashAlpha = (radarFlashAlpha - dt * 2.15f).coerceAtLeast(0f)
        }

        if (isLobbyOrGarage && modal == ActiveModal.MENU) {
            showroomOrbitRad = (showroomOrbitRad + dt * 0.28f) % (2f * kotlin.math.PI.toFloat())
        }

        // Handle Cinematic 20-second Instant Replay Studio playback
        if (isReplay) {
            val replayFrame = replayRecorder.stepPlayback(dt)
            val rTelemetry = replayFrame?.telemetry ?: state.telemetry
            val rTimeHours = replayFrame?.worldTimeHours ?: worldTimeHours
            val rWeather = replayFrame?.weather ?: state.saveState.weather
            val rBlinker = replayFrame?.blinkerOn ?: blinkerPhase
            val rAtmo = LivingWorldSimulation.sampleAtmosphere(rTimeHours, rWeather)

            if (replayRecorder.isPlaying) {
                if (replayRecorder.cameraAngle == ReplayCameraAngle.DRONE_ORBIT) {
                    showroomOrbitRad = (showroomOrbitRad + dt * 0.36f * replayRecorder.playbackSpeed) % (2f * kotlin.math.PI.toFloat())
                }

            }

            replayFrame?.world?.let { replayWorld.showRecorded(it) }
            // Soft cinematic audio during replay
            audioEngine.drivingActive = replayRecorder.isPlaying
            audioEngine.engineRunning = rTelemetry.engineRunning
            audioEngine.vehicleId = physics.vehicleId
        audioEngine.engineBaseFreqHz = physics.spec.engineBaseFreqHz
            audioEngine.engineRpm = rTelemetry.rpm * (0.72f + 0.28f * replayRecorder.playbackSpeed)
            audioEngine.throttle = rTelemetry.throttle
            audioEngine.speedKmh = rTelemetry.speedKmh * replayRecorder.playbackSpeed
            audioEngine.surface = rTelemetry.surface
            audioEngine.weather = rWeather
            audioEngine.tireScreech = rTelemetry.tireScreechIntensity * 0.7f
            audioEngine.turboBoostPsi = rTelemetry.turboBoostPsi
            audioEngine.exhaustPopIntensity = rTelemetry.exhaustPopIntensity
            audioEngine.inTunnel = rTelemetry.inTunnel
            audioEngine.emergencySirenProximity = 0f

            uiSyncAccumulator += dt
            if (uiSyncAccumulator >= 0.05f) {
                uiSyncAccumulator = 0f
                _uiState.update {
                    it.copy(
                        telemetry = rTelemetry,
                        replayProgress = replayRecorder.playbackProgress,
                        replayPlaying = replayRecorder.isPlaying,
                        replaySpeed = replayRecorder.playbackSpeed,
                        replayCameraAngle = replayRecorder.cameraAngle,
                        replayDurationSec = replayRecorder.durationSeconds(),
                        fps = currentFps
                    )
                }
            }

            val currentSave = _uiState.value.saveState
            val activeCustom = currentSave.customizations[rTelemetry.vehicleId]
                ?: VehicleFleetCatalog.defaultCustomizations().getValue(rTelemetry.vehicleId)
            val previewId = _uiState.value.previewVehicleId
            val previewCustom = currentSave.customizations[previewId]
                ?: VehicleFleetCatalog.defaultCustomizations().getValue(previewId)

            return MasalikGLRenderer.RenderFrameSnapshot(
                graphicsQuality = state.saveState.graphicsQuality,
                targetFps = state.saveState.hudSettings.targetFps,
                telemetry = rTelemetry,
                customization = activeCustom,
                previewVehicleId = previewId,
                previewCustomization = previewCustom,
                isLobbyOrGarage = false,
                showroomOrbitRad = showroomOrbitRad,
                cameraMode = _uiState.value.cameraMode,
                photoFovDeg = _uiState.value.photoFovDeg,
                worldTimeHours = rTimeHours,
                weather = rWeather,
                livingWorld = replayWorld,
                finishDistanceM = run?.target?.distance,
                objectiveLane = run?.target?.lane ?: 2.85f,
                isStop = run?.target?.isStop ?: false,
                controls = currentSave.hudSettings,
                blinkerOn = rBlinker,
                wheelStyle = currentSave.hudSettings.smartWheel.wheelStyle,
                seatSide = currentSave.hudSettings.smartWheel.seatSide,
                rearMirrorEnabled = false,
                isReplayMode = true,
                replayCameraAngle = replayRecorder.cameraAngle
            )
        }

        // Subtle cinematic auto-sweep in CINEMATIC_ORBIT when user isn't dragging
        if (isDriving && state.cameraMode == CameraMode.CINEMATIC_ORBIT) {
            drivingOrbitIdleSec += dt
            if (drivingOrbitIdleSec > 2.4f) {
                drivingOrbitYawRad = (drivingOrbitYawRad + dt * 0.16f) % (2f * kotlin.math.PI.toFloat())
            }
        }

        // Continuous 24h day/night clock
        if (state.saveState.autoTimeCycleEnabled && isDriving) {
            worldTimeHours = (worldTimeHours + dt * 0.045f) % 24f
        }

        val atmo = LivingWorldSimulation.sampleAtmosphere(worldTimeHours, state.saveState.weather)

        val telemetry: VehicleTelemetry
        if (isDriving) {
            val effectiveSteer = if (state.saveState.hudSettings.controlMode == TouchControlMode.TILT) {
                gyroController.steerNorm
            } else {
                inputSteerNorm
            }
            val manualInputs = ControlInputs(
                throttle = inputThrottle,
                brake = inputBrake,
                steer = effectiveSteer,
                clutch = inputClutch, handbrake = inputHandbrake
            )
            if (autoDriveStatus != null && (manualInputs.throttle > .08f ||
                    manualInputs.brake > .08f || kotlin.math.abs(manualInputs.steer) > .22f ||
                    manualInputs.clutch > .08f || manualInputs.handbrake)) {
                stopAutoDrive()
            }
            fixedRemainder = (fixedRemainder + dt).coerceAtMost(0.12)
            val tick = 1.0 / 60.0
            var collision = 0f
            var radar: com.masalik.yemen.game.SpeedRadarDef? = null
            while (fixedRemainder + 1e-9 >= tick) {
                livingWorld.update(tick.toFloat(), physics.distanceAlongRoadM, physics.lateralOffsetM, physics.forwardSpeedMs, atmo.nightFactor, physics.weather)
                physics.jobPayloadKg = run?.payloadKg ?: 0f
                previousTelemetry = lastTelemetry
                val automated = when {
                    autoDriveStatus == null -> null
                    autoDriveHoldSec > 0f -> {
                        autoDriveHoldSec -= tick.toFloat()
                        com.masalik.yemen.game.AutoDriveCommand(ControlInputs(brake = .85f), AutoDriveStatus.HOLDING)
                    }
                    else -> autoDriver.step(physics, livingWorld, state.autoDriveStyle)
                }
                if (automated != null) autoDriveStatus = automated.status
                lastTelemetry = physics.step(tick.toFloat(), automated?.pedals ?: manualInputs, livingWorld)
                if (automated != null) {
                    if (!physics.engineRunning || physics.lastCollisionImpulse > 7f ||
                        kotlin.math.abs(physics.headingOffsetFromRoadRad) > .90f) {
                        stopAutoDrive()
                        _uiState.update { it.copy(toastMessageAr = "توقّف السائق الآلي · تولَّ القيادة") }
                    } else if (physics.lastCollisionImpulse > 2f) {
                        autoDriveHoldSec = 1.25f
                    }
                }
                val journey = run
                val oldStage = journey?.stage
                journey?.advance(tick.toFloat(), lastTelemetry, physics.headingOffsetFromRoadRad, physics.lastCollisionImpulse)
                if (journey != null && journey.stage != oldStage && !journey.finished) {
                    if (journey.mission.id == "story_2" && journey.stage == 2) setWeather(WeatherPreset.LIGHT_RAIN)
                    saveSnapshotNow()
                }
                replayRecorder.recordStep(tick.toFloat()) {
                    ReplayFrame(lastTelemetry,worldTimeHours,physics.weather,blinkerPhase,
                        livingWorld.captureNearby(physics.distanceAlongRoadM))
                }
                collision = maxOf(collision, physics.lastCollisionImpulse)
                radar = physics.lastTriggeredRadar ?: radar
                fixedRemainder -= tick
            }
            telemetry = lastTelemetry

            if (collision > 2.0f) {
                audioEngine.triggerCollision((collision / 14f).coerceIn(0.25f, 1f))
            }

            // Speed Radar (رادار ساهر) Violation Detection -> Camera Flash + Shutter + Fine (-150 ر.ي)
            val radarHit = radar
            if (radarHit != null) {
                radarFlashAlpha = 1.0f
                audioEngine.triggerCameraShutter()
                val curSave = _uiState.value.saveState
                val fine = 150
                val nextCoins = (curSave.masalikCoins - fine).coerceAtLeast(0)
                commitSave(curSave.copy(masalikCoins = nextCoins))
                _uiState.update {
                    it.copy(
                        radarFlashAlpha = 1.0f,
                        toastMessageAr = "فلاش رادار ساهر (${radarHit.nameAr})! سرعة زائدة فوق ${radarHit.speedLimitKmh} كم/س · مخالفة −$fine ر.ي"
                    )
                }
            }

            if (telemetry.speedKmh > tripMaxSpeedKmh) tripMaxSpeedKmh = telemetry.speedKmh

            // Mission progress evaluation
            val mission = state.activeMission
            if (mission != null) {
                val active = run
                missionElapsedSec = active?.elapsed ?: 0f
                if (active?.finished == true) completeActiveMission(mission, missionElapsedSec)
                else if (active?.failure != null) {
                    run = null
                    physics.configureAiRival(false)
                    _uiState.update { it.copy(activeMission = null, missionBannerAr = active.failure) }
                    saveSnapshotNow()
                }
            }

            // Periodic auto-save every 15s while driving
            autoSaveAccumulator += dt
            if (autoSaveAccumulator >= 15f) {
                autoSaveAccumulator = 0f
                saveSnapshotNow()
            }
        } else {
            fixedRemainder = 0.0
            telemetry = lastTelemetry
        }

        // Update live cinematic audio synthesizer parameters
        physics.assistedClutch = physics.realismMode != DrivingRealismMode.SIMULATION
        audioEngine.insideCabin = state.cameraMode == CameraMode.COCKPIT
        audioEngine.drivingActive = isDriving
        audioEngine.engineRunning = telemetry.engineRunning
        audioEngine.vehicleId = physics.vehicleId
        audioEngine.engineBaseFreqHz = physics.spec.engineBaseFreqHz
        audioEngine.engineRpm = telemetry.rpm
        audioEngine.throttle = telemetry.throttle
        audioEngine.speedKmh = telemetry.speedKmh
        audioEngine.surface = telemetry.surface
        audioEngine.weather = state.saveState.weather
        audioEngine.tireScreech = telemetry.tireScreechIntensity
        audioEngine.turboBoostPsi = telemetry.turboBoostPsi
        audioEngine.exhaustPopIntensity = telemetry.exhaustPopIntensity
        audioEngine.inTunnel = telemetry.inTunnel
        audioEngine.emergencySirenProximity = if (isDriving) livingWorld.emergencySirenProximity else 0f

        // Sync UI StateFlow at ~20Hz so Compose HUD stays buttery smooth with low recomposition cost
        uiSyncAccumulator += dt
        if (uiSyncAccumulator >= 0.05f) {
            uiSyncAccumulator = 0f
            val mission = _uiState.value.activeMission
            val remSec = if (mission != null) (mission.targetTimeSec - missionElapsedSec.toInt()).coerceAtLeast(0) else 0
            _uiState.update {
                it.copy(
                    telemetry = telemetry,
                    autoDriveStatus = autoDriveStatus,
                    nearbySignal = livingWorld.nearbyTrafficSignal,
                    nearbyRadar = livingWorld.nearbyRadar,
                    radarFlashAlpha = radarFlashAlpha,
                    displayClockStr = LivingWorldSimulation.formatWorldTime(worldTimeHours),
                    activeTimePhase = LivingWorldSimulation.hoursToPreset(worldTimeHours),
                    missionTimeRemainingSec = remSec,
                    objectiveAr = run?.target?.let { it.label + if (it.isStop) " · جلنط لثانيتين" else "" } ?: "",
                    missionAction = run?.takeIf { it.waitingForAction }?.target?.let {
                        when (it.kind) {
                            com.masalik.yemen.game.ObjectiveKind.LOAD -> "تحميل وتثبيت"
                            com.masalik.yemen.game.ObjectiveKind.UNLOAD -> "تفريغ الشحنة"
                            com.masalik.yemen.game.ObjectiveKind.ATTACH -> "توصيل المقطورة"
                            else -> "فتح باب الركاب"
                        }
                    },
                    missionFraction = run?.let { it.stage.toFloat()/it.objectives.size } ?: 0f,
                    objectiveM = run?.target?.distance ?: 0f,
                    objectiveLane = run?.target?.lane ?: 2.85f,
                    missionCondition = run?.condition ?: 100f,
                    fps = currentFps
                )
            }
        }

        val currentSave = _uiState.value.saveState
        val activeCustom = currentSave.customizations[telemetry.vehicleId]
            ?: VehicleFleetCatalog.defaultCustomizations().getValue(telemetry.vehicleId)
        val previewId = _uiState.value.previewVehicleId
        val previewCustom = currentSave.customizations[previewId]
            ?: VehicleFleetCatalog.defaultCustomizations().getValue(previewId)

        val alpha = (fixedRemainder * 60).toFloat().coerceIn(0f,1f)
        val prev = previousTelemetry
        val visual = if (isDriving && prev.vehicleId == telemetry.vehicleId &&
            kotlin.math.abs(prev.distanceAlongRoadM - telemetry.distanceAlongRoadM) < 4f) {
            fun blend(a: Float, b: Float) = a + (b-a)*alpha
            val angle = telemetry.headingRad - prev.headingRad
            telemetry.copy(posX = blend(prev.posX,telemetry.posX), posY = blend(prev.posY,telemetry.posY),
                posZ = blend(prev.posZ,telemetry.posZ), pitchDeg = blend(prev.pitchDeg,telemetry.pitchDeg),
                rollDeg = blend(prev.rollDeg,telemetry.rollDeg),
                headingRad = prev.headingRad + alpha * kotlin.math.atan2(kotlin.math.sin(angle), kotlin.math.cos(angle)))
        } else telemetry
        return MasalikGLRenderer.RenderFrameSnapshot(
                graphicsQuality = state.saveState.graphicsQuality,
                targetFps = state.saveState.hudSettings.targetFps,
            telemetry = visual,
            customization = activeCustom,
            previewVehicleId = previewId,
            previewCustomization = previewCustom,
            isLobbyOrGarage = isLobbyOrGarage,
            showroomOrbitRad = showroomOrbitRad,
            cameraMode = _uiState.value.cameraMode,
            photoFovDeg = _uiState.value.photoFovDeg,
            worldTimeHours = worldTimeHours,
            weather = currentSave.weather,
            livingWorld = livingWorld,
            finishDistanceM = run?.target?.distance,
                objectiveLane = run?.target?.lane ?: 2.85f,
                isStop = run?.target?.isStop ?: false,
                controls = currentSave.hudSettings,
            blinkerOn = blinkerPhase,
            wheelStyle = currentSave.hudSettings.smartWheel.wheelStyle,
            seatSide = currentSave.hudSettings.smartWheel.seatSide,
            rearMirrorEnabled = currentSave.hudSettings.rearMirrorEnabled,
            isReplayMode = false,
            replayCameraAngle = replayRecorder.cameraAngle,
            orbitYawRad = drivingOrbitYawRad,
            orbitPitchRad = drivingOrbitPitchRad
        )
    }

    private fun completeActiveMission(mission: MissionDefinition, elapsedSec: Float) {
        run = null
        missionElapsedSec = 0f
        physics.configureAiRival(false)
        val current = _uiState.value.saveState
        val nextCoins = current.masalikCoins + mission.rewardCoins
        val nextXp = current.driverXp + mission.rewardXp
        val prevBest = current.missionBestTimesSec[mission.id]
        val newBest = if (prevBest == null || elapsedSec < prevBest) elapsedSec else prevBest
        val prevCount = current.missionCompletions[mission.id] ?: 0

        val updatedSave = current.copy(
            missionRun = null,
            playerDistanceM = physics.distanceAlongRoadM,
            fuelByVehicle = current.fuelByVehicle + (physics.vehicleId to physics.fuelLiters),
            damageByVehicle = current.damageByVehicle + (physics.vehicleId to physics.damage),
            masalikCoins = nextCoins,
            driverXp = nextXp,
            missionBestTimesSec = current.missionBestTimesSec + (mission.id to newBest),
            missionCompletions = current.missionCompletions + (mission.id to (prevCount + 1))
        )
        commitSave(updatedSave)
        _uiState.update {
            it.copy(
                activeMission = null,
                missionBannerAr = "أحسنت! أنجزت «${mission.titleAr}» وحصلت على +${mission.rewardCoins} ر.ي و +${mission.rewardXp} XP"
            )
        }
    }

    @Synchronized fun operateMissionEquipment() { run?.useEquipment() }

    @Synchronized fun cancelMission() {
        run = null
        physics.configureAiRival(false)
        _uiState.update { it.copy(activeMission = null, missionBannerAr = null) }
        saveSnapshotNow()
    }

    fun dismissMissionBanner() = _uiState.update { it.copy(missionBannerAr = null) }
    fun dismissToast() = _uiState.update { it.copy(toastMessageAr = null) }

    @Synchronized fun setModal(modal: ActiveModal) {
        if (modal != ActiveModal.DRIVING) stopAutoDrive()
        if (modal == ActiveModal.DRIVING && _uiState.value.activeModal != ActiveModal.DRIVING) {
            tripStartDistM = physics.totalDistanceDrivenM
            tripMaxSpeedKmh = 0f
            tripStartTimeMs = System.currentTimeMillis()
            if (_uiState.value.saveState.hudSettings.controlMode == TouchControlMode.TILT) {
                gyroController.start()
            }
        } else if (modal != ActiveModal.DRIVING && _uiState.value.activeModal == ActiveModal.DRIVING) {
            recordCurrentTripIfSignificant()
            inputThrottle = 0f
            inputBrake = 0f
            inputSteerNorm = 0f
            inputClutch = 0f
            audioEngine.hornActive = false
            saveSnapshotNow()
        }
        if (modal == ActiveModal.SETTINGS) {
            refreshDbStats()
        }
        _uiState.update { it.copy(activeModal = modal) }
    }

    private fun recordCurrentTripIfSignificant() {
        val drivenKm = (physics.totalDistanceDrivenM - tripStartDistM) / 1000f
        if (drivenKm < 0.08f) return
        val durSec = ((System.currentTimeMillis() - tripStartTimeMs) / 1000L).toInt().coerceAtLeast(1)
        val region = RoadSplineNetwork.getRegionAt(physics.distanceAlongRoadM)
        val log = DrivingTripLog(
            timestampIso = Instant.now().toString(),
            vehicleId = physics.vehicleId,
            regionId = region.id,
            regionNameAr = region.nameAr,
            distanceKm = drivenKm,
            maxSpeedKmh = tripMaxSpeedKmh,
            durationSec = durSec
        )
        viewModelScope.launch {
            repository.logDrivingTrip(log)
            refreshDbStats()
        }
    }

    @Synchronized fun startMission(mission: MissionDefinition) {
        stopAutoDrive()
        val current = _uiState.value.saveState
        val chosenVehicle = if (mission.recommendedVehicleId in current.unlockedVehicles) {
            mission.recommendedVehicleId
        } else current.selectedVehicleId

        val custom = current.customizations[chosenVehicle]
            ?: VehicleFleetCatalog.defaultCustomizations().getValue(chosenVehicle)
        physics.selectVehicle(chosenVehicle, custom, current.fuelByVehicle[chosenVehicle], current.damageByVehicle[chosenVehicle])
        inputHandbrake = false
        inputClutch = 0f
        run = MissionRun(mission)
        physics.weather = mission.weatherPreset
        physics.resetToRoad(mission.startDistanceM, if (mission.id == MissionsCatalog.PARKING_LESSON_ID) 34f else 2.55f)
        snapToCurrentPose()
        physics.configureAiRival(mission.hasAiRival, mission.startDistanceM, mission.aiRivalSpeedMs)
        worldTimeHours = mission.timeOfDayPreset.hours
        missionElapsedSec = 0f

        val nextSave = current.copy(
            selectedVehicleId = chosenVehicle,
            weather = mission.weatherPreset,
            timeOfDay = mission.timeOfDayPreset,
            worldTimeHours = worldTimeHours,
            missionRun = run?.snapshot(physics.lateralOffsetM, physics.aiRivalDistanceM, physics.headingOffsetFromRoadRad),
            playerDistanceM = mission.startDistanceM
        )
        commitSave(nextSave)
        _uiState.update {
            it.copy(
                activeMission = mission,
                activeModal = ActiveModal.DRIVING,
                missionTimeRemainingSec = mission.targetTimeSec,
                missionBannerAr = mission.dialogueBriefAr ?: "بدأت المهمة: ${mission.titleAr}"
            )
        }
    }

    fun startFreeRoam() {
        if (run != null) { setModal(ActiveModal.DRIVING); return }
        physics.configureAiRival(false)
        _uiState.update { it.copy(activeMission = null, activeModal = ActiveModal.DRIVING) }
    }

    @Synchronized fun fastTravelToCity(city: YemenCityHub) {
        stopAutoDrive()
        if (run != null) {
            _uiState.update { it.copy(toastMessageAr = "أكمل المهمة أو ألغها قبل الانتقال السريع") }
            return
        }
        physics.resetToRoad(city.distanceM)
        snapToCurrentPose()
        physics.weather = city.defaultWeather
        worldTimeHours = city.defaultTimeOfDay.hours
        val current = _uiState.value.saveState
        val visited = (current.visitedCities + city.id).distinct()
        val updated = current.copy(
            playerDistanceM = city.distanceM,
            lastRegionId = city.regionId,
            visitedCities = visited,
            weather = city.defaultWeather,
            timeOfDay = city.defaultTimeOfDay,
            worldTimeHours = worldTimeHours
        )
        commitSave(updated)
        _uiState.update {
            it.copy(
                activeModal = ActiveModal.DRIVING,
                toastMessageAr = "تم الانتقال السريع إلى ${city.nameAr}"
            )
        }
    }

    // Garage & Customization
    fun selectPreviewVehicle(id: VehicleId) = _uiState.update { it.copy(previewVehicleId = id) }

    fun orbitShowroom(deltaRad: Float) {
        showroomOrbitRad = (showroomOrbitRad + deltaRad) % (2f * kotlin.math.PI.toFloat())
    }

    @Synchronized fun confirmSelectVehicle(id: VehicleId) {
        val current = _uiState.value.saveState
        if (id !in current.unlockedVehicles) return
        val custom = current.customizations[id] ?: VehicleFleetCatalog.defaultCustomizations().getValue(id)
        physics.selectVehicle(id, custom, current.fuelByVehicle[id], current.damageByVehicle[id])
        snapToCurrentPose()
        commitSave(current.copy(selectedVehicleId = id))
        _uiState.update { it.copy(previewVehicleId = id, toastMessageAr = "تم تجهيز مركبة «${VehicleFleetCatalog.specOf(id).nameAr}»") }
    }

    @Synchronized fun unlockVehicle(id: VehicleId) {
        val spec = VehicleFleetCatalog.specOf(id)
        val current = _uiState.value.saveState
        if (id in current.unlockedVehicles || current.masalikCoins < spec.unlockPriceCoins) return
        val updated = current.copy(
            masalikCoins = current.masalikCoins - spec.unlockPriceCoins,
            unlockedVehicles = (current.unlockedVehicles + id).distinct(),
            selectedVehicleId = id
        )
        commitSave(updated)
        val custom = updated.customizations[id] ?: VehicleFleetCatalog.defaultCustomizations().getValue(id)
        physics.selectVehicle(id, custom, updated.fuelByVehicle[id], updated.damageByVehicle[id])
        snapToCurrentPose()
        _uiState.update { it.copy(previewVehicleId = id, toastMessageAr = "مبارك! تم شراء مركبة «${spec.nameAr}»") }
    }

    fun updateVehicleCustomization(id: VehicleId, custom: VehicleCustomization) {
        val current = _uiState.value.saveState
        val updated = current.copy(customizations = current.customizations + (id to custom))
        if (physics.vehicleId == id) physics.updateCustomization(custom)
        commitSave(updated)
    }

    @Synchronized fun upgradeMechanicalPart(id: VehicleId, part: String, cost: Int) {
        val current = _uiState.value.saveState
        if (current.masalikCoins < cost) return
        val old = current.customizations[id] ?: VehicleFleetCatalog.defaultCustomizations().getValue(id)
        val next = when (part) {
            "engine" -> old.copy(engineUpgradeLevel = (old.engineUpgradeLevel + 1).coerceAtMost(5))
            "transmission" -> old.copy(transmissionUpgradeLevel = (old.transmissionUpgradeLevel + 1).coerceAtMost(5))
            "suspension" -> old.copy(suspensionUpgradeLevel = (old.suspensionUpgradeLevel + 1).coerceAtMost(5))
            "brake" -> old.copy(brakeUpgradeLevel = (old.brakeUpgradeLevel + 1).coerceAtMost(5))
            else -> old
        }
        val updated = current.copy(
            masalikCoins = current.masalikCoins - cost,
            customizations = current.customizations + (id to next)
        )
        if (physics.vehicleId == id) physics.updateCustomization(next)
        commitSave(updated)
    }

    @Synchronized fun serviceAction(type: String, targetVehicleId: VehicleId = physics.vehicleId, cost: Int = 0) {
        val current = _uiState.value.saveState
        if (cost > 0 && current.masalikCoins < cost) {
            _uiState.update { it.copy(toastMessageAr = "الرصيد غير كافٍ لهذه الخدمة") }
            return
        }
        val spec = VehicleFleetCatalog.specOf(targetVehicleId)
        val curFuel = current.fuelByVehicle.toMutableMap()
        val curDmg = current.damageByVehicle.toMutableMap()
        val msg = when (type) {
            "fuel" -> {
                curFuel[targetVehicleId] = spec.fuelTankLiters
                if (physics.vehicleId == targetVehicleId) physics.refuelTank()
                "تم تعبئة خزان الوقود بالكامل"
            }
            "workshop" -> {
                val old = curDmg[targetVehicleId] ?: com.masalik.yemen.game.DamageState()
                curDmg[targetVehicleId] = old.copy(engineHealthPct = 100f, tiresHealthPct = 100f, suspensionHealthPct = 100f, bodyHealthPct = 100f, cargoIntegrityPct = 100f)
                if (physics.vehicleId == targetVehicleId) physics.repairAll()
                "تمت الصيانة الشاملة وإصلاح المركبة"
            }
            else -> {
                val old = curDmg[targetVehicleId] ?: com.masalik.yemen.game.DamageState()
                curDmg[targetVehicleId] = old.copy(dirtMudLevel = 0f)
                if (physics.vehicleId == targetVehicleId) physics.washVehicle()
                "تم غسيل وتلميع المركبة وإزالة الطين"
            }
        }
        commitSave(current.copy(masalikCoins = current.masalikCoins - cost, fuelByVehicle = curFuel, damageByVehicle = curDmg))
        _uiState.update { it.copy(toastMessageAr = msg) }
    }

    // Driving Controls & Systems
    fun cycleCameraMode() {
        val primaryCycle = listOf(
            CameraMode.COCKPIT,
            CameraMode.HOOD,
            CameraMode.CINEMATIC_ORBIT,
            CameraMode.CHASE_NEAR,
            CameraMode.CHASE_FAR,
            CameraMode.REVERSE,
            CameraMode.CINEMATIC_FRONT
        )
        val cur = _uiState.value.cameraMode
        val idx = primaryCycle.indexOf(cur)
        val next = primaryCycle[(idx + 1) % primaryCycle.size]
        setCameraMode(next)
    }

    /** Quick 3-way immersive toggle strictly between Cockpit, Hood, and Cinematic 360° Orbit views. */
    fun toggleImmersiveCameraView() {
        val coreCycle = listOf(
            CameraMode.COCKPIT,
            CameraMode.HOOD,
            CameraMode.CINEMATIC_ORBIT
        )
        val cur = _uiState.value.cameraMode
        val idx = coreCycle.indexOf(cur)
        val next = if (idx == -1) CameraMode.COCKPIT else coreCycle[(idx + 1) % coreCycle.size]
        setCameraMode(next)
    }

    fun setCameraMode(mode: CameraMode) {
        if (mode == CameraMode.CINEMATIC_ORBIT) {
            drivingOrbitIdleSec = 0f
        }
        _uiState.update {
            it.copy(
                cameraMode = mode,
                toastMessageAr = "الكاميرا: ${mode.labelAr} — ${mode.subtitleAr}"
            )
        }
    }

    fun orbitDrivingCamera(deltaYawRad: Float, deltaPitchRad: Float) {
        drivingOrbitIdleSec = 0f
        drivingOrbitYawRad = (drivingOrbitYawRad + deltaYawRad) % (2f * kotlin.math.PI.toFloat())
        drivingOrbitPitchRad = (drivingOrbitPitchRad + deltaPitchRad).coerceIn(0.10f, 0.72f)
    }

    fun resetDrivingOrbitCamera() {
        drivingOrbitIdleSec = 0f
        drivingOrbitYawRad = 0.0f
        drivingOrbitPitchRad = 0.24f
    }
    fun toggleCleanHud() = _uiState.update { it.copy(cleanCinematicHud = !it.cleanCinematicHud) }
    fun setPhotoFilter(filter: PhotoColorFilter) = _uiState.update { it.copy(photoFilter = filter) }
    fun setPhotoFov(fov: Float) = _uiState.update { it.copy(photoFovDeg = fov.coerceIn(32f, 82f)) }

    @Synchronized fun setShifter(shifter: AutomaticShifterState) {
        if (shifter != AutomaticShifterState.D) stopAutoDrive()
        physics.autoShifter = shifter
    }
    @Synchronized fun toggleTransmissionMode() {
        stopAutoDrive()
        physics.transmissionMode = if (physics.transmissionMode == "auto") "manual" else "auto"
    }
    fun manualGearUp() = physics.shiftManualUp()
    fun manualGearDown() = physics.shiftManualDown()
    fun cycleDrivetrain() = physics.cycleDrivetrain()
    fun toggleDiffLock() {
        if (physics.drivetrainMode == com.masalik.yemen.game.DrivetrainMode.FOUR_H ||
            physics.drivetrainMode == com.masalik.yemen.game.DrivetrainMode.FOUR_L) physics.diffLockActive = !physics.diffLockActive
    }
    fun toggleEngine() { physics.engineRunning = !physics.engineRunning }
    fun toggleHeadlights() {
        val modes = com.masalik.yemen.game.HeadlightBeamMode.entries
        val next = modes[(modes.indexOf(physics.headlightBeamMode) + 1) % modes.size]
        physics.headlightBeamMode = next
        _uiState.update { it.copy(toastMessageAr = "المصابيح الأمامية: ${next.labelAr}") }
    }
    @Synchronized fun toggleWipers() {
        val modes = com.masalik.yemen.game.WiperMode.entries
        physics.wiperMode = modes[(physics.wiperMode.ordinal + 1) % modes.size]
    }
    fun toggleLeftIndicator() {
        physics.leftIndicatorOn = !physics.leftIndicatorOn
        if (physics.leftIndicatorOn) physics.rightIndicatorOn = false
    }
    fun toggleRightIndicator() {
        physics.rightIndicatorOn = !physics.rightIndicatorOn
        if (physics.rightIndicatorOn) physics.leftIndicatorOn = false
    }
    @Synchronized fun toggleAutoDrive() {
        if (autoDriveStatus != null) {
            stopAutoDrive()
            return
        }
        if (run != null || _uiState.value.activeModal != ActiveModal.DRIVING) {
            _uiState.update { it.copy(toastMessageAr = "السائق الآلي متاح أثناء التجوال الحر") }
            return
        }
        if (inputHandbrake || !autoDriver.canEngage(physics)) {
            _uiState.update { it.copy(toastMessageAr = "شغّل المحرك، اختر D، حرّر الجلنط ووجّه السيارة مع الطريق في الحارة اليمنى") }
            return
        }
        physics.cruiseControlEnabled = false
        autoDriveStatus = AutoDriveStatus.CRUISING
        _uiState.update { it.copy(autoDriveStatus = autoDriveStatus) }
    }

    @Synchronized fun cycleAutoDriveStyle() {
        _uiState.update {
            val styles = AutoDriveStyle.entries
            it.copy(autoDriveStyle = styles[(it.autoDriveStyle.ordinal + 1) % styles.size])
        }
    }

    private fun stopAutoDrive() {
        if (autoDriveStatus == null) return
        autoDriveStatus = null
        autoDriveHoldSec = 0f
        _uiState.update { it.copy(autoDriveStatus = null) }
    }

    @Synchronized fun toggleCruiseControl() {
        stopAutoDrive()
        physics.cruiseControlEnabled = !physics.cruiseControlEnabled
        if (physics.cruiseControlEnabled) {
            physics.cruiseTargetKmh = (physics.forwardSpeedMs * 3.6f).coerceIn(35f, 120f)
        }
    }
    fun toggleAssist(name: String) {
        val a = physics.assists
        physics.assists = when (name) {
            "abs" -> a.copy(absEnabled = !a.absEnabled)
            "tcs" -> a.copy(tcsEnabled = !a.tcsEnabled)
            "esc" -> a.copy(escEnabled = !a.escEnabled)
            else -> a.copy(hdcEnabled = !a.hdcEnabled)
        }
    }
    @Synchronized fun recoverVehicleOnRoad() {
        stopAutoDrive()
        physics.resetToRoad(physics.distanceAlongRoadM, 2.85f)
        snapToCurrentPose()
    }
    fun setHornActive(active: Boolean) { audioEngine.hornActive = active }

    fun cycleQuickDayNight() {
        val cycle = listOf(TimeOfDayPreset.MORNING, TimeOfDayPreset.SUNSET, TimeOfDayPreset.NIGHT, TimeOfDayPreset.DAWN)
        val cur = LivingWorldSimulation.hoursToPreset(worldTimeHours)
        val idx = cycle.indexOf(cur)
        val next = cycle[(idx + 1) % cycle.size]
        setTimeOfDay(next)
        if (next == TimeOfDayPreset.NIGHT || next == TimeOfDayPreset.DAWN) {
            if (physics.headlightBeamMode == com.masalik.yemen.game.HeadlightBeamMode.OFF) {
                physics.headlightBeamMode = com.masalik.yemen.game.HeadlightBeamMode.LOW_BEAM
            }
        }
        _uiState.update { it.copy(toastMessageAr = "الوضع الزمني: ${next.labelAr}") }
    }

    fun cycleSteeringWheelStyle() {
        val curHud = _uiState.value.saveState.hudSettings
        val styles = com.masalik.yemen.game.SteeringWheelStyle.entries
        val next = styles[(styles.indexOf(curHud.smartWheel.wheelStyle) + 1) % styles.size]
        updateHudSettings(
            curHud.copy(
                smartWheel = curHud.smartWheel.copy(
                    wheelStyle = next,
                    rotationRangeDeg = next.defaultLockDeg
                )
            )
        )
        _uiState.update { it.copy(toastMessageAr = "طراز الدركسون: ${next.labelAr} (${next.defaultLockDeg}°)") }
    }

    fun toggleDriverSeatSide() {
        val curHud = _uiState.value.saveState.hudSettings
        val next = if (curHud.smartWheel.seatSide == com.masalik.yemen.game.DriverSeatSide.LEFT_HAND) {
            com.masalik.yemen.game.DriverSeatSide.RIGHT_HAND
        } else {
            com.masalik.yemen.game.DriverSeatSide.LEFT_HAND
        }
        updateHudSettings(curHud.copy(smartWheel = curHud.smartWheel.copy(seatSide = next)))
        _uiState.update { it.copy(toastMessageAr = "موضع المقود في المقصورة: ${next.labelAr}") }
    }

    fun toggleRearMirror() {
        val curHud = _uiState.value.saveState.hudSettings
        val next = !curHud.rearMirrorEnabled
        updateHudSettings(curHud.copy(rearMirrorEnabled = next))
        _uiState.update {
            it.copy(toastMessageAr = if (next) "تم تفعيل المرآة الخلفية الحية" else "تم إخفاء المرآة الخلفية")
        }
    }

    // Cinematic 20-Second Instant Replay Studio Controls
    @Synchronized fun enterReplayMode() {
        stopAutoDrive()
        val cur = _uiState.value
        val fallback = ReplayFrame(
            telemetry = cur.telemetry,
            worldTimeHours = worldTimeHours,
            weather = cur.saveState.weather,
            blinkerOn = blinkerPhase, world = livingWorld.captureNearby(physics.distanceAlongRoadM)
        )
        replayRecorder.enterReplaySession(fallback)
        inputThrottle = 0f
        inputBrake = 0f
        inputSteerNorm = 0f
        audioEngine.hornActive = false
        _uiState.update {
            it.copy(
                activeModal = ActiveModal.REPLAY,
                replayProgress = 0f,
                replaySpeed = replayRecorder.playbackSpeed,
                replayPlaying = true,
                replayCameraAngle = replayRecorder.cameraAngle,
                replayDurationSec = replayRecorder.durationSeconds()
            )
        }
    }

    fun exitReplayMode() {
        _uiState.update { it.copy(activeModal = ActiveModal.DRIVING) }
    }

    fun toggleReplayPlayPause() {
        replayRecorder.isPlaying = !replayRecorder.isPlaying
        _uiState.update { it.copy(replayPlaying = replayRecorder.isPlaying) }
    }

    fun scrubReplay(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        replayRecorder.playbackProgress = p
        _uiState.update { it.copy(replayProgress = p) }
    }

    fun setReplaySpeed(speed: Float) {
        replayRecorder.playbackSpeed = speed
        _uiState.update { it.copy(replaySpeed = speed) }
    }

    fun setReplayCameraAngle(angle: ReplayCameraAngle) {
        replayRecorder.cameraAngle = angle
        _uiState.update { it.copy(replayCameraAngle = angle) }
    }

    fun notifyScreenshotSaved(success: Boolean) {
        _uiState.update {
            it.copy(
                toastMessageAr = if (success) {
                    "تم حفظ لقطة HD عالية الدقة في معرض الصور (Pictures/Masalik)"
                } else {
                    "تعذّر حفظ اللقطة في المعرض"
                }
            )
        }
    }

    // Time, Weather, Audio, Radio, HUD Studio & Settings
    fun setWeather(weather: WeatherPreset) {
        physics.weather = weather
        audioEngine.weather = weather
        commitSave(_uiState.value.saveState.copy(weather = weather))
    }

    fun setTimeOfDay(preset: TimeOfDayPreset) {
        worldTimeHours = preset.hours
        commitSave(_uiState.value.saveState.copy(timeOfDay = preset, worldTimeHours = preset.hours))
    }

    fun toggleAutoTimeCycle() {
        val next = !_uiState.value.saveState.autoTimeCycleEnabled
        commitSave(_uiState.value.saveState.copy(autoTimeCycleEnabled = next))
    }

    fun setDrivingRealism(mode: DrivingRealismMode) {
        physics.realismMode = mode
        commitSave(_uiState.value.saveState.copy(drivingRealism = mode))
    }

    fun setGraphicsQuality(quality: GraphicsQuality) {
        commitSave(_uiState.value.saveState.copy(graphicsQuality = quality))
    }

    fun updateAudioMixer(mixer: AudioMixerSettings) {
        audioEngine.mixer = mixer
        commitSave(_uiState.value.saveState.copy(audioMixer = mixer))
    }

    fun toggleRadioOpen() {
        val open = !_uiState.value.radioOpen
        _uiState.update { it.copy(radioOpen = open) }
        commitSave(_uiState.value.saveState.copy(radioSettings = _uiState.value.saveState.radioSettings.copy(isOpen = open)))
    }

    fun toggleRadioPlaying() {
        val cur = _uiState.value.saveState.radioSettings
        val next = cur.copy(isPlaying = !cur.isPlaying)
        audioEngine.radioSettings = next
        commitSave(_uiState.value.saveState.copy(radioSettings = next))
    }

    fun selectRadioStation(stationId: RadioStationId) {
        val next = _uiState.value.saveState.radioSettings.copy(stationId = stationId, isPlaying = true)
        audioEngine.radioSettings = next
        commitSave(_uiState.value.saveState.copy(radioSettings = next))
    }

    fun setRadioVolume(vol: Float) {
        val next = _uiState.value.saveState.radioSettings.copy(volume = vol.coerceIn(0f, 1f))
        audioEngine.radioSettings = next
        commitSave(_uiState.value.saveState.copy(radioSettings = next))
    }

    fun toggleRadioVinyl() {
        val cur = _uiState.value.saveState.radioSettings
        val next = cur.copy(lofiVinylEnabled = !cur.lofiVinylEnabled)
        audioEngine.radioSettings = next
        commitSave(_uiState.value.saveState.copy(radioSettings = next))
    }

    fun updateHudSettings(hud: HudCustomizationSettings) {
        gyroController.sensitivity = hud.tiltSensitivity
        gyroController.inverted = hud.invertTilt
        physics.wheelRotationRangeDeg = hud.smartWheel.rotationRangeDeg
        physics.steeringWheelStyle = hud.smartWheel.wheelStyle
        physics.steeringAssistEnabled = hud.smartWheel.steeringAssist
        if (hud.controlMode == TouchControlMode.TILT) gyroController.start() else gyroController.stop()
        commitSave(_uiState.value.saveState.copy(hudSettings = hud))
    }

    fun runDiagnostics() {
        val res = physics.runEngineeringDiagnostics()
        _uiState.update { it.copy(diagnosticResults = res) }
    }

    fun refreshDbStats() {
        viewModelScope.launch {
            val stats = repository.getDatabaseStats(_uiState.value.saveState)
            _uiState.update { it.copy(dbStats = stats) }
        }
    }

    fun notifyBackupResult(success: Boolean) {
        _uiState.update { it.copy(toastMessageAr = if (success) "تم حفظ النسخة الاحتياطية محلياً" else "تعذر حفظ النسخة الاحتياطية") }
    }

    fun exportSaveJson(): String = repository.exportSaveJson(_uiState.value.saveState)

    fun importSaveJson(raw: String) {
        val result = repository.importSaveJson(raw)
        result.onSuccess { imported ->
            worldTimeHours = imported.worldTimeHours
            applySaveToEngines(imported)
            commitSave(imported)
            _uiState.update { it.copy(toastMessageAr = "تم استيراد ملف الحفظ بنجاح!") }
        }.onFailure {
            _uiState.update { it.copy(toastMessageAr = "تعذر قراءة ملف الحفظ، تأكد من صلاحية الملف.") }
        }
    }

    fun resetAllProgress() {
        viewModelScope.launch {
            val fresh = repository.resetAllData()
            worldTimeHours = fresh.worldTimeHours
            applySaveToEngines(fresh)
            val stats = repository.getDatabaseStats(fresh)
            _uiState.update {
                it.copy(
                    saveState = fresh,
                    previewVehicleId = fresh.selectedVehicleId,
                    dbStats = stats,
                    toastMessageAr = "تم تصفير قاعدة البيانات المحلية بالكامل."
                )
            }
        }
    }

    private fun saveSnapshotNow() {
        val cur = _uiState.value.saveState
        val updated = cur.copy(
            missionRun = run?.snapshot(physics.lateralOffsetM, physics.aiRivalDistanceM, physics.headingOffsetFromRoadRad),
            worldTimeHours = worldTimeHours,
            timeOfDay = LivingWorldSimulation.hoursToPreset(worldTimeHours),
            playerDistanceM = physics.distanceAlongRoadM,
            lastRegionId = RoadSplineNetwork.getRegionAt(physics.distanceAlongRoadM).id,
            fuelByVehicle = cur.fuelByVehicle + (physics.vehicleId to physics.fuelLiters),
            damageByVehicle = cur.damageByVehicle + (physics.vehicleId to physics.damage),
            totalCareerDistanceM = cur.totalCareerDistanceM + (physics.totalDistanceDrivenM - tripStartDistM).coerceAtLeast(0f)
        )
        tripStartDistM = physics.totalDistanceDrivenM
        commitSave(updated)
    }

    private fun commitSave(next: SaveGameSchema) {
        _uiState.update { it.copy(saveState = next) }
        pendingSaves.trySend(next)
    }
}
