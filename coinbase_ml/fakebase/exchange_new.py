"""Simulates the Coinbase Pro exchange
"""
from __future__ import annotations
from datetime import datetime, timedelta
from time import sleep
from typing import Any, Dict, List, Optional

from dateutil import parser
from google.protobuf.empty_pb2 import Empty
from google.protobuf.duration_pb2 import Duration

import coinbase_ml.fakebase.account_new as _account
from coinbase_ml.common import constants as cc
from coinbase_ml.fakebase.protos.fakebase_pb2 import (
    ExchangeInfo,
    OrderBooksRequest,
    OrderBooks,
    SimulationStartRequest,
)
from coinbase_ml.fakebase.protos.fakebase_pb2_grpc import ExchangeServiceStub
from coinbase_ml.fakebase import constants as c
from coinbase_ml.fakebase.base_classes.exchange import ExchangeBase
from coinbase_ml.fakebase.database_workers import DatabaseWorkers
from coinbase_ml.fakebase.orm import CoinbaseCancellation, CoinbaseMatch, CoinbaseOrder
from coinbase_ml.fakebase.types import (
    BinnedOrderBook,
    OrderSide,
    ProductId,
    ProductVolume,
    QuoteVolume,
)


class Exchange(ExchangeBase[_account.Account]):  # pylint: disable=R0903,R0902
    """Summary
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

        self._order_books: Dict[OrderSide, BinnedOrderBook] = {}
        self._received_cancellations: List[CoinbaseCancellation] = []
        self._received_orders: List[CoinbaseOrder] = []
        self.account = _account.Account(self)
        self.database_workers: Optional[DatabaseWorkers] = None
        self.stub = ExchangeServiceStub(c.MATCHING_ENGINE_CHANNEL)

    def __eq__(self, other: Any) -> bool:
        """
        __eq__ [summary]

        Args:
            other (Any): [description]

        Returns:
            bool: [description]
        """
        if isinstance(other, Exchange):
            _other: Exchange = other
            return_val = (
                self.end_dt == _other.end_dt
                and self.interval_end_dt == _other.interval_end_dt
                and self.interval_start_dt == _other.interval_start_dt
                and self.time_delta == _other.time_delta
                and self.account == _other.account
            )
        else:
            raise TypeError

        return return_val

    def _update_interval_dt(self, exchange_info: ExchangeInfo) -> None:
        """
        _update_interval_dt [summary]

        Args:
            exchange_info (ExchangeInfo): [description]
        """
        self.interval_start_dt = parser.parse(exchange_info.intervalStartTime).replace(
            tzinfo=None
        )
        self._interval_end_dt = parser.parse(exchange_info.intervalEndTime).replace(
            tzinfo=None
        )

    @property
    def account(self) -> _account.Account:
        """
        account [summary]

        Returns:
            _account.Account: [description]
        """
        return self._account

    @account.setter
    def account(self, value: _account.Account) -> None:
        """
        account [summary]

        Args:
            value (Account): [description]
        """
        self._account = value

    def bin_order_book_by_price(self, order_side: OrderSide) -> BinnedOrderBook:
        """
        bin_order_book_by_price [summary]

        Args:
            order_side (OrderSide): [description]

        Returns:
            DefaultDict[ProductPrice, ProductVolume]: [description]
        """
        if len(self._order_books) == 0:
            response: OrderBooks = self.stub.getOrderBooks(
                OrderBooksRequest(orderBookDepth=cc.ORDER_BOOK_DEPTH)
            )
            self._order_books = {
                OrderSide.buy: {
                    cc.PRODUCT_ID.price_type(price): cc.PRODUCT_ID.product_volume_type(
                        volume
                    )
                    for (price, volume) in response.buyOrderBook.items()
                },
                OrderSide.sell: {
                    cc.PRODUCT_ID.price_type(price): cc.PRODUCT_ID.product_volume_type(
                        volume
                    )
                    for (price, volume) in response.sellOrderBook.items()
                },
            }

        return self._order_books[order_side]

    def cancel_order(self, order: CoinbaseOrder) -> None:
        """
        cancel_order [summary]

        Args:
            order (CoinbaseOrder): [description]
        """

    def checkpoint(self) -> None:
        """
        checkpoint [summary]
        """
        self.stub.checkpoint(Empty())

    @property
    def matches(self) -> List[CoinbaseMatch]:
        """
        matches [summary]

        Returns:
            List[CoinbaseMatch]: [description]
        """
        match_events = self.stub.getMatches(Empty()).matchEvents
        return [CoinbaseMatch.from_proto(m) for m in match_events]

    @property
    def received_cancellations(self) -> List[CoinbaseCancellation]:
        """
        received_cancellations [summary]

        Returns:
            CoinbaseEvent: [description]
        """
        return self._received_cancellations

    @property
    def received_orders(self) -> List[CoinbaseOrder]:
        """
        received_orders [summary]

        Returns:
            CoinbaseEvent: [description]
        """
        return self._received_orders

    @received_orders.setter
    def received_orders(self, value: List[CoinbaseOrder]) -> None:
        """
        received_orders [summary]

        Args:
            value (List[CoinbaseEvent]): [description]
        """
        self._received_orders = value

    def start(
        self,
        initial_product_funds: ProductVolume,
        initial_quote_funds: QuoteVolume,
        num_warmup_time_steps: int,
    ) -> None:
        """
        start [summary]

        Args:
            initial_product_funds (ProductVolume): [description]
            initial_quote_funds (QuoteVolume): [description]
            num_warmup_time_steps (int): [description]
        """
        self._order_books = {}

        if self.database_workers:
            self.stop_database_workers()

        self.database_workers = DatabaseWorkers(
            end_dt=self.end_dt,
            num_workers=c.NUM_DATABASE_WORKERS,
            product_id=self.product_id,
            results_queue_size=c.DATABASE_RESULTS_QUEUE_SIZE,
            start_dt=self.start_dt,
            time_delta=self.time_delta,
        )

        message = SimulationStartRequest(
            startTime=self.start_dt.isoformat() + "Z",
            endTime=self.end_dt.isoformat() + "Z",
            timeDelta=Duration(seconds=int(self.time_delta.total_seconds())),
            numWarmUpSteps=0,
            initialProductFunds=str(initial_product_funds),
            initialQuoteFunds=str(initial_quote_funds),
        )

        self.stub.start(message)

        for _ in range(num_warmup_time_steps):
            self.step()

        self.stub.checkpoint(Empty())

    def reset(self) -> None:
        """
        Reset the exchange to the state created with `checkpoint`. Useful when
        doing multiple simulations that need to start from the same warmed up state.
        """
        self._order_books = {}
        exchange_info: ExchangeInfo = self.stub.reset(Empty())
        self._update_interval_dt(exchange_info)

        self.stop_database_workers()

        self.database_workers = DatabaseWorkers(
            end_dt=self.end_dt,
            num_workers=c.NUM_DATABASE_WORKERS,
            product_id=self.product_id,
            results_queue_size=c.DATABASE_RESULTS_QUEUE_SIZE,
            start_dt=self.interval_start_dt + self.time_delta,
            time_delta=self.time_delta,
        )

    def step(
        self,
        insert_cancellations: Optional[List[CoinbaseCancellation]] = None,
        insert_orders: Optional[List[CoinbaseOrder]] = None,
    ) -> None:
        """
        step advances the exchange by time increment self.time_delta.
        Increments self.interval_start_dt and self.interval_end_dt by
        self.time_delta. Loads new orders and cancellations from
        DatabaseWorkers.results_queue. Goes through simulated exchange logic
        to updated self.matches and self.order_book.

        Args:
            insert_cancellations (Optional[List[CoinbaseCancellation]], optional): Defaults to None
            insert_orders (Optional[List[CoinbaseOrder]], optional): Defaults to None
        """
        super().step(insert_cancellations, insert_orders)
        self._order_books = {}
        insert_cancellations = (
            [] if insert_cancellations is None else insert_cancellations
        )
        insert_orders = [] if insert_orders is None else insert_orders

        exchange_info: ExchangeInfo = self.stub.step(Empty())
        self._update_interval_dt(exchange_info)

        # It's useful to set c.NUM_DATABASE_WORKERS to 0 for some unit tests and skip the
        # below block
        if c.NUM_DATABASE_WORKERS > 0:

            # Wait until first element of results queue is the data for the next time interval
            # otherwise a race condition can cause the queue elements to be popped off out
            # of sync
            while True:
                try:
                    interval_start_dt = self.database_workers.results_queue.peek()[0]
                except IndexError:
                    continue

                print(interval_start_dt)
                print(self.interval_start_dt)
                if interval_start_dt == self.interval_start_dt:
                    break

                sleep(0.001)

            _, results = self.database_workers.results_queue.get()
            self._received_cancellations = results.cancellations + insert_cancellations
            self._received_orders = results.orders + insert_orders
        else:
            self._received_cancellations = insert_cancellations
            self._received_orders = insert_orders

        self.received_orders.sort(key=lambda event: event.time)
        self.received_cancellations.sort(key=lambda event: event.time)

    def stop(self) -> None:
        """
        stop [summary]
        """
        self.stub.stop(Empty())

    def stop_database_workers(self) -> None:
        """Summary
        """
        self.database_workers.stop_workers()
