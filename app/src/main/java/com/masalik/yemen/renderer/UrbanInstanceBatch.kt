package com.masalik.yemen.renderer

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class UrbanMaterial(
    val texture: Int, val r: Float, val g: Float, val b: Float,
    val uvX: Float, val uvY: Float, val emR: Float, val emG: Float, val emB: Float,
    val metallic: Float, val roughness: Float
)

/** Opaque buildings and street furniture share one cube VBO and instance transforms. */
internal class UrbanInstanceBatch {
    private val groups = LinkedHashMap<UrbanMaterial, MutableList<FloatArray>>()
    private val bufferId = IntArray(1).also { GLES30.glGenBuffers(1, it, 0) }[0]
    private var scratch = ByteBuffer.allocateDirect(256 * 64).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun add(material: UrbanMaterial, matrix: FloatArray) {
        groups.getOrPut(material) { ArrayList() }.add(matrix.copyOf())
    }

    fun flush(mesh: GpuMesh, shaders: ShaderLibrary, vp: FloatArray, whiteTexture: Int) {
        if (groups.isEmpty()) return
        GLES30.glUniform1i(shaders.uInstanced, 1)
        GLES30.glUniformMatrix4fv(shaders.uViewProjection, 1, false, vp, 0)
        for ((mat, matrices) in groups) {
            if (scratch.capacity() < matrices.size * 16)
                scratch = ByteBuffer.allocateDirect(matrices.size * 64).order(ByteOrder.nativeOrder()).asFloatBuffer()
            scratch.clear()
            matrices.forEach { scratch.put(it) }
            scratch.flip()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufferId)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, matrices.size * 64, scratch, GLES30.GL_STREAM_DRAW)
            for (column in 0..3) {
                val attribute = 3 + column
                GLES30.glEnableVertexAttribArray(attribute)
                GLES30.glVertexAttribPointer(attribute, 4, GLES30.GL_FLOAT, false, 64, column * 16)
                GLES30.glVertexAttribDivisor(attribute, 1)
            }
            GLES30.glUniform4f(shaders.uBaseColor, mat.r, mat.g, mat.b, 1f)
            GLES30.glUniform3f(shaders.uEmissive, mat.emR, mat.emG, mat.emB)
            GLES30.glUniform1f(shaders.uMetallic, mat.metallic)
            GLES30.glUniform1f(shaders.uRoughness, mat.roughness)
            GLES30.glUniform1f(shaders.uWetness, 0f)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mat.texture)
            GLES30.glUniform1f(shaders.uUseTexture, if (mat.texture == whiteTexture) 0f else 1f)
            GLES30.glUniform2f(shaders.uUvScale, mat.uvX, mat.uvY)
            mesh.draw(matrices.size)
        }
        for (attribute in 3..6) {
            GLES30.glVertexAttribDivisor(attribute, 0)
            GLES30.glDisableVertexAttribArray(attribute)
        }
        GLES30.glUniform1i(shaders.uInstanced, 0)
        groups.clear()
    }
}
