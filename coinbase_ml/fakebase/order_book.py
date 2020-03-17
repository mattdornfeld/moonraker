"""
Module for representing the order book in the fakebase simulation
"""
from __future__ import annotations
from datetime import datetime, timedelta
from typing import Any, Dict, Iterator, Optional, Tuple

from bintrees import FastRBTree as TreeBase
from sortedcontainers import SortedDict

from coinbase_ml.common import constants as cc
from coinbase_ml.fakebase.orm import CoinbaseOrder, ProductPrice
from coinbase_ml.fakebase.types import OrderId, OrderSide


class PriceInterval:
    """
    A (lower, upper) ProductPrice tuple
    """

    def __init__(self, lower: ProductPrice, upper: ProductPrice) -> None:
        """
        __init__ [summary]

        Args:
            lower (ProductPrice): [description]
            upper (ProductPrice): [description]
        """
        self.lower = lower
        self.upper = upper
        self._hash = (self.lower, self.upper).__hash__()

    def __eq__(self, other: Any) -> bool:
        """
        __eq__ [summary]

        Args:
            other (Any): [description]

        Raises:
            TypeError: [description]

        Returns:
            bool: [description]
        """
        if isinstance(other, PriceInterval):
            return_val = self.lower == other.lower and self.upper == other.upper
        else:
            raise TypeError

        return return_val

    def __gt__(self, other: PriceInterval) -> bool:
        """
        __gt__ is True if self.upper > other.upper

        Args:
            other (PriceInterval): [description]

        Returns:
            bool: [description]
        """
        return self.upper > other.upper

    def __hash__(self) -> int:
        """
        __hash__ [summary]

        Returns:
            int: [description]
        """
        return self._hash

    def __lt__(self, other: PriceInterval) -> bool:
        """
        __lt__ is True self.lower < other.lower

        Args:
            other (PriceInterval): [description]

        Returns:
            bool: [description]
        """
        return self.lower < other.lower

    def __repr__(self) -> str:
        """
        __repr__ [summary]

        Returns:
            str: [description]
        """
        return f"<PriceInterval ({self.lower}, {self.upper})>"

    @classmethod
    def from_price(
        cls, price: ProductPrice, interval_length: ProductPrice
    ) -> PriceInterval:
        """
        from_price returns the PriceInterval containing `price`
        assuming all PriceInterval objects have length `interval_length`
        starting from 0

        Args:
            price (ProductPrice): [description]
            interval_length (ProductPrice): [description]

        Returns:
            PriceInterval: [description]
        """
        quotient = price.amount // interval_length.amount
        lower = cc.PRODUCT_ID.price_type(quotient * interval_length.amount)
        upper = cc.PRODUCT_ID.price_type(lower.amount + interval_length.amount)

        return cls(lower, upper)


OrderBookPartitionKey = Tuple[ProductPrice, timedelta]
OrderBookKey = Tuple[PriceInterval, OrderBookPartitionKey]

# pylint: disable=useless-super-delegation
class OrderBookPartition(TreeBase):
    """OrderBookPartition is used to represent a portion of the
    Exchange order book for a given PriceInterval. It's basically
    a subclass of bintrees.FastRBTree along with some helper methods
    and a typed interface. The partioning exists to improve performance
    of the MatchingEngine.
    """

    def __init__(
        self, items: Optional[Dict[OrderBookPartitionKey, CoinbaseOrder]] = None
    ) -> None:
        """
        __init__ [summary]

        Args:
            items Optional[Dict[OrderBookPartitionKey, CoinbaseOrder]]: Defaults to None.
        """
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
        if isinstance(other, OrderBookPartition):
            if len(self) == len(other):
                tree_equal = True
                for (self_key, self_value), (other_key, other_value) in zip(
                    self.items(), other.items()
                ):
                    if self_key != other_key or self_value != other_value:
                        tree_equal = False
                        break

            else:
                tree_equal = False

            dict_equal = self.orders_dict == other.orders_dict

            equal = tree_equal and dict_equal

        else:
            raise TypeError

        return equal

    @staticmethod
    def calculate_order_book_partition_key(
        order: CoinbaseOrder,
    ) -> OrderBookPartitionKey:
        """Calculates order book partition key for order according to formula
        (price, datetime.max - order.time if order.side == 'buy' else \\
         order.time - datetime.min)

        Args:
            order (CoinbaseOrder): Description

        Returns:
            OrderBookPartitionKey
        """
        return (
            order.price,
            datetime.max - order.time
            if order.side == OrderSide.buy
            else order.time - datetime.min,
        )

    def insert(self, key: OrderBookPartitionKey, value: CoinbaseOrder) -> None:
        """
        insert [summary]

        Args:
            key (OrderBookPartitionKey): [description]
            value (CoinbaseOrder): [description]
        """
        super().insert(key, value)

        # When calling deepcopy on this object, orders_dict is not
        # instantiated before insert is called. For this case check
        # if attribute exists, if not create it.
        if not hasattr(self, "orders_dict"):
            self.orders_dict = {}

        self.orders_dict[value.order_id] = value

    def is_empty(self) -> bool:
        """
        is_empty [summary]

        Returns:
            bool: [description]
        """
        return super().is_empty()

    def items(
        self, reverse: bool = False
    ) -> Iterator[Tuple[OrderBookPartitionKey, CoinbaseOrder]]:
        """
        items [summary]

        Args:
            reverse (bool, optional): [description]. Defaults to False.

        Returns:
            Iterator[Tuple[OrderBookPartitionKey, CoinbaseOrder]]: [description]
        """
        return super().items(reverse)

    def max_item(self) -> Tuple[OrderBookPartitionKey, CoinbaseOrder]:
        """
        max_item [summary]

        Raises:
            OrderBookEmpty: [description]

        Returns:
            Tuple[OrderBookPartitionKey, CoinbaseOrder]: [description]
        """
        try:
            return super().max_item()
        except ValueError:
            raise OrderBookEmpty

    def min_item(self) -> Tuple[OrderBookPartitionKey, CoinbaseOrder]:
        """
        min_item [summary]

        Raises:
            OrderBookEmpty: [description]

        Returns:
            Tuple[OrderBookPartitionKey, CoinbaseOrder]: [description]
        """
        try:
            return super().min_item()
        except ValueError:
            raise OrderBookEmpty

    def pop(self, key: OrderBookPartitionKey) -> CoinbaseOrder:
        """
        pop [summary]

        Args:
            key (OrderBookPartitionKey): [description]

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
        key = self.calculate_order_book_partition_key(value)
        super().pop(key)

        return value


class PartitionsCollection:
    """
    A (key, value) collection of OrderBookPartition objects keyed
    by PriceInterval. Each OrderBookPartition is a binary tree that
    contains orders with price contained in the PriceInterval key.
    """

    def __init__(self) -> None:
        """
        __init__ [summary]
        """
        self.partitions_map = SortedDict()

    def __eq__(self, other: Any) -> bool:
        """
        __eq__ [summary]

        Args:
            other (Any): [description]

        Raises:
            TypeError: [description]

        Returns:
            bool: [description]
        """
        if isinstance(other, PartitionsCollection):
            return_val = self.partitions_map == other.partitions_map
        else:
            raise TypeError

        return return_val

    def __contains__(self, key: PriceInterval) -> bool:
        """
        __contains__ [summary]

        Args:
            key (PriceInterval): [description]

        Returns:
            bool: [description]
        """
        return key in self.partitions_map

    def __getitem__(self, key: PriceInterval) -> OrderBookPartition:
        """
        __getitem__ [summary]

        Args:
            key (PriceInterval): [description]

        Returns:
            OrderBookPartition: [description]
        """
        return self.partitions_map[key]

    def __setitem__(self, key: PriceInterval, value: OrderBookPartition) -> None:
        """
        __setitem__ [summary]

        Args:
            key (PriceInterval): [description]
            value (OrderBookPartition): [description]
        """
        self.partitions_map[key] = value

    def peekitem(self, index: int = -1) -> OrderBookPartition:
        """
        peekitem [summary]

        Args:
            index (int, optional): [description]. Defaults to -1.

        Returns:
            OrderBookPartition: [description]
        """
        return self.partitions_map.peekitem(index)

    def pop(
        self, key: PriceInterval, default: Optional[PriceInterval] = None
    ) -> OrderBookPartition:
        """
        pop [summary]

        Args:
            key (PriceInterval): [description]
            default (Optional[PriceInterval], optional): [description]. Defaults to None.

        Returns:
            OrderBookPartition: [description]
        """
        return self.partitions_map.pop(key, default)

    def values(self, reverse: bool = False) -> Iterator[OrderBookPartition]:
        """
        values returns a generator that iterates over the OrderBookPartition
        objects stored in the collection sorted by PriceInterval in ascending
        order. If reverse is True, iteration will occur in descending order.

        Args:
            reverse (bool, optional): [description]. Defaults to False.

        Returns:
            Iterator[OrderBookPartition]: [description]
        """
        return (
            self.partitions_map.values().__reversed__()
            if reverse
            else self.partitions_map.values()
        )


class OrderBook:
    """
    Used to represent either the buy or sell order book in the simulation.
    The orders are stored in binary trees partioned by price in the `partitions`
    property.
    """

    PRICE_INTERVAL_LENGTH = cc.PRODUCT_ID.price_type("1000.00")

    def __init__(self) -> None:
        """
        __init__ [summary]
        """
        self.partitions = PartitionsCollection()
        self.order_id_to_price_interval: Dict[OrderId, PriceInterval] = {}

    def __eq__(self, other: Any) -> bool:
        """
        __eq__ returns True if `other` is another OrderBook
        object with the same `partitions` and `order_id_to_price_interval`
        properties

        Args:
            other (Any): [description]

        Raises:
            TypeError: [description]

        Returns:
            bool: [description]
        """
        if isinstance(other, OrderBook):
            partitions_equal = self.partitions == other.partitions
            order_id_lookup_equal = (
                self.order_id_to_price_interval == other.order_id_to_price_interval
            )
            return_val = partitions_equal and order_id_lookup_equal
        else:
            raise TypeError

        return return_val

    @classmethod
    def calculate_order_book_key(cls, order: CoinbaseOrder) -> OrderBookKey:
        """
        calculate_order_book_key [summary]

        Args:
            order (CoinbaseOrder): [description]

        Returns:
            OrderBookKey: [description]
        """
        price_interval = PriceInterval.from_price(
            order.price, cls.PRICE_INTERVAL_LENGTH
        )
        partition_key = OrderBookPartition.calculate_order_book_partition_key(order)

        return (price_interval, partition_key)

    def contains_order_id(self, order_id: OrderId) -> bool:
        """
        contains_order_id [summary]

        Args:
            order_id (OrderId): [description]

        Returns:
            bool: [description]
        """
        return order_id in self.order_id_to_price_interval

    def get_order_by_order_id(self, order_id: OrderId) -> CoinbaseOrder:
        """
        get_order_by_order_id [summary]

        Args:
            order_id (OrderId): [description]

        Returns:
            CoinbaseOrder: [description]
        """
        price_interval = self.order_id_to_price_interval[order_id]
        partition = self.partitions[price_interval]

        return partition.orders_dict[order_id]

    def insert(self, key: OrderBookKey, value: CoinbaseOrder) -> None:
        """
        insert [summary]

        Args:
            key (OrderBookKey): [description]
            value (CoinbaseOrder): [description]
        """
        price_interval = key[0]
        partition_key = key[1]
        self.order_id_to_price_interval[value.order_id] = price_interval

        if price_interval not in self.partitions:
            self.partitions[price_interval] = OrderBookPartition()

        self.partitions[price_interval].insert(partition_key, value)

    def items(
        self, reverse: bool = False
    ) -> Iterator[Tuple[OrderBookPartitionKey, CoinbaseOrder]]:
        """
        items returns a generator that iterates over the
        (OrderBookPartitionKey, CoinbaseOrder) objects stored
        in the order book, sorted by price, in ascending order.
        If reverse is True iteration occurs in descending order.

        Args:
            reverse (bool, optional): [description]. Defaults to False.

        Returns:
            Iterator[Tuple[OrderBookPartitionKey, CoinbaseOrder]]: [description]

        Yields:
            Iterator[Tuple[OrderBookPartitionKey, CoinbaseOrder]]: [description]
        """
        for partition in self.partitions.values(reverse):
            try:
                for item in partition.items(reverse):
                    yield item
            except StopIteration:
                continue

    def max_partition(self) -> OrderBookPartition:
        """
        max_partition returns the partition corresponding
        to the highest PriceInterval

        Raises:
            OrderBookEmpty: [description]

        Returns:
            OrderBookPartition: [description]
        """
        try:
            return self.partitions.peekitem()[1]
        except IndexError:
            raise OrderBookEmpty

    def min_partition(self) -> OrderBookPartition:
        """
        min_partition returns the partition corresponding
        to the lowest PriceInterval

        Raises:
            OrderBookEmpty: [description]

        Returns:
            OrderBookPartition: [description]
        """
        try:
            return self.partitions.peekitem(0)[1]
        except IndexError:
            raise OrderBookEmpty

    def pop(self, key: OrderBookKey) -> CoinbaseOrder:
        """
        pop [summary]

        Args:
            key (OrderBookKey): [description]

        Returns:
            CoinbaseOrder: [description]
        """
        price_interval = key[0]
        partition_key = key[1]
        partition = self.partitions[price_interval]
        order = partition.pop(partition_key)
        self.order_id_to_price_interval.pop(order.order_id)

        if partition.is_empty():
            self.partitions.pop(price_interval)

        return order

    def pop_by_order_id(self, order_id: OrderId) -> CoinbaseOrder:
        """
        pop_by_order_id [summary]

        Args:
            order_id (OrderId): [description]

        Returns:
            CoinbaseOrder: [description]
        """
        price_interval = self.order_id_to_price_interval.pop(order_id)
        partition = self.partitions[price_interval]
        order = partition.pop_by_order_id(order_id)

        if partition.is_empty():
            self.partitions.pop(price_interval)

        return order


class OrderBookEmpty(Exception):
    """
    OrderBookEmpty raised when trying to get an item from an empty order book
    """
