package com.masalik.yemen.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.masalik.yemen.game.*
import com.masalik.yemen.ui.theme.*

@Composable
internal fun JourneySelector(onStart: (MissionDefinition) -> Unit, modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val entries = YemenJourneys.all.filterNot { it == YemenJourneys.taizRoutes.last() }
    var mainRoad by rememberSaveable { mutableStateOf(false) }
    val journey = if (selected == 0 && mainRoad) YemenJourneys.taizRoutes.last() else entries[selected]
    val m = journey.mission
    val accent = when (selected) {
        0 -> Color(0xFFE8B88B)
        1 -> Color(0xFFA4D49C)
        2 -> Color(0xFFF0BB85)
        else -> Color(0xFF92DADD)
    }
    val elevations = remember(m.id) {
        List(72) { i -> RoadSplineNetwork.sampleAt(m.startDistanceM + (m.finishDistanceM-m.startDistanceM)*i/71f).y }
    }
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xEF14201F)).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("رحلات اليمن", color = MasalikIvory, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().selectableGroup(),horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            entries.forEachIndexed { i, entry ->
                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .background(if (selected==i) accent else Color.White.copy(alpha=.08f))
                    .selectable(selected==i, role=Role.Tab) { selected=i }
                    .padding(vertical=7.dp), contentAlignment=androidx.compose.ui.Alignment.Center) {
                    Text(if (i == 0) "تعز" else entry.place, color=if (selected==i) Color(0xFF10201A) else MasalikIvory, fontSize=12.sp)
                }
            }
        }
        if (selected == 0) Row(Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (useMainRoad in listOf(false, true)) {
                val active = mainRoad == useMainRoad
                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .background(if (active) accent.copy(alpha = .22f) else Color.White.copy(alpha = .07f))
                    .selectable(active, role = Role.Tab) { mainRoad = useMainRoad }
                    .padding(6.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(if (useMainRoad) "الشارع الرئيسي" else "شارع السوق", color = MasalikIvory, fontSize = 11.sp)
                }
            }
        }
        Text(m.titleAr, color=accent, fontSize=20.sp, fontWeight=FontWeight.Bold)
        Text(m.descriptionAr, color=MasalikIvory, fontSize=12.sp, lineHeight=17.sp)
        Canvas(Modifier.fillMaxWidth().height(38.dp)) {
            val low=elevations.min(); val range=(elevations.max()-low).coerceAtLeast(4f)
            fun x(f: Float) = size.width*(1f-f)
            fun y(v: Float) = size.height*(.87f-.70f*(v-low)/range)
            val path=Path()
            elevations.forEachIndexed { i,v ->
                val px=x(i.toFloat()/elevations.lastIndex)
                if (i==0) path.moveTo(px,y(v)) else path.lineTo(px,y(v))
            }
            drawPath(path,accent,style=Stroke(width=2.dp.toPx()))
            path.lineTo(0f,size.height); path.lineTo(size.width,size.height); path.close()
            drawPath(path,Brush.verticalGradient(listOf(accent.copy(alpha=.32f),Color.Transparent)))
            for (stop in journey.stops) {
                val fraction=(stop.distance-m.startDistanceM)/(m.finishDistanceM-m.startDistanceM)
                val idx=(fraction*elevations.lastIndex).toInt().coerceIn(elevations.indices)
                drawCircle(accent,3.dp.toPx(),Offset(x(fraction).coerceIn(3.dp.toPx(), size.width-3.dp.toPx()),y(elevations[idx])))
            }
        }
        val distance = if (selected == 0) (m.finishDistanceM - m.startDistanceM) +
            (m.finishDistanceM - journey.stops.last().distance) else m.finishDistanceM - m.startDistanceM
        Text("ارتفاع الطريق · ${distance.toInt()} م · ${m.timeOfDayPreset.labelAr}",
            color=MasalikMuted,fontSize=10.sp)
        Button(onClick={ onStart(m) }, modifier=Modifier.fillMaxWidth(),
            colors=ButtonDefaults.buttonColors(containerColor=accent,contentColor=Color(0xFF10201A))) {
            Text("انطلق في الرحلة",fontWeight=FontWeight.Bold)
        }
    }
}
