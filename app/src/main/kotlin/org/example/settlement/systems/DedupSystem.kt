package org.example.settlement.systems

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import org.example.settlement.components.*
import org.example.settlement.domain.DomainEvent

class DedupSystem : IteratingSystem(
    family { all(ParsedStatusC, CandidateKeyC) }
) {
    
    private val obligations = world.family { all(IdentityC, MatchingKeyC, LifecycleC, QuantitiesC, CsdStatusC, IdempotencyC) }
    private val indexEntities = world.family { all(IndexC) }
    private val outboxEvents = mutableListOf<DomainEvent>()
    
    fun getOutboxEvents(): List<DomainEvent> = outboxEvents.toList()
    fun clearOutbox() = outboxEvents.clear()

    override fun onTickEntity(entity: Entity) {
        val statusEvent = entity[ParsedStatusC]
        val candidateKey = entity[CandidateKeyC]
        
        // Find matching obligation using index for O(1) lookup
        var matchedObligation: Entity? = null
        
        // Get the index component
        val indexEntity = indexEntities.firstOrNull()
        val index = indexEntity?.get(IndexC)
        
        if (index != null) {
            // O(1) lookup using the index
            val entityId = index.findObligation(candidateKey.isin, candidateKey.account, candidateKey.settleDate)
            
            if (entityId != null) {
                // Find the actual entity by ID - still O(n) but cleaner than forEach
                matchedObligation = obligations.find { obligation -> obligation.id == entityId }
            }
        } else {
            // Fallback to linear search if index not available (defensive programming)
            matchedObligation = obligations.find { obligation ->
                val matchingKey = obligation[MatchingKeyC]
                matchingKey.isin == candidateKey.isin &&
                    matchingKey.account == candidateKey.account &&
                    matchingKey.settleDate == candidateKey.settleDate
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
                entity.remove()
                return
            }
            
            // Check for out-of-order (seq <= lastSeq) 
            csdStatus.lastSeq?.let { lastSeq ->
                if (statusEvent.seq <= lastSeq) {
                    outboxEvents.add(DomainEvent.OutOfOrderIgnored(
                        obligationId = identity.obligationId,
                        lastSeq = lastSeq,
                        msgId = statusEvent.msgId,
                        seq = statusEvent.seq
                    ))
                    entity.remove()
                    return
                }
            }
            
            // Mark as seen and ready for correlation
            idempotency.seen.add(msgIdSeqPair)
            entity.configure { e ->
                e += ProcessedStatusC(matchedObligation!!.id)
            }
        } else {
            // No matching obligation found
            val keyString = "${candidateKey.isin}-${candidateKey.account}-${candidateKey.settleDate}"
            outboxEvents.add(DomainEvent.NoMatch(
                msgId = statusEvent.msgId,
                seq = statusEvent.seq,
                key = keyString
            ))
            entity.remove()
        }
    }
}