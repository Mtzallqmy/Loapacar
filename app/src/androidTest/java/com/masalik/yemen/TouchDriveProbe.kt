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
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
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
            val start=SystemClock.uptimeMillis()
            fun touch(action:Int,points:List<Rect>) {
                val props=Array(points.size){ i->MotionEvent.PointerProperties().apply { id=i; toolType=MotionEvent.TOOL_TYPE_FINGER } }
                val coords=Array(points.size){ i->MotionEvent.PointerCoords().apply {
                    x=points[i].exactCenterX(); y=points[i].exactCenterY(); pressure=1f; size=.2f
                } }
                val e=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,points.size,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
                try { sendPointerSync(e) } finally { e.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN,listOf(gas))
            await { vm.inputThrottle>.06f }
            println("Gas pressed at "+gas+" steer="+steer)
            val dispatchMs=SystemClock.uptimeMillis()-start
            touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(gas,steer))
            await { abs(vm.inputSteerNorm)>.12f && vm.inputThrottle>.06f && vm.uiState.value.telemetry.speedKmh>1f }
            val peak=vm.uiState.value.telemetry.speedKmh
            touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(gas,steer))
            await { abs(vm.inputSteerNorm)<.01f && vm.inputThrottle>.06f }
            touch(MotionEvent.ACTION_UP,listOf(gas))
            await { vm.inputThrottle==0f }
            val brake=widget("brake_pedal")
            touch(MotionEvent.ACTION_DOWN,listOf(brake))
            await { vm.inputBrake>.06f }
            uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            await { vm.inputBrake==0f && vm.inputThrottle==0f && vm.inputSteerNorm==0f }
            result.putString("stream","PASS two pointers, independent release, focus loss. Emulator dispatch observation=${dispatchMs}ms, speed=${peak}km/h")
            finish(Activity.RESULT_OK,result)
        } catch (error:Throwable) {
            result.putString("stream","FAIL "+android.util.Log.getStackTraceString(error))
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
            if(n.viewIdResourceName?.substringAfterLast('/')==id) return Rect().also { n.getBoundsInScreen(it) }
            for (i in 0 until n.childCount) visit(n.getChild(i))?.let { return it }
            return null
        }
        await { rect=visit(uiAutomation.rootInActiveWindow); rect!=null }
        return requireNotNull(rect)
    }
}
