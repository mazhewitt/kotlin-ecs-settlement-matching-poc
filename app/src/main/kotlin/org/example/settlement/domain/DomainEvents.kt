package org.example.settlement.domain

import kotlinx.datetime.Instant

sealed interface DomainEvent {
    data class NoMatch(val msgId: String, val seq: Long, val key: String) : DomainEvent
    data class DuplicateIgnored(val obligationId: String, val msgId: String, val seq: Long) : DomainEvent
    data class OutOfOrderIgnored(val obligationId: String, val lastSeq: Long, val msgId: String, val seq: Long) : DomainEvent
    data class StateChanged(
        val obligationId: String,
        val from: LifecycleState,
        val to: LifecycleState,
        val settledQty: Long,
        val remainingQty: Long,
        val msgId: String,
        val seq: Long,
        val at: Instant
    ) : DomainEvent
}