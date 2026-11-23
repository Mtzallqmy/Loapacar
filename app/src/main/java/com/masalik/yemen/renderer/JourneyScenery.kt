package com.masalik.yemen.renderer

import kotlin.math.*
import com.masalik.yemen.game.QuayHumps

/** Static geometry in metres, grouped by material so repeated details share a draw call. */
internal object JourneyScenery {
    enum class Finish(val r: Float, val g: Float, val b: Float) {
        EARTH(.53f,.31f,.18f), STONE(.56f,.50f,.41f), LIME(.92f,.86f,.70f),
        WOOD(.33f,.17f,.09f), TEAL(.10f,.44f,.41f), LEAVES(.20f,.36f,.13f),
        GREEN(.36f,.47f,.17f), CANVAS(.89f,.77f,.53f), GOLD(.85f,.57f,.18f)
    }
    private data class P(val x: Float, val y: Float, val z: Float)
    private class Builder {
        private val groups = mutableMapOf<Finish, MutableList<Float>>()
        fun tri(m: Finish, a: P, b: P, c: P) {
            val ux=b.x-a.x; val uy=b.y-a.y; val uz=b.z-a.z
            val vx=c.x-a.x; val vy=c.y-a.y; val vz=c.z-a.z
            val nx=uy*vz-uz*vy; val ny=uz*vx-ux*vz; val nz=ux*vy-uy*vx
            val length=sqrt(nx*nx+ny*ny+nz*nz)
            if (length < .00001f) return
            val out=groups.getOrPut(m) { ArrayList() }
            for (p in listOf(a,b,c)) {
                out.addAll(listOf(p.x,p.y,p.z,nx/length,ny/length,nz/length,p.x*.33f,p.y*.33f+p.z*.20f))
            }
        }
        fun quad(m: Finish, a: P, b: P, c: P, d: P) { tri(m,a,b,c); tri(m,a,c,d) }
        fun box(m: Finish, x: Float, base: Float, z: Float, width: Float, height: Float, depth: Float) {
            val l=x-width/2; val r=x+width/2; val f=z-depth/2; val k=z+depth/2; val h=base+height
            val a=P(l,base,f); val b=P(r,base,f); val c=P(r,base,k); val d=P(l,base,k)
            val e=P(l,h,f); val g=P(r,h,f); val j=P(r,h,k); val i=P(l,h,k)
            quad(m,a,e,g,b); quad(m,b,g,j,c); quad(m,c,j,i,d); quad(m,d,i,e,a); quad(m,e,i,j,g)
        }
        fun ring(m: Finish, x: Float, y: Float, z: Float, bottom: Float, top: Float, height: Float, sides: Int) {
            for (i in 0 until sides) {
                fun point(j: Int, radius: Float, dy: Float): P {
                    val angle=2*PI*j/sides
                    return P(x+cos(angle).toFloat()*radius,y+dy,z+sin(angle).toFloat()*radius)
                }
                val a=point(i,bottom,0f); val b=point(i+1,bottom,0f)
                val c=point(i+1,top,height); val d=point(i,top,height)
                quad(m,a,d,c,b); tri(m,P(x,y+height,z),c,d)
            }
        }
        fun finish() = groups.mapValues { it.value.toFloatArray() }
    }

    fun quayHump(width: Float): FloatArray = Builder().apply {
        val half = width * .5f
        val lanes = listOf(-half, -half+.45f, half-.45f, half)
        fun point(i: Int, x: Float): P {
            val z = QuayHumps.halfLength * (i/8f - 1f)
            return P(x, QuayHumps.height(QuayHumps.centers.first()+z, x, half), z)
        }
        for (i in 0 until 16) for (j in 0 until lanes.lastIndex)
            quad(Finish.GOLD, point(i,lanes[j]), point(i+1,lanes[j]),
                point(i+1,lanes[j+1]), point(i,lanes[j+1]))
    }.finish().getValue(Finish.GOLD)

    fun gate(roadWidth: Float): Map<Finish, FloatArray> = Builder().apply {
        val radius = roadWidth * .5f + .85f
        for (side in listOf(-1,1)) {
            val x = side*(radius+1.85f)
            ring(Finish.EARTH,x,-.2f,0f,3.2f,2.75f,11.7f,16)
            ring(Finish.LIME,x,10.6f,0f,2.91f,2.94f,.32f,16)
            for (i in 0 until 12) {
                val a=2*PI*i/12
                box(Finish.EARTH,x+cos(a).toFloat()*2.48f,11.48f,sin(a).toFloat()*2.48f,.68f,.78f,.65f)
            }
        }
        // Continuous masonry over a true vaulted opening, inset voussoirs on both faces.
        for (i in 0 until 28) {
            val a=PI*i/28; val b=PI*(i+1)/28
            fun edge(t: Double, extra: Float, z: Float) =
                P(cos(t).toFloat()*(radius+extra),3.65f+sin(t).toFloat()*(4.7f+extra),z)
            val p=edge(a,0f,-1.9f); val q=edge(b,0f,-1.9f)
            val r=edge(a,0f,1.9f); val s=edge(b,0f,1.9f)
            quad(Finish.STONE,p,r,s,q)
            for (side in listOf(-1,1)) {
                val z=1.9f*side
                val u=edge(a,0f,z); val v=edge(b,0f,z)
                val capU=P(u.x,10.4f,z); val capV=P(v.x,10.4f,z)
                if (side==1) quad(Finish.EARTH,u,capU,capV,v) else quad(Finish.EARTH,v,capV,capU,u)
                val face=z+side*.04f
                val trim=if (i%2==0) Finish.LIME else Finish.STONE
                val k=edge(a,0f,face); val l=edge(b,0f,face)
                if (side==1) quad(trim,k,edge(a,.44f,face),edge(b,.44f,face),l)
                else quad(trim,l,edge(b,.44f,face),edge(a,.44f,face),k)
            }
        }
        box(Finish.LIME,0f,10.38f,0f,2*radius,.36f,4.05f)
        for (i in -5..5) box(Finish.EARTH,radius*i/5.7f,10.74f,0f,.68f,.68f,3.5f)
    }.finish()

    fun roadsideTree(palm: Boolean): Map<Finish,FloatArray> = Builder().apply {
        val rng = kotlin.random.Random(if (palm) 349 else 613)
        if (palm) {
            for (i in 0 until 10) {
                val bend=i*i*.003f
                val radius=.24f-i*.009f
                ring(if(i%3==0) Finish.EARTH else Finish.WOOD,bend,i*.52f,0f,
                    radius+.017f,radius,.55f,9)
            }
            for (leaf in 0 until 17) {
                val angle=leaf*2.4
                val length=if (leaf%3==0) 2.9f else 3.8f
                val dx=cos(angle).toFloat();val dz=sin(angle).toFloat()
                fun stem(t: Float) = P(.25f+dx*length*t,5.3f+1.45f*sin(PI*t).toFloat()-.59f*t,
                    dz*length*t)
                for (j in 0 until 9) {
                    val t=j/9f;val next=(j+1)/9f
                    val v=stem(t);val w=stem(next)
                    val material=if(leaf%3==0) Finish.GREEN else Finish.LEAVES
                    quad(material,P(v.x-.035f*dz,v.y,v.z+.035f*dx),P(w.x-.024f*dz,w.y,w.z+.024f*dx),
                        P(w.x+.024f*dz,w.y,w.z-.024f*dx),P(v.x+.035f*dz,v.y,v.z-.035f*dx))
                    for(side in listOf(-1,1)) {
                        val span=.17f+.49f*sin(PI*(.12f+.88f*t)).toFloat()
                        val tip=P(w.x+side*dz*span+dx*.19f,w.y-.28f,w.z-side*dx*span+dz*.19f)
                        if(side==1) tri(material,v,w,tip) else tri(material,w,v,tip)
                    }
                }
            }
        } else {
            ring(Finish.WOOD,0f,-.1f,0f,.27f,.17f,2.4f,9)
            for (i in 0 until 11) {
                val angle=2.40*i
                val reach=.35f+rng.nextFloat()*1.9f
                val x=cos(angle).toFloat()*reach;val z=sin(angle).toFloat()*reach
                val y=2.65f+(2.3f-reach)*.68f+rng.nextFloat()*.45f
                val width=.92f+rng.nextFloat()*.59f
                val m=if(i%3==0) Finish.GREEN else Finish.LEAVES
                tri(Finish.WOOD,P(-.16f,1.3f,.06f),P(.17f,1.7f,-.08f),P(x,y+.2f,z))
                ring(m,x,y-.38f,z,width*.37f,width,.59f,9)
                ring(m,x,y+.21f,z,width,width*.66f,.52f,9)
                ring(m,x,y+.73f,z,width*.66f,.07f,.28f,9)
            }
        }
    }.finish()

    fun taizMarket(): Map<Finish, FloatArray> = Builder().apply {
        for (bay in -2..2) {
            val z=bay*5.1f
            val height=6.7f+(abs(bay)%3)*1.45f
            val wall=if (bay%2==0) Finish.STONE else Finish.EARTH
            box(wall,2.1f,-.26f,z,4.0f,height,4.96f)
            box(Finish.WOOD,.032f,.07f,z,.15f,2.45f,2.9f)
            for (j in -2..2) box(Finish.EARTH,-.062f,.16f,z+j*.47f,.12f,2.25f,.32f)
            for (course in 0..2) for (j in -2..2) {
                if (abs(j)<2 && course<2) continue
                box(if ((j+course+bay)%3==0) Finish.EARTH else Finish.LIME,
                    -.04f,2.76f+course*.215f,z+j*.91f,.17f,.15f,.77f)
            }
            for (floor in 1..2) for (j in listOf(-1,1)) {
                val sill=3.12f+floor*1.31f
                box(Finish.LIME,.01f,sill-.12f,z+j*1.03f,.22f,.16f,.94f)
                box(Finish.WOOD,-.055f,sill+.025f,z+j*1.03f,.16f,.86f,.62f)
                box(Finish.TEAL,-.145f,sill+.13f,z+j*1.03f,.023f,.61f,.38f)
            }
            val roof=height-.27f
            box(Finish.LIME,2.1f,roof,z,4.13f,.19f,5.03f)
            for (j in -2..2) box(wall,.045f,roof+.19f,z+j*.86f,.33f,.35f,.56f)
            val cloth=if (bay%2==0) Finish.TEAL else Finish.CANVAS
            for (fold in 0..8) {
                val a=z-1.96f+fold*.44f
                val b=a+.45f
                val dip=if (fold%2==0) .07f else 0f
                quad(cloth,P(.09f,3.04f,a),P(.09f,3.04f,b),
                    P(-1.8f,2.68f-dip,b),P(-1.8f,2.68f-dip,a))
                quad(cloth,P(-1.8f,2.68f-dip,a),P(-1.8f,2.68f-dip,b),
                    P(-1.8f,2.49f,b),P(-1.8f,2.49f,a))
            }
            for (side in listOf(-1,1)) box(Finish.WOOD,-1.76f,0f,z+side*1.88f,.075f,2.72f,.075f)
            for (j in -2..2) {
                val x=-.76f-(j%2)*.24f
                ring(if (j%2==0) Finish.CANVAS else Finish.GOLD,x,0f,z+j*.64f,.24f,.33f,.20f,9)
                ring(Finish.CANVAS,x,.20f,z+j*.64f,.33f,.25f,.43f,9)
            }
        }
    }.finish()

    fun sabirLookout(): Map<Finish, FloatArray> = Builder().apply {
        box(Finish.STONE,3.8f,-2.9f,0f,10.6f,2.92f,23f)
        box(Finish.LIME,3.8f,.02f,0f,10.6f,.16f,23f)
        for (section in -5..5) {
            val z=section*1.96f
            box(Finish.STONE,8.8f,.14f,z,.67f,.78f,1.97f)
            box(Finish.LIME,8.8f,.92f,z,.75f,.14f,1.97f)
            box(Finish.STONE,8.8f,1.06f,z,.74f,.27f,.70f)
        }
        for (x in listOf(1.4f,5.6f)) for (z in listOf(-3.2f,3.2f)) {
            box(Finish.STONE,x,.16f,z,.57f,3.64f,.6f)
            box(Finish.LIME,x,3.66f,z,.76f,.23f,.77f)
        }
        for (beam in -4..4) box(Finish.WOOD,3.5f,3.82f,beam*.87f,5.4f,.16f,.13f)
        for (wall in -3..3) box(Finish.TEAL,3.5f+wall*.71f,3.99f,0f,.38f,.14f,7.4f)
        for (z in listOf(-6.8f,6.8f)) box(Finish.WOOD,6.7f,.42f,z,1.05f,.19f,2.26f)
    }.finish()

    fun taizHillFort(): Map<Finish, FloatArray> = Builder().apply {
        box(Finish.STONE,0f,-5f,0f,27f,8f,44f)
        box(Finish.EARTH,0f,3f,0f,25f,6.2f,43f)
        box(Finish.STONE,1.4f,9.2f,-5f,17f,3f,24f)
        for (x in listOf(-12.8f,12.8f)) {
            for (z in listOf(-20f,20f)) ring(Finish.STONE,x,-2f,z,3.7f,3.05f,13.9f,12)
            for (i in -6..6) box(Finish.LIME,x,9.2f, i*2.9f,.65f,.96f,1.42f)
        }
    }.finish()

    fun coffeeTerrace(): Map<Finish, FloatArray> = Builder().apply {
        for (row in 0..3) {
            val x=-row*4.2f; val h=row*1.4f
            box(Finish.STONE,x,h-3f,0f,4.4f,3.3f,27f-row*1.8f)
            box(Finish.GREEN,x,h+.27f,0f,4.35f,.21f,27f-row*1.8f)
            for (i in -4..4) {
                val z=i*2.65f+sin(row+i.toFloat())*.43f
                ring(Finish.WOOD,x,h+.4f,z,.12f,.08f,.64f,5)
                ring(Finish.LEAVES,x,h+.83f,z,.57f,.78f,.56f,7)
                ring(if ((i+row)%2==0) Finish.GREEN else Finish.LEAVES,x,h+1.39f,z,.78f,.24f,.49f,7)
            }
        }
        for (i in 0..4) ring(Finish.CANVAS,2.7f,-.1f,i*.7f,.34f,.24f,.8f,8)
    }.finish()

    fun dhow(): Map<Finish, FloatArray> = Builder().apply {
        val length=13f
        fun hull(t: Float, level: Int, side: Int): P {
            val beam=.07f+1.77f*sin(PI*t).toFloat().coerceAtLeast(0f).pow(.68f)
            val sheer=(abs(t-.5f)*2f).pow(3)*1.05f
            val width=when(level){0 -> .18f; 1 -> .68f; else -> 1f}
            val y=when(level){0 -> -.88f; 1 -> -.16f; 2 -> .51f; else -> .75f}
            return P(side*beam*width,y+sheer,t*length-length/2)
        }
        for (i in 0 until 16) {
            val a=i/16f; val b=(i+1)/16f
            for(side in listOf(-1,1)) for (level in 0..2) {
                val m=if(level==2) Finish.TEAL else Finish.WOOD
                val p=hull(a,level,side); val q=hull(b,level,side)
                val r=hull(b,level+1,side); val s=hull(a,level+1,side)
                if(side==1) quad(m,p,s,r,q) else quad(m,p,q,r,s)
            }
            quad(Finish.EARTH,hull(a,3,-1),hull(b,3,-1),hull(b,3,1),hull(a,3,1))
        }
        ring(Finish.WOOD,0f,.6f,0f,.14f,.08f,7.7f,8)
        val head=P(0f,8.9f,-1.8f); val tack=P(0f,2.5f,4.8f); val clew=P(0f,3.2f,-4.1f)
        val belly=P(.65f,4.9f,-.55f)
        tri(Finish.CANVAS,head,tack,belly); tri(Finish.CANVAS,tack,clew,belly); tri(Finish.LIME,clew,head,belly)
        box(Finish.WOOD,0f,.78f,3f,1.95f,.68f,1.7f)
    }.finish()
}
