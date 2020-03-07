"""
Types used by the package
"""
from __future__ import annotations
from dataclasses import dataclass
from decimal import Decimal
from enum import Enum
from typing import NewType, Optional

from coinbase_ml.fakebase.types.currency import (
    Currency,
    InvalidTypeError,
    Price,
    PreciseNumber,
    ProductId,
    ProductPrice,
    ProductVolume,
    ProductVolumeSubType,
    QuoteVolume,
    QuoteVolumeSubType,
    Volume,
)


class DoneReason(Enum):
    """
    DoneReason contains the different reasons an order can be done
    """

    cancelled = "cancelled"
    filled = "filled"


class Liquidity(Enum):
    """
    Liquidity encapsulates the possibility liquidity statuses of an order.
    A maker order is placed on the order book. A taker order
    """

    maker = "M"
    taker = "T"

    def __init__(self, liquidity: str) -> None:
        """
        __init__ [summary]

        Args:
            liquidity (str): [description]
        """
        self._fee_fraction = {"M": Decimal("0.0050"), "T": Decimal("0.0050")}[liquidity]

    @property
    def fee_fraction(self) -> Decimal:
        """
        fee_fraction [summary]

        Returns:
            Decimal: [description]
        """
        return self._fee_fraction


OrderId = NewType("OrderId", str)


class OrderSide(Enum):
    """
    OrderSide encapsulates the possible order sides
    """

    buy = "buy"
    sell = "sell"

    @staticmethod
    def from_string(order_side: str) -> Optional[OrderSide]:
        """
        from_string [summary]

        Args:
            order_side (str): [description]

        Returns:
            Optional[OrderSide]: [description]
        """
        if order_side == OrderSide.buy.value:
            return_val = OrderSide.buy
        elif order_side == OrderSide.sell.value:
            return_val = OrderSide.sell
        else:
            return_val = None

        return return_val

    def get_opposite_side(self) -> OrderSide:
        """
        get_opposite_side [summary]

        Returns:
            OrderSide: [description]
        """
        return OrderSide.sell if self == OrderSide.buy else OrderSide.buy


class OrderStatus(Enum):
    """
    OrderStatus encapsulates the possible order statuses
    """

    done = "done"
    open = "open"
    pending = "pending"
    received = "received"
    rejected = "rejected"

    @staticmethod
    def from_string(order_status: str) -> Optional[OrderStatus]:
        """
        from_string [summary]

        Args:
            order_status (str): [description]

        Returns:
            Optional[OrderStatus]: [description]
        """
        if order_status == OrderStatus.done.value:
            return_val = OrderStatus.done
        elif order_status == OrderStatus.open.value:
            return_val = OrderStatus.open
        elif order_status == OrderStatus.pending.value:
            return_val = OrderStatus.pending
        elif order_status == OrderStatus.received.value:
            return_val = OrderStatus.received
        elif order_status == OrderStatus.rejected:
            return_val = OrderStatus.rejected
        else:
            return_val = None

        return return_val


class OrderType(Enum):
    """
    OrderType encapsulates the possible order types
    """

    limit = "limit"
    market = "market"

    def __str__(self) -> str:
        return self.value

    @staticmethod
    def from_string(order_type: str) -> Optional[OrderType]:
        """
        from_string [summary]

        Args:
            order_type (str): [description]

        Returns:
            Optional[OrderType]: [description]
        """
        if order_type == OrderType.limit.value:
            return_val = OrderType.limit
        elif order_type == OrderType.market.value:
            return_val = OrderType.market
        else:
            return_val = None

        return return_val


class RejectReason(Enum):
    """
    RejectReason encapsulates the possible reasons an order can be rejected
    """

    funds_too_large = "funds_too_large"
    funds_too_small = "funds_too_small"
    insufficient_funds = "insufficient_funds"
    post_only = "post_only"
    price_too_large = "price_too_large"
    price_too_small = "price_too_small"
    size_too_large = "size_too_large"
    size_too_small = "size_too_small"
