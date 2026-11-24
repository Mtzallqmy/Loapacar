package com.masalik.yemen.renderer

import android.opengl.GLES30
import android.util.Log

/** Asynchronous GPU timer. Never stalls rendering to read an unfinished sample. */
internal class FrameProbe {
    private val id=IntArray(1)
    private val value=IntArray(1)
    private var pending=false
    private var active=false
    private var frames=0
    init {
        val extensions=GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty().split(' ')
        if ("GL_EXT_disjoint_timer_query" in extensions) GLES30.glGenQueries(1,id,0)
        Log.i("CitiesGPU","timer="+(id[0]!=0)+" renderer="+GLES30.glGetString(GLES30.GL_RENDERER))
    }
    fun begin() {
        if (id[0]==0) return
        if (pending) {
            GLES30.glGetQueryObjectuiv(id[0], GLES30.GL_QUERY_RESULT_AVAILABLE,value,0)
            if (value[0] == 0) return
            GLES30.glGetIntegerv(0x8FBB,value,0) // GL_GPU_DISJOINT_EXT
            if (value[0] == 0) {
                GLES30.glGetQueryObjectuiv(id[0],GLES30.GL_QUERY_RESULT,value,0)
                Log.i("CitiesGPU","gpu_ms="+(Integer.toUnsignedLong(value[0])/1000000.0))
            }
            pending=false
        }
        if (frames++ % 45 == 0) {
            GLES30.glBeginQuery(0x88BF,id[0]) // GL_TIME_ELAPSED_EXT
            active=true
        }
    }
    fun end() { if(active) { GLES30.glEndQuery(0x88BF); active=false; pending=true } }
}
