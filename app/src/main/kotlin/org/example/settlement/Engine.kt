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

    fun tick()

    fun outbox(): List<DomainEvent>
}
