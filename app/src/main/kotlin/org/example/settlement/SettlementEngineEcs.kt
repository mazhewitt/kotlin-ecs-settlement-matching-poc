package org.example.settlement

import com.github.quillraven.fleks.configureWorld
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World.Companion.family
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Instant
import org.example.settlement.components.*
import org.example.settlement.domain.LifecycleState
import org.example.settlement.domain.CanonCode
import org.example.settlement.domain.DomainEvent
import org.example.common.Option

// ObligationView already defined in SettlementEngine.kt

class SettlementEngineEcs {
    private val world = configureWorld {}
    private val obligations = world.family { all(IdentityC, MatchingKeyC, LifecycleC, QuantitiesC) }
    private val statusEvents = world.family { all(ParsedStatusC, CandidateKeyC) }
    private val outboxEvents = mutableListOf<DomainEvent>()
    
    fun createObligation(
        id: String,
        venue: String,
        isin: String,
        account: String,
        settleDate: LocalDate,
        intendedQty: Long
    ): Int {
        val entity = world.entity {
            it += IdentityC(id, venue)
            it += MatchingKeyC(isin, account, settleDate, intendedQty)
            it += LifecycleC(LifecycleState.New)
            it += QuantitiesC(intendedQty, 0L, intendedQty)
            it += CsdStatusC()
            it += IdempotencyC()
            it += CorrelationC()
        }
        return entity.id
    }
    
    fun ingestStatus(
        msgId: String,
        seq: Long,
        code: CanonCode,
        isin: String,
        account: String,
        settleDate: LocalDate,
        qty: Long,
        at: Instant
    ) {
        world.entity {
            it += ParsedStatusC(msgId, seq, code, isin, account, settleDate, qty, at)
            it += CandidateKeyC(isin, account, settleDate, qty)
        }
    }
    
    fun tick() {
        // DedupSystem: Process status events for deduplication and correlation
        dedupSystem()
        
        // CorrelateSystem: Update correlations and CSD status
        correlateSystem()
        
        // LifecycleSystem: Apply state transitions 
        lifecycleSystem()
        
        // OutboxSystem: Collect and clean up processed events
        outboxSystem()
        
        world.update(0f)
    }
    
    private fun dedupSystem() {
        statusEvents.forEach { statusEntity ->
            val statusEvent = statusEntity[ParsedStatusC]
            val candidateKey = statusEntity[CandidateKeyC]
            
            // Find matching obligation
            var matchedObligation: Entity? = null
            obligations.forEach { obligation ->
                val matchingKey = obligation[MatchingKeyC]
                if (matchingKey.isin == candidateKey.isin &&
                    matchingKey.account == candidateKey.account &&
                    matchingKey.settleDate == candidateKey.settleDate
                ) {
                    matchedObligation = obligation
                    return@forEach
                }
            }
            
            if (matchedObligation != null) {
                val identity = matchedObligation!![IdentityC]
                val idempotency = matchedObligation!![IdempotencyC]
                val csdStatus = matchedObligation!![CsdStatusC]
                val msgIdSeqPair = Pair(statusEvent.msgId, statusEvent.seq)
                
                // Check for duplicate (msgId, seq)
                if (idempotency.seen.contains(msgIdSeqPair)) {
                    outboxEvents.add(DomainEvent.DuplicateIgnored(
                        obligationId = identity.obligationId,
                        msgId = statusEvent.msgId,
                        seq = statusEvent.seq
                    ))
                    statusEntity.remove()
                    return@forEach
                }
                
                // Check for out-of-order (seq <= lastSeq) 
                csdStatus.lastSeq.fold(
                    ifEmpty = { /* No previous seq, continue */ },
                    ifSome = { lastSeq ->
                        if (statusEvent.seq <= lastSeq) {
                            outboxEvents.add(DomainEvent.OutOfOrderIgnored(
                                obligationId = identity.obligationId,
                                lastSeq = lastSeq,
                                msgId = statusEvent.msgId,
                                seq = statusEvent.seq
                            ))
                            statusEntity.remove()
                            return@forEach
                        }
                    }
                )
                
                // Mark as seen and ready for correlation
                idempotency.seen.add(msgIdSeqPair)
                statusEntity.configure { entity ->
                    entity += ProcessedStatusC(matchedObligation!!.id)
                }
            } else {
                // No matching obligation found
                val keyString = "${candidateKey.isin}-${candidateKey.account}-${candidateKey.settleDate}"
                outboxEvents.add(DomainEvent.NoMatch(
                    msgId = statusEvent.msgId,
                    seq = statusEvent.seq,
                    key = keyString
                ))
                statusEntity.remove()
            }
        }
    }
    
    private fun correlateSystem() {
        world.family { all(ParsedStatusC, ProcessedStatusC) }.forEach { statusEntity ->
            val statusEvent = statusEntity[ParsedStatusC]
            val processed = statusEntity[ProcessedStatusC]
            
            // Find the obligation entity by ID
            var obligationEntity: Entity? = null
            obligations.forEach { entity ->
                if (entity.id == processed.obligationEntityId) {
                    obligationEntity = entity
                    return@forEach
                }
            }
            
            if (obligationEntity != null) {
                // Update correlation
                obligationEntity!![CorrelationC].lastStatusEventId = Option.Some(statusEntity.id.toString())
                
                // Update CSD status
                val csdStatus = obligationEntity!![CsdStatusC]
                csdStatus.lastCode = Option.Some(statusEvent.code)
                csdStatus.lastMsgId = Option.Some(statusEvent.msgId)
                csdStatus.lastSeq = Option.Some(statusEvent.seq)
                csdStatus.lastAt = Option.Some(statusEvent.at)
                
                // Mark as ready for lifecycle processing
                statusEntity.configure { entity ->
                    entity += CorrelatedStatusC(processed.obligationEntityId)
                }
            }
        }
    }
    
    private fun lifecycleSystem() {
        world.family { all(ParsedStatusC, CorrelatedStatusC) }.forEach { statusEntity ->
            val statusEvent = statusEntity[ParsedStatusC]
            val correlated = statusEntity[CorrelatedStatusC]
            
            // Find the obligation entity by ID
            var obligationEntity: Entity? = null
            obligations.forEach { entity ->
                if (entity.id == correlated.obligationEntityId) {
                    obligationEntity = entity
                    return@forEach
                }
            }
            
            if (obligationEntity == null) return@forEach
            val identity = obligationEntity!![IdentityC]
            val lifecycle = obligationEntity!![LifecycleC]
            val quantities = obligationEntity!![QuantitiesC]
            
            // Handle lifecycle transition  
            val oldState = lifecycle.state
            val newState = when (statusEvent.code) {
                CanonCode.ACK -> LifecycleState.New
                CanonCode.MATCHED -> LifecycleState.Matched
                CanonCode.PARTIAL_SETTLED -> {
                    quantities.settledQty += statusEvent.qty
                    quantities.remainingQty = quantities.intendedQty - quantities.settledQty
                    
                    if (quantities.settledQty >= quantities.intendedQty) {
                        LifecycleState.Settled
                    } else {
                        LifecycleState.PartiallySettled
                    }
                }
                CanonCode.SETTLED -> {
                    quantities.settledQty = quantities.intendedQty
                    quantities.remainingQty = 0L
                    LifecycleState.Settled
                }
            }
            
            // Always emit events for meaningful changes
            val shouldEmitEvent = when (statusEvent.code) {
                CanonCode.PARTIAL_SETTLED, CanonCode.SETTLED -> true
                else -> oldState != newState
            }
            
            if (shouldEmitEvent) {
                lifecycle.state = newState
                
                outboxEvents.add(DomainEvent.StateChanged(
                    obligationId = identity.obligationId,
                    from = oldState,
                    to = newState,
                    settledQty = quantities.settledQty,
                    remainingQty = quantities.remainingQty,
                    msgId = statusEvent.msgId,
                    seq = statusEvent.seq,
                    at = statusEvent.at
                ))
            } else if (oldState != newState) {
                lifecycle.state = newState
            }
        }
    }
    
    private fun outboxSystem() {
        // Remove processed status events
        world.family { all(ParsedStatusC, CorrelatedStatusC) }.forEach { statusEntity ->
            statusEntity.remove()
        }
    }
    
    fun outbox(): List<DomainEvent> {
        return outboxEvents.toList()
    }
    
    fun clearOutbox() {
        outboxEvents.clear()
    }
    
    fun getObligation(entityId: Int): Option<ObligationView> {
        return try {
            var result: Option<ObligationView> = Option.None
            obligations.forEach { entity ->
                if (entity.id == entityId) {
                    result = Option.Some(ObligationView(
                        identity = entity[IdentityC],
                        matchingKey = entity[MatchingKeyC],
                        lifecycle = entity[LifecycleC],
                        quantities = entity[QuantitiesC]
                    ))
                }
            }
            result
        } catch (e: Exception) {
            Option.None
        }
    }
}

// Components moved to components/Components.kt