"""
 [summary]
"""
import logging
from collections import defaultdict
from decimal import Decimal
from threading import Lock
from typing import DefaultDict, Dict, Iterator, List, Tuple

from fakebase import constants as c
from fakebase.utils import get_currency_precision

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

    def __init__(self) -> None:
        """
        __init__ [summary]
        """
        self.order_books: Dict[str, BinnedOrderBook] = {
            order_side: defaultdict(Decimal) for order_side in ["buy", "sell"]
        }
        self.book_lock = Lock()

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
                self.convert_to_decimal(level[0], c.QUOTE_CURRENCY),
                self.convert_to_decimal(level[1], c.PRODUCT_CURRENCY),
            )
            for level in snapshot
        )

    @staticmethod
    def convert_to_decimal(value: str, currency: str) -> Decimal:
        """
        convert_to_decimal [summary]

        Args:
            value (str): [description]
            currency (str): [description]

        Returns:
            Decimal: [description]
        """
        precision = get_currency_precision(currency)
        return Decimal(value).quantize(Decimal(precision))

    def insert_book_snapshot(
        self, order_book_snapshots: Dict[str, OrderBookSnapshot]
    ) -> None:
        """
        insert_book_snapshot [summary]

        Args:
            order_book_snapshots (Dict[str, OrderBookSnapshot]): [description]
        """
        LOGGER.debug("Inserting order book snapshot")

        self.book_lock.acquire()
        try:
            for order_side in ["buy", "sell"]:
                snapshot = order_book_snapshots[order_side]
                self.order_books[order_side] = defaultdict(
                    Decimal, self._create_snapshot_generator(snapshot)
                )
        finally:
            self.book_lock.release()

    def insert_book_change(
        self, order_side: str, price: Decimal, size: Decimal
    ) -> None:
        """
        insert_book_change [summary]

        Args:
            order_side (str): [description]
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
                order_side, price, size = change

                if order_side not in ["buy", "sell"]:
                    LOGGER.error("Error: change side not recognized %s", change)
                    continue

                _price = self.convert_to_decimal(price, c.QUOTE_CURRENCY)
                _size = self.convert_to_decimal(size, c.PRODUCT_CURRENCY)
                self.insert_book_change(order_side, _price, _size)
        finally:
            self.book_lock.release()
