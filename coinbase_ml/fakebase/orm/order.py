"""
CoinbaseOrder orm
"""
from __future__ import annotations
from datetime import datetime, timedelta
from decimal import Decimal
from typing import Any, Dict, List, Optional, Union

from dateutil import parser
from sqlalchemy import BigInteger, Column, Float, String
from sqlalchemy.orm import reconstructor

import coinbase_ml.common.constants as cc
from coinbase_ml.fakebase import constants as c
from coinbase_ml.fakebase.orm.match import CoinbaseMatch
from coinbase_ml.fakebase.orm.mixins import Base, MatchOrderEvent
from coinbase_ml.fakebase.protos.fakebase_pb2 import (
    BuyLimitOrder,
    BuyMarketOrder,
    SellLimitOrder,
    SellMarketOrder,
)
from coinbase_ml.fakebase.types import (
    DoneReason,
    InvalidTypeError,
    OrderId,
    OrderSide,
    OrderStatus,
    OrderType,
    ProductId,
    ProductPrice,
    ProductVolume,
    QuoteVolume,
    RejectReason,
    Volume,
)


class CoinbaseOrder(MatchOrderEvent, Base):  # pylint: disable=R0903,R0902

    """Model for Coinbase orders
    """

    __tablename__ = "coinbase_orders"

    _funds = Column("funds", Float(asdecimal=True))
    _order_status = Column("order_status", String)
    _order_type = Column("order_type", String)
    _order_id = Column("order_id", String)
    client_oid = Column(String)
    sequence = Column(BigInteger)

    def __init__(
        self,
        order_id: Optional[OrderId] = None,
        order_status: Optional[OrderStatus] = None,
        order_type: Optional[OrderType] = None,
        product_id: Optional[ProductId] = None,
        side: Optional[OrderSide] = None,
        time: Optional[datetime] = None,
        post_only: Optional[bool] = False,
        price: Optional[ProductPrice] = None,
        funds: Optional[QuoteVolume] = None,
        time_in_force: Optional[str] = "gtc",
        time_to_live: Optional[timedelta] = timedelta.max,
        size: Optional[ProductVolume] = None,
        **kwargs: Any,
    ) -> None:
        """
        __init__ [summary]

        Args:
            order_id (Optional[OrderId], optional): [description]. Defaults to None.
            order_status (Optional[OrderStatus], optional): [description]. Defaults to None.
            order_type (Optional[OrderType], optional): [description]. Defaults to None.
            product_id (Optional[ProductId], optional): [description]. Defaults to None.
            side (Optional[OrderSide], optional): [description]. Defaults to None.
            time (Optional[datetime], optional): [description]. Defaults to None.
            post_only (Optional[bool], optional): [description]. Defaults to False.
            price (Optional[ProductPrice], optional): [description]. Defaults to None.
            funds (Optional[QuoteVolume], optional): [description]. Defaults to None.
            time_in_force (Optional[str], optional): [description]. Defaults to "gtc".
            time_to_live (Optional[timedelta], optional): [description]. Defaults to timedelta.max.
            size (Optional[ProductVolume], optional): [description]. Defaults to None.
        """
        MatchOrderEvent.__init__(
            self,
            product_id=product_id,
            side=side,
            price=price,
            size=size,
            time=time,
            **kwargs,
        )

        Base.__init__(  # pylint: disable=non-parent-init-called
            self,
            product_id=product_id,
            side=side,
            price=price,
            size=size,
            time=time,
            **kwargs,
        )

        self._funds = None if funds is None else funds.amount
        self._order_id = order_id
        self._order_status = order_status.value if order_status else None
        self._order_type = order_type.value if order_type else None
        self._typed_order_status = order_status
        self.original_funds = funds
        self._remaining_size = None if size is None else size.amount
        self.done_at: Optional[datetime] = None
        self.done_reason: Optional[DoneReason] = None
        self.is_taker: Optional[bool] = None
        self.matches: List[CoinbaseMatch] = []
        self.post_only = post_only
        self.reject_reason: Optional[RejectReason] = None
        self.time_in_force = time_in_force
        self.time_to_live = time_to_live

        self._set_typed_order_type()

    def __eq__(self, other: Any) -> bool:
        """
        __eq__ [summary]

        Args:
            other (Any): only implemented for type(other)==CoinbaseOrder

        Returns:
            bool: [description]
        """
        if isinstance(other, CoinbaseOrder):
            _other: CoinbaseOrder = other
            return_val = (
                self.order_id == _other.order_id  # pylint: disable=W0143
                and self.price == _other.price
                and self.product_id == _other.product_id  # pylint: disable=W0143
                and self.remaining_size == _other.remaining_size
                and self.side == _other.side
                and self.size == _other.size
                and self.time == _other.time
            )
        else:
            raise InvalidTypeError(type(other), "other")

        return return_val

    def __repr__(self) -> str:
        return str(self.__dict__)

    def _set_order_status(self, order_status: OrderStatus) -> None:
        """
        _set_order_status [summary]

        Args:
            order_status (OrderStatus): [description]
        """
        self._order_status = order_status.value
        self._typed_order_status = order_status

    def _set_typed_order_type(self) -> None:
        """
        _set_typed_order_type [summary]
        """
        self._typed_order_type = OrderType.from_string(self._order_type)

    @reconstructor
    def init_on_load(self) -> None:
        """Summary
        """
        self._set_typed_product_info()
        self._set_typed_price()
        self._set_typed_size()
        self._set_typed_side()
        self._set_typed_order_type()
        self._typed_order_status = OrderStatus.from_string(self._order_status)
        self._remaining_size = self.size.amount if self.size else None
        self.done_at = None
        self.done_reason = None
        self.is_taker = None
        self.matches = []
        self.post_only = None
        self.reject_reason = None
        self.time_in_force = None
        self.time_to_live = None

    def close_order(self, done_at: datetime, done_reason: DoneReason) -> None:
        """
        close_order [summary]

        Args:
            done_at (datetime): [description]
            done_reason (DoneReason): [description]

        Returns:
            None: [description]
        """
        self._set_order_status(OrderStatus.done)
        self.done_at = done_at
        self.done_reason = done_reason

    @property
    def executed_value(self) -> QuoteVolume:
        """
        executed_value [summary]

        Returns:
            QuoteVolume: [description]
        """
        s = sum([match.usd_volume.amount for match in self.matches], c.ZERO_DECIMAL)
        return self.get_product_id().quote_volume_type(s)

    @property
    def fill_fees(self) -> QuoteVolume:
        """
        fill_fees [summary]

        Returns:
            QuoteVolume: [description]
        """
        s = sum([match.fee.amount for match in self.matches], c.ZERO_DECIMAL)
        return self.get_product_id().quote_volume_type(s)

    @property
    def filled_size(self) -> ProductVolume:
        """
        filled_size [summary]

        Returns:
            ProductVolume: [description]
        """
        s = sum([match.size.amount for match in self.matches], c.ZERO_DECIMAL)
        return self.get_product_id().product_volume_type(s)

    @staticmethod
    def from_proto(
        order_proto: Union[
            BuyLimitOrder, BuyMarketOrder, SellLimitOrder, SellMarketOrder
        ]
    ) -> CoinbaseOrder:
        """
        from_proto

        Args:
            order_proto (Union[ BuyLimitOrder, BuyMarketOrder, SellLimitOrder, SellMarketOrder ])

        Returns:
            CoinbaseOrder
        """
        funds = (
            cc.PRODUCT_ID.quote_volume_type(order_proto.funds)
            if isinstance(order_proto, BuyMarketOrder)
            else None
        )

        price = (
            cc.PRODUCT_ID.price_type(order_proto.price)
            if isinstance(order_proto, (BuyLimitOrder, SellLimitOrder))
            else None
        )

        size = (
            cc.PRODUCT_ID.product_volume_type(order_proto.size)
            if isinstance(order_proto, (BuyLimitOrder, SellLimitOrder, SellMarketOrder))
            else None
        )

        order_type = (
            OrderType.limit
            if isinstance(order_proto, (BuyLimitOrder, SellLimitOrder))
            else OrderType.market
        )

        order_status = OrderStatus.from_proto(order_proto.orderStatus)

        order = CoinbaseOrder(
            funds=funds,
            order_id=OrderId(order_proto.orderId),
            order_type=order_type,
            price=price,
            product_id=cc.PRODUCT_ID,
            order_status=order_status,
            side=OrderSide.from_proto(order_proto.side),
            size=size,
            time=parser.parse(order_proto.time),
        )

        order.matches = [
            CoinbaseMatch.from_proto(match)
            for match in order_proto.matchEvents.matchEvents
        ]

        order.done_reason = DoneReason.from_proto(order_proto.doneReason)

        if order_status is OrderStatus.rejected:
            order.reject_order(RejectReason.from_proto(order_proto.rejectReason))

        return order

    @property
    def funds(self) -> Optional[QuoteVolume]:
        """
        funds [summary]

        Returns:
            Optional[QuoteVolume]: [description]
        """
        return (
            None
            if self._funds is None
            else self.get_product_id().quote_volume_type(self._funds)
        )

    @funds.setter
    def funds(self, value: Optional[Union[QuoteVolume, Decimal, float]]) -> None:
        """
        funds [summary]

        Args:
            value (Optional[Union[QuoteVolume, Decimal, float]]): [description]

        Raises:
            InvalidTypeError: [description]
        """
        if isinstance(value, Volume):
            self._funds = value.amount
        elif isinstance(value, float):
            self._funds = Decimal(value)
        elif isinstance(value, Decimal) or value is None:
            self._funds = value
        else:
            raise InvalidTypeError(type(value), "value")

    def open_order(self) -> None:
        """Summary
        """
        self._set_order_status(OrderStatus.open)

    @property
    def order_id(self) -> OrderId:
        """
        order_id [summary]

        Returns:
            OrderId: [description]
        """
        return OrderId(self._order_id)

    @order_id.setter
    def order_id(self, value: Union[str, OrderId]) -> None:
        self._order_id = value

    @property
    def order_status(self) -> OrderStatus:
        """
        order_status [summary]

        Returns:
            OrderStatus: [description]
        """
        return self._typed_order_status

    @property
    def order_type(self) -> OrderType:
        """
        order_type [summary]

        Returns:
            OrderType: [description]
        """
        return self._typed_order_type

    def reject_order(self, reject_reason: RejectReason) -> None:
        """Summary

        Args:
            reject_reason (RejectReason): Description
        """
        self._set_order_status(OrderStatus.rejected)
        self.reject_reason = reject_reason

    @property
    def remaining_size(self) -> Optional[ProductVolume]:
        """
        remaining_size [summary]

        Returns:
            Optional[ProductVolume]: [description]
        """
        return (
            self._product_volume_type(self._remaining_size)
            if self._remaining_size is not None
            else None
        )

    @remaining_size.setter
    def remaining_size(
        self, value: Optional[Union[ProductVolume, Decimal, float]]
    ) -> None:
        """
        remaining_size [summary]

        Args:
            value (Optional[Union[ProductVolume, Decimal, float]]): [description]

        Raises:
            InvalidTypeError: [description]
        """
        if isinstance(value, Volume):
            self._remaining_size = value.amount
        elif isinstance(value, float):
            self._remaining_size = Decimal(value)
        elif isinstance(value, Decimal) or value is None:
            self._remaining_size = value
        else:
            raise InvalidTypeError(type(value), "value")

    def reject_order_if_invalid(self) -> None:  # pylint: disable = too-many-branches
        """
        reject_order_if_invalid [summary]
        """
        if self.order_type == OrderType.limit:
            if self.price.is_too_small():
                self.reject_order(reject_reason=RejectReason.price_too_small)
            elif self.price.is_too_large():
                self.reject_order(reject_reason=RejectReason.price_too_large)
            elif self.size.is_too_small():
                self.reject_order(reject_reason=RejectReason.size_too_small)
            elif self.size.is_too_large():
                self.reject_order(reject_reason=RejectReason.size_too_large)
            elif self.post_only and self.is_taker:
                self.reject_order(reject_reason=RejectReason.post_only)

        elif self.order_type == OrderType.market:
            if self.side == OrderSide.buy:
                if self.funds.is_too_large():
                    self.reject_order(reject_reason=RejectReason.funds_too_large)
                elif self.funds.is_too_small():
                    self.reject_order(reject_reason=RejectReason.funds_too_small)
            else:
                if self.size.is_too_small():
                    self.reject_order(reject_reason=RejectReason.size_too_small)
                elif self.size.is_too_large():
                    self.reject_order(reject_reason=RejectReason.size_too_large)

        else:
            raise InvalidTypeError(type(self.order_type), "order")

    def to_dict(self) -> Dict[str, Any]:
        """This representation gets sent to MockAuthenticatedClient
        when it requests information about orders.

        Returns:
            Dict[str, Any]: Description
        """
        return dict(
            created_at=str(self.time),
            executed_value=str(self.executed_value),
            fill_fees=str(self.fill_fees),
            filled_size=str(self.filled_size),
            id=self.order_id,
            post_only=self.post_only,
            price=str(self.price),
            product_id=self.product_id,
            reject_reason=self.reject_reason,
            settled=False,
            side=self.side,
            size=str(self.size),
            status=self.order_status,
            time_in_force=self.time_in_force,
            type=self.order_type,
        )
