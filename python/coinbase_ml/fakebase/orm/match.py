"""
CoinbaseMatch orm
"""
from __future__ import annotations
from datetime import datetime
from typing import Any, Dict, Optional

from dateutil import parser
from sqlalchemy import BigInteger, Column, String
from sqlalchemy.orm import reconstructor

from coinbase_ml.common import constants as c
from coinbase_ml.fakebase.protos.events_pb2 import Match
from coinbase_ml.fakebase.orm.mixins import Base, MatchOrderEvent
from coinbase_ml.fakebase.types import (
    Liquidity,
    OrderId,
    OrderSide,
    ProductId,
    ProductPrice,
    ProductVolume,
    QuoteVolume,
)


class CoinbaseMatch(MatchOrderEvent, Base):  # pylint: disable=R0903

    """Model for storing Coinbase Matches
    """

    __tablename__ = "coinbase_matches"
    _maker_order_id = Column("maker_order_id", String)
    _taker_order_id = Column("taker_order_id", String)
    sequence = Column(BigInteger)
    trade_id = Column(BigInteger)

    def __init__(
        self,
        product_id: ProductId,
        maker_order_id: OrderId,
        taker_order_id: OrderId,
        trade_id: int,
        side: OrderSide,
        time: datetime,
        liquidity: Optional[Liquidity] = None,
        price: Optional[ProductPrice] = None,
        size: Optional[ProductVolume] = None,
        **kwargs: Any,
    ) -> None:
        """
        __init__ [summary]

        Args:
            product_id (ProductId): [description]
            maker_order_id (OrderId): [description]
            taker_order_id (OrderId): [description]
            trade_id (int): [description]
            side (OrderSide): [description]
            time (datetime): [description]
            liquidity (Optional[Liquidity], optional): [description]. Defaults to None.
            price (Optional[ProductPrice], optional): [description]. Defaults to None.
            size (Optional[ProductVolume], optional): [description]. Defaults to None.
        """
        MatchOrderEvent.__init__(
            self,
            product_id=product_id,
            side=side,
            time=time,
            price=price,
            size=size,
            **kwargs,
        )

        Base.__init__(  # pylint: disable=non-parent-init-called
            self,
            product_id=product_id,
            side=side,
            time=time,
            price=price,
            size=size,
            **kwargs,
        )

        self._maker_order_id = maker_order_id
        self._taker_order_id = taker_order_id
        self.liquidity = liquidity
        self.trade_id = trade_id

    def __eq__(self, other: Any) -> bool:
        """
        __eq__ [summary]

        Args:
            other (Any): [description]

        Returns:
            bool: [description]
        """

        if isinstance(other, CoinbaseMatch):
            _other: CoinbaseMatch = other
            return_val = (
                self.maker_order_id == _other.maker_order_id
                and self.price == _other.price
                and self.product_id == _other.product_id  # pylint: disable=W0143
                and self.side == _other.side
                and self.taker_order_id == _other.taker_order_id
                and self.time == _other.time
                and self.trade_id == _other.trade_id
            )
        else:
            raise NotImplementedError

        return return_val

    @reconstructor
    def init_on_load(self) -> None:
        """Summary
        """
        self._set_typed_product_info()
        self._set_typed_price()
        self._set_typed_size()
        self._set_typed_side()

    @property
    def account_order_side(self) -> OrderSide:
        """'buy' if you placed a buy order. 'sell' if you placed a sell order.side
        Different from self.side, which indicates the side of the maker order.

        Returns:
            str: Description
        """
        return (
            self.side
            if self.liquidity == Liquidity.maker
            else self.side.get_opposite_side()
        )

    @property
    def fee(self) -> QuoteVolume:
        """
        fee [summary]

        Returns:
            QuoteVolume: [description]
        """
        fee_fraction = (
            Liquidity.taker.fee_fraction
            if self.liquidity == Liquidity.taker
            else Liquidity.maker.fee_fraction
        )

        return self.get_product_id().quote_volume_type(
            fee_fraction * self.usd_volume.amount
        )

    @staticmethod
    def from_proto(match: Match) -> CoinbaseMatch:
        """
        from_proto [summary]

        Args:
            match (Match): [description]

        Returns:
            CoinbaseMatch: [description]
        """
        return CoinbaseMatch(
            product_id=c.PRODUCT_ID,
            maker_order_id=OrderId(match.makerOrderId),
            taker_order_id=OrderId(match.takerOrderId),
            trade_id=match.tradeId,
            side=OrderSide.from_proto(match.side),
            time=parser.parse(match.time),
            liquidity=Liquidity.from_proto(match.liquidity),
            price=c.PRODUCT_ID.price_type(match.price),
            size=c.PRODUCT_ID.product_volume_type(match.size),
        )

    @property
    def maker_order_id(self) -> OrderId:
        """
        maker_order_id [summary]

        Returns:
            OrderId: [description]
        """
        return OrderId(self._maker_order_id)

    @property
    def taker_order_id(self) -> OrderId:
        """
        taker_order_id [summary]

        Returns:
            OrderId: [description]
        """
        return OrderId(self._taker_order_id)

    def to_fill_dict(self) -> Dict[str, Any]:
        """Summary

        Returns:
            Dict[str, Any]: Description
        """
        return dict(
            created_at=str(self.time),
            fee=str(self.fee),
            liquidity=self.liquidity,
            order_id=self.taker_order_id
            if self.liquidity == Liquidity.taker
            else self.maker_order_id,
            price=str(self.price),
            product_id=self.product_id,
            settled=True,
            side=self.account_order_side,
            size=str(self.size),
            trade_id=self.trade_id,
            usd_volume=self.usd_volume,
        )

    @property
    def usd_volume(self) -> QuoteVolume:
        """
        usd_volume [summary]

        Returns:
            QuoteVolume: [description]
        """
        return self.price * self.size
