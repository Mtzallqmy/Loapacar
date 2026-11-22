package com.masalik.yemen.renderer

import kotlin.math.*

/** CPU-side indexed profile extrusion expanded to the renderer's eight-float vertex layout. */
object VisualMeshData {
    fun roundedBox(bevel: Float = 0.14f): FloatArray {
        val out = ArrayList<Float>()
        val b = bevel.coerceIn(0.01f, 0.3f)
        fun vertex(face: Int, u: Float, v: Float): FloatArray {
            val p = when(face) {
                0 -> floatArrayOf(0.5f,u,v)
                1 -> floatArrayOf(-0.5f,u,v)
                2 -> floatArrayOf(u,0.5f,v)
                3 -> floatArrayOf(u,-0.5f,v)
                4 -> floatArrayOf(u,v,0.5f)
                else -> floatArrayOf(u,v,-0.5f)
            }
            val q = FloatArray(3) { p[it].coerceIn(-0.5f+b,0.5f-b) }
            val delta = FloatArray(3) { p[it]-q[it] }
            val length = sqrt(delta.sumOf { (it*it).toDouble() }).toFloat().coerceAtLeast(0.0001f)
            val n = FloatArray(3) { delta[it]/length }
            return floatArrayOf(q[0]+n[0]*b,q[1]+n[1]*b,q[2]+n[2]*b,n[0],n[1],n[2],u+0.5f,v+0.5f)
        }
        val cuts = listOf(-0.5f,-0.5f+b,0f,0.5f-b,0.5f).distinct().sorted()
        for(face in 0..5) for(i in 0 until cuts.lastIndex) for(j in 0 until cuts.lastIndex) {
            val a=vertex(face,cuts[i],cuts[j]); val c=vertex(face,cuts[i+1],cuts[j+1])
            val b0=vertex(face,cuts[i+1],cuts[j]);val d=vertex(face,cuts[i],cuts[j+1])
            for(vtx in listOf(a,b0,c,a,c,d)) out.addAll(vtx.toList())
        }
        return out.toFloatArray()
    }

    fun ellipsoid(slices: Int = 12, rings: Int = 8): FloatArray {
        val out=ArrayList<Float>()
        fun vertex(i:Int,j:Int):FloatArray {
            val phi=PI*i/rings; val theta=2*PI*j/slices
            val nx=(sin(phi)*cos(theta)).toFloat();val ny=cos(phi).toFloat();val nz=(sin(phi)*sin(theta)).toFloat()
            return floatArrayOf(nx*0.5f,ny*0.5f,nz*0.5f,nx,ny,nz,j.toFloat()/slices,i.toFloat()/rings)
        }
        for(i in 0 until rings) for(j in 0 until slices) {
            for(v in listOf(vertex(i,j),vertex(i+1,j),vertex(i+1,j+1),vertex(i,j),vertex(i+1,j+1),vertex(i,j+1))) out.addAll(v.toList())
        }
        return out.toFloatArray()
    }
}
