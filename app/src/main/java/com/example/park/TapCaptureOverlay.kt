package com.example.park

import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

class TapCaptureOverlay(private val getCallback: () -> ((GeoPoint) -> Unit)?) : Overlay() {
    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val callback = getCallback() ?: return false // not in pin-drop mode — let other overlays handle the tap
        val point = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
        callback(point)
        return true
    }
}