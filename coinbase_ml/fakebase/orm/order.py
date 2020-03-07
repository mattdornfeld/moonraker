"""
CoinbaseOrder orm
"""
from __future__ import annotations
from datetime import datetime, timedelta
from decimal import Decimal
from typing import Any, Dict, List, Optional, Union

from sqlalchemy import BigInteger, Column, Float, String
from sqlalchemy.orm import reconstructor

from .. import constants as c
from ..orm.match import CoinbaseMatch
from ..orm.mixins import Base, MatchOrderEvent
from ..types import (
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

    @reconstructor
    def init_on_load(self) -> None:
        """Summary
        """
        self._set_typed_price()
        self._set_typed_size()
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
        self._order_status = OrderStatus.done.value
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
            volume_value: QuoteVolume = value
            self._funds = volume_value.amount
        elif isinstance(value, float):
            float_value: float = value
            self._funds = Decimal(float_value)
        elif isinstance(value, Decimal) or value is None:
            volume_optional_decimal: Optional[Decimal] = value
            self._funds = volume_optional_decimal
        else:
            raise InvalidTypeError(type(value), "value")

    def open_order(self) -> None:
        """Summary
        """
        self._order_status = OrderStatus.open.value

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
        return OrderStatus.__members__[self._order_status]

    @property
    def order_type(self) -> OrderType:
        """
        order_type [summary]

        Returns:
            OrderType: [description]
        """
        return OrderType.__members__[self._order_type]

    def reject_order(self, reject_reason: RejectReason) -> None:
        """Summary

        Args:
            reject_reason (RejectReason): Description
        """
        self._order_status = OrderStatus.rejected.value
        self.reject_reason = reject_reason

    @property
    def remaining_size(self) -> Optional[ProductVolume]:
        """
        remaining_size [summary]

        Returns:
            Optional[ProductVolume]: [description]
        """
        return (
            self.get_product_id().product_volume_type(self._remaining_size)
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
            volume_value: ProductVolume = value
            self._remaining_size = volume_value.amount
        elif isinstance(value, float):
            volume_float: float = value
            self._remaining_size = Decimal(volume_float)
        elif isinstance(value, Decimal) or value is None:
            volume_optional_decimal: Optional[Decimal] = value
            self._remaining_size = volume_optional_decimal
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
