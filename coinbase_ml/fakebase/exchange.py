"""Simulates the Coinbase Pro exchange
"""
from __future__ import annotations
from copy import deepcopy
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, Type

import coinbase_ml.fakebase.account as _account
from . import constants as c
from .base_classes.exchange import ExchangeBase
from .database_workers import DatabaseWorkers
from .order_book import OrderBook
from .orm import CoinbaseEvent, CoinbaseCancellation, CoinbaseMatch, CoinbaseOrder
from .matching_engine import MatchingEngine
from .types import (
    BinnedOrderBook,
    OrderSide,
    OrderStatus,
    ProductId,
    ProductPrice,
    ProductVolume,
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

        self.database_workers = DatabaseWorkers(
            end_dt=self.end_dt,
            num_workers=c.NUM_DATABASE_WORKERS,
            product_id=product_id,
            results_queue_size=c.DATABASE_RESULTS_QUEUE_SIZE,
            start_dt=self.start_dt,
            time_delta=self.time_delta,
        )

        self._received_cancellations: List[CoinbaseCancellation] = []
        self._received_orders: List[CoinbaseOrder] = []
        self.matching_engine = MatchingEngine(account=None)
        self.account = _account.Account(self)

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
                and self.order_book == _other.order_book
            )
        else:
            raise TypeError

        return return_val

    def _add_account_orders_to_received_orders_list(self) -> None:
        """Summary
        """
        for order in self.account.orders.values():
            if order.order_status == OrderStatus.received:
                self.received_orders.append(order)

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
        if hasattr(self, "matching_engine"):
            self.matching_engine.account = value

    def check_is_taker(self, order: CoinbaseOrder) -> bool:
        """Summary

        Args:
            order (CoinbaseOrder): Description

        Returns:
            bool: Description
        """
        return self.matching_engine.check_is_taker(order)

    def bin_order_book_by_price(
        self, order_side: OrderSide
    ) -> Dict[ProductPrice, ProductVolume]:
        """
        bin_order_book_by_price [summary]

        Args:
            order_side (OrderSide): [description]

        Returns:
            BinnedOrderBook: [description]
        """
        price_volume_dict: BinnedOrderBook = {}

        for _, order in self.order_book[order_side].items():
            if order.price not in price_volume_dict:
                price_volume_dict[order.price] = order.remaining_size
            else:
                price_volume_dict[order.price] += order.remaining_size

        return price_volume_dict

    def cancel_order(self, order: CoinbaseOrder) -> None:
        """
        cancel_order [summary]

        Args:
            order (CoinbaseOrder): [description]
        """
        self.matching_engine.cancel_order(order)

    def create_checkpoint(self) -> ExchangeCheckpoint:
        """Summary

        Returns:
            ExchangeCheckpoint: Description
        """
        return ExchangeCheckpoint(
            account=self.account,
            current_dt=self.interval_start_dt,
            end_dt=self.end_dt,
            order_book=self.order_book,
            product_id=self.product_id,
            time_delta=self.time_delta,
            exchange_class=self.__class__,
        )

    @property
    def matches(self) -> List[CoinbaseMatch]:
        """
        matches [summary]

        Returns:
            List[CoinbaseMatch]: [description]
        """
        return self.matching_engine.matches

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

    @property
    def order_book(self) -> Dict[OrderSide, OrderBook]:
        """
        order_book [summary]

        Returns:
            Dict[OrderSide, OrderBook]: [description]
        """
        return self.matching_engine.order_book

    @order_book.setter
    def order_book(self, value: Dict[OrderSide, OrderBook]) -> None:
        """
        order_book [summary]

        Args:
            value (Dict[OrderSide, OrderBook]): [description]
        """
        self.matching_engine.order_book = value

    def step(
        self,
        insert_cancellations: Optional[List[CoinbaseCancellation]] = None,
        insert_orders: Optional[List[CoinbaseOrder]] = None,
    ) -> None:
        """
        step advances the exchange by time increment self.time_delta.
        Increcemets self.interval_start_dt and self.interval_end_dt by
        self.time_delta. Loads new orders and cancellations from
        DatabaseWorkers.results_queue. Goes through simulated exchange logic
        to updated self.matches and self.order_book.

        Args:
            insert_cancellations (Optional[List[CoinbaseCancellation]], optional): Defaults to None
            insert_orders (Optional[List[CoinbaseOrder]], optional): Defaults to None
        """
        super().step(insert_cancellations, insert_orders)
        insert_cancellations = (
            [] if insert_cancellations is None else insert_cancellations
        )
        insert_orders = [] if insert_orders is None else insert_orders

        self.matching_engine.matches = []

        # It's useful to set c.NUM_DATABASE_WORKERS to 0 for some unit tests and skip the
        # below block
        if c.NUM_DATABASE_WORKERS > 0:

            # Wait until first element of results queue is the data for the next time interval
            # otherwise a race condition can cause the queue elements to be popped off out
            # of sync
            while True:
                try:
                    next_interval_start_dt = self.database_workers.results_queue.peek()[
                        0
                    ]
                except IndexError:
                    continue

                if next_interval_start_dt == self.interval_end_dt:
                    break

            self.interval_start_dt, results = self.database_workers.results_queue.get()
            self.interval_end_dt = self.interval_start_dt + self.time_delta
            self._received_cancellations = results.cancellations + insert_cancellations
            self._received_orders = results.orders + insert_orders
        else:
            self.interval_start_dt = self.interval_start_dt + self.time_delta
            self.interval_end_dt = self.interval_end_dt + self.time_delta
            self._received_cancellations = insert_cancellations
            self._received_orders = insert_orders

        self._add_account_orders_to_received_orders_list()

        self.received_orders.sort(key=lambda event: event.time)
        self.received_cancellations.sort(key=lambda event: event.time)
        received_events: List[CoinbaseEvent] = []
        received_events.extend(self.received_orders)
        received_events.extend(self.received_cancellations)
        received_events.sort(key=lambda event: event.time)

        for event in received_events:
            if isinstance(event, CoinbaseCancellation):
                cancellation_event: CoinbaseCancellation = event
                self.matching_engine.process_cancellation(cancellation_event)
            elif isinstance(event, CoinbaseOrder):
                order_event: CoinbaseOrder = event
                self.matching_engine.process_order(order_event)

    def stop_database_workers(self) -> None:
        """Summary
        """
        self.database_workers.stop_workers()


class ExchangeCheckpoint:

    """Summary
    """

    def __init__(
        self,
        account: "_account.Account",
        current_dt: datetime,
        end_dt: datetime,
        order_book: Dict[OrderSide, OrderBook],
        product_id: ProductId,
        time_delta: timedelta,
        exchange_class: Type[Exchange] = Exchange,
    ):
        """
        __init__ [summary]

        Args:
            account (Account): [description]
            current_dt (datetime): [description]
            end_dt (datetime): [description]
            order_book (Dict[OrderSide, OrderBook]): [description]
            product_id (ProductId): [description]
            time_delta (timedelta): [description]
            exchange_class (Type[Exchange], optional): [description]. Defaults to Exchange.

        Raises:
            TypeError: [description]
        """
        if not issubclass(exchange_class, Exchange):
            raise TypeError(f"{exchange_class} is not a subclass of Exchange")

        self._account = account.copy()
        self._current_dt = current_dt
        self._end_dt = end_dt
        self._exchange_class = exchange_class
        self._order_book = deepcopy(order_book)
        self._product_id = product_id
        self._time_delta = time_delta

    def restore(self) -> Exchange:
        """
        restore [summary]

        Returns:
            Exchange: [description]
        """
        exchange = self._exchange_class(
            end_dt=self._end_dt,
            product_id=self._product_id,
            start_dt=self._current_dt + self._time_delta,
            time_delta=self._time_delta,
        )
        account = self._account.copy()
        account.exchange = exchange
        exchange.account = account
        exchange.order_book = deepcopy(self._order_book)

        return exchange
