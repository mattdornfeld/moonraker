"""
[summary]
"""
from __future__ import annotations
from datetime import datetime, timedelta
from typing import Any, Dict, Iterator, Optional, Tuple

from bintrees import FastRBTree as TreeBase

from fakebase.orm import CoinbaseOrder, ProductPrice
from fakebase.types import OrderId, OrderSide

OrderBookKey = Tuple[ProductPrice, timedelta]

# pylint: disable=useless-super-delegation
class OrderBook(TreeBase):
    """OrderBook is used to represent the Exchange order book.
    It's basically a subclass of bintrees.FastRBTree along with
    some helper methods and a typed interface.
    """

    def __init__(
        self, items: Optional[Dict[OrderBookKey, CoinbaseOrder]] = None
    ) -> None:
        super().__init__(items)
        self.orders_dict: Dict[OrderId, CoinbaseOrder] = {}

        if items:
            for order in items.values():
                self.orders_dict[order.order_id] = order

    def __eq__(self, other: Any) -> bool:
        """
        __eq__ [summary]

        Args:
            other (Any): [description]
        """
        if isinstance(other, OrderBook):
            _other: OrderBook = other

            if len(self) == len(_other):
                tree_equal = True
                for (self_key, self_value), (other_key, other_value) in zip(
                    self.items(), _other.items()
                ):
                    if self_key != other_key or self_value != other_value:
                        tree_equal = False
                        break

            else:
                tree_equal = False

            dict_equal = self.orders_dict == other.orders_dict

            equal = tree_equal and dict_equal

        else:
            raise NotImplementedError

        return equal

    @staticmethod
    def calculate_order_book_key(order: CoinbaseOrder) -> OrderBookKey:
        """Calculates order book for order according to formula
        (price, datetime.max - order.time if order.side == 'buy' else \\
         order.time - datetime.min)

        Args:
            order (CoinbaseOrder): Description

        Returns:
            OrderBookKey
        """
        return (
            order.price,
            datetime.max - order.time
            if order.side == OrderSide.buy
            else order.time - datetime.min,
        )

    def insert(self, key: OrderBookKey, value: CoinbaseOrder) -> None:
        """
        insert [summary]

        Args:
            key (OrderBookKey): [description]
            value (CoinbaseOrder): [description]
        """
        super().insert(key, value)

        # When calling deepcopy on this object, orders_dict is not
        # instantiated before insert is called. For this case check
        # if attribute exists, if not create it.
        if not hasattr(self, "orders_dict"):
            self.orders_dict = {}

        self.orders_dict[value.order_id] = value

    def items(
        self, reverse: bool = False
    ) -> Iterator[Tuple[OrderBookKey, CoinbaseOrder]]:
        """
        items [summary]

        Args:
            reverse (bool, optional): [description]. Defaults to False.

        Returns:
            Iterator[Tuple[OrderBookKey, CoinbaseOrder]]: [description]
        """
        return super().items(reverse)

    def max_item(self) -> Tuple[OrderBookKey, CoinbaseOrder]:
        """
        max_item [summary]

        Raises:
            OrderBookEmpty: [description]

        Returns:
            Tuple[OrderBookKey, CoinbaseOrder]: [description]
        """
        try:
            return super().max_item()
        except ValueError:
            raise OrderBookEmpty

    def max_key_or_default(
        self, default_value: Optional[OrderBookKey] = None
    ) -> Optional[OrderBookKey]:
        """
        max_key_or_default [summary]

        Args:
            default_value (Optional[OrderBookKey], optional): [description]. Defaults to None.

        Returns:
            Optional[OrderBookKey]: [description]
        """
        try:
            return super().max_key()
        except OrderBookEmpty:
            return default_value

    def min_item(self) -> Tuple[OrderBookKey, CoinbaseOrder]:
        """
        min_item [summary]

        Raises:
            OrderBookEmpty: [description]

        Returns:
            Tuple[OrderBookKey, CoinbaseOrder]: [description]
        """
        try:
            return super().min_item()
        except ValueError:
            raise OrderBookEmpty

    def min_key_or_default(
        self, default_value: Optional[OrderBookKey] = None
    ) -> Optional[OrderBookKey]:
        """
        min_key_or_default [summary]

        Args:
            default_value (Optional[OrderBookKey], optional): [description]. Defaults to None.

        Returns:
            Optional[OrderBookKey]: [description]
        """
        try:
            return super().min_key()
        except OrderBookEmpty:
            return default_value

    def pop(self, key: OrderBookKey) -> CoinbaseOrder:
        """
        pop [summary]

        Args:
            key (OrderBookKey): [description]

        Returns:
            CoinbaseOrder: [description]
        """
        value: CoinbaseOrder

        value = super().pop(key)
        self.orders_dict.pop(value.order_id)

        return value

    def pop_by_order_id(self, order_id: OrderId) -> CoinbaseOrder:
        """
        pop_by_order_id [summary]

        Args:
            order_id (OrderId): [description]

        Returns:
            CoinbaseOrder: [description]
        """
        value = self.orders_dict.pop(order_id)
        key = self.calculate_order_book_key(value)
        super().pop(key)

        return value

    def pop_max(self) -> Tuple[OrderBookKey, CoinbaseOrder]:
        """
        pop_max [summary]

        Returns:
            Tuple[OrderBookKey, CoinbaseOrder]: [description]
        """
        key: OrderBookKey
        value: CoinbaseOrder

        key, value = super().pop_max()
        self.orders_dict.pop(value.order_id)

        return key, value

    def pop_min(self) -> Tuple[OrderBookKey, CoinbaseOrder]:
        """
        pop_min [summary]

        Returns:
            CoinbaseOrder: [description]
        """
        key: OrderBookKey
        value: CoinbaseOrder
        key, value = super().pop_min()
        self.orders_dict.pop(value.order_id)

        return key, value


class OrderBookEmpty(Exception):
    """
    OrderBookEmpty raised when trying to get an item from an empty order book
    """
