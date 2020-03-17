"""
CoinbaseCancellation orm
"""
from __future__ import annotations
from datetime import datetime
from decimal import Decimal
from typing import Any, Optional, Union

from sqlalchemy import Column, String, Float
from sqlalchemy.orm import reconstructor

from coinbase_ml.fakebase.orm.mixins import Base, CoinbaseEvent
from coinbase_ml.fakebase.types import (
    InvalidTypeError,
    OrderId,
    OrderSide,
    ProductId,
    ProductPrice,
    ProductVolume,
    Volume,
)


class CoinbaseCancellation(CoinbaseEvent, Base):  # pylint: disable=R0903
    """Model for storing Coinbase order cancellations
    """

    __tablename__ = "coinbase_cancellations"

    _order_id = Column("order_id", String)
    _remaining_size = Column("remaining_size", Float(asdecimal=True))

    def __init__(
        self,
        price: ProductPrice,
        product_id: ProductId,
        order_id: OrderId,
        side: OrderSide,
        remaining_size: ProductVolume,
        time: datetime,
        **kwargs: Any,
    ) -> None:
        """
        __init__ [summary]

        Args:
            price (ProductPrice): [description]
            product_id (ProductId): [description]
            order_id (OrderId): [description]
            side (OrderSide): [description]
            remaining_size (ProductVolume): [description]
            time (datetime): [description]
        """
        self.remaining_size = remaining_size
        CoinbaseEvent.__init__(
            self,
            price=price,
            product_id=product_id,
            order_id=order_id,
            side=side,
            time=time,
            **kwargs,
        )

        Base.__init__(  # pylint: disable=non-parent-init-called
            self,
            price=price,
            product_id=product_id,
            order_id=order_id,
            side=side,
            time=time,
            **kwargs,
        )

    def __eq__(self, other: Any) -> bool:
        """
        __eq__ [summary]

        Args:
            other (Any): [description]

        Returns:
            bool: [description]
        """
        if isinstance(other, CoinbaseCancellation):
            _other: CoinbaseCancellation = other
            return_val = (
                self.order_id == _other.order_id  # pylint: disable=W0143
                and self.price == _other.price
                and self.remaining_size == _other.remaining_size
            )
        else:
            raise InvalidTypeError(type(other), "other")

        return return_val

    @reconstructor
    def init_on_load(self) -> None:
        """Summary
        """
        self._set_typed_product_info()
        self._set_typed_price()
        self._set_typed_side()

    @property
    def order_id(self) -> OrderId:
        """
        order_id [summary]

        Returns:
            OrderId: [description]
        """
        return OrderId(self._order_id)

    @order_id.setter  # type: ignore
    def order_id(self, value: Union[str, OrderId]) -> None:
        self._order_id = value

    @property
    def remaining_size(self) -> ProductVolume:
        """Summary

        Returns:
            ProductVolume: Description
        """
        return self._product_volume_type(self._remaining_size)

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
