package com.masalik.yemen.renderer

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.Choreographer
import android.os.SystemClock
import com.masalik.yemen.game.GraphicsQuality

/** Dispatch frame requests against Android's display clock, rather than sleeping the GL thread. */
internal class FramePacedSurfaceView(context: Context) : GLSurfaceView(context), Choreographer.FrameCallback {
    private var targetFps: Int = 60
    private var quality = GraphicsQuality.HIGH
    private var scale = 1f
    private var lastAdaptMs = SystemClock.uptimeMillis()
    private var requestedWidth = 0
    private var requestedHeight = 0
    private var slowSamples = 0
    private var fastSamples = 0

    fun updateProfile(next: GraphicsQuality, fps: Int, target: Int) {
        targetFps = target.coerceIn(30, 60)
        val base = when (next) {
            GraphicsQuality.LOW -> .65f
            GraphicsQuality.MEDIUM -> .85f
            else -> 1f
        }
        if (next != quality) {
            quality = next
            scale = base
            slowSamples = 0
            fastSamples = 0
            lastAdaptMs = SystemClock.uptimeMillis()
        }
        val now = SystemClock.uptimeMillis()
        if (now-lastAdaptMs >= 2500 && next != GraphicsQuality.CINEMATIC) {
            lastAdaptMs = now
            slowSamples = if (fps > 0 && fps < targetFps*.78f) slowSamples+1 else 0
            fastSamples = if (fps >= targetFps*.98f) fastSamples+1 else 0
            if (slowSamples >= 2) {
                scale = (scale-.10f).coerceAtLeast(if (next == GraphicsQuality.LOW) .50f else .60f)
                slowSamples = 0
            } else if (fastSamples >= 4) {
                scale = (scale+.05f).coerceAtMost(base)
                fastSamples = 0
            }
        }
        resizeSurface()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        resizeSurface()
    }

    private fun resizeSurface() {
        if (width <= 0 || height <= 0) return
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        if (w != requestedWidth || h != requestedHeight) {
            requestedWidth = w
            requestedHeight = h
            holder.setFixedSize(w,h)
        }
    }
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
