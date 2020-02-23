"""
Types used by the package
"""
from __future__ import annotations
from dataclasses import dataclass
from decimal import Decimal
from enum import Enum
from typing import NewType

from .currency import (
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


class OrderType(Enum):
    """
    OrderType encapsulates the possible order types
    """

    limit = "limit"
    market = "market"

    def __str__(self) -> str:
        return self.value


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
