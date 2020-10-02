"""
Types used by the package
"""
from __future__ import annotations
from dataclasses import dataclass
from decimal import Decimal
from enum import Enum
from typing import Dict, NewType, Optional

import coinbase_ml.fakebase.protos.events_pb2 as events_pb2
import coinbase_ml.fakebase.protos.fakebase_pb2 as fakebase_pb2
from coinbase_ml.fakebase.protos.events_pb2 import (
    DoneReason as DoneReasonProto,
    OrderSide as OrderSideProto,
    OrderStatus as OrderStatusProto,
    Liquidity as LiquidityProto,
    RejectReason as RejectReasonProto,
)
from coinbase_ml.fakebase.protos.fakebase_pb2 import OrderType as OrderTypeProto
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

BinnedOrderBook = Dict[ProductPrice, ProductVolume]


class DoneReason(Enum):
    """
    DoneReason contains the different reasons an order can be done
    """

    not_done = "not_done"
    cancelled = "cancelled"
    filled = "filled"

    @staticmethod
    def from_proto(done_reason: "events_pb2.DoneReasonValue") -> DoneReason:
        """
        from_proto [summary]

        Args:
            done_reason (events_pb2.DoneReasonValue): [description]

        Returns:
            DoneReason: [description]
        """
        return _DONE_REASON_PROTO_MAPPING[done_reason]


_DONE_REASON_PROTO_MAPPING = {
    DoneReasonProto.notDone: DoneReason.not_done,
    DoneReasonProto.cancelled: DoneReason.cancelled,
    DoneReasonProto.filled: DoneReason.filled,
}


class Liquidity(Enum):
    """
    Liquidity encapsulates the possibility liquidity statuses of an order.
    A maker order is placed on the order book. A taker order
    """

    maker = "M"
    taker = "T"
    global_liquidity = "G"

    def __init__(self, liquidity: str) -> None:
        """
        __init__ [summary]

        Args:
            liquidity (str): [description]
        """
        self._fee_fraction = {
            "M": Decimal("0.0050"),
            "T": Decimal("0.0050"),
            "G": Decimal("0.0"),
        }[liquidity]

    @property
    def fee_fraction(self) -> Decimal:
        """
        fee_fraction [summary]

        Returns:
            Decimal: [description]
        """
        return self._fee_fraction

    @staticmethod
    def from_proto(liquidity: "events_pb2.LiquidityValue") -> Liquidity:
        """
        from_proto [summary]

        Args:
            liquidity (events_pb2.LiquidityValue): [description]

        Returns:
            Liquidity: [description]
        """
        return _LIQUIDITY_PROTO_MAPPING[liquidity]


_LIQUIDITY_PROTO_MAPPING = {
    LiquidityProto.Value("global"): Liquidity.global_liquidity,
    LiquidityProto.maker: Liquidity.maker,
    LiquidityProto.taker: Liquidity.taker,
}

OrderId = NewType("OrderId", str)


class OrderSide(Enum):
    """
    OrderSide encapsulates the possible order sides
    """

    buy = "buy"
    sell = "sell"

    @staticmethod
    def from_proto(order_side: "events_pb2.OrderSideValue") -> OrderSide:
        """
        from_proto [summary]

        Args:
            order_side (events_pb2.OrderSideValue): [description]

        Returns:
            OrderSide: [description]
        """
        return _ORDER_SIDE_PROTO_MAPPING[order_side]

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


_ORDER_SIDE_PROTO_MAPPING = {
    OrderSideProto.buy: OrderSide.buy,
    OrderSideProto.sell: OrderSide.sell,
}


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
    def from_proto(order_status: "events_pb2.OrderStatusValue") -> OrderStatus:
        """
        from_proto [summary]

        Args:
            order_status (events_pb2.OrderStatusValue): [description]

        Returns:
            OrderStatus: [description]
        """
        return _ORDER_STATUS_PROTO_MAPPING[order_status]

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


_ORDER_STATUS_PROTO_MAPPING = {
    OrderStatusProto.done: OrderStatus.done,
    OrderStatusProto.open: OrderStatus.open,
    OrderStatusProto.pending: OrderStatus.pending,
    OrderStatusProto.received: OrderStatus.received,
    OrderStatusProto.rejected: OrderStatus.rejected,
}


class OrderType(Enum):
    """
    OrderType encapsulates the possible order types
    """

    limit = "limit"
    market = "market"

    def __str__(self) -> str:
        return str(self.value)

    @staticmethod
    def from_proto(order_proto: "fakebase_pb2.OrderTypeValue") -> OrderType:
        """
        from_proto [summary]

        Args:
            order_proto (fakebase_pb2.OrderTypeValue): [description]

        Returns:
            OrderType: [description]
        """
        return _ORDER_TYPE_PROTO_MAPPING[order_proto]

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


_ORDER_TYPE_PROTO_MAPPING = {
    OrderTypeProto.limit: OrderType.limit,
    OrderTypeProto.market: OrderType.market,
}


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

    @staticmethod
    def from_proto(reject_reason: "events_pb2.RejectReasonValue") -> RejectReason:
        """
        from_proto [summary]

        Args:
            reject_reason (events_pb2.RejectReasonValue): [description]

        Returns:
            RejectReason: [description]
        """
        return _REJECT_REASON_PROTO_MAPPING[reject_reason]


_REJECT_REASON_PROTO_MAPPING = {
    RejectReasonProto.fundsTooLarge: RejectReason.funds_too_large,
    RejectReasonProto.fundsTooSmall: RejectReason.funds_too_small,
    RejectReasonProto.insufficientFunds: RejectReason.insufficient_funds,
    RejectReasonProto.postOnly: RejectReason.post_only,
    RejectReasonProto.priceTooLarge: RejectReason.price_too_large,
    RejectReasonProto.priceTooSmall: RejectReason.price_too_small,
    RejectReasonProto.sizeTooLarge: RejectReason.size_too_large,
    RejectReasonProto.sizeTooSmall: RejectReason.size_too_small,
}
