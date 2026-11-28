package com.masalik.yemen.renderer

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.Choreographer

/** Dispatch frame requests against Android's display clock, rather than sleeping the GL thread. */
internal class FramePacedSurfaceView(context: Context) : GLSurfaceView(context), Choreographer.FrameCallback {
    var targetFps: Int = 60
    private var running = false
    private var resumed = true
    private var nextFrameNs = 0L

    private fun startClock() {
        if (isAttachedToWindow && resumed && !running) {
            running = true
            nextFrameNs = 0
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun stopClock() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startClock()
    }

    override fun onDetachedFromWindow() {
        stopClock()
        super.onDetachedFromWindow()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        startClock()
    }

    override fun onPause() {
        resumed = false
        stopClock()
        super.onPause()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val period = 1_000_000_000L / targetFps.coerceIn(30, 60)
        if (nextFrameNs == 0L || frameTimeNanos >= nextFrameNs - 750_000L) {
            requestRender()
            nextFrameNs = if (nextFrameNs > 0 && frameTimeNanos - nextFrameNs < period)
                nextFrameNs + period else frameTimeNanos + period
        }
        Choreographer.getInstance().postFrameCallback(this)
    }
}
