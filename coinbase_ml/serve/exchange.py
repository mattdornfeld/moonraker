"""
Serving Exchange makes a connection to the Coinbase websocket feed,
pulls in orders, cancellations, matches, and the binned order book.
"""
from datetime import datetime, timedelta
from decimal import Decimal
from typing import TYPE_CHECKING, DefaultDict, List, Optional

from dateutil.tz import UTC

from fakebase.base_classes import ExchangeBase
from fakebase.orm import (
    CoinbaseCancellation,
    CoinbaseEvent,
    CoinbaseMatch,
    CoinbaseOrder,
)
from fakebase.types import OrderSide, ProductId

from coinbase_ml.serve.order_book import OrderBookBinner
from coinbase_ml.serve.stream_processors.coinbase_stream_processor import (
    CoinbaseStreamProcessor,
)

if TYPE_CHECKING:
    import coinbase_ml.serve.account


class Exchange(ExchangeBase["coinbase_ml.serve.account.Account"]):
    """
    Exchange encapsulates the order book, received orders, and
    received cancelations from the live Coinbase exchange.
    """

    def __init__(
        self,
        end_dt: datetime,
        product_id: ProductId,
        start_dt: datetime,
        time_delta: timedelta,
    ) -> None:
        """
        __init__ [summary]

        Args:
            end_dt (datetime): [description]
            product_id (ProductId): [description]
            start_dt (datetime): [description]
            time_delta (timedelta): [description]
        """
        super().__init__(end_dt, product_id, start_dt, time_delta)
        self.order_book_binner = OrderBookBinner(product_id)
        self.stream_processor = CoinbaseStreamProcessor(
            order_book_binner=self.order_book_binner
        )

    def bin_order_book_by_price(
        self, order_side: OrderSide, price_aggregation: Decimal = Decimal("0.01")
    ) -> DefaultDict[Decimal, Decimal]:
        """
        bin_order_book_by_price [summary]

        Args:
            order_side (OrderSide): [description]
            price_aggregation (Decimal, optional): [description]. Defaults to Decimal("0.01").

        Returns:
            DefaultDict[Decimal, Decimal]: [description]
        """
        return self.order_book_binner.order_books[order_side]

    @property
    def interval_end_dt(self) -> datetime:
        """
        interval_end_dt

        Returns:
            datetime: [description]
        """
        return self.interval_start_dt + self.time_delta

    @interval_end_dt.setter
    def interval_end_dt(self, value: datetime) -> None:
        """
        interval_end_dt does nothing. This method exists to maintain LSP.

        Args:
            value (datetime): [description]
        """

    @property
    def matches(self) -> List[CoinbaseMatch]:
        """
        matches [summary]

        Returns:
            List[CoinbaseMatch]: [description]
        """
        return self.stream_processor.matches

    @property
    def received_cancellations(self) -> List[CoinbaseEvent]:
        """
        received_cancellations [summary]

        Returns:
            List[CoinbaseEvent]: [description]
        """
        return self.stream_processor.received_cancellations

    @property
    def received_orders(self) -> List[CoinbaseEvent]:
        """
        received_orders [summary]

        Returns:
            List[CoinbaseEvent]: [description]
        """
        return self.stream_processor.received_orders

    def run(self) -> None:
        """
        run starts the stream processor in a background thread to collect data from the
        Coinbase websocket API
        """
        self.stream_processor.start()

    def step(
        self,
        insert_cancellations: Optional[List[CoinbaseCancellation]] = None,
        insert_orders: Optional[List[CoinbaseOrder]] = None,
    ) -> None:
        """
        step advances the interval, clears self.received_cancellations, self.received_orders,
        and self.matches. Raises an ExchangeFinishedException if self.finished is True.

        Args:
            insert_cancellations (Optional[List[CoinbaseCancellation]], optional): Defaults to None
            insert_orders (Optional[List[CoinbaseOrder]], optional): Defaults to None
        """
        super().step(insert_cancellations, insert_orders)
        self.interval_start_dt = datetime.now(UTC)
        self.stream_processor.flush()

    def stop(self) -> None:
        """
        stop closes the stream processor.
        """
        self.stream_processor.close()
