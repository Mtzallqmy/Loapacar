package com.masalik.yemen.game

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos

@Serializable
data class MissionProgress(val id: String, val stage: Int = 0, val elapsed: Float = 0f,
                           val lateralM: Float = 2.85f, val cargoCondition: Float = 100f,
                           val rivalDistance: Float? = null)

enum class ObjectiveKind { GATE, LOAD, UNLOAD, BOARD, ALIGHT, ATTACH, PARK }
data class RoadObjective(val distance: Float, val lane: Float, val kind: ObjectiveKind,
                         val label: String, val reverseRoute: Boolean = false) {
    val isStop get() = kind != ObjectiveKind.GATE
}
class MissionRun(val mission: MissionDefinition, saved: MissionProgress? = null) {
    val objectives = objectivesFor(mission)
    var stage = (saved?.stage ?: 0).coerceIn(0, objectives.lastIndex)
        private set
    var elapsed = saved?.elapsed ?: 0f
        private set
    private var dwell = 0f
    var condition = saved?.cargoCondition ?: 100f
        private set
    var failure: String? = null
        private set
    val finished get() = stage == objectives.size
    val target get() = objectives.getOrNull(stage)
    fun snapshot(lateral: Float, rival: Float?) = MissionProgress(mission.id, stage, elapsed, lateral, condition, rival)

    fun advance(dt: Float, t: VehicleTelemetry, headingOffset: Float, impact: Float) {
        val goal = target ?: return
        if (failure != null) return
        elapsed += dt
        if (stage > 0 && impact > 1f) condition = (condition - impact * 1.8f).coerceAtLeast(0f)
        if (mission.hasAiRival && (elapsed > mission.targetTimeSec ||
                    (t.aiRivalDistanceM ?: 0f) >= mission.finishDistanceM && t.distanceAlongRoadM < mission.finishDistanceM - 5f)) {
            failure = "انتهى السباق قبل وصولك. أعد المحاولة من قائمة المهام."
            return
        }
        if (mission.cargoRequiredPct > 0 && condition < mission.cargoRequiredPct) {
            failure = "تضررت الشحنة أثناء النقل. أعد المحاولة بقيادة أهدأ."
            return
        }
        val inZone = abs(t.distanceAlongRoadM - goal.distance) <= (if (goal.isStop) 5f else 8f) &&
            abs(t.lateralOffsetM - goal.lane) <= (if (goal.isStop) 1.65f else 6f)
        val alignment = cos(headingOffset) * (if (goal.reverseRoute) -1f else 1f)
        if (!goal.isStop) {
            if (inZone && alignment * t.forwardSpeedMs > 0.15f) stage++
            return
        }
        val secured = t.handbrake || (t.transmissionMode == "auto" && t.autoShifter == AutomaticShifterState.P)
        if (!inZone || t.speedKmh > 0.8f || abs(alignment) < 0.90f || !secured) {
            dwell = 0f
            return
        }
        dwell += dt
        if (dwell >= 2f) { stage++; dwell = 0f }
    }

    companion object {
        fun objectivesFor(m: MissionDefinition): List<RoadObjective> {
            fun at(f: Float) = m.startDistanceM + (m.finishDistanceM - m.startDistanceM) * f
            fun stop(f: Float, kind: ObjectiveKind, label: String) = RoadObjective(at(f), 2.85f, kind, label)
            fun gate(f: Float) = RoadObjective(at(f), 2.85f, ObjectiveKind.GATE, "اعبر نقطة المسار بالاتجاه الصحيح")
            if (m.id == "story_2") return listOf(
                RoadObjective(230f, 34f, ObjectiveKind.LOAD, "سوق تعز · ادخل الشارع الموازي شرق الطريق وثبّت المركبة لتحميل البن"),
                gate(.55f), stop(1f, ObjectiveKind.UNLOAD, "خزان المطل · ثبّت المركبة لتسليم البن"),
                RoadObjective(1150f, -2.85f, ObjectiveKind.GATE, "عُد إلى السوق عبر المنحدر", true),
                RoadObjective(230f, 34f, ObjectiveKind.PARK, "موقف السوق · ثبّت المركبة لإنهاء العودة", true))
            return when {
                m.hasAiRival -> listOf(gate(.3f), gate(.7f), gate(1f))
                m.category == GameModeCategory.PASSENGER || m.id == "story_17" -> listOf(
                    stop(.06f, ObjectiveKind.BOARD,"توقف لصعود الركاب"), stop(.52f,ObjectiveKind.BOARD,"المحطة الثانية · انتظر الركاب"),
                    stop(1f,ObjectiveKind.ALIGHT,"المحطة الأخيرة · أنزل الركاب"))
                m.category == GameModeCategory.RESCUE || m.id == "story_13" -> listOf(
                    stop(.36f,ObjectiveKind.ATTACH,"توقف لإعداد مركبة الإنقاذ"),gate(.7f),stop(1f,ObjectiveKind.PARK,"توقف عند الورشة"))
                m.cargoRequiredPct > 0 || m.category == GameModeCategory.CARGO -> listOf(
                    stop(.06f,ObjectiveKind.LOAD,"توقف لتحميل الشحنة"),gate(.55f),stop(1f,ObjectiveKind.UNLOAD,"توقف لتسليم الشحنة"))
                m.category == GameModeCategory.CHALLENGE -> listOf(gate(.33f),gate(.67f),stop(1f,ObjectiveKind.PARK,"اصطف داخل إطار الموقف"))
                else -> listOf(gate(.42f),gate(1f))
            }
        }
    }
}
