package co.firstorderlabs.bqwriter

import org.scalatest.funspec.AnyFunSpec

class TestBigQueryEventWriter extends AnyFunSpec {
  Configs.testMode = true
  SequenceTracker.setMaxBufferSize(3)

  describe("EventObserver") {
    it("Test that the contents of BigQueryRows are written when EventObserver contains maxEventBufferSize events.") {
      val eventObserver = new EventObserver
      eventObserver.bigQueryEventsWriter.setMaxEventBufferSize(3)
      val firstBatch = List(
        TestData.cancellationTransaction,
        TestData.matchTransaction,
        TestData.buyLimitOrderTransaction,
      )

      firstBatch.foreach(eventObserver.onNext(_))
      val firstWriter = eventObserver.bigQueryEventsWriter
      assert(eventObserver.bigQueryEventsWriter.numEvents == firstBatch.size)

      val secondBatch = List(
        TestData.sellLimitOrderTransaction,
        TestData.buyMarketOrderTransaction,
        TestData.sellMarketOrderTransaction,
      )

      secondBatch.foreach(eventObserver.onNext(_))

      assert(eventObserver.bigQueryEventsWriter.numEvents == secondBatch.size)
      assert(firstWriter ne eventObserver.bigQueryEventsWriter)

      SequenceTracker.clear
    }
    it(
      "Test valid transactions are added to the BigQueryRows objects and SequenceTracker."
    ) {
      List(
        (
          TestData.cancellationTransaction,
          (eventObserver: EventObserver) =>
            eventObserver.bigQueryEventsWriter.cancellationEvents
        ),
        (
          TestData.matchTransaction,
          (eventObserver: EventObserver) =>
            eventObserver.bigQueryEventsWriter.matchEvents
        ),
        (
          TestData.buyLimitOrderTransaction,
          (eventObserver: EventObserver) =>
            eventObserver.bigQueryEventsWriter.orderEvents
        ),
        (
          TestData.sellLimitOrderTransaction,
          (eventObserver: EventObserver) =>
            eventObserver.bigQueryEventsWriter.orderEvents
        ),
        (
          TestData.buyMarketOrderTransaction,
          (eventObserver: EventObserver) =>
            eventObserver.bigQueryEventsWriter.orderEvents
        ),
        (
          TestData.sellMarketOrderTransaction,
          (eventObserver: EventObserver) =>
            eventObserver.bigQueryEventsWriter.orderEvents
        )
      ).foreach { item =>
        val eventObserver = new EventObserver
        val transaction = item._1
        val bigQueryRows = item._2(eventObserver)
        eventObserver.onNext(transaction)
        assert(bigQueryRows.numRows == 1)
        assert(SequenceTracker.numReceived == 1)
        SequenceTracker.clear
      }
    }

    it(
      "Test invalid transactions are not added to the BigQueryRows objects but are added to the SequenceTracker."
    ) {
      List(
        (
          TestData.invalidCancellationTransactions,
          (eventObserver: EventObserver) =>
            eventObserver.bigQueryEventsWriter.cancellationEvents
        ),
        (
          TestData.invalidMatchTransactions,
          (eventObserver: EventObserver) =>
            eventObserver.bigQueryEventsWriter.matchEvents
        ),
        (
          TestData.invalidBuyLimitOrderTransactions,
          (eventObserver: EventObserver) =>
            eventObserver.bigQueryEventsWriter.orderEvents
        ),
        (
          TestData.invalidSellLimitOrderTransactions,
          (eventObserver: EventObserver) =>
            eventObserver.bigQueryEventsWriter.orderEvents
        ),
        (
          TestData.invalidBuyMarketOrderTransactions,
          (eventObserver: EventObserver) =>
            eventObserver.bigQueryEventsWriter.orderEvents
        ),
        (
          TestData.invalidSellMarketOrderTransactions,
          (eventObserver: EventObserver) =>
            eventObserver.bigQueryEventsWriter.orderEvents
        )
      ).foreach { item =>
        val eventObserver = new EventObserver
        val transactions = item._1
        val bigQueryRows = item._2(eventObserver)
        transactions.foreach(transaction => eventObserver.onNext(transaction))
        assert(bigQueryRows.numRows == 0)
        assert(SequenceTracker.numReceived == transactions.size)
        SequenceTracker.clear
      }
    }
  }

  describe("SequenceTracker") {
    val extraSequenceId = 4000L
    val sequenceIds = List(1000L, 2000L, 3000L)
    it("Assert SequenceTracker contains added sequenceIds") {
      SequenceTracker.addAll(sequenceIds)
      sequenceIds.foreach(id => assert(SequenceTracker.bufferContains(id)))
      SequenceTracker.clear
    }

    it("Assert the min, max, and size of SequenceTracker are correct") {
      SequenceTracker.addAll(sequenceIds)
      assert(sequenceIds(0) == SequenceTracker.min)
      assert(sequenceIds(2) == SequenceTracker.max)
      assert(sequenceIds.size == SequenceTracker.numReceived)
      SequenceTracker.clear
    }

    it("Assert SequenceTracker never contains more than maxSize sequenceIds") {
      val sequenceIds2 = sequenceIds.appended(extraSequenceId)
      SequenceTracker.addAll(sequenceIds2)
      sequenceIds2
        .drop(0)
        .foreach(SequenceTracker.bufferContains(_))

      assert(!SequenceTracker.bufferContains(sequenceIds2(0)))
      assert(sequenceIds2(0) == SequenceTracker.min)
      assert(sequenceIds2(3) == SequenceTracker.max)
      assert(sequenceIds2.size == SequenceTracker.numReceived)
      SequenceTracker.clear
    }

    it(
      "Assert SequenceTracker counts numMissing and numMissingDelta correctly."
    ) {
      SequenceTracker.addAll(sequenceIds)
      val expectedNumMissing =
        sequenceIds.last - sequenceIds.head - sequenceIds.size
      val expectedNumMissingDelta = sequenceIds(2) - sequenceIds(1) - 1
      assert(expectedNumMissing == SequenceTracker.numMissing)
      assert(expectedNumMissingDelta == SequenceTracker.numMissingDelta)
      SequenceTracker.clear
    }
  }
}
