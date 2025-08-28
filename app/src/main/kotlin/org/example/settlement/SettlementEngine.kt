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
import org.example.settlement.systems.DedupSystem
import org.example.settlement.systems.CorrelateSystem
import org.example.settlement.systems.LifecycleSystem
import org.example.settlement.systems.OutboxSystem
import org.example.settlement.systems.IndexingSystem

data class ObligationView(
    val identity: IdentityC,
    val matchingKey: MatchingKeyC,
    val lifecycle: LifecycleC,
    val quantities: QuantitiesC
)

class SettlementEngine : Engine {
    private val world = configureWorld {
        systems {
            add(IndexingSystem())
            add(DedupSystem())
            add(CorrelateSystem())
            add(LifecycleSystem())
            add(OutboxSystem())
        }
    }
    private val obligations = world.family { all(IdentityC, MatchingKeyC, LifecycleC, QuantitiesC) }
    private val outboxEvents = mutableListOf<DomainEvent>()
    
    // Get system references for accessing outbox
    private val indexingSystem = world.system<IndexingSystem>()
    private val dedupSystem = world.system<DedupSystem>()
    private val lifecycleSystem = world.system<LifecycleSystem>()
    
    // Workaround for testing until getObligation compiler issue is fixed
    internal val obligationsForTesting get() = obligations
    
    override fun createObligation(
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
    
    override fun ingestStatus(
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
    
    /**
     * Domain-aligned processor for a batch of status updates.
     * ECS note: this corresponds to a single world "tick" over our systems.
     */
    override fun processStatusUpdates() {
        // Let Fleks systems run in their registered order
        world.update(0f)
        
        // Collect outbox events from systems
        outboxEvents.addAll(dedupSystem.getOutboxEvents())
        dedupSystem.clearOutbox()
        
        outboxEvents.addAll(lifecycleSystem.getOutboxEvents())
        lifecycleSystem.clearOutbox()
        
        // All systems now run automatically with world.update() including OutboxSystem
    }

    /**
     * Backwards compat: previous ECS-style name. Prefer processStatusUpdates().
     */
    fun tick() = processStatusUpdates()
    
    
    
    
    
    override fun outbox(): List<DomainEvent> {
        return outboxEvents.toList()
    }
    
    fun clearOutbox() {
        outboxEvents.clear()
    }
    
    private fun findEntity(entityId: Int): Entity? {
        var foundEntity: Entity? = null
        obligations.forEach { entity ->
            if (entity.id == entityId) {
                foundEntity = entity
                return@forEach
            }
        }
        return foundEntity
    }
    
    fun getObligation(entityId: Int): Result<ObligationView> {
        var view: ObligationView? = null
        obligations.forEach { e ->
            if (e.id == entityId) {
                val identity = e[IdentityC]
                val matchingKey = e[MatchingKeyC]
                val lifecycle = e[LifecycleC]
                val quantities = e[QuantitiesC]
                view = ObligationView(
                    identity = identity,
                    matchingKey = matchingKey,
                    lifecycle = lifecycle,
                    quantities = quantities
                )
                return@forEach
            }
        }
        return view?.let { Result.success(it) }
            ?: Result.failure(NoSuchElementException("Obligation entity $entityId not found"))
    }
}

