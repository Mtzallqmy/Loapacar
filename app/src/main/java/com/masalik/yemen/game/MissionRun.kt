package com.masalik.yemen.game

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos

@Serializable
data class MissionProgress(val id: String, val stage: Int = 0, val elapsed: Float = 0f,
                           val lateralM: Float = 2.85f, val cargoCondition: Float = 100f,
                           val rivalDistance: Float? = null, val headingOffsetRad: Float = 0f)

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
    var waitingForAction = false
        private set
    private var actionTaken = false
    fun useEquipment() {
        if (waitingForAction) actionTaken = true
    }
    private var lastSpeed: Float? = null
    val payloadKg: Float get() {
        var payload = 0f
        for (i in 0 until stage) when (objectives[i].kind) {
            ObjectiveKind.LOAD -> payload = 280f
            ObjectiveKind.BOARD -> payload += 140f
            ObjectiveKind.ATTACH -> payload = 820f
            ObjectiveKind.ALIGHT, ObjectiveKind.UNLOAD -> payload = 0f
            else -> Unit
        }
        return payload
    }
    private var dwell = 0f
    var condition = saved?.cargoCondition ?: 100f
        private set
    var failure: String? = null
        private set
    val finished get() = stage == objectives.size
    val target get() = objectives.getOrNull(stage)
    fun snapshot(lateral: Float, rival: Float?, headingOffsetRad: Float = 0f) =
        MissionProgress(mission.id, stage, elapsed, lateral, condition, rival, headingOffsetRad)

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
        val speedBefore = lastSpeed ?: t.forwardSpeedMs
        lastSpeed = t.forwardSpeedMs
        if (mission.category == GameModeCategory.PASSENGER && stage > 0) {
            val acceleration = abs(t.forwardSpeedMs-speedBefore) / dt.coerceAtLeast(.001f)
            condition = (condition - (acceleration-4f).coerceAtLeast(0f)*dt*.65f).coerceAtLeast(0f)
            if (condition < 45f) { failure = "تعذّر إكمال الرحلة بسبب انزعاج الركاب من القيادة"; return }
        }
        val spec = VehicleFleetCatalog.specOf(t.vehicleId)
        val relativeYaw = abs(kotlin.math.sin(headingOffset))
        val longitudinal = abs(cos(headingOffset))
        val halfLength = (spec.bodyLengthM * longitudinal + relativeYaw * spec.bodyWidthM) * .5f
        val halfWidth = (spec.bodyWidthM * longitudinal + relativeYaw * spec.bodyLengthM) * .5f
        val inZone = abs(t.distanceAlongRoadM - goal.distance) <= (if (goal.isStop) (6f-halfLength).coerceAtLeast(.8f) else 8f) &&
            abs(t.lateralOffsetM - goal.lane) <= (if (goal.isStop) (2.4f-halfWidth).coerceAtLeast(.35f) else 6f)
        val alignment = cos(headingOffset) * (if (goal.reverseRoute) -1f else 1f)
        if (!goal.isStop) {
            if (inZone && alignment * t.forwardSpeedMs > 0.15f) stage++
            return
        }
        val secured = t.handbrake || (t.transmissionMode == "auto" && t.autoShifter == AutomaticShifterState.P)
        if (!inZone || t.speedKmh > 0.8f || abs(alignment) < 0.90f || !secured) {
            dwell = 0f
            waitingForAction = false
            actionTaken = false
            return
        }
        waitingForAction = goal.kind != ObjectiveKind.PARK && !actionTaken
        if (waitingForAction) return
        dwell += dt
        if (dwell >= 2f) { stage++; dwell = 0f; actionTaken = false; waitingForAction = false }
    }

    companion object {
        fun objectivesFor(m: MissionDefinition): List<RoadObjective> {
            fun at(f: Float) = m.startDistanceM + (m.finishDistanceM - m.startDistanceM) * f
            fun stop(f: Float, kind: ObjectiveKind, label: String) = RoadObjective(at(f), 2.85f, kind, label)
            fun gate(f: Float) = RoadObjective(at(f), 2.85f, ObjectiveKind.GATE, "اعبر نقطة المسار بالاتجاه الصحيح")
            if (m.id == MissionsCatalog.PARKING_LESSON_ID) return listOf(
                RoadObjective(m.finishDistanceM, 34f, ObjectiveKind.PARK, "اصطف داخل الإطار ثم اسحب الجلنط"))
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
