package com.masalik.yemen

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.masalik.yemen.game.TouchControlMode
import com.masalik.yemen.ui.MasalikGameViewModel
import kotlin.math.abs

/** Inject two touchscreen pointers into the actual driving view on Android. */
class TouchDriveProbe : Instrumentation() {
    private var backupMode = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        backupMode = arguments?.getString("mode") == "backup"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        if (backupMode) { backupProbe(result); return }
        var stage="start"
        try {
            val launch=Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("masalik_test_cockpit",true).putExtra("masalik_test_vehicle","MADINA")
                .putExtra("masalik_test_graphics","LOW").putExtra("masalik_test_city","city_taiz_sabir")
            val activity=startActivitySync(launch) as MainActivity
            lateinit var vm: MasalikGameViewModel
            runOnMainSync { vm=ViewModelProvider(activity)[MasalikGameViewModel::class.java] }
            await { vm.uiState.value.profileReady }
            runOnMainSync { vm.updateHudSettings(vm.uiState.value.saveState.hudSettings.copy(controlMode=TouchControlMode.BUTTONS)) }
            await { activity.hasWindowFocus() }
            SystemClock.sleep(850)
            val gas=widget("gas_pedal")
            val steer=widget("steer_right_button")
            val origin=IntArray(2)
            runOnMainSync { activity.window.decorView.getLocationOnScreen(origin) }
            var start=SystemClock.uptimeMillis()
            fun touch(action:Int,points:List<Rect>) {
                val props=Array(points.size){ i->MotionEvent.PointerProperties().apply { id=i; toolType=MotionEvent.TOOL_TYPE_FINGER } }
                val coords=Array(points.size){ i->MotionEvent.PointerCoords().apply {
                    x=points[i].exactCenterX()-origin[0]; y=points[i].exactCenterY()-origin[1]; pressure=1f; size=.2f
                } }
                val e=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,points.size,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
                try { runOnMainSync { activity.dispatchTouchEvent(e) } } finally { e.recycle() }
            }
            stage="press gas $gas, steering $steer"
            touch(MotionEvent.ACTION_DOWN,listOf(gas))
            await { vm.inputThrottle>.06f }
            println("Gas pressed at "+gas+" steer="+steer)
            val dispatchMs=SystemClock.uptimeMillis()-start
            stage="overlap two pointers"
            touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(gas,steer))
            await { abs(vm.inputSteerNorm)>.12f && vm.inputThrottle>.06f && vm.uiState.value.telemetry.speedKmh>1f }
            val peak=vm.uiState.value.telemetry.speedKmh
            stage="lift only the steering finger"
            touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(gas,steer))
            await { abs(vm.inputSteerNorm)<.01f && vm.inputThrottle>.06f }
            touch(MotionEvent.ACTION_UP,listOf(gas))
            await { vm.inputThrottle==0f }
            stage="brake"
            start=SystemClock.uptimeMillis()
            val brake=widget("brake_pedal")
            touch(MotionEvent.ACTION_DOWN,listOf(brake))
            await { vm.inputBrake>.06f }
            stage="clear held brake on background"
            runOnMainSync { activity.moveTaskToBack(true) }
            await { vm.inputBrake==0f && vm.inputThrottle==0f && vm.inputSteerNorm==0f }
            result.putString("stream","PASS two pointers, independent release, focus loss. Emulator dispatch observation=${dispatchMs}ms, speed=${peak}km/h")
            finish(Activity.RESULT_OK,result)
        } catch (error:Throwable) {
            result.putString("stream","FAIL during $stage: "+android.util.Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED,result)
        }
    }

    private fun backupProbe(result: Bundle) {
        try {
            val context = targetContext
            val directory = java.io.File(context.filesDir, "migration-test")
            val original = java.io.File(directory, "legacy.json")
            val expected = org.json.JSONObject(original.readText())
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, context.packageName + ".migration-test", original)
            val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setAction(Intent.ACTION_SEND).setType("application/json")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val activity = startActivitySync(launch) as MainActivity
            lateinit var vm: MasalikGameViewModel
            runOnMainSync { vm = ViewModelProvider(activity)[MasalikGameViewModel::class.java] }
            await { vm.uiState.value.profileReady }
            await { uiAutomation.rootInActiveWindow != null }
            // Touch the real share-import confirmation dialog through accessibility.
            await {
                fun choose(node: AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    if (node.text?.toString()=="استيراد الحفظ") {
                        var clickable: AccessibilityNodeInfo? = node
                        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                        return clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    }
                    for (i in 0 until node.childCount) if (choose(node.getChild(i))) return true
                    return false
                }
                choose(uiAutomation.rootInActiveWindow)
            }
            await { !vm.uiState.value.backupBusy && vm.uiState.value.saveState.missionRun?.id == expected.getJSONObject("missionRun").getString("id") }
            val db = com.masalik.yemen.data.MasalikDatabase.getInstance(context)
            val repo = com.masalik.yemen.data.MasalikRepository(context,db)
            kotlinx.coroutines.runBlocking {
                val restored = repo.loadSaveGame()
                check(restored.masalikCoins == expected.getInt("masalikCoins"))
                check(restored.driverXp == expected.getInt("driverXp"))
                check(restored.selectedVehicleId.name == expected.getString("selectedVehicleId"))
                check(restored.missionRun?.id == expected.getJSONObject("missionRun").getString("id"))
                val exported = repo.exportSaveJson(restored)
                java.io.File(directory,"restored.json").writeText(exported)
                val document = org.json.JSONObject(exported)
                check(document.getInt("schemaVersion") == 4 && document.has("tripHistory"))
            }
            result.putString("stream","PASS legacy document shared through Android, ContentResolver, validation, committed Room profile and export")
            finish(Activity.RESULT_OK,result)
        } catch (error: Throwable) {
            result.putString("stream","FAIL backup handoff: "+android.util.Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED,result)
        }
    }

    private fun await(condition:()->Boolean) {
        val end=SystemClock.uptimeMillis()+18000
        while (!condition()) { check(SystemClock.uptimeMillis()<end) { "Timed out waiting for driving state" }; SystemClock.sleep(30) }
    }

    private fun widget(id:String): Rect {
        var rect: Rect?=null
        fun visit(n:AccessibilityNodeInfo?): Rect? {
            if (n==null) return null
            if(n.isVisibleToUser && n.viewIdResourceName?.substringAfterLast('/')==id) {
                val bounds=Rect().also { n.getBoundsInScreen(it) }
                if (!bounds.isEmpty) return bounds
            }
            for (i in 0 until n.childCount) visit(n.getChild(i))?.let { return it }
            return null
        }
        await { rect=visit(uiAutomation.rootInActiveWindow); rect!=null }
        return requireNotNull(rect)
    }
}
