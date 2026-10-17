package com.dramaflow.app.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorEventQueuePolicyTest {
    @Test
    fun `keeps first-video events while bounding ordinary events`() {
        var state = BehaviorQueueState()
        repeat(4) { index ->
            state = BehaviorEventQueuePolicy.enqueue(
                state,
                BehaviorQueueEvent("ui-$index", "{\"event\":$index}"),
                maxNormalEvents = 2,
            )
        }
        state = BehaviorEventQueuePolicy.enqueue(
            state,
            BehaviorQueueEvent("start-1", "{\"event\":\"start\"}", "started:drama:episode"),
            maxNormalEvents = 2,
        )
        state = BehaviorEventQueuePolicy.enqueue(
            state,
            BehaviorQueueEvent("complete-1", "{\"event\":\"complete\"}", "completed:drama:episode"),
            maxNormalEvents = 2,
        )

        assertEquals(setOf("ui-2", "ui-3", "start-1", "complete-1"), state.events.map { it.eventId }.toSet())
    }

    @Test
    fun `acknowledged critical event remains deduplicated after ordinary trimming`() {
        val critical = BehaviorQueueEvent("start-1", "payload", "started:drama:episode")
        val pending = BehaviorEventQueuePolicy.enqueue(BehaviorQueueState(), critical, maxNormalEvents = 1)
        val acknowledged = BehaviorEventQueuePolicy.acknowledge(pending, critical.eventId, maxNormalEvents = 1)
        val withUi = BehaviorEventQueuePolicy.enqueue(
            acknowledged,
            BehaviorQueueEvent("ui-1", "payload"),
            maxNormalEvents = 1,
        )
        val reloaded = BehaviorEventQueuePolicy.normalize(
            BehaviorQueueState(withUi.events, withUi.earlyVideoStates),
            maxNormalEvents = 1,
        )
        val duplicate = BehaviorEventQueuePolicy.enqueue(reloaded, critical, maxNormalEvents = 1)

        assertEquals(EarlyVideoDeliveryState.ACKED, duplicate.earlyVideoStates[critical.criticalKey])
        assertFalse(duplicate.events.any { it.eventId == critical.eventId })
        assertEquals(listOf("ui-1"), duplicate.events.map { it.eventId })
    }

    @Test
    fun `migrates legacy queue while honoring confirmed markers and repairing dangling pending markers`() {
        val migrated = BehaviorEventQueuePolicy.migrateLegacy(
            legacyEvents = listOf(
                BehaviorQueueEvent("legacy-start", "payload", "started:drama:episode"),
                BehaviorQueueEvent("legacy-complete", "payload", "completed:drama:episode"),
            ),
            acknowledgedCriticalKeys = setOf("completed:drama:episode"),
            maxNormalEvents = 2,
        )
        val repaired = BehaviorEventQueuePolicy.normalize(
            BehaviorQueueState(
                events = migrated.events,
                earlyVideoStates = migrated.earlyVideoStates + ("started:lost" to EarlyVideoDeliveryState.PENDING),
            ),
            maxNormalEvents = 2,
        )

        assertTrue(repaired.events.any { it.eventId == "legacy-start" })
        assertFalse(repaired.events.any { it.eventId == "legacy-complete" })
        assertEquals(EarlyVideoDeliveryState.ACKED, repaired.earlyVideoStates["completed:drama:episode"])
        assertFalse(repaired.earlyVideoStates.containsKey("started:lost"))
    }
}
