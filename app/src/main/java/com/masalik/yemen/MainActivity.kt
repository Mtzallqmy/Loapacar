package com.masalik.yemen

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masalik.yemen.renderer.MasalikGLRenderer
import com.masalik.yemen.renderer.FramePacedSurfaceView
import com.masalik.yemen.ui.ActiveModal
import com.masalik.yemen.ui.MasalikGameViewModel
import com.masalik.yemen.ui.screens.ControlStudioScreen
import com.masalik.yemen.ui.screens.DrivingHudOverlay
import com.masalik.yemen.ui.screens.GarageShowroomScreen
import com.masalik.yemen.ui.screens.MainMenuLobbyScreen
import com.masalik.yemen.ui.screens.MissionsAndStoryScreen
import com.masalik.yemen.ui.screens.ReplayStudioOverlay
import com.masalik.yemen.ui.screens.SettingsAndSaveScreen
import com.masalik.yemen.ui.screens.YemenWorldMapScreen
import com.masalik.yemen.ui.screens.YemeniRadioOverlay
import com.masalik.yemen.ui.theme.MasalikAmber
import com.masalik.yemen.ui.theme.MasalikIvory
import com.masalik.yemen.ui.theme.MasalikObsidian
import com.masalik.yemen.ui.theme.MasalikTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val viewModel: MasalikGameViewModel by viewModels()
    private var incomingBackup by mutableStateOf<Uri?>(null)
    private var glSurfaceViewRef: GLSurfaceView? = null
    private val screenshotPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveGameScreenshot() else viewModel.notifyScreenshotSaved(false)
    }

    private fun saveGameScreenshot() {
        viewModel.audioEngine.triggerCameraShutter()
        captureGLScreenshotToGallery(glSurfaceViewRef, this) { viewModel.notifyScreenshotSaved(it) }
    }

    private fun requestGameScreenshot() {
        if (Build.VERSION.SDK_INT <= 28 && androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            screenshotPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else saveGameScreenshot()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBarsImmersive()
        receiveSharedBackup(intent)
        // Test-only entry point for a repeatable emulator smoke check. Release builds ignore it.
        if (BuildConfig.DEBUG && intent.getBooleanExtra("masalik_test_cockpit", false)) {
            viewModel.setCameraMode(com.masalik.yemen.game.CameraMode.COCKPIT)
            viewModel.setModal(ActiveModal.DRIVING)
            val testHandler = Handler(Looper.getMainLooper())
            testHandler.post(object : Runnable {
                override fun run() {
                    if (!viewModel.uiState.value.profileReady) { testHandler.postDelayed(this, 500L); return }

                intent.getStringExtra("masalik_test_graphics")?.let { name ->
                    runCatching { com.masalik.yemen.game.GraphicsQuality.valueOf(name) }.getOrNull()?.let { viewModel.setGraphicsQuality(it) }
                }
                intent.getStringExtra("masalik_test_vehicle")?.let { name ->
                    runCatching { com.masalik.yemen.game.VehicleId.valueOf(name) }.getOrNull()?.let { viewModel.confirmSelectVehicle(it) }
                }
                intent.getStringExtra("masalik_test_city")?.let { id ->
                    com.masalik.yemen.game.RoadSplineNetwork.YEMEN_CITY_HUBS.firstOrNull { it.id == id }?.let { viewModel.fastTravelToCity(it) }
                }
                intent.getStringExtra("masalik_test_weather")?.let { name ->
                    runCatching { com.masalik.yemen.game.WeatherPreset.valueOf(name) }.getOrNull()?.let { viewModel.setWeather(it) }
                }
                intent.getStringExtra("masalik_test_camera")?.let { name ->
                    runCatching { com.masalik.yemen.game.CameraMode.valueOf(name) }.getOrNull()?.let { viewModel.setCameraMode(it) }
                }
                if (intent.getBooleanExtra("masalik_test_night", false)) viewModel.setTimeOfDay(com.masalik.yemen.game.TimeOfDayPreset.NIGHT)
                if (intent.getBooleanExtra("masalik_test_clean", false)) viewModel.toggleCleanHud()
                }
            })
        }

        setContent {
            MasalikTheme {
                MasalikRootScreen(
                    vm = viewModel,
                    incomingBackup = incomingBackup,
                    dismissIncomingBackup = { incomingBackup = null },
                    onBindGLView = { glSurfaceViewRef = it },
                    onCaptureScreenshot = {
                        requestGameScreenshot()
                    }
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun receiveSharedBackup(message: Intent?) {
        if (message?.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= 33) message.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else message.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        incomingBackup = uri?.takeIf { it.scheme == "content" }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveSharedBackup(intent)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBarsImmersive()
        glSurfaceViewRef?.onResume()
        viewModel.onResumeLifecycle()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceViewRef?.onPause()
        viewModel.onPauseLifecycle()
    }

    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK) &&
            viewModel.uiState.value.activeModal == ActiveModal.DRIVING) {
            fun axis(id: Int): Float {
                val v = event.getAxisValue(id)
                val flat = event.device?.getMotionRange(id, event.source)?.flat ?: .04f
                return if (kotlin.math.abs(v) <= flat) 0f else v
            }
            viewModel.inputSteerNorm = axis(android.view.MotionEvent.AXIS_X).coerceIn(-1f, 1f)
            viewModel.inputThrottle = maxOf(axis(android.view.MotionEvent.AXIS_RTRIGGER), axis(android.view.MotionEvent.AXIS_GAS)).coerceIn(0f,1f)
            viewModel.inputBrake = maxOf(axis(android.view.MotionEvent.AXIS_LTRIGGER), axis(android.view.MotionEvent.AXIS_BRAKE)).coerceIn(0f,1f)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBarsImmersive() else viewModel.releaseDriverInputs()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBarsImmersive() {
        if (Build.VERSION.SDK_INT < 30) {
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MasalikRootScreen(
    vm: MasalikGameViewModel,
    incomingBackup: Uri?,
    dismissIncomingBackup: () -> Unit,
    onBindGLView: (GLSurfaceView?) -> Unit,
    onCaptureScreenshot: () -> Unit
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var worldReady by remember { mutableStateOf(false) }

    // Both document operations are performed by the serialized save worker.
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { vm.backupDocument(it, importing = false) } }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.backupDocument(it, importing = true) } }

    if (incomingBackup != null && state.profileReady && !state.backupBusy) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = dismissIncomingBackup,
            title = { Text("استيراد نسخة اللعبة") },
            text = { Text("سيُستبدل الملف الشخصي بالنسخة المختارة، وتُدمج الرحلات السابقة. هل تريد المتابعة؟") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.setModal(ActiveModal.SETTINGS)
                    vm.releaseDriverInputs()
                    vm.backupDocument(incomingBackup, importing = true)
                    dismissIncomingBackup()
                }) { Text("استيراد الحفظ") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = dismissIncomingBackup) { Text("إلغاء") }
            }
        )
    }
    if (state.backupBusy) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text("النسخة الاحتياطية") },
            text = { Text("جارٍ معالجة بيانات اللعبة...") },
            confirmButton = {},
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        )
    }

    // Auto-dismiss transient toast messages after 3.2s
    LaunchedEffect(state.toastMessageAr) {
        if (state.toastMessageAr != null) {
            delay(3200L)
            vm.dismissToast()
        }
    }

    // Handle Android Back button cleanly across every modal/sub-screen
    BackHandler(enabled = state.activeModal != ActiveModal.MENU) {
        when (state.activeModal) {
            ActiveModal.DRIVING -> vm.setModal(ActiveModal.PAUSE)
            ActiveModal.PAUSE, ActiveModal.PHOTO -> vm.setModal(ActiveModal.DRIVING)
            ActiveModal.REPLAY -> vm.exitReplayMode()
            ActiveModal.WORLD_MAP,
            ActiveModal.GARAGE,
            ActiveModal.MISSIONS,
            ActiveModal.CONTROL_STUDIO,
            ActiveModal.SETTINGS -> vm.setModal(ActiveModal.MENU)
            ActiveModal.MENU -> Unit
        }
    }

    DisposableEffect(Unit) {
        onDispose { onBindGLView(null) }
    }

    Box(modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.background(MasalikObsidian)) {
        // 1. Live Native 60fps OpenGL ES 3.0 3D Yemen World & Vehicle Surface
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                FramePacedSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    preserveEGLContextOnPause = true
                    setRenderer(MasalikGLRenderer(ctx.assets, onFirstFrame = { Handler(Looper.getMainLooper()).post { worldReady = true } }) { dtSec -> vm.stepSimulationFrame(dtSec) })
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    onBindGLView(this)
                }
            },
            update = { view ->
                view.updateProfile(state.saveState.graphicsQuality, state.fps, state.saveState.hudSettings.targetFps)
            }
        )

        if (!worldReady || !state.profileReady) {
            Box(Modifier.fillMaxSize().background(MasalikObsidian), contentAlignment = Alignment.Center) {
                Text("سيارتي\nجارٍ تجهيز الطرق والمركبات…", color = MasalikIvory, fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }

        // 2. Active Screen Overlay
        if (worldReady && state.profileReady) when (state.activeModal) {
            ActiveModal.MENU -> {
                MainMenuLobbyScreen(
                    saveState = state.saveState,
                    activeTimePhase = state.activeTimePhase,
                    onStartDriving = { vm.setModal(ActiveModal.DRIVING) },
                    onJourney = vm::startMission,
                    onParkingLesson = {
                        val catalog = com.masalik.yemen.game.MissionsCatalog
                        vm.startMission(catalog.ALL_OFFLINE_MISSIONS.first { it.id == catalog.PARKING_LESSON_ID })
                    },
                    onCycleDayNight = { vm.cycleQuickDayNight() },
                    onOpenWorldMap = { vm.setModal(ActiveModal.WORLD_MAP) },
                    onOpenMissions = { vm.setModal(ActiveModal.MISSIONS) },
                    onOpenGarage = { vm.setModal(ActiveModal.GARAGE) },
                    onOpenControlStudio = { vm.setModal(ActiveModal.CONTROL_STUDIO) },
                    onOpenSettings = { vm.setModal(ActiveModal.SETTINGS) },
                    onToggleRadio = { vm.toggleRadioOpen() }
                )
            }

            ActiveModal.DRIVING, ActiveModal.PAUSE, ActiveModal.PHOTO -> {
                DrivingHudOverlay(
                    state = state,
                    vm = vm,
                    onCaptureScreenshot = onCaptureScreenshot
                )
            }

            ActiveModal.REPLAY -> {
                ReplayStudioOverlay(
                    state = state,
                    onOrbitDrone = { vm.orbitShowroom(it) },
                    onTogglePlay = { vm.toggleReplayPlayPause() },
                    onScrub = { vm.scrubReplay(it) },
                    onSelectSpeed = { vm.setReplaySpeed(it) },
                    onSelectCamera = { vm.setReplayCameraAngle(it) },
                    onCaptureScreenshot = onCaptureScreenshot,
                    onExitReplay = { vm.exitReplayMode() }
                )
            }

            ActiveModal.WORLD_MAP -> {
                YemenWorldMapScreen(
                    currentDistanceM = state.telemetry.distanceAlongRoadM,
                    visitedCities = state.saveState.visitedCities,
                    onFastTravel = { vm.fastTravelToCity(it) },
                    onClose = { vm.setModal(ActiveModal.DRIVING) }
                )
            }

            ActiveModal.GARAGE -> {
                GarageShowroomScreen(
                    saveState = state.saveState,
                    previewVehicleId = state.previewVehicleId,
                    onSelectPreview = { vm.selectPreviewVehicle(it) },
                    onOrbit = { vm.orbitShowroom(it) },
                    onConfirmEquip = { vm.confirmSelectVehicle(it) },
                    onUnlockVehicle = { vm.unlockVehicle(it) },
                    onUpdateCustomization = { id, c -> vm.updateVehicleCustomization(id, c) },
                    onUpgradePart = { id, part, cost -> vm.upgradeMechanicalPart(id, part, cost) },
                    onServiceAction = { type, id, cost -> vm.serviceAction(type, id, cost) },
                    onClose = { vm.setModal(ActiveModal.MENU) }
                )
            }

            ActiveModal.MISSIONS -> {
                MissionsAndStoryScreen(
                    saveState = state.saveState,
                    onStartMission = { vm.startMission(it) },
                    onStartFreeRoam = { vm.startFreeRoam() },
                    onClose = { vm.setModal(ActiveModal.MENU) }
                )
            }

            ActiveModal.CONTROL_STUDIO -> {
                ControlStudioScreen(
                    hudSettings = state.saveState.hudSettings,
                    onUpdateHud = { vm.updateHudSettings(it) },
                    onCalibrateGyro = { vm.gyroController.calibrateCenter() },
                    onClose = { vm.setModal(ActiveModal.MENU) }
                )
            }

            ActiveModal.SETTINGS -> {
                SettingsAndSaveScreen(
                    saveState = state.saveState,
                    dbStats = state.dbStats,
                    diagnostics = state.diagnosticResults,
                    onSetGraphics = { vm.setGraphicsQuality(it) },
                    onSetRealism = { vm.setDrivingRealism(it) },
                    onSetWeather = { vm.setWeather(it) },
                    onSetTimeOfDay = { vm.setTimeOfDay(it) },
                    onToggleAutoCycle = { vm.toggleAutoTimeCycle() },
                    onUpdateAudio = { vm.updateAudioMixer(it) },
                    onExportJson = { exportLauncher.launch("masalik_yemen_backup.masalik.json") },
                    onImportJson = { importLauncher.launch(arrayOf("*/*")) },
                    onResetProgress = { vm.resetAllProgress() },
                    onRunDiagnostics = { vm.runDiagnostics() },
                    onClose = { vm.setModal(ActiveModal.MENU) }
                )
            }
        }

        // 3. Floating Yemeni Lo-Fi Radio Widget
        if (state.radioOpen) {
            YemeniRadioOverlay(
                radio = state.saveState.radioSettings,
                onTogglePlay = { vm.toggleRadioPlaying() },
                onSelectStation = { vm.selectRadioStation(it) },
                onVolumeChange = { vm.setRadioVolume(it) },
                onToggleVinyl = { vm.toggleRadioVinyl() },
                onClose = { vm.toggleRadioOpen() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 54.dp, end = 14.dp)
            )
        }

        // 4. Transient Toast Pill
        state.toastMessageAr?.let { msg ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MasalikObsidian.copy(alpha = 0.94f))
                    .border(1.5.dp, MasalikAmber, RoundedCornerShape(14.dp))
                    .clickable { vm.dismissToast() }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(msg, color = MasalikIvory, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Zero-permission Play-Policy-compliant HD Screenshot capture of the live OpenGL ES 3.0 GLSurfaceView
 * directly into the user's Pictures/Masalik album via PixelCopy + MediaStore.Images.
 */
private fun captureGLScreenshotToGallery(
    glView: GLSurfaceView?,
    context: Context,
    onResult: (Boolean) -> Unit
) {
    if (glView == null || glView.width <= 0 || glView.height <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        onResult(false)
        return
    }
    val frame = glView.holder.surfaceFrame
    val bitmap = Bitmap.createBitmap(frame.width().coerceAtLeast(1), frame.height().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    try {
        PixelCopy.request(
            glView,
            bitmap,
            { copyResult ->
                if (copyResult != PixelCopy.SUCCESS) {
                    bitmap.recycle()
                    onResult(false)
                    return@request
                }
                val saved = runCatching {
                    val fileName = "Masalik_${System.currentTimeMillis()}.png"
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Masalik")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: return@runCatching false
                    resolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                    true
                }.getOrDefault(false)
                bitmap.recycle()
                onResult(saved)
            },
            Handler(Looper.getMainLooper())
        )
    } catch (_: Throwable) {
        bitmap.recycle()
        onResult(false)
    }
}
