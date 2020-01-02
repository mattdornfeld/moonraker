"""
 [summary]
"""
import logging
from collections import defaultdict
from decimal import Decimal
from threading import Lock
from typing import DefaultDict, Dict, Iterator, List, Tuple

from fakebase.types import Currency, OrderSide, ProductId
from fakebase.utils.currency_utils import get_currency_precision


LOGGER = logging.getLogger(__name__)

BinnedOrderBook = DefaultDict[Decimal, Decimal]
OrderBookChanges = List[Tuple[str, str, str]]
OrderBookSnapshot = List[Tuple[str, str]]
OrderBookLevel = Tuple[Decimal, Decimal]
ProcessedOrderBook = List[OrderBookLevel]


class OrderBookBinner:
    """
     [summary]
    """

    def __init__(self, product_id: ProductId) -> None:
        """
        __init__ [summary]
        """
        self.order_books: Dict[OrderSide, BinnedOrderBook] = {}
        self.book_lock = Lock()
        self.product_id = product_id

    def _create_snapshot_generator(
        self, snapshot: OrderBookSnapshot
    ) -> Iterator[OrderBookLevel]:
        """
        _create_snapshot_generator [summary]

        Args:
            snapshot (OrderBookSnapshot): [description]

        Returns:
            Iterator[OrderBookLevel]: [description]
        """
        return (
            (
                self.convert_to_decimal(level[0], self.product_id.quote_currency),
                self.convert_to_decimal(level[1], self.product_id.product_currency),
            )
            for level in snapshot
        )

    @staticmethod
    def convert_to_decimal(value: str, currency: Currency) -> Decimal:
        """
        convert_to_decimal [summary]

        Args:
            value (str): [description]
            currency (Currency): [description]

        Returns:
            Decimal: [description]
        """
        precision = get_currency_precision(currency)
        return Decimal(value).quantize(Decimal(precision))

    def insert_book_snapshot(
        self, order_book_snapshots: Dict[OrderSide, OrderBookSnapshot]
    ) -> None:
        """
        insert_book_snapshot [summary]

        Args:
            order_book_snapshots (Dict[OrderSide, OrderBookSnapshot]): [description]
        """
        LOGGER.debug("Inserting order book snapshot")

        self.book_lock.acquire()
        try:
            for order_side in [OrderSide.buy, OrderSide.sell]:
                snapshot = order_book_snapshots[order_side]
                self.order_books[order_side] = defaultdict(
                    Decimal, self._create_snapshot_generator(snapshot)
                )
        finally:
            self.book_lock.release()

    def insert_book_change(
        self, order_side: OrderSide, price: Decimal, size: Decimal
    ) -> None:
        """
        insert_book_change [summary]

        Args:
            order_side (OrderSide): [description]
            price (Decimal): [description]
            size (Decimal): [description]

        Returns:
            None: [description]
        """
        # if size is 0, Coinbase says order is filled, remove from book
        # size can be '0' or '0.00000000'
        # https://docs.pro.coinbase.com/#the-level2-channel
        if size == Decimal("0"):
            LOGGER.debug("Removing orders at price: %s", price)
            del self.order_books[order_side][price]

        # Coinbase changes are updated totals, not delta's. Overwrite values
        # https://docs.pro.coinbase.com/#the-level2-channel
        else:
            self.order_books[order_side][price] = size

    def process_book_changes(self, changes: OrderBookChanges) -> None:
        """
        process_book_changes [summary]

        Args:
            changes (OrderBookChanges): [description]

        Returns:
            None: [description]
        """
        LOGGER.debug("Inserting %s changes to order book", len(changes))
        self.book_lock.acquire()
        try:
            for change in changes:
                _order_side, price, size = change
                order_side = OrderSide[_order_side]

                if order_side not in [OrderSide.buy, OrderSide.sell]:
                    LOGGER.error("Error: change side not recognized %s", change)
                    continue

                _price = self.convert_to_decimal(price, self.product_id.quote_currency)
                _size = self.convert_to_decimal(size, self.product_id.product_currency)
                self.insert_book_change(order_side, _price, _size)
        finally:
            self.book_lock.release()
