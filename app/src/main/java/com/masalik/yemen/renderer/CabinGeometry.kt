package com.masalik.yemen.renderer

import com.masalik.yemen.game.VehicleId
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Coordinates in metres, with +Z toward the bonnet and +X toward the passenger on LHD cars. */
internal enum class CabinLayout(
    val width: Float, val cowl: Float, val roof: Float,
    val glassBaseZ: Float, val glassTopZ: Float, val dashDepth: Float,
    val seatY: Float, val seatZ: Float, val wheelSize: Float,
    val digitalCluster: Boolean
) {
    PICKUP(1.72f, 1.18f, 2.03f, 1.27f, .54f, .78f, 1.58f, -.21f, .39f, false),
    TOURER(1.83f, 1.21f, 2.11f, 1.27f, .50f, .84f, 1.62f, -.25f, .40f, false),
    SEDAN(1.68f, .97f, 1.72f, 1.26f, .32f, .78f, 1.34f, -.28f, .36f, true),
    SPORT(1.70f, .90f, 1.55f, 1.24f, .38f, .71f, 1.20f, -.23f, .34f, true),
    COMMERCIAL(2.05f, 1.43f, 2.52f, 1.49f, 1.04f, .97f, 1.91f, -.02f, .47f, false);

    val driverX get() = width * .236f
    val wheelY get() = cowl - .015f
    val wheelZ get() = dashDepth - .195f
    val clusterY get() = cowl + .12f
    val clusterZ get() = dashDepth + .135f
    val clusterWidth get() = width * .314f

    companion object {
        fun of(vehicleId: VehicleId) = when (vehicleId) {
            VehicleId.SAQR, VehicleId.BADIYA, VehicleId.MUNQIDH, VehicleId.NIMR -> PICKUP
            VehicleId.SHAHIQ, VehicleId.RAHHAL -> TOURER
            VehicleId.MADINA, VehicleId.ASIL, VehicleId.SAHIL -> SEDAN
            VehicleId.BARQ -> SPORT
            VehicleId.HAFILAT, VehicleId.QAFILA, VehicleId.JABAL -> COMMERCIAL
        }
    }
}

internal object CabinGeometry {
    enum class Material(val r: Float, val g: Float, val b: Float, val metal: Float, val roughness: Float) {
        HIDE(.116f,.124f,.135f,.04f,.79f),
        SHELL(.228f,.236f,.242f,.04f,.71f),
        RUBBER(.067f,.075f,.083f,.02f,.93f),
        ALLOY(.48f,.53f,.56f,.65f,.31f),
        TRIM(.38f,.21f,.115f,.28f,.37f),
        STITCH(.53f,.48f,.41f,.02f,.85f),
        CLOTH(.29f,.30f,.28f,.02f,.96f)
    }

    private val unit = VisualMeshData.roundedBox(.18f)

    fun bake(layout: CabinLayout, driverSign: Int): Map<Material, FloatArray> {
        val batches = linkedMapOf<Material, MutableList<Float>>()
        val width = layout.width
        val dash = layout.dashDepth
        val cowl = layout.cowl
        val driverX = layout.driverX * driverSign
        val tall = layout == CabinLayout.COMMERCIAL
        val sporty = layout == CabinLayout.SPORT
        val premium = layout == CabinLayout.SEDAN || layout == CabinLayout.TOURER
        val trim = when {
            premium -> Material.TRIM
            sporty -> Material.RUBBER
            else -> Material.ALLOY
        }

        fun part(material: Material, x: Float, y: Float, z: Float,
                 w: Float, h: Float, d: Float, pitch: Float = 0f, yaw: Float = 0f) {
            val out = batches.getOrPut(material) { ArrayList() }
            val cp = cos(pitch); val sp = sin(pitch)
            val cy = cos(yaw); val sy = sin(yaw)
            for (i in unit.indices step 8) {
                val px = unit[i] * w
                val py = unit[i + 1] * h * cp - unit[i + 2] * d * sp
                val pz = unit[i + 1] * h * sp + unit[i + 2] * d * cp
                val nx = unit[i + 3] / w
                val ny = unit[i + 4] / h * cp - unit[i + 5] / d * sp
                val nz = unit[i + 4] / h * sp + unit[i + 5] / d * cp
                val norm = sqrt(nx * nx + ny * ny + nz * nz)
                out.add(x + px * cy + pz * sy)
                out.add(y + py)
                out.add(z - px * sy + pz * cy)
                out.add((nx * cy + nz * sy) / norm)
                out.add(ny / norm)
                out.add((-nx * sy + nz * cy) / norm)
                out.add(unit[i + 6])
                out.add(unit[i + 7])
            }
        }

        // Main dashboard, brow, recessed instruments and horizontal belt line.
        part(Material.SHELL, 0f, cowl - .19f, dash + .13f, width, .34f, .52f, -.15f)
        part(Material.HIDE, 0f, cowl - .025f, dash + .23f, width * .99f, .155f, .60f, -.045f)
        part(Material.RUBBER, driverX, layout.clusterY, layout.clusterZ + .047f,
            layout.clusterWidth * 1.16f, .24f, .19f, -.13f)
        part(trim, 0f, cowl - .059f, dash - .131f, width * .955f, .028f, .035f)
        for (i in -17..17) {
            part(Material.STITCH, i * width / 38f, cowl + .025f, dash - .066f, .021f, .0037f, .008f)
        }

        // Console families each have their own silhouette, vents and tactile controls.
        val stackX = if (sporty) driverSign * .047f else 0f
        val stackY = cowl - if (tall) .12f else .15f
        val stackW = if (premium) .33f else .25f
        part(Material.RUBBER, stackX, stackY, dash - .134f, stackW, .29f, .094f, -.16f)
        part(trim, stackX, stackY + .102f, dash - .189f, stackW * 1.06f, .033f, .028f)
        for (side in listOf(-1, 1)) {
            val x = side * width * .387f
            part(trim, x, cowl - .046f, dash - .112f, .223f, .123f, .036f)
            part(Material.RUBBER, x, cowl - .046f, dash - .138f, .196f, .090f, .025f)
            for (slat in -2..2) {
                part(Material.SHELL, x, cowl - .046f + slat * .015f, dash - .153f, .178f, .006f, .013f)
            }
            part(Material.HIDE, side * (width * .5f + .021f), cowl - .12f, .29f,
                .108f, .16f, 1.39f)
            part(trim, side * (width * .5f - .037f), cowl - .126f, .43f,
                .022f, .036f, .49f)
        }
        for (knob in -1..1) {
            part(Material.ALLOY, stackX + knob * .066f, stackY - .033f, dash - .205f,
                .045f, .045f, .032f)
        }
        for (row in 0..1) for (key in -2..2) {
            part(Material.SHELL, stackX + key * .040f, stackY + .030f + row * .028f,
                dash - .186f, .033f, .018f, .016f)
        }
        if (tall) {
            val side = -driverSign
            part(Material.CLOTH, side * .54f, cowl - .18f, .43f, .38f, .071f, .31f)
            part(Material.TRIM, side * .79f, cowl + .18f, .83f, .042f, .91f, .042f)
            part(Material.RUBBER, stackX, cowl - .065f, .55f, .074f, .16f, .087f)
        } else {
            part(Material.SHELL, stackX, cowl - .38f, .47f, stackW, .27f, .83f, -.29f)
            part(Material.RUBBER, stackX, cowl - .235f, .30f, .14f, .075f, .18f)
        }

        // The posts and glass follow the same rake so the wipers report the real opening.
        val glassHeight = layout.roof - cowl
        val rake = layout.glassTopZ - layout.glassBaseZ
        val length = sqrt(glassHeight * glassHeight + rake * rake)
        val angle = kotlin.math.atan2(rake, glassHeight)
        for (side in listOf(-1, 1)) {
            part(Material.HIDE, side * width * .488f, (layout.roof + cowl) / 2f,
                (layout.glassBaseZ + layout.glassTopZ) / 2f, .093f, length, .098f, angle)
        }
        part(Material.CLOTH, 0f, layout.roof, layout.glassTopZ - .17f, width + .075f, .079f, .46f, .12f)
        part(Material.HIDE, 0f, layout.roof - .054f, layout.glassTopZ + .017f, width, .092f, .083f)
        return batches.mapValues { it.value.toFloatArray() }
    }

    fun panel(width: Float, height: Float, bottomZ: Float = 0f, topZ: Float = 0f, mirror: Boolean = false): FloatArray {
        val normalScale = sqrt(height * height + (topZ - bottomZ) * (topZ - bottomZ))
        val normalY = (topZ - bottomZ) / normalScale
        val normalZ = -height / normalScale
        fun point(x: Float, y: Float, z: Float, u: Float, v: Float) =
            floatArrayOf(x, y, z, 0f, normalY, normalZ, if (mirror) u else 1f - u, if (mirror) 1f - v else v)
        val a = point(-width / 2, -height / 2, bottomZ, 0f, 1f)
        val b = point(width / 2, -height / 2, bottomZ, 1f, 1f)
        val c = point(width / 2, height / 2, topZ, 1f, 0f)
        val d = point(-width / 2, height / 2, topZ, 0f, 0f)
        return a + d + c + a + c + b
    }
}
