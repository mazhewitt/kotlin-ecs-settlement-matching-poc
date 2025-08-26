package org.example.settlement

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import org.example.settlement.domain.LifecycleState
import org.example.settlement.domain.CanonCode

class SettlementEngineEcsTest : DescribeSpec({
    
    describe("Settlement Engine ECS") {
        
        it("createsObligationWithInitialComponents") {
            val engine = SettlementEngineEcs()
            
            val obligationId = engine.createObligation(
                id = "OBL001",
                venue = "NYSE",
                isin = "US0378331005",
                account = "ACC123",
                settleDate = LocalDate(2024, 1, 15),
                intendedQty = 1000L
            )
            
            // Verify entity was created
            obligationId shouldBe 0
            
            // Verify components
            val obligation = engine.getObligation(obligationId)
            obligation.fold(
                ifEmpty = { throw AssertionError("Expected obligation to be found") },
                ifSome = { view ->
                    view.identity.obligationId shouldBe "OBL001"
                    view.lifecycle.state shouldBe LifecycleState.New
                    view.quantities.intendedQty shouldBe 1000L
                }
            )
        }
        
        it("matchedEventAdvancesStateToMatched") {
            val engine = SettlementEngineEcs()
            engine.clearOutbox()
            
            val obligationId = engine.createObligation(
                id = "OBL001",
                venue = "NYSE",
                isin = "US0378331005",
                account = "ACC123",
                settleDate = LocalDate(2024, 1, 15),
                intendedQty = 1000L
            )
            
            engine.ingestStatus(
                msgId = "MSG001",
                seq = 1L,
                code = CanonCode.MATCHED,
                isin = "US0378331005",
                account = "ACC123", 
                settleDate = LocalDate(2024, 1, 15),
                qty = 1000L,
                at = kotlinx.datetime.Clock.System.now()
            )
            
            engine.tick()
            
            val obligation = engine.getObligation(obligationId)
            obligation.fold(
                ifEmpty = { throw AssertionError("Expected obligation to be found") },
                ifSome = { view ->
                    view.lifecycle.state shouldBe LifecycleState.Matched
                }
            )
            
            val events = engine.outbox()
            events.size shouldBe 1
        }
    }
})