package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Back / Cancel through the parking flow (ParkingFlowNavigator). */
class ParkingFlowNavigatorTest {

    private val point = LatLng(37.76, -122.42)
    private val choosing = ParkingFlowState.ChoosingCar(point)
    private val noStreet = ParkingFlowState.NoStreetNearby(1, point)
    private val viaMap = ParkingFlowState.PickingViaMap(1, point)

    @Test
    fun theFirstStep_hasNoBack_andBackFromItCloses() {
        val nav = ParkingFlowNavigator()
        nav.go(choosing)
        assertFalse(nav.canGoBack)
        nav.back()
        assertEquals(ParkingFlowState.Hidden, nav.current)
    }

    @Test
    fun backReturnsToEachPreviousChoiceInTurn() {
        val nav = ParkingFlowNavigator()
        nav.go(choosing); nav.go(noStreet); nav.go(viaMap)
        assertTrue(nav.canGoBack)
        nav.back(); assertEquals(noStreet, nav.current)
        nav.back(); assertEquals(choosing, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun cancelClosesAndForgetsTheSteps() {
        val nav = ParkingFlowNavigator()
        nav.go(choosing); nav.go(noStreet)
        nav.cancel()
        assertEquals(ParkingFlowState.Hidden, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun aNewFlowNeverInheritsTheLastOnesHistory() {
        val nav = ParkingFlowNavigator()
        nav.go(choosing); nav.go(noStreet)
        nav.go(ParkingFlowState.Hidden) // e.g. a park was saved (finishManualPark)
        nav.go(viaMap)                   // a later flow starts somewhere else
        assertFalse(nav.canGoBack)
    }

    @Test
    fun reassigningTheSameStepDoesNotAddABackStep() {
        val nav = ParkingFlowNavigator()
        nav.go(choosing); nav.go(noStreet); nav.go(noStreet)
        nav.back()
        assertEquals(choosing, nav.current)
    }

    @Test
    fun theDelegatedPropertyRecordsHistory() {
        val nav = ParkingFlowNavigator()
        var state by nav // how MapScreen uses it: plain assignments go through go()
        state = choosing
        state = noStreet
        assertTrue(nav.canGoBack)
        nav.back()
        assertEquals(choosing, state)
    }
}
