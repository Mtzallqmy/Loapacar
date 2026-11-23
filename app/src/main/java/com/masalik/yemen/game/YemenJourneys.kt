package com.masalik.yemen.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** Authored itineraries share coordinates with the road, scenery, and mission runner. */
data class YemenJourney(val place: String, val mission: MissionDefinition, val stops: List<RoadObjective>)

object YemenJourneys {
    const val GATE_M = 15450f
    const val HARBOUR_M = 21640f
    const val FARM_M = 2870f

    private fun trip(id: String, place: String, title: String, description: String, start: Float,
                     vehicle: VehicleId, mode: GameModeCategory, time: TimeOfDayPreset,
                     weather: WeatherPreset, vararg stops: RoadObjective): YemenJourney {
        val mission = MissionDefinition(id, title, description, mode,
            regionId = RoadSplineNetwork.getRegionAt(start).id, startDistanceM = start,
            finishDistanceM = stops.last().distance, targetTimeSec = 240, rewardCoins = 2400,
            rewardXp = 220, recommendedVehicleId = vehicle, weatherPreset = weather,
            timeOfDayPreset = time, cargoRequiredPct = if (mode == GameModeCategory.CARGO) 80 else 0,
            dialogueBriefAr = stops.first().label)
        return YemenJourney(place, mission, stops.toList())
    }

    val all = listOf(
        trip("journey_ibb_harvest", "إب", "صباح القطاف",
            "حمّل أكياس البن عند المدرجات، ثم سلّمها في الشارع الموازي لسوق إب تحت المطر.",
            2790f, VehicleId.SAQR, GameModeCategory.CARGO, TimeOfDayPreset.MORNING, WeatherPreset.LIGHT_RAIN,
            RoadObjective(FARM_M, 2.85f, ObjectiveKind.LOAD, "عند المدرجات · ثبّت السيارة وحمّل أكياس البن"),
            RoadObjective(3210f, 2.85f, ObjectiveKind.GATE, "اتبع الطريق نحو السوق"),
            RoadObjective(3400f, 34f, ObjectiveKind.UNLOAD, "ادخل الشارع الشرقي الموازي عند التقاطع وسلّم المحصول")),
        trip("journey_sanaa_gate", "صنعاء", "آخر ضوء على البوابة",
            "اصعد بالركاب من الحي القديم. اعبر القوس الحجري عند الغروب وأنزلهم قرب السوق.",
            15110f, VehicleId.MADINA, GameModeCategory.PASSENGER, TimeOfDayPreset.SUNSET, WeatherPreset.CLEAR,
            RoadObjective(15250f, 34f, ObjectiveKind.BOARD, "توقّف في الشارع الشرقي الموازي لصعود الركاب"),
            RoadObjective(GATE_M, 2.85f, ObjectiveKind.GATE, "عُد إلى الطريق الرئيسي واعبر تحت القوس"),
            RoadObjective(15610f, 2.85f, ObjectiveKind.ALIGHT, "أنزل الركاب عند سوق المساء")),
        trip("journey_aden_quay", "عدن", "موعد المراكب",
            "من الكورنيش إلى مرسى الصيادين عند الفجر. خفّف سرعتك فوق مطبّي الرصيف واصطف عند المراكب.",
            21370f, VehicleId.SAHIL, GameModeCategory.CHALLENGE, TimeOfDayPreset.DAWN, WeatherPreset.CLEAR,
            RoadObjective(21480f, 2.85f, ObjectiveKind.GATE, "تابع السير بمحاذاة البحر"),
            RoadObjective(21590f, 2.85f, ObjectiveKind.GATE, "اقترب من الرصيف على مهل"),
            RoadObjective(HARBOUR_M, 2.85f, ObjectiveKind.PARK, "اصطف عند المراكب وثبّت المكابح لثانيتين"))
    )
    val byId = all.associateBy { it.mission.id }
}

object QuayHumps {
    val centers = listOf(21602f, 21618f)
    const val halfLength = 1.65f
    const val rise = .095f

    fun height(distance: Float, lane: Float, roadHalfWidth: Float): Float {
        if (distance < centers.first() - halfLength || distance > centers.last() + halfLength) return 0f
        val across = ((roadHalfWidth - abs(lane)) / .45f).coerceIn(0f, 1f)
        for (center in centers) {
            val offset = (distance - center) / halfLength
            if (abs(offset) < 1f) return across * rise * .5f * (1f + cos(offset * PI).toFloat())
        }
        return 0f
    }
}
