package org.example.settlement.systems

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import org.example.settlement.components.*

class CorrelateSystem : IteratingSystem(
    family { all(ParsedStatusC, ProcessedStatusC) }
) {
    
    // Cache the family reference for better performance
    private val obligations = world.family { all(IdentityC, MatchingKeyC, LifecycleC, QuantitiesC, CsdStatusC, IdempotencyC, CorrelationC) }

    override fun onTickEntity(entity: Entity) {
        val statusEvent = entity[ParsedStatusC]
        val processed = entity[ProcessedStatusC]
        
        // Find the obligation entity by ID
        val obligationEntity = findEntity(processed.obligationEntityId)
        if (obligationEntity != null) {
            // Update correlation
            obligationEntity[CorrelationC].lastStatusEventId = entity.id.toString()
            
            // Update CSD status
            val csdStatus = obligationEntity[CsdStatusC]
            csdStatus.lastCode = statusEvent.code
            csdStatus.lastMsgId = statusEvent.msgId
            csdStatus.lastSeq = statusEvent.seq
            csdStatus.lastAt = statusEvent.at
            
            // Mark as ready for lifecycle processing
            entity.configure { e ->
                e += CorrelatedStatusC(processed.obligationEntityId)
            }
        }
    }
    
    private fun findEntity(entityId: Int): Entity? {
        // Use cached family reference and find for better performance
        return obligations.find { entity -> entity.id == entityId }
    }
}