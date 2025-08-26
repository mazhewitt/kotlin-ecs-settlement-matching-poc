package org.example.settlement

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.LocalDate
import org.example.settlement.domain.LifecycleState

class SettlementEngineTest : DescribeSpec({
    
    describe("Settlement Engine") {
        
        it("createsObligationWithInitialComponents") {
            val engine = SettlementEngine()
            
            val obligationId = engine.createObligation(
                id = "OBL001",
                venue = "NYSE",
                isin = "US0378331005",
                account = "ACC123",
                settleDate = LocalDate(2024, 1, 15),
                intendedQty = 1000L
            )
            
            // Verify entity was created with valid ID
            obligationId shouldBe 0  // First entity should have ID 0
            
            // Verify all initial component states
            val obligation = engine.getObligation(obligationId)
            obligation.fold(
                onSuccess = { view ->
                    // Identity component
                    view.identity.obligationId shouldBe "OBL001"
                    view.identity.venue shouldBe "NYSE"
                    
                    // Matching key component
                    view.matchingKey.isin shouldBe "US0378331005"
                    view.matchingKey.account shouldBe "ACC123"
                    view.matchingKey.settleDate shouldBe LocalDate(2024, 1, 15)
                    view.matchingKey.qty shouldBe 1000L
                    
                    // Lifecycle component - should start as New
                    view.lifecycle.state shouldBe LifecycleState.New
                    
                    // Quantities component - initial state
                    view.quantities.intendedQty shouldBe 1000L
                    view.quantities.settledQty shouldBe 0L
                    view.quantities.remainingQty shouldBe 1000L
                },
                onFailure = { throw AssertionError("Expected obligation to be found", it) }
            )
        }
        
        it("matchedEventAdvancesStateToMatchedAndEmitsEvent") {
            val engine = SettlementEngine()
            engine.clearOutbox() // Start clean
            
            // Create obligation first
            val obligationId = engine.createObligation(
                id = "OBL001",
                venue = "NYSE",
                isin = "US0378331005",
                account = "ACC123",
                settleDate = LocalDate(2024, 1, 15),
                intendedQty = 1000L
            )
            
            // Ingest MATCHED status event
            engine.ingestStatus(
                msgId = "MSG001",
                seq = 1L,
                code = org.example.settlement.domain.CanonCode.MATCHED,
                isin = "US0378331005",
                account = "ACC123", 
                settleDate = LocalDate(2024, 1, 15),
                qty = 1000L,
                at = kotlinx.datetime.Clock.System.now()
            )
            
            // Process world tick
            engine.processStatusUpdates()
            
            // Verify state change and event emission
            val obligation = engine.getObligation(obligationId)
            obligation.fold(
                onSuccess = { view ->
                    view.lifecycle.state shouldBe LifecycleState.Matched
                },
                onFailure = { throw AssertionError("Expected obligation to be found", it) }
            )
            
            val events = engine.outbox()
            events.size shouldBe 1
            val stateChangedEvent = events.first() as org.example.settlement.domain.DomainEvent.StateChanged
            stateChangedEvent.obligationId shouldBe "OBL001"
            stateChangedEvent.from shouldBe LifecycleState.New
            stateChangedEvent.to shouldBe LifecycleState.Matched
            stateChangedEvent.msgId shouldBe "MSG001"
            stateChangedEvent.seq shouldBe 1L
        }
        
        it("partialSettlesAccumulateQuantitiesAndRemainPartiallySettled") {
            val engine = SettlementEngine()
            engine.clearOutbox() // Start clean
            
            // Create obligation for 100 shares
            val obligationId = engine.createObligation(
                id = "OBL002",
                venue = "NYSE", 
                isin = "US0378331005",
                account = "ACC123",
                settleDate = LocalDate(2024, 1, 15),
                intendedQty = 100L
            )
            
            // Verify initial state
            val initialObligation = engine.getObligation(obligationId)
            initialObligation.fold(
                onSuccess = { view ->
                    view.lifecycle.state shouldBe LifecycleState.New
                    view.quantities.intendedQty shouldBe 100L
                    view.quantities.settledQty shouldBe 0L
                    view.quantities.remainingQty shouldBe 100L
                },
                onFailure = { throw AssertionError("Expected obligation to be found", it) }
            )
            
            // First MATCHED event
            engine.ingestStatus(
                msgId = "MSG001",
                seq = 1L,
                code = org.example.settlement.domain.CanonCode.MATCHED,
                isin = "US0378331005",
                account = "ACC123",
                settleDate = LocalDate(2024, 1, 15),
                qty = 100L,
                at = kotlinx.datetime.Clock.System.now()
            )
            engine.processStatusUpdates()
            
            // First partial settlement - 30 shares
            engine.ingestStatus(
                msgId = "MSG002",
                seq = 2L,
                code = org.example.settlement.domain.CanonCode.PARTIAL_SETTLED,
                isin = "US0378331005", 
                account = "ACC123",
                settleDate = LocalDate(2024, 1, 15),
                qty = 30L,
                at = kotlinx.datetime.Clock.System.now()
            )
            engine.processStatusUpdates()
            
            // Check state after first partial
            val afterFirstPartial = engine.getObligation(obligationId)
            afterFirstPartial.fold(
                onSuccess = { view ->
                    view.lifecycle.state shouldBe LifecycleState.PartiallySettled
                    view.quantities.settledQty shouldBe 30L
                    view.quantities.remainingQty shouldBe 70L
                },
                onFailure = { throw AssertionError("Expected obligation to be found", it) }
            )
            
            // Second partial settlement - 20 shares
            engine.ingestStatus(
                msgId = "MSG003",
                seq = 3L,
                code = org.example.settlement.domain.CanonCode.PARTIAL_SETTLED,
                isin = "US0378331005",
                account = "ACC123", 
                settleDate = LocalDate(2024, 1, 15),
                qty = 20L,
                at = kotlinx.datetime.Clock.System.now()
            )
            engine.processStatusUpdates()
            
            // Verify final quantities and state
            val obligation = engine.getObligation(obligationId)
            obligation.fold(
                onSuccess = { view ->
                    view.lifecycle.state shouldBe LifecycleState.PartiallySettled
                    view.quantities.intendedQty shouldBe 100L
                    view.quantities.settledQty shouldBe 50L
                    view.quantities.remainingQty shouldBe 50L
                },
                onFailure = { throw AssertionError("Expected obligation to be found", it) }
            )
            
            // Verify we have partial settlement events
            val events = engine.outbox()
            val stateChangedEvents = events.filterIsInstance<org.example.settlement.domain.DomainEvent.StateChanged>()
            
            // Verify we have at least the expected partial settlement events
            val partialEvents = stateChangedEvents.filter { it.to == LifecycleState.PartiallySettled }
            partialEvents.size shouldBe 2
            
            // Verify first partial event details
            val firstPartial = partialEvents.find { it.seq == 2L }
            firstPartial shouldNotBe null
            firstPartial!!.obligationId shouldBe "OBL002"
            firstPartial.from shouldBe LifecycleState.Matched
            firstPartial.to shouldBe LifecycleState.PartiallySettled
            firstPartial.settledQty shouldBe 30L
            firstPartial.remainingQty shouldBe 70L
            
            // Verify second partial event details
            val secondPartial = partialEvents.find { it.seq == 3L }
            secondPartial shouldNotBe null
            secondPartial!!.settledQty shouldBe 50L
            secondPartial.remainingQty shouldBe 50L
        }
        
        it("settledEventCompletesObligationAndEmitsEvent") {
            val engine = SettlementEngine()
            engine.clearOutbox() // Start clean
            
            // Create obligation for 1000 shares
            val obligationId = engine.createObligation(
                id = "OBL003",
                venue = "NYSE",
                isin = "US0378331005", 
                account = "ACC123",
                settleDate = LocalDate(2024, 1, 15),
                intendedQty = 1000L
            )
            
            // MATCHED event first
            engine.ingestStatus(
                msgId = "MSG001",
                seq = 1L,
                code = org.example.settlement.domain.CanonCode.MATCHED,
                isin = "US0378331005",
                account = "ACC123",
                settleDate = LocalDate(2024, 1, 15),
                qty = 1000L,
                at = kotlinx.datetime.Clock.System.now()
            )
            engine.processStatusUpdates()
            
            // Some partial settlements first (300 + 200 = 500)
            engine.ingestStatus(
                msgId = "MSG002", 
                seq = 2L,
                code = org.example.settlement.domain.CanonCode.PARTIAL_SETTLED,
                isin = "US0378331005",
                account = "ACC123",
                settleDate = LocalDate(2024, 1, 15),
                qty = 300L,
                at = kotlinx.datetime.Clock.System.now()
            )
            engine.processStatusUpdates()
            
            engine.ingestStatus(
                msgId = "MSG003",
                seq = 3L, 
                code = org.example.settlement.domain.CanonCode.PARTIAL_SETTLED,
                isin = "US0378331005",
                account = "ACC123",
                settleDate = LocalDate(2024, 1, 15),
                qty = 200L,
                at = kotlinx.datetime.Clock.System.now()
            )
            engine.processStatusUpdates()
            
            // Final SETTLED event for remaining 500 shares
            engine.ingestStatus(
                msgId = "MSG004",
                seq = 4L,
                code = org.example.settlement.domain.CanonCode.SETTLED,
                isin = "US0378331005",
                account = "ACC123", 
                settleDate = LocalDate(2024, 1, 15),
                qty = 500L,
                at = kotlinx.datetime.Clock.System.now()
            )
            engine.processStatusUpdates()
            
            // Verify final state - should be completely settled  
            val obligation = engine.getObligation(obligationId)
            obligation.fold(
                onSuccess = { view ->
                    view.lifecycle.state shouldBe LifecycleState.Settled
                    view.quantities.intendedQty shouldBe 1000L
                    view.quantities.settledQty shouldBe 1000L
                    view.quantities.remainingQty shouldBe 0L
                },
                onFailure = { throw AssertionError("Expected obligation to be found", it) }
            )
            
            // Verify events emitted (MATCHED + 2 PARTIAL + 1 SETTLED)
            val events = engine.outbox()
            val stateChangedEvents = events.filterIsInstance<org.example.settlement.domain.DomainEvent.StateChanged>()
            
            // Should have exactly 4 state changes: MATCHED + 2 PARTIAL + 1 SETTLED
            stateChangedEvents.size shouldBe 4
            
            // Verify MATCHED event
            val matchedEvent = stateChangedEvents.find { it.to == LifecycleState.Matched }
            matchedEvent shouldNotBe null
            matchedEvent!!.obligationId shouldBe "OBL003"
            matchedEvent.from shouldBe LifecycleState.New
            matchedEvent.seq shouldBe 1L
            
            // Verify PARTIAL events
            val partialEvents = stateChangedEvents.filter { it.to == LifecycleState.PartiallySettled }
            partialEvents.size shouldBe 2
            
            val firstPartial = partialEvents.find { it.seq == 2L }
            firstPartial shouldNotBe null
            firstPartial!!.settledQty shouldBe 300L
            firstPartial.remainingQty shouldBe 700L
            
            val secondPartial = partialEvents.find { it.seq == 3L }
            secondPartial shouldNotBe null
            secondPartial!!.settledQty shouldBe 500L
            secondPartial.remainingQty shouldBe 500L
            
            // Verify SETTLED event
            val settledEvent = stateChangedEvents.find { it.to == LifecycleState.Settled }
            settledEvent shouldNotBe null
            settledEvent!!.obligationId shouldBe "OBL003"
            settledEvent.from shouldBe LifecycleState.PartiallySettled
            settledEvent.to shouldBe LifecycleState.Settled
            settledEvent.settledQty shouldBe 1000L
            settledEvent.remainingQty shouldBe 0L
            settledEvent.msgId shouldBe "MSG004"
            settledEvent.seq shouldBe 4L
        }
        
        it("duplicateEventIsIgnoredAndEmitsDuplicateIgnored") {
            val engine = SettlementEngine()
            engine.clearOutbox() // Start clean
            
            // Create obligation
            val obligationId = engine.createObligation(
                id = "OBL004",
                venue = "NYSE",
                isin = "US0378331005",
                account = "ACC123",
                settleDate = LocalDate(2024, 1, 15),
                intendedQty = 1000L
            )
            
            // MATCHED event first
            engine.ingestStatus(
                msgId = "MSG001",
                seq = 1L,
                code = org.example.settlement.domain.CanonCode.MATCHED,
                isin = "US0378331005",
                account = "ACC123",
                settleDate = LocalDate(2024, 1, 15),
                qty = 1000L,
                at = kotlinx.datetime.Clock.System.now()
            )
            engine.processStatusUpdates()
            
            // Verify state after MATCHED
            val afterMatched = engine.getObligation(obligationId)
            afterMatched.fold(
                onSuccess = { view ->
                    view.lifecycle.state shouldBe LifecycleState.Matched
                },
                onFailure = { throw AssertionError("Expected obligation to be found", it) }
            )
            
            // Send the EXACT SAME message again (same msgId and seq)
            engine.ingestStatus(
                msgId = "MSG001", // Same msgId
                seq = 1L,         // Same seq  
                code = org.example.settlement.domain.CanonCode.MATCHED,
                isin = "US0378331005",
                account = "ACC123",
                settleDate = LocalDate(2024, 1, 15),
                qty = 1000L,
                at = kotlinx.datetime.Clock.System.now()
            )
            engine.processStatusUpdates()
            
            // State should be unchanged - still Matched
            val afterDuplicate = engine.getObligation(obligationId)
            afterDuplicate.fold(
                onSuccess = { view ->
                    view.lifecycle.state shouldBe LifecycleState.Matched // No state change
                },
                onFailure = { throw AssertionError("Expected obligation to be found", it) }
            )
            
            // Should have DuplicateIgnored event
            val events = engine.outbox()
            val duplicateEvents = events.filterIsInstance<org.example.settlement.domain.DomainEvent.DuplicateIgnored>()
            duplicateEvents.size shouldBe 1
            
            val duplicateEvent = duplicateEvents.first()
            duplicateEvent.obligationId shouldBe "OBL004"
            duplicateEvent.msgId shouldBe "MSG001"
            duplicateEvent.seq shouldBe 1L
        }

        it("outOfOrderEventIsIgnoredAndEmitsOutOfOrderIgnored") {
            val engine = SettlementEngine()
            engine.clearOutbox()

            val obligationId = engine.createObligation(
                id = "OBL005",
                venue = "NYSE",
                isin = "US0000000001",
                account = "ACC999",
                settleDate = LocalDate(2024, 1, 16),
                intendedQty = 100L
            )

            // First process a high sequence
            engine.ingestStatus(
                msgId = "MSG010",
                seq = 10L,
                code = org.example.settlement.domain.CanonCode.MATCHED,
                isin = "US0000000001",
                account = "ACC999",
                settleDate = LocalDate(2024, 1, 16),
                qty = 100L,
                at = kotlinx.datetime.Clock.System.now()
            )
            engine.processStatusUpdates()

            // Now send an out-of-order lower sequence
            engine.ingestStatus(
                msgId = "MSG009",
                seq = 9L,
                code = org.example.settlement.domain.CanonCode.PARTIAL_SETTLED,
                isin = "US0000000001",
                account = "ACC999",
                settleDate = LocalDate(2024, 1, 16),
                qty = 10L,
                at = kotlinx.datetime.Clock.System.now()
            )
            engine.tick()

            // State remains Matched, quantities unchanged
            val after = engine.getObligation(obligationId)
            after.fold(
                onSuccess = { view ->
                    view.lifecycle.state shouldBe LifecycleState.Matched
                    view.quantities.settledQty shouldBe 0L
                    view.quantities.remainingQty shouldBe 100L
                },
                onFailure = { throw AssertionError("Expected obligation to be found", it) }
            )

            // One OutOfOrderIgnored event emitted
            val events = engine.outbox()
            val outOfOrder = events.filterIsInstance<org.example.settlement.domain.DomainEvent.OutOfOrderIgnored>()
            outOfOrder.size shouldBe 1
            outOfOrder.first().let { evt ->
                evt.obligationId shouldBe "OBL005"
                evt.lastSeq shouldBe 10L
                evt.msgId shouldBe "MSG009"
                evt.seq shouldBe 9L
            }
        }

        it("noMatchingObligationEmitsNoMatch") {
            val engine = SettlementEngine()
            engine.clearOutbox()

            // No obligations created. Ingest an event.
            engine.ingestStatus(
                msgId = "MSG100",
                seq = 1L,
                code = org.example.settlement.domain.CanonCode.MATCHED,
                isin = "US1111111111",
                account = "ACC111",
                settleDate = LocalDate(2024, 2, 1),
                qty = 50L,
                at = kotlinx.datetime.Clock.System.now()
            )
            engine.tick()

            val events = engine.outbox()
            val noMatchEvents = events.filterIsInstance<org.example.settlement.domain.DomainEvent.NoMatch>()
            noMatchEvents.size shouldBe 1
            noMatchEvents.first().apply {
                msgId shouldBe "MSG100"
                seq shouldBe 1L
                key shouldBe "US1111111111-ACC111-2024-02-01"
            }
        }

        it("replayIsDeterministicForSameInputSequence") {
            fun runScenario(): Pair<List<org.example.settlement.domain.DomainEvent>, ObligationView> {
                val engine = SettlementEngine()
                val id = engine.createObligation(
                    id = "OBLDET",
                    venue = "LSE",
                    isin = "US2222222222",
                    account = "ACC222",
                    settleDate = LocalDate(2024, 3, 1),
                    intendedQty = 100L
                )

                // Deterministic input sequence
                val t1 = kotlinx.datetime.Instant.parse("2024-03-01T00:00:00Z")
                val t2 = kotlinx.datetime.Instant.parse("2024-03-01T00:00:10Z")
                val t3 = kotlinx.datetime.Instant.parse("2024-03-01T00:00:20Z")
                val t4 = kotlinx.datetime.Instant.parse("2024-03-01T00:00:30Z")
                engine.ingestStatus("M1", 1, org.example.settlement.domain.CanonCode.MATCHED, "US2222222222", "ACC222", LocalDate(2024, 3, 1), 100, t1)
                engine.processStatusUpdates()
                engine.ingestStatus("M2", 2, org.example.settlement.domain.CanonCode.PARTIAL_SETTLED, "US2222222222", "ACC222", LocalDate(2024, 3, 1), 30, t2)
                engine.processStatusUpdates()
                engine.ingestStatus("M3", 3, org.example.settlement.domain.CanonCode.PARTIAL_SETTLED, "US2222222222", "ACC222", LocalDate(2024, 3, 1), 20, t3)
                engine.processStatusUpdates()
                engine.ingestStatus("M4", 4, org.example.settlement.domain.CanonCode.SETTLED, "US2222222222", "ACC222", LocalDate(2024, 3, 1), 50, t4)
                engine.processStatusUpdates()

                val view = engine.getObligation(id).getOrThrow()
                return engine.outbox() to view
            }

            val (outbox1, view1) = runScenario()
            val (outbox2, view2) = runScenario()

            // Outbox event streams should be identical
            outbox1 shouldBe outbox2

            // Final state should be identical
            view1.lifecycle.state shouldBe view2.lifecycle.state
            view1.quantities.intendedQty shouldBe view2.quantities.intendedQty
            view1.quantities.settledQty shouldBe view2.quantities.settledQty
            view1.quantities.remainingQty shouldBe view2.quantities.remainingQty
        }
    }
})