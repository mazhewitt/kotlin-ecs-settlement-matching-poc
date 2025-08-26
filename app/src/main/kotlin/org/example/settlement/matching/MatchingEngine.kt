package org.example.settlement.matching

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.example.settlement.Engine
import org.example.settlement.domain.CanonCode

/**
 * Domain matching orchestrator for three sources:
 * - bankUpdates: internal obligation changes/status inputs from bank backoffice
 * - marketUpdates: external CSD/market status messages
 * - statusIndications: output status indications (derived events to downstream)
 *
 * ECS note: process* methods map to one ECS world "tick" over our systems.
 */
class MatchingEngine(
    private val engine: Engine,
    val bankUpdates: MessageQueue<BankUpdate>,
    val marketUpdates: MessageQueue<MarketUpdate>,
    val statusIndications: MessageQueue<StatusIndication>
) {
    // Domain inputs
    data class BankUpdate(
        val obligationId: String,
        val venue: String,
        val isin: String,
        val account: String,
        val settleDate: LocalDate,
        val intendedQty: Long
    )

    data class MarketUpdate(
        val msgId: String,
        val seq: Long,
        val code: CanonCode,
        val isin: String,
        val account: String,
        val settleDate: LocalDate,
        val qty: Long,
        val at: Instant
    )

    data class StatusIndication(
        val summary: String
    )

    /**
     * Ingests all pending inputs and processes one matching cycle (ECS tick) producing indications.
     */
    fun processOnce() {
        // 1) Apply/ensure obligations exist based on bank updates (idempotent in tests)
        bankUpdates.drainTo { b ->
            engine.createObligation(
                id = b.obligationId,
                venue = b.venue,
                isin = b.isin,
                account = b.account,
                settleDate = b.settleDate,
                intendedQty = b.intendedQty
            )
        }

        // 2) Ingest market updates into ECS
        marketUpdates.drainTo { m ->
            engine.ingestStatus(
                msgId = m.msgId,
                seq = m.seq,
                code = m.code,
                isin = m.isin,
                account = m.account,
                settleDate = m.settleDate,
                qty = m.qty,
                at = m.at
            )
        }

        // 3) Run ECS processing once (domain-aligned name)
        engine.processStatusUpdates()

        // 4) Translate outbox domain events into status indications (simple text for now)
        engine.outbox().forEach { evt ->
            statusIndications.offer(StatusIndication(summary = evt.toString()))
        }
        // Clear outbox so subsequent cycles do not re-emit prior events.
        if (engine is org.example.settlement.SettlementEngine) {
            engine.clearOutbox()
        }
    }
}
