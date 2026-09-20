package com.example.park

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class ParkWidgetReceiver : GlanceAppWidgetReceiver() {
    // Same instance updateParkWidget() calls — see the comment on ParkWidget.instance for why
    // this needed to stop being two separate ParkWidget() objects.
    override val glanceAppWidget: GlanceAppWidget = ParkWidget.instance
}