package com.masalik.yemen.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import com.masalik.yemen.game.VehicleTelemetry
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

internal class CabinDisplays {
    private val gauge = CanvasTexture(704, 256)
    private val glass = CanvasTexture(640, 304)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val random = Random(5431)
    private val droplets = Array(145) { Drop() }
    private var lastGauge = -1f
    private var lastGlass = -1f
    private var lastAngle = PARKED_ANGLE

    private class Drop {
        var x = 0f
        var y = 0f
        var radius = 0f
        var speed = 0f
        var respawn = 0f
    }

    init {
        for (drop in droplets) reset(drop, true)
    }

    fun cluster(layout: CabinLayout, state: VehicleTelemetry, blink: Boolean, clock: Float): Int {
        if (clock - lastGauge < .082f && lastGauge >= 0f) return gauge.id
        lastGauge = clock
        val c = gauge.canvas
        c.drawColor(Color.rgb(12, 16, 20))
        val main = Color.rgb(232, 237, 226)
        val dim = Color.rgb(161, 175, 172)
        val accent = when (layout) {
            CabinLayout.PICKUP -> Color.rgb(250, 191, 108)
            CabinLayout.TOURER -> Color.rgb(155, 214, 201)
            CabinLayout.SEDAN -> Color.rgb(139, 219, 241)
            CabinLayout.SPORT -> Color.rgb(248, 118, 94)
            CabinLayout.COMMERCIAL -> Color.rgb(187, 225, 155)
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(26, 36, 39)
        c.drawRoundRect(7f, 7f, 697f, 249f, 26f, 26f, paint)
        paint.color = Color.rgb(15, 21, 25)
        c.drawRoundRect(12f, 12f, 692f, 244f, 22f, 22f, paint)

        val rpm = state.rpm / state.redlineRpm.coerceAtLeast(1f)
        if (layout.digitalCluster) {
            val segments = 22
            val first = 78f
            val stride = 24.8f
            for (i in 0 until segments) {
                paint.color = when {
                    i / segments.toFloat() >= rpm -> Color.rgb(41, 52, 56)
                    i >= 18 -> Color.rgb(251, 92, 79)
                    else -> accent
                }
                val x = first + i * stride
                c.drawRoundRect(x, 43f, x + 18f, 59f, 2f, 2f, paint)
            }
            label(c, "1  ·  2  ·  3  ·  4  ·  5  ·  6", 352f, 89f, 21f, dim)
            label(c, state.speedKmh.roundToInt().toString(), 318f, 174f, 83f, main, true)
            label(c, "كم/س", 327f, 204f, 24f, dim)
            label(c, state.gear, 521f, 149f, 48f, accent, true)
        } else {
            dial(c, 133f, 127f, state.speedKmh / 160f, "كم/س", 160, accent, main)
            dial(c, 571f, 127f, rpm, "RPM", (state.redlineRpm / 1000).roundToInt(), accent, main)
            label(c, state.speedKmh.roundToInt().toString(), 352f, 129f, 62f, main, true)
            label(c, state.gear, 352f, 173f, 42f, accent, true)
        }

        val fuel = (state.fuelLiters / state.fuelTankCapacityL.coerceAtLeast(1f)).coerceIn(0f, 1f)
        paint.color = Color.rgb(60, 71, 73)
        c.drawRoundRect(286f, 219f, 420f, 227f, 3f, 3f, paint)
        paint.color = if (fuel < .15f) Color.rgb(254, 104, 82) else accent
        c.drawRoundRect(286f, 219f, 286f + 134f * fuel, 227f, 3f, 3f, paint)
        if (blink && state.leftIndicatorOn) label(c, "◀", 261f, 41f, 23f, accent)
        if (blink && state.rightIndicatorOn) label(c, "▶", 443f, 41f, 23f, accent)
        if (state.handbrake) label(c, "Ⓟ", 352f, 35f, 23f, Color.rgb(255, 103, 89), true)
        if (state.lowFuelWarning) label(c, "وقود", 580f, 232f, 22f, Color.rgb(255, 131, 87), true)
        gauge.upload()
        return gauge.id
    }

    private fun label(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        canvas.drawText(text, x, y, paint)
    }

    private fun dial(canvas: Canvas, x: Float, y: Float, progress: Float, name: String,
                     max: Int, accent: Int, main: Int) {
        paint.color = Color.rgb(67, 83, 83)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawArc(x - 94f, y - 94f, x + 94f, y + 94f, 135f, 270f, false, paint)
        paint.style = Paint.Style.FILL
        for (tick in 0..20) {
            val a = (135f + tick * 13.5f) * PI.toFloat() / 180f
            val inner = if (tick % 5 == 0) 77f else 84f
            paint.strokeWidth = if (tick % 5 == 0) 3f else 1.7f
            paint.color = if (tick == 20) accent else main
            canvas.drawLine(x + cos(a) * inner, y + sin(a) * inner, x + cos(a) * 89f, y + sin(a) * 89f, paint)
        }
        label(canvas, "0", x - 43f, y + 46f, 18f, main)
        label(canvas, max.toString(), x + 43f, y + 46f, 18f, main)
        label(canvas, name, x, y + 75f, 20f, main)
        val angle = (135f + 270f * progress.coerceIn(0f, 1f)) * PI.toFloat() / 180f
        paint.color = accent
        paint.strokeWidth = 4f
        canvas.drawLine(x, y, x + 72f * cos(angle), y + 72f * sin(angle), paint)
        canvas.drawCircle(x, y, 7f, paint)
    }

    fun windshield(density: Float, playerSpeed: Float, phase: Float, clock: Float): Int {
        val elapsed = clock - lastGlass
        if (elapsed < .042f && lastGlass >= 0f) return glass.id
        val dt = elapsed.coerceIn(0f, .10f)
        val angle = bladeAngle(phase)
        val previousAngle = if (elapsed > .30f || lastGlass < 0f) angle else lastAngle
        lastGlass = clock
        lastAngle = angle
        val c = glass.canvas
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val w = glass.width.toFloat()
        val h = glass.height.toFloat()
        val blade = h * .84f
        val airflow = (playerSpeed / 105f).coerceIn(0f, 1.35f)
        val dropCount = (density * droplets.size).roundToInt()
        for (i in 0 until dropCount) {
            val drop = droplets[i]
            if (drop.respawn > 0f) {
                drop.respawn -= dt
                continue
            }
            drop.y += dt * drop.speed * (1f - airflow * 1.27f)
            drop.x += dt * airflow * .016f * (drop.x - .5f)
            if (drop.y > 1f || drop.y < 0f) reset(drop, false)
            val x = drop.x * w
            val y = drop.y * h
            if (angle != PARKED_ANGLE && (swept(x, y, .285f*w, .99f*h, blade, previousAngle, angle) ||
                        swept(x, y, .658f*w, .99f*h, blade, previousAngle, angle))) {
                reset(drop, false)
                drop.respawn = .45f + random.nextFloat() * 1.4f / density.coerceAtLeast(.2f)
                continue
            }
            val radius = drop.radius
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(55, 12, 23, 29)
            c.drawOval(x - radius * .72f, y - radius * .35f, x + radius, y + radius * 1.5f, paint)
            paint.color = Color.argb(111, 186, 216, 231)
            c.drawOval(x - radius * .68f, y - radius, x + radius * .67f, y + radius * .8f, paint)
            paint.color = Color.argb(149, 234, 244, 247)
            c.drawCircle(x - radius * .24f, y - radius * .48f, radius * .32f, paint)
            if (airflow > .35f && radius > 2.2f) {
                paint.color = Color.argb(69, 186, 216, 231)
                paint.strokeWidth = .8f + radius * .22f
                c.drawLine(x, y, x + airflow * (drop.x - .5f) * 11f, y - (airflow * radius * 4f), paint)
            }
        }
        for (pivot in floatArrayOf(.285f*w, .658f*w)) {
            val y = .99f*h
            val dx = cos(angle) * blade
            val dy = sin(angle) * blade
            paint.strokeWidth = 4.4f
            paint.color = Color.rgb(27, 35, 39)
            c.drawLine(pivot, y, pivot + dx * .90f, y + dy * .90f, paint)
            paint.strokeWidth = 6.5f
            paint.color = Color.rgb(13, 18, 21)
            c.drawLine(pivot + dx * .39f, y + dy * .39f, pivot + dx, y + dy, paint)
        }
        glass.upload()
        return glass.id
    }

    private fun reset(drop: Drop, anywhere: Boolean) {
        drop.x = .016f + random.nextFloat() * .963f
        drop.y = if (anywhere) random.nextFloat() else random.nextFloat() * .83f + .07f
        drop.radius = .75f + random.nextFloat() * 2.80f
        drop.speed = .03f + random.nextFloat() * .12f
        drop.respawn = 0f
    }

    companion object {
        private const val PARKED_ANGLE = -.13f
        private fun bladeAngle(phase: Float) = PARKED_ANGLE - (1f - cos(phase)) * .98f

        private fun swept(x: Float, y: Float, px: Float, py: Float, length: Float,
                          old: Float, current: Float): Boolean {
            val dx = x - px
            val dy = y - py
            if (dx*dx + dy*dy > length*length) return false
            val angle = kotlin.math.atan2(dy, dx)
            return angle >= min(old, current) - .064f && angle <= maxOf(old, current) + .064f
        }
    }

    private class CanvasTexture(val width: Int, val height: Int) {
        private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val id: Int

        init {
            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            id = textures[0]
            bind()
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        }

        private fun bind() = GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        fun upload() {
            bind()
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
    }
}
