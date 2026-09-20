package com.example.park

import org.json.JSONObject

data class LatLng(val lat: Double, val lng: Double)

fun parseLineString(lineJson: String): List<LatLng> {
    if (lineJson.isEmpty()) return emptyList()
    val obj = JSONObject(lineJson)
    val coords = obj.getJSONArray("coordinates")
    val points = mutableListOf<LatLng>()
    for (i in 0 until coords.length()) {
        val pair = coords.getJSONArray(i)
        points.add(LatLng(pair.getDouble(1), pair.getDouble(0)))
    }
    return points
}

/**
 * Parses a WKT LINESTRING string \u2014 DataSF's CSV export's geometry format, e.g.
 * "LINESTRING (-122.416291701103 37.766, -122.417 37.767)" \u2014 into the same LatLng list
 * parseLineString produces from the JSON export's GeoJSON coordinates array. Coordinate order
 * in the source text is lng-then-lat either way (WKT's X Y is the same order as GeoJSON's
 * [lng, lat] pairs); only the textual wrapper differs.
 */
fun parseWktLineString(wkt: String): List<LatLng> {
    val start = wkt.indexOf('(')
    val end = wkt.lastIndexOf(')')
    if (start == -1 || end == -1 || end <= start) return emptyList()
    return wkt.substring(start + 1, end).split(",").mapNotNull { pair ->
        val parts = pair.trim().split(Regex("\\s+"))
        if (parts.size < 2) return@mapNotNull null
        val lng = parts[0].toDoubleOrNull() ?: return@mapNotNull null
        val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
        LatLng(lat, lng)
    }
}