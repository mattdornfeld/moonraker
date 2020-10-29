package co.firstorderlabs.fakebase.sql

object BigQueryReaderScrap {
import java.time.{Duration, Instant}

import co.firstorderlabs.fakebase.types.Types.TimeInterval

  val timeInterval = {
    TimeInterval(
      Instant.parse("2019-11-01T00:00:00.00Z"),
      Instant.parse("2019-11-02T00:00:00.00Z"),
    )
  }
  BigQueryReader.start(timeInterval.startTime, timeInterval.endTime, Duration.ofSeconds(30))

  val timeInterval2 = {
    TimeInterval(
      Instant.parse("2019-11-20T19:20:00.63Z"),
      Instant.parse("2019-11-20T19:20:30.63Z"),
    )
  }
  //import co.firstorderlabs.fakebase.sql._
  //import co.firstorderlabs.fakebase.sql.
//  BigQueryReader.executeQuery(BigQueryReader.queryBuyLimitOrders(ProductPrice.productId, timeInterval2))
}
