package com.masalik.yemen.renderer

import kotlin.math.*

/** Continuous upper-body shell. Window borders, pillars and glass share the same curved surface. */
internal object Coachwork {
    enum class Profile(val frontRake: Float, val rearRake: Float, val taper: Float) {
        SALOON(.25f,.22f,.17f), COUPE(.31f,.31f,.20f), WAGON(.19f,.09f,.11f),
        PICKUP(.24f,.075f,.12f), COMMERCIAL(.105f,.045f,.075f)
    }
    enum class Material { PAINT, GLAZING, RUBBER, BRIGHTWORK }
    private data class Point(val x: Float,val y: Float,val z: Float)

    fun build(profile: Profile): Map<Material,FloatArray> {
        val vertices=mutableMapOf<Material,MutableList<Float>>()
        val steps=64
        val heights=floatArrayOf(0f,.075f,.105f,.135f,.19f,.28f,.44f,.62f,.77f,.835f,.87f,.91f,.96f,1f)
        fun point(a: Double, h: Float): Point {
            val sx=sin(a); val cz=cos(a)
            // Rounded plan form; extra tumblehome near the roof rounds the cant rails.
            val norm=(abs(sx).pow(5.0)+abs(cz).pow(5.0)).pow(.2)
            val top=h*h
            val x=(sx/norm).toFloat() * .5f * (1f-profile.taper*h-.04f*top*top)
            val front=.5f-profile.frontRake*h
            val back=-.5f+profile.rearRake*h
            val z=(front+back)*.5f + (cz/norm).toFloat()*(front-back)*.5f
            return Point(x,h-.5f,z)
        }
        fun vertex(a: Double,h: Float): List<Float> {
            val p=point(a,h)
            val left=point(a-.002,h); val right=point(a+.002,h)
            val low=point(a,h-.002f); val high=point(a,h+.002f)
            val dx=right.x-left.x; val dz=right.z-left.z
            val ux=high.x-low.x; val uy=high.y-low.y; val uz=high.z-low.z
            val nx=-dz*uy; val ny=dz*ux-dx*uz; val nz=dx*uy
            val length=sqrt(nx*nx+ny*ny+nz*nz)
            return listOf(p.x,p.y,p.z,nx/length,ny/length,nz/length,
                if(abs(cos(a))>abs(sin(a))) p.x+.5f else p.z+.5f,h)
        }
        for(i in 0 until steps) {
            val a=2*PI*i/steps; val b=2*PI*(i+1)/steps; val mid=(a+b)/2
            val sx=abs(sin(mid));val cz=cos(mid)
            val side=sx>.77; val end=sx<.63
            val post=side && (abs(cz-.09)<.068 || (profile==Profile.COMMERCIAL && abs(cz+.41)<.07))
            for(j in 0 until heights.lastIndex) {
                val h=heights[j];val next=heights[j+1]
                val material=when {
                    h==.075f -> Material.BRIGHTWORK
                    h<.105f || h>=.87f || (!side && !end) -> Material.PAINT
                    h<.135f || h>=.835f || post -> Material.RUBBER
                    else -> Material.GLAZING
                }
                val out=vertices.getOrPut(material){ArrayList()}
                val p=vertex(a,h); val q=vertex(b,h);val r=vertex(b,next);val s=vertex(a,next)
                for(v in listOf(p,q,r,p,r,s)) out.addAll(v)
            }
            // Domed roof, with a second ring creating a narrow highlight around the edge.
            val pa=point(a,1f);val pb=point(b,1f)
            fun roof(p:Point,k:Float,y:Float) = listOf(p.x*k,y,p.z*k,p.x*.28f,1f,p.z*.25f,p.x+.5f,p.z+.5f)
            val outerA=roof(pa,1f,.5f);val outerB=roof(pb,1f,.5f)
            val innerA=roof(pa,.89f,.55f);val innerB=roof(pb,.89f,.55f)
            val out=vertices.getValue(Material.PAINT)
            for(v in listOf(outerA,outerB,innerB,outerA,innerB,innerA,innerA,innerB,
                listOf(0f,.573f,0f,0f,1f,0f,.5f,.5f))) out.addAll(v)
        }
        return vertices.mapValues { it.value.toFloatArray() }
    }
}
