package org.example.settlement.components

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.example.settlement.domain.LifecycleState
import org.example.settlement.domain.CanonCode
import org.example.settlement.domain.DomainEvent

data class IdentityC(
    val obligationId: String,
    val venue: String
) : Component<IdentityC> {
    override fun type() = IdentityC
    companion object : ComponentType<IdentityC>()
}

data class MatchingKeyC(
    val isin: String,
    val account: String,
    val settleDate: LocalDate,
    val qty: Long
) : Component<MatchingKeyC> {
    override fun type() = MatchingKeyC
    companion object : ComponentType<MatchingKeyC>()
}

data class LifecycleC(
    var state: LifecycleState
) : Component<LifecycleC> {
    override fun type() = LifecycleC
    companion object : ComponentType<LifecycleC>()
}

data class QuantitiesC(
    val intendedQty: Long,
    var settledQty: Long = 0L,
    var remainingQty: Long = intendedQty
) : Component<QuantitiesC> {
    override fun type() = QuantitiesC
    companion object : ComponentType<QuantitiesC>()
}

data class CsdStatusC(
    var lastCode: CanonCode? = null,
    var lastMsgId: String? = null,
    var lastSeq: Long? = null,
    var lastAt: Instant? = null
) : Component<CsdStatusC> {
    override fun type() = CsdStatusC
    companion object : ComponentType<CsdStatusC>()
}

data class IdempotencyC(
    val seen: MutableSet<Pair<String, Long>> = mutableSetOf()
) : Component<IdempotencyC> {
    override fun type() = IdempotencyC
    companion object : ComponentType<IdempotencyC>()
}

data class CorrelationC(
    var lastStatusEventId: String? = null
) : Component<CorrelationC> {
    override fun type() = CorrelationC
    companion object : ComponentType<CorrelationC>()
}

data class ParsedStatusC(
    val msgId: String,
    val seq: Long,
    val code: CanonCode,
    val isin: String,
    val account: String,
    val settleDate: LocalDate,
    val qty: Long,
    val at: Instant
) : Component<ParsedStatusC> {
    override fun type() = ParsedStatusC
    companion object : ComponentType<ParsedStatusC>()
}

data class CandidateKeyC(
    val isin: String,
    val account: String,
    val settleDate: LocalDate,
    val qty: Long
) : Component<CandidateKeyC> {
    override fun type() = CandidateKeyC
    companion object : ComponentType<CandidateKeyC>()
}

// ECS Processing Components
data class ProcessedStatusC(
    val obligationEntityId: Int
) : Component<ProcessedStatusC> {
    override fun type() = ProcessedStatusC
    companion object : ComponentType<ProcessedStatusC>()
}

data class CorrelatedStatusC(
    val obligationEntityId: Int
) : Component<CorrelatedStatusC> {
    override fun type() = CorrelatedStatusC
    companion object : ComponentType<CorrelatedStatusC>()
}

data class IndexC(
    val matchingKeyToEntityId: MutableMap<String, Int> = mutableMapOf(),
    val entityIdToMatchingKey: MutableMap<Int, String> = mutableMapOf()
) : Component<IndexC> {
    override fun type() = IndexC
    companion object : ComponentType<IndexC>()
    
    fun createKey(isin: String, account: String, settleDate: LocalDate): String {
        return "$isin-$account-$settleDate"
    }
    
    fun addObligation(isin: String, account: String, settleDate: LocalDate, entityId: Int) {
        val key = createKey(isin, account, settleDate)
        matchingKeyToEntityId[key] = entityId
        entityIdToMatchingKey[entityId] = key
    }
    
    fun removeObligation(isin: String, account: String, settleDate: LocalDate) {
        val key = createKey(isin, account, settleDate)
        val entityId = matchingKeyToEntityId.remove(key)
        if (entityId != null) {
            entityIdToMatchingKey.remove(entityId)
        }
    }
    
    fun removeObligationByEntityId(entityId: Int) {
        val key = entityIdToMatchingKey.remove(entityId)
        if (key != null) {
            matchingKeyToEntityId.remove(key)
        }
    }
    
    fun findObligation(isin: String, account: String, settleDate: LocalDate): Int? {
        val key = createKey(isin, account, settleDate)
        return matchingKeyToEntityId[key]
    }
}

data class PendingStatusC(
    val pendingByKey: MutableMap<String, MutableList<ParsedStatusC>> = mutableMapOf()
) : Component<PendingStatusC> {
    override fun type() = PendingStatusC
    companion object : ComponentType<PendingStatusC>()
    
    fun createKey(isin: String, account: String, settleDate: LocalDate): String {
        return "$isin-$account-$settleDate"
    }
    
    fun addPendingStatus(status: ParsedStatusC) {
        val key = createKey(status.isin, status.account, status.settleDate)
        pendingByKey.getOrPut(key) { mutableListOf() }.add(status)
    }
    
    fun getPendingStatuses(isin: String, account: String, settleDate: LocalDate): List<ParsedStatusC> {
        val key = createKey(isin, account, settleDate)
        return pendingByKey[key]?.toList() ?: emptyList()
    }
    
    fun removePendingStatuses(isin: String, account: String, settleDate: LocalDate) {
        val key = createKey(isin, account, settleDate)
        pendingByKey.remove(key)
    }
}