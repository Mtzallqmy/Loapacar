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
    const val TAIZ_MARKET_M = 221f
    const val SABIR_LOOKOUT_M = 2350f
    private const val MARKET_LANE = CityStreetNetwork.SIDE_STREET_OFFSET_M

    private fun trip(id: String, place: String, title: String, description: String, start: Float,
                     vehicle: VehicleId, mode: GameModeCategory, time: TimeOfDayPreset,
                     weather: WeatherPreset, vararg stops: RoadObjective): YemenJourney {
        val mission = MissionDefinition(id, title, description, mode,
            regionId = RoadSplineNetwork.getRegionAt(start).id, startDistanceM = start,
            finishDistanceM = stops.maxOf { it.distance }, targetTimeSec = 240, rewardCoins = 2400,
            rewardXp = 220, recommendedVehicleId = vehicle, weatherPreset = weather,
            timeOfDayPreset = time, cargoRequiredPct = if (mode == GameModeCategory.CARGO) 80 else 0,
            dialogueBriefAr = stops.first().label)
        return YemenJourney(place, mission, stops.toList())
    }

    private fun sabirTrip(marketStreet: Boolean): YemenJourney {
        val route = if (marketStreet)
            RoadObjective(335f, MARKET_LANE, ObjectiveKind.GATE, "تابع عبر سوق البن ثم اخرج يسارًا عند آخر التقاطع")
        else RoadObjective(315f, 2.85f, ObjectiveKind.GATE, "اخرج من السوق عند التقاطع الأول واتبع الشارع الرئيسي")
        return trip(
            if (marketStreet) "journey_taiz_market" else "journey_taiz_main",
            if (marketStreet) "سوق تعز" else "صعود صبر",
            if (marketStreet) "بنّ السوق إلى مطلّ صبر" else "حمولة صبر · الطريق الرئيسي",
            if (marketStreet) "اعبر سوق البن ثم اصعد بالحمولة إلى المطلّ. عُد إلى المخزن بعد التسليم."
                else "خذ الشارع الأوسع إلى المطلّ، ثم جرّب نزول صبر بالصندوق الفارغ.",
            110f, VehicleId.SAQR, GameModeCategory.CARGO, TimeOfDayPreset.MORNING, WeatherPreset.CLOUDY,
            RoadObjective(TAIZ_MARKET_M, MARKET_LANE, ObjectiveKind.LOAD, "مخزن البن · ادخل الشارع الشرقي الموازي وحمّل الأكياس"),
            route,
            if (marketStreet) RoadObjective(1380f, SabirRoad.offset(1380f)+1.7f, ObjectiveKind.GATE,
                "خذ التفريعة اليسرى واصعد طريق المدرجات إلى مطلّ صبر")
            else RoadObjective(1380f, 2.85f, ObjectiveKind.GATE, "تابع صعود المنحدر إلى مطلّ جبل صبر"),
            RoadObjective(SABIR_LOOKOUT_M, 2.85f, ObjectiveKind.UNLOAD, "مطلّ صبر · ثبّت السيارة وسلّم أكياس البن"),
            RoadObjective(1450f, -2.85f, ObjectiveKind.GATE, "استدر حين يخلو الطريق وعُد إلى السوق بالصندوق الفارغ", reverseRoute = true),
            RoadObjective(TAIZ_MARKET_M, MARKET_LANE, ObjectiveKind.PARK, "اصطف بجوار مخزن البن لإنهاء الرحلة", reverseRoute = true)
        )
    }

    private val otherCities = listOf(
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
            "من الكورنيش إلى مرسى الصيادين عند الشروق. خفّف سرعتك فوق مطبّي الرصيف واصطف عند المراكب.",
            21370f, VehicleId.SAHIL, GameModeCategory.CHALLENGE, TimeOfDayPreset.SUNRISE, WeatherPreset.CLEAR,
            RoadObjective(21480f, 2.85f, ObjectiveKind.GATE, "تابع السير بمحاذاة البحر"),
            RoadObjective(21590f, 2.85f, ObjectiveKind.GATE, "اقترب من الرصيف على مهل"),
            RoadObjective(HARBOUR_M, 2.85f, ObjectiveKind.PARK, "اصطف عند المراكب وثبّت المكابح لثانيتين")),
        trip("journey_shibam_bus","شبام","خط الحافلة · وادي حضرموت",
            "استلم حافلة الخط وأضفها إلى مرآبك. خذ الركاب عبر مدخل المدينة الطينية وحافظ على راحتهم.",
            18535f,VehicleId.HAFILAT,GameModeCategory.PASSENGER,TimeOfDayPreset.AFTERNOON,WeatherPreset.CLEAR,
            RoadObjective(18570f,2.65f,ObjectiveKind.BOARD,"محطة الوادي · توقّف لصعود الركاب"),
            RoadObjective(18640f,2.65f,ObjectiveKind.BOARD,"محطة السوق · اصعد بالدفعة الثانية"),
            RoadObjective(18775f,2.65f,ObjectiveKind.ALIGHT,"محطة الحصن · أنزل الركاب")),
        trip("journey_jibla_rain","جبلة","قيادة ليلية تحت المطر",
            "اتبع علامات الطريق المبلل إلى مفرق جبلة. اعبر المعبر بهدوء ثم اصطف بجانب السوق.",
            4350f,VehicleId.MADINA,GameModeCategory.CHALLENGE,TimeOfDayPreset.NIGHT,WeatherPreset.HEAVY_RAIN,
            RoadObjective(4410f,2.65f,ObjectiveKind.GATE,"المطر كثيف · اتبع الحارة حتى المفرق"),
            RoadObjective(4498f,2.65f,ObjectiveKind.GATE,"اعبر التقاطع بعد خلوّ المعبر"),
            RoadObjective(4545f,2.65f,ObjectiveKind.PARK,"موقف جبلة · توقّف وثبّت السيارة"))
    )
    val taizRoutes = listOf(sabirTrip(true), sabirTrip(false))
    val all = taizRoutes + otherCities
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
