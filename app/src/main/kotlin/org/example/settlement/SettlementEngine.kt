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

data class ObligationView(
    val identity: IdentityC,
    val matchingKey: MatchingKeyC,
    val lifecycle: LifecycleC,
    val quantities: QuantitiesC
)

class SettlementEngine {
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
        // Manual system execution for now - correlate status events to obligations
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
                    // Note: qty not compared as status events contain settlement amounts, not total obligation amounts
                ) {
                    matchedObligation = obligation
                    return@forEach
                }
            }
            
            if (matchedObligation != null) {
                val identity = matchedObligation!![IdentityC]
                val idempotency = matchedObligation!![IdempotencyC]
                val msgIdSeqPair = Pair(statusEvent.msgId, statusEvent.seq)
                
                // Check for duplicate (msgId, seq)
                if (idempotency.seen.contains(msgIdSeqPair)) {
                    // Emit duplicate ignored event
                    outboxEvents.add(DomainEvent.DuplicateIgnored(
                        obligationId = identity.obligationId,
                        msgId = statusEvent.msgId,
                        seq = statusEvent.seq
                    ))
                    // Remove the status event entity and skip processing
                    statusEntity.remove()
                    return@forEach
                }
                
                // Check for out-of-order (seq <= lastSeq) 
                val csdStatus = matchedObligation!![CsdStatusC]
                csdStatus.lastSeq.fold(
                    ifEmpty = { /* No previous seq, continue */ },
                    ifSome = { lastSeq ->
                        if (statusEvent.seq <= lastSeq) {
                            // Emit out of order ignored event
                            outboxEvents.add(DomainEvent.OutOfOrderIgnored(
                                obligationId = identity.obligationId,
                                lastSeq = lastSeq,
                                msgId = statusEvent.msgId,
                                seq = statusEvent.seq
                            ))
                            // Remove the status event entity and skip processing
                            statusEntity.remove()
                            return@forEach
                        }
                    }
                )
                
                // Add to seen set
                idempotency.seen.add(msgIdSeqPair)
                
                // Update correlation
                matchedObligation!![CorrelationC].lastStatusEventId = Option.Some(statusEntity.id.toString())
                
                // Update CSD status
                csdStatus.lastCode = Option.Some(statusEvent.code)
                csdStatus.lastMsgId = Option.Some(statusEvent.msgId)
                csdStatus.lastSeq = Option.Some(statusEvent.seq)
                csdStatus.lastAt = Option.Some(statusEvent.at)
                
                // Handle lifecycle transition  
                val lifecycle = matchedObligation!![LifecycleC]
                val quantities = matchedObligation!![QuantitiesC]
                
                val oldState = lifecycle.state
                val newState = when (statusEvent.code) {
                    CanonCode.ACK -> LifecycleState.New
                    CanonCode.MATCHED -> LifecycleState.Matched
                    CanonCode.PARTIAL_SETTLED -> {
                        // Update quantities for partial settlement
                        quantities.settledQty += statusEvent.qty
                        quantities.remainingQty = quantities.intendedQty - quantities.settledQty
                        
                        if (quantities.settledQty >= quantities.intendedQty) {
                            LifecycleState.Settled
                        } else {
                            LifecycleState.PartiallySettled
                        }
                    }
                    CanonCode.SETTLED -> {
                        // Final settlement - set to complete
                        quantities.settledQty = quantities.intendedQty
                        quantities.remainingQty = 0L
                        LifecycleState.Settled
                    }
                }
                
                // Always emit events for meaningful changes
                val shouldEmitEvent = when (statusEvent.code) {
                    CanonCode.PARTIAL_SETTLED, CanonCode.SETTLED -> true  // Always emit for settlement updates
                    else -> oldState != newState  // Only emit for state transitions for other codes
                }
                
                if (shouldEmitEvent) {
                    lifecycle.state = newState
                    
                    // Emit state changed event
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
                    // Update state even if not emitting event
                    lifecycle.state = newState
                }
            } else {
                // No matching obligation found
                val keyString = "${candidateKey.isin}-${candidateKey.account}-${candidateKey.settleDate}"
                outboxEvents.add(DomainEvent.NoMatch(
                    msgId = statusEvent.msgId,
                    seq = statusEvent.seq,
                    key = keyString
                ))
            }
            
            // Remove the status event entity
            statusEntity.remove()
        }
        
        world.update(0f)
    }
    
    fun outbox(): List<DomainEvent> {
        return outboxEvents.toList()
    }
    
    fun clearOutbox() {
        outboxEvents.clear()
    }
    
    fun getObligation(entityId: Int): Option<ObligationView> {
        return try {
            // Convert entity ID back to entity and access components
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