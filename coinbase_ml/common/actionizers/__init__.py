"""
 [summary]
"""
from __future__ import annotations
from datetime import datetime, timedelta
from decimal import Decimal
import logging
from math import inf
from typing import Callable, Dict, Generic, Optional, TypeVar

import numpy as np
from funcy import compose, partial, rpartial

import coinbase_ml.common.action as action
from coinbase_ml.common import constants as c
from coinbase_ml.common.utils.preprocessing_utils import clamp_to_range, softmax
from coinbase_ml.fakebase.base_classes import AccountBase
from coinbase_ml.fakebase.orm import CoinbaseOrder
from coinbase_ml.fakebase.types import (
    OrderSide,
    OrderStatus,
    OrderType,
    ProductPrice,
    ProductVolume,
    QuoteVolume,
)

Account = TypeVar("Account", bound=AccountBase)


class Actionizer(Generic[Account]):
    """
    Actionizer [summary]
    """

    BUY_RESERVE_FRACTION = Decimal("0.005")
    MAX_PRICE = 13000.00

    def __init__(
        self, account: Account, actor_prediction: Optional[np.ndarray] = None
    ) -> None:
        """
        Actionizer [summary]

        Args:
            Generic ([Account]): [description]
            account (Account): [description]
            actor_prediction (Optional[np.ndarray], optional): [description]. Defaults to None.
        """
        if actor_prediction is None:
            # this counts as a NoTransaction
            self._is_buy = False
            self._is_sell = False
            self._normalized_price = None
        else:
            (
                transaction_buy,
                transaction_none,
                normalized_price,
                transaction_sell,
            ) = actor_prediction

            self._is_buy, _, self._is_sell = softmax(
                [transaction_buy, transaction_none, transaction_sell]
            )

            self._normalized_price = normalized_price

        self._actor_prediction = actor_prediction
        self.account = account
        self._funds = self.account.funds

    def __deepcopy__(self, memo: Dict[str, object]) -> Actionizer:
        """
        __deepcopy__ [summary]

        Args:
            memo ([type]): [description]

        Returns:
            Actionizer: [description]
        """
        return Actionizer(self.account, self._actor_prediction)

    @property
    def _available_product(self) -> ProductVolume:
        """
        _available_product [summary]

        Returns:
            ProductVolume: [description]
        """
        return (
            self._funds[c.PRODUCT_CURRENCY].balance
            - self._funds[c.PRODUCT_CURRENCY].holds
        )

    @property
    def _available_quote(self) -> QuoteVolume:
        """
        _available_quote [summary]

        Returns:
            QuoteVolume: [description]
        """
        return (
            self._funds[c.QUOTE_CURRENCY].balance - self._funds[c.QUOTE_CURRENCY].holds
        )

    def get_action(self) -> action.ActionBase:
        """
        get_action [summary]

        Returns:
            action.ActionBase: [description]
        """
        return action.Action(self) if self.order_side else action.NoTransaction(self)

    @property
    def order_side(self) -> Optional[OrderSide]:
        """
        order_side [summary]

        Returns:
            Optional[OrderSide]: [description]
        """
        if self._is_buy:
            order_side = OrderSide.buy
        elif self._is_sell:
            order_side = OrderSide.sell
        else:
            order_side = None

        return order_side

    @property
    def order_type(self) -> OrderType:
        """
        order_type [summary]

        Returns:
            OrderType: [description]
        """
        return OrderType.limit

    @property
    def price(self) -> ProductPrice:
        """
        price [summary]

        Raises:
            AttributeError: [description]

        Returns:
            ProductPrice: [description]
        """
        if self.order_side is None:
            raise AttributeError

        min_value = float(c.PRODUCT_ID.min_price.amount)
        clamp_normalized_price_to_range: Callable[[float], float] = rpartial(
            clamp_to_range, 0.0, 1.0
        )
        clamp_price_to_range: Callable[[float], float] = rpartial(
            clamp_to_range, min_value, inf
        )
        denormalize_price: Callable[
            [float], float
        ] = lambda price: self.MAX_PRICE * price

        return compose(
            c.PRODUCT_ID.price_type,
            str,
            clamp_price_to_range,
            denormalize_price,
            clamp_normalized_price_to_range,
        )(self._normalized_price)

    @property
    def size(self) -> ProductVolume:
        """
        size [summary]

        Raises:
            AttributeError: [description]

        Returns:
            ProductVolume: [description]
        """
        if self.order_side is None:
            raise AttributeError

        if self.order_side == OrderSide.buy:
            transaction_size = c.PRODUCT_ID.product_volume_type(
                self._available_quote.amount
                * (1 - self.BUY_RESERVE_FRACTION)
                / self.price.amount
            )  # quote * product / quote = product
        else:
            transaction_size = self._available_product

        return transaction_size
