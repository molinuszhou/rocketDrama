package com.dramaflow.app.analytics

/**
 * Pure queue rules shared by the durable behavior-event store and unit tests.
 *
 * Critical early-video events are deliberately outside the normal-event cap: at most two events
 * for each of the first twenty videos are eligible, while routine UI and lifecycle events remain
 * bounded. Delivery state is persisted by the caller in the same atomic snapshot as the queue.
 */
internal enum class EarlyVideoDeliveryState {
    PENDING,
    ACKED,
}

internal data class BehaviorQueueEvent(
    val eventId: String,
    val payload: String,
    val criticalKey: String? = null,
)

internal data class BehaviorQueueState(
    val events: List<BehaviorQueueEvent> = emptyList(),
    val earlyVideoStates: Map<String, EarlyVideoDeliveryState> = emptyMap(),
)

internal object BehaviorEventQueuePolicy {
    fun enqueue(
        state: BehaviorQueueState,
        event: BehaviorQueueEvent,
        maxNormalEvents: Int,
    ): BehaviorQueueState {
        if (event.eventId.isBlank() || event.payload.isBlank()) return state
        if (state.events.any { it.eventId == event.eventId }) return state
        if (event.criticalKey != null) {
            if (state.earlyVideoStates[event.criticalKey] == EarlyVideoDeliveryState.ACKED) return state
            if (state.events.any { it.criticalKey == event.criticalKey }) return state
        }

        val nextStates = state.earlyVideoStates.toMutableMap()
        event.criticalKey?.let { nextStates[it] = EarlyVideoDeliveryState.PENDING }
        return normalize(
            BehaviorQueueState(
                events = state.events + event,
                earlyVideoStates = nextStates,
            ),
            maxNormalEvents,
        )
    }

    fun acknowledge(
        state: BehaviorQueueState,
        eventId: String,
        maxNormalEvents: Int,
    ): BehaviorQueueState {
        val delivered = state.events.firstOrNull { it.eventId == eventId } ?: return state
        val nextStates = state.earlyVideoStates.toMutableMap()
        delivered.criticalKey?.let { nextStates[it] = EarlyVideoDeliveryState.ACKED }
        return normalize(
            BehaviorQueueState(
                events = state.events.filterNot { it.eventId == eventId },
                earlyVideoStates = nextStates,
            ),
            maxNormalEvents,
        )
    }

    /**
     * Reconciles a loaded snapshot. An ACKED marker wins over an old duplicate queue row; a
     * dangling PENDING marker is removed so an unavailable/corrupt old row cannot suppress a
     * future first-video event forever.
     */
    fun normalize(state: BehaviorQueueState, maxNormalEvents: Int): BehaviorQueueState {
        val acknowledged = state.earlyVideoStates
            .filterValues { it == EarlyVideoDeliveryState.ACKED }
            .keys
        val deduplicated = linkedMapOf<String, BehaviorQueueEvent>()
        state.events.forEach { event ->
            if (event.eventId.isNotBlank() && event.payload.isNotBlank() &&
                (event.criticalKey == null || event.criticalKey !in acknowledged)
            ) {
                deduplicated.putIfAbsent(event.eventId, event)
            }
        }
        val states = state.earlyVideoStates.toMutableMap()
        deduplicated.values.forEach { event ->
            event.criticalKey?.let { key ->
                if (states[key] != EarlyVideoDeliveryState.ACKED) {
                    states[key] = EarlyVideoDeliveryState.PENDING
                }
            }
        }
        val queuedCriticalKeys = deduplicated.values.mapNotNull { it.criticalKey }.toSet()
        states.entries.removeAll { (key, value) ->
            value == EarlyVideoDeliveryState.PENDING && key !in queuedCriticalKeys
        }

        val normalEvents = deduplicated.values.filter { it.criticalKey == null }
        val retainedNormalIds = normalEvents.takeLast(maxNormalEvents.coerceAtLeast(0)).map { it.eventId }.toSet()
        val retained = deduplicated.values.filter { event ->
            event.criticalKey != null || event.eventId in retainedNormalIds
        }
        return BehaviorQueueState(retained, states)
    }

    /** Merges a legacy NDJSON queue into the current state without discarding confirmed events. */
    fun migrateLegacy(
        legacyEvents: List<BehaviorQueueEvent>,
        acknowledgedCriticalKeys: Set<String>,
        maxNormalEvents: Int,
    ): BehaviorQueueState {
        val initialStates = acknowledgedCriticalKeys.associateWith { EarlyVideoDeliveryState.ACKED }
        val migrated = legacyEvents.fold(BehaviorQueueState(earlyVideoStates = initialStates)) { state, event ->
            enqueue(state, event, maxNormalEvents)
        }
        return normalize(migrated, maxNormalEvents)
    }
}
