package org.example.settlement.systems

import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World.Companion.family
import org.example.settlement.components.*

class IndexingSystem : IntervalSystem() {
    
    private val obligations = world.family { all(IdentityC, MatchingKeyC) }
    private val indexEntities = world.family { all(IndexC) }
    private var indexEntity: Entity? = null
    private val trackedObligations = mutableSetOf<Int>()
    private var initialized = false
    
    override fun onTick() {
        // Initialize index entity on first tick
        if (!initialized) {
            indexEntity = indexEntities.firstOrNull() ?: world.entity { entity ->
                entity += IndexC()
            }
            initialized = true
        }
        
        val index = indexEntity?.get(IndexC) ?: return
        
        // Track new obligations and add them to index
        obligations.forEach { obligation ->
            val entityId = obligation.id
            if (!trackedObligations.contains(entityId)) {
                val matchingKey = obligation[MatchingKeyC]
                index.addObligation(
                    isin = matchingKey.isin,
                    account = matchingKey.account,
                    settleDate = matchingKey.settleDate,
                    entityId = entityId
                )
                trackedObligations.add(entityId)
            }
        }
        
        // Remove obligations that no longer exist
        val currentObligationIds = obligations.map { it.id }.toSet()
        val idsToRemove = trackedObligations - currentObligationIds
        
        idsToRemove.forEach { entityId ->
            // Find and remove from index - we need to search by entity ID
            val entryToRemove = index.matchingKeyToEntityId.entries.find { it.value == entityId }
            entryToRemove?.let { entry ->
                index.matchingKeyToEntityId.remove(entry.key)
            }
            trackedObligations.remove(entityId)
        }
    }
    
    fun getIndexEntity(): Entity? = indexEntity
}