"""
Serving Exchange makes a connection to the Coinbase websocket feed,
pulls in orders, cancellations, matches, and the binned order book.
"""
from datetime import datetime, timedelta
from decimal import Decimal
from typing import DefaultDict, List

from dateutil.tz import UTC

from fakebase.base_classes import ExchangeBase
from fakebase.orm import CoinbaseCancellation, CoinbaseMatch, CoinbaseOrder

from coinbase_ml.serve.account import Account
from coinbase_ml.serve.order_book import OrderBookBinner
from coinbase_ml.serve.stream_processors.coinbase_stream_processor import (
    CoinbaseStreamProcessor,
)


class Exchange(ExchangeBase[Account]):
    """
    Exchange encapsulates the order book, received orders, and
    received cancelations from the live Coinbase exchange.
    """

    def __init__(
        self, end_dt: datetime, start_dt: datetime, time_delta: timedelta,
    ) -> None:
        """
        Exchange [summary]

        Args:
            end_dt (datetime): [description]
            start_dt (datetime): [description]
            time_delta (timedelta): [description]

        Returns:
            [type]: [description]
        """
        super().__init__(end_dt, start_dt, time_delta)
        self.order_book_binner = OrderBookBinner()
        self.stream_processor = CoinbaseStreamProcessor(
            order_book_binner=self.order_book_binner,
            matches=self.matches,
            received_cancellations=self.received_cancellations,
            received_orders=self.received_orders,
        )

    def bin_order_book_by_price(
        self, order_side: str, price_aggregation: Decimal = Decimal("0.01")
    ) -> DefaultDict[Decimal, Decimal]:
        """
        bin_order_book_by_price [summary]

        Args:
            order_side (str): [description]
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

    def run(self) -> None:
        """
        run starts the stream processor in a background thread to collect data from the
        Coinbase websocket API
        """
        self.stream_processor.start()

    def step(self) -> None:
        """
        step advances the interval, clears self.received_cancellations, self.received_orders,
        and self.matches. Raises an ExchangeFinishedException if self.finished is True.
        """
        super().step()
        self.interval_start_dt = datetime.now(UTC)
        self.received_cancellations: List[CoinbaseCancellation] = []
        self.received_orders: List[CoinbaseOrder] = []
        self.matches: List[CoinbaseMatch] = []

    def stop(self) -> None:
        """
        stop closes the stream processor.
        """
        self.stream_processor.close()
