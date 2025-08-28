package org.example.settlement.systems

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import org.example.settlement.components.*
import org.example.settlement.domain.DomainEvent
import org.example.settlement.domain.LifecycleState
import org.example.settlement.domain.CanonCode

class LifecycleSystem : IteratingSystem(
    family { all(ParsedStatusC, CorrelatedStatusC) }
) {
    
    private val outboxEvents = mutableListOf<DomainEvent>()
    
    // Cache the family reference for better performance
    private val obligations = world.family { all(IdentityC, MatchingKeyC, LifecycleC, QuantitiesC, CsdStatusC, IdempotencyC, CorrelationC) }
    
    fun getOutboxEvents(): List<DomainEvent> = outboxEvents.toList()
    fun clearOutbox() = outboxEvents.clear()

    override fun onTickEntity(entity: Entity) {
        val statusEvent = entity[ParsedStatusC]
        val correlated = entity[CorrelatedStatusC]
        
        // Find the obligation entity by ID
        val obligationEntity = findEntity(correlated.obligationEntityId)
        if (obligationEntity != null) {
            val identity = obligationEntity[IdentityC]
            val lifecycle = obligationEntity[LifecycleC]
            val quantities = obligationEntity[QuantitiesC]
    
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
    
    private fun findEntity(entityId: Int): Entity? {
        // Use cached family reference and find for better performance
        return obligations.find { entity -> entity.id == entityId }
    }
}