package org.example.settlement.domain

enum class LifecycleState {
    New, Matched, PartiallySettled, Settled
}

enum class CanonCode {
    ACK, MATCHED, PARTIAL_SETTLED, SETTLED
}