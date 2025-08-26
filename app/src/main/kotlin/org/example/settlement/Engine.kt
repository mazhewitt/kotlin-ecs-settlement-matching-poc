package org.example.settlement

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.example.settlement.domain.CanonCode
import org.example.settlement.domain.DomainEvent

interface Engine {
    fun createObligation(
        id: String,
        venue: String,
        isin: String,
        account: String,
        settleDate: LocalDate,
        intendedQty: Long
    ): Int

    fun ingestStatus(
        msgId: String,
        seq: Long,
        code: CanonCode,
        isin: String,
        account: String,
        settleDate: LocalDate,
        qty: Long,
        at: Instant
    )

    /**
     * Processes all currently ingested status events and applies matching and lifecycle transitions.
     *
     * Note: This is the domain-aligned name for the ECS world "tick". In ECS terms, this would be one tick.
     */
    fun processStatusUpdates()

    fun outbox(): List<DomainEvent>
}
