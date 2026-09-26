package com.example.park

/*
 * Banner lines that describe the CITY'S DATA rather than one car: "City tow-zone data may be out of date",
 * "Tow-zone check unavailable", "Street-closure check unavailable". Every parked car gets the same one (the
 * tow feed and the closure sync are citywide), so the map banner shows each of them once instead of repeating
 * it under every car. Lines about a car's own spot (a zone or closure on its block, "in effect now") stay per car.
 * Showing it once doesn't hide it: it is still never replaced by "clear".
 */

/** Whether the tow line for [status] is about the city's tow data, not the car's spot. */
fun towLineIsFeedWide(status: TowStatus?): Boolean = status is TowStatus.Stale || status == TowStatus.Unchecked

/** Whether the closure line for [status] is about the city's closure data, not the car's spot. */
fun closureLineIsFeedWide(status: ClosureStatus?): Boolean = status == ClosureStatus.Unchecked

/** Whether the permit line for [status] is about the city's permit data, not the car's spot. */
fun permitLineIsFeedWide(status: PermitStatus?): Boolean = status == PermitStatus.Unchecked

/** The feed-wide lines across [cars], each once: closure, then tow, then permits (the order each car's lines use). */
fun feedWideBannerLines(cars: List<CarWithStatus>, nowMillis: Long): List<String> {
    val closure = cars.mapNotNull { c -> c.closureStatus?.takeIf(::closureLineIsFeedWide)?.let { closureBannerText(it, nowMillis) } }
    val tow = cars.mapNotNull { c -> c.towStatus?.takeIf(::towLineIsFeedWide)?.let { towBannerText(it, nowMillis) } }
    val permit = cars.mapNotNull { c -> c.permitStatus?.takeIf(::permitLineIsFeedWide)?.let { permitBannerText(it) } }
    return (closure + tow + permit).distinct()
}
