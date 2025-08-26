package org.example.settlement.matching

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.example.settlement.SettlementEngine
import org.example.settlement.domain.CanonCode

class MatchingEngineTest : DescribeSpec({

    describe("MatchingEngine orchestration") {
        it("creates obligation from bank queue, processes market update, and emits indication") {
            val engine = SettlementEngine()
            val bankQ = InMemoryQueue<MatchingEngine.BankUpdate>()
            val mktQ = InMemoryQueue<MatchingEngine.MarketUpdate>()
            val outQ = InMemoryQueue<MatchingEngine.StatusIndication>()
            val orchestrator = MatchingEngine(engine, bankQ, mktQ, outQ)

            // 1) Seed bank side
            bankQ.offer(
                MatchingEngine.BankUpdate(
                    obligationId = "OBL100",
                    venue = "LSE",
                    isin = "GB0000000001",
                    account = "ACC100",
                    settleDate = LocalDate(2024, 6, 1),
                    intendedQty = 100
                )
            )

            // 2) Seed market side with MATCHED
            mktQ.offer(
                MatchingEngine.MarketUpdate(
                    msgId = "M100",
                    seq = 1,
                    code = CanonCode.MATCHED,
                    isin = "GB0000000001",
                    account = "ACC100",
                    settleDate = LocalDate(2024, 6, 1),
                    qty = 100,
                    at = Instant.parse("2024-06-01T00:00:00Z")
                )
            )

            orchestrator.processOnce()

            // 3) We should have at least one status indication reflecting a StateChanged
            outQ.isEmpty() shouldBe false
            val first = outQ.poll()!!
            first.summary.contains("StateChanged") shouldBe true
        }

        it("partial then settled produces multiple indications in order") {
            val engine = SettlementEngine()
            val bankQ = InMemoryQueue<MatchingEngine.BankUpdate>()
            val mktQ = InMemoryQueue<MatchingEngine.MarketUpdate>()
            val outQ = InMemoryQueue<MatchingEngine.StatusIndication>()
            val orchestrator = MatchingEngine(engine, bankQ, mktQ, outQ)

            bankQ.offer(
                MatchingEngine.BankUpdate(
                    obligationId = "OBL200",
                    venue = "XETRA",
                    isin = "DE0000000002",
                    account = "ACC200",
                    settleDate = LocalDate(2024, 7, 1),
                    intendedQty = 100
                )
            )

            // MATCHED
            mktQ.offer(
                MatchingEngine.MarketUpdate("A1", 1, CanonCode.MATCHED, "DE0000000002", "ACC200", LocalDate(2024,7,1), 100, Instant.parse("2024-07-01T00:00:00Z"))
            )
            orchestrator.processOnce()

            // PARTIAL 40
            mktQ.offer(
                MatchingEngine.MarketUpdate("A2", 2, CanonCode.PARTIAL_SETTLED, "DE0000000002", "ACC200", LocalDate(2024,7,1), 40, Instant.parse("2024-07-01T00:00:10Z"))
            )
            orchestrator.processOnce()

            // SETTLED remaining 60
            mktQ.offer(
                MatchingEngine.MarketUpdate("A3", 3, CanonCode.SETTLED, "DE0000000002", "ACC200", LocalDate(2024,7,1), 60, Instant.parse("2024-07-01T00:00:20Z"))
            )
            orchestrator.processOnce()

            // Drain out indications and verify ordering keywords
            val summaries = mutableListOf<String>()
            while (!outQ.isEmpty()) {
                summaries.add(outQ.poll()!!.summary)
            }

            // We expect at least three StateChanged indications across the three runs
            val stateChanged = summaries.filter { it.contains("StateChanged") }
            stateChanged.size shouldBe 3
            stateChanged[0].contains("to=Matched") shouldBe true
            stateChanged[1].contains("to=PartiallySettled") shouldBe true
            stateChanged[2].contains("to=Settled") shouldBe true
        }
    }
})
