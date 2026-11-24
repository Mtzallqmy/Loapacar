package com.masalik.yemen.game

import kotlin.math.abs

/** One loading bay on the west service lane. Other roads and all mission stops stay open. */
object MarketDelivery {
    const val distance=199f
    const val lane=-CityStreetNetwork.SIDE_STREET_OFFSET_M
    fun closeAt(hour: Float, carD: Float, carL: Float, alreadyClosed: Boolean): Boolean =
        hour >= 7f && hour < 11f && (alreadyClosed || abs(carD-distance)>18f || abs(carL-lane)>6.5f)
    fun blocks(street: MapStreet) = street.a.l==lane && street.b.l==lane &&
        distance in minOf(street.a.d,street.b.d)..maxOf(street.a.d,street.b.d)
    fun inBay(d: Float, l: Float, padding: Float=0f) =
        abs(d-distance)<6f+padding && abs(l-lane)<CityStreetNetwork.SIDE_HALF_WIDTH_M+padding
}
