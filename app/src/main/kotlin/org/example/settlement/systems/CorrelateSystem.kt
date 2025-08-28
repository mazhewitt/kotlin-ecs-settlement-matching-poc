package org.example.settlement.systems

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import org.example.settlement.components.*

class CorrelateSystem : IteratingSystem(
    family { all(ParsedStatusC, ProcessedStatusC) }
) {

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
        val obligations = world.family { all(IdentityC, MatchingKeyC, LifecycleC, QuantitiesC, CsdStatusC, IdempotencyC, CorrelationC) }
        var foundEntity: Entity? = null
        obligations.forEach { entity ->
            if (entity.id == entityId) {
                foundEntity = entity
                return@forEach
            }
        }
        return foundEntity
    }
}