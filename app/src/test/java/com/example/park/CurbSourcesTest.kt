package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The source registry's bookkeeping (docs/park-sources-refactor-spec.md, Part B, Tests §4): every source is
 * registered once, and every reminder kind, roll-forward kind, deadline kind and per-car notification id
 * slot has exactly one owner, so two sources can never cancel or overwrite each other's alarms.
 */
class CurbSourcesTest {
    private val sources = CurbSources.all

    /** Per-car id slots that belong to the car but not to any curb source. */
    private val nonSourcePurposes = setOf(
        NotificationIds.Purpose.BLUETOOTH_AUTO_DETECT,  // Bluetooth auto-park notices
        NotificationIds.Purpose.BLUETOOTH_AUTO_UNPARK,
        NotificationIds.Purpose.SAVED_LOCATION_RECOMPUTE, // saved-location edits (SavedLocationRecompute.kt)
        NotificationIds.Purpose.METER_TIMER              // the user's meter timer: its own path (MeterTimer.kt)
    )

    private fun <T> assertOwnedOnce(all: Collection<T>, ownedBy: (CurbRestrictionSource) -> Set<T>, extraOwners: Set<T> = emptySet()) {
        for (item in all) {
            val owners = sources.filter { item in ownedBy(it) }.map { it.id.name } + listOfNotNull("non-source".takeIf { item in extraOwners })
            assertEquals("$item must have exactly one owner, has $owners", 1, owners.size)
        }
    }

    @Test
    fun everySourceIdIsRegisteredExactlyOnce() {
        assertEquals(SourceId.entries.toSet(), sources.map { it.id }.toSet())
        assertEquals(SourceId.entries.size, sources.size)
    }

    @Test
    fun theRegistryOrderIsTheOldHandWrittenOrder() {
        // Arming ran sweep, RPP, closures, tow; soonestDeadline listed sweep, RPP, meter, tow (ties go to the first).
        assertEquals(listOf(SourceId.SWEEP, SourceId.RPP, SourceId.METER, SourceId.CLOSURE, SourceId.TOW), sources.map { it.id })
    }

    @Test
    fun everyReminderKindHasOneOwner() = assertOwnedOnce(ReminderKind.entries, { it.reminderKinds })

    @Test
    fun everyRollForwardKindHasOneOwner() = assertOwnedOnce(RollForwardKind.entries, { it.rollForwardKinds })

    @Test
    fun everyDeadlineKindHasOneOwner() = assertOwnedOnce(DeadlineKind.entries, { it.deadlineKinds })

    @Test
    fun everyPerCarNotificationSlotHasOneOwner() =
        assertOwnedOnce(NotificationIds.Purpose.entries, { it.notificationPurposes }, nonSourcePurposes)

    @Test
    fun aSourcesReminderKindsUseItsOwnIdSlots() {
        // reminderRequestCode maps a kind to its Purpose; that Purpose must belong to the same source.
        for (source in sources) {
            for (kind in source.reminderKinds) {
                val code = reminderRequestCode(1L, kind)
                val purpose = NotificationIds.Purpose.entries.single { NotificationIds.forCar(1L, it) == code }
                assertEquals("$kind -> $purpose", true, purpose in source.notificationPurposes)
            }
        }
    }
}
