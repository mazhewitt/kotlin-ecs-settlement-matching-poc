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
            engine.tick()
            
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
            engine.tick()
            
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
            engine.tick()
            
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
            engine.tick()
            
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
            engine.tick()
            
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
            engine.tick()
            
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
            engine.tick()
            
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
            engine.tick()
            
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
            engine.tick()
            
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
            engine.tick()
            
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
    }
})