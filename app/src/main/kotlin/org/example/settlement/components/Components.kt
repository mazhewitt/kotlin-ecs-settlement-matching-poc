package org.example.settlement.components

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.example.settlement.domain.LifecycleState
import org.example.settlement.domain.CanonCode
import org.example.common.Option

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
    var lastCode: Option<CanonCode> = Option.None,
    var lastMsgId: Option<String> = Option.None,
    var lastSeq: Option<Long> = Option.None,
    var lastAt: Option<Instant> = Option.None
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
    var lastStatusEventId: Option<String> = Option.None
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