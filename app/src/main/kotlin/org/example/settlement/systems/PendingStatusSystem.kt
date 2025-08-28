package org.example.settlement.systems

import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World.Companion.family
import org.example.settlement.components.*

class PendingStatusSystem : IntervalSystem() {
    
    private val obligations = world.family { all(IdentityC, MatchingKeyC) }
    private val pendingEntities = world.family { all(PendingStatusC) }
    private var pendingEntity: Entity? = null
    private var initialized = false
    
    override fun onTick() {
        // Initialize pending status entity on first tick
        if (!initialized) {
            pendingEntity = pendingEntities.firstOrNull() ?: world.entity { entity ->
                entity += PendingStatusC()
            }
            initialized = true
        }
        
        val pending = pendingEntity?.get(PendingStatusC) ?: return
        
        // Check for new obligations that might match pending statuses
        obligations.forEach { obligation ->
            val matchingKey = obligation[MatchingKeyC]
            val pendingStatuses = pending.getPendingStatuses(
                matchingKey.isin, 
                matchingKey.account, 
                matchingKey.settleDate
            )
            
            if (pendingStatuses.isNotEmpty()) {
                // Create status events for each pending status
                pendingStatuses.forEach { pendingStatus ->
                    world.entity { entity ->
                        entity += pendingStatus
                        entity += CandidateKeyC(
                            pendingStatus.isin,
                            pendingStatus.account, 
                            pendingStatus.settleDate,
                            pendingStatus.qty
                        )
                    }
                }
                
                // Remove processed pending statuses
                pending.removePendingStatuses(
                    matchingKey.isin,
                    matchingKey.account, 
                    matchingKey.settleDate
                )
            }
        }
    }
    
    fun getPendingEntity(): Entity? = pendingEntity
}