package com.masalik.yemen.game

import com.masalik.yemen.renderer.VisualMeshData
import org.junit.Test
import org.junit.Assert.*

class VisualMeshTest {
    @Test fun roundedBodyHasFiniteUnitNormalsAndBeveledCorners() {
        val mesh = VisualMeshData.roundedBox()
        assertEquals(0, mesh.size % 24)
        assertTrue(mesh.size > 288)
        for (i in mesh.indices step 8) {
            for (j in 0..7) assertTrue(mesh[i+j].isFinite())
            for (j in 0..2) assertTrue(kotlin.math.abs(mesh[i+j]) <= 0.501f)
            val n = mesh[i+3]*mesh[i+3]+mesh[i+4]*mesh[i+4]+mesh[i+5]*mesh[i+5]
            assertEquals(1f,n,0.001f)
        }
    }
    @Test fun humanMeshIsClosedProfileWithNormalizedNormals() {
        val mesh = VisualMeshData.ellipsoid()
        assertEquals(12*8*6*8, mesh.size)
        for (i in mesh.indices step 8) {
            for (j in 0..7) assertTrue(mesh[i+j].isFinite())
            val n = mesh[i+3]*mesh[i+3]+mesh[i+4]*mesh[i+4]+mesh[i+5]*mesh[i+5]
            assertEquals(1f,n,0.001f)
        }
    }
}
