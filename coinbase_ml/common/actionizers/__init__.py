"""
 [summary]
"""
from __future__ import annotations
from datetime import datetime, timedelta
from decimal import Decimal
import logging
from math import inf
from typing import Dict, Generic, Optional, TypeVar

import numpy as np
from funcy import compose, partial, rpartial

from fakebase.utils.currency_utils import (
    get_currency_min_value,
    round_to_currency_precision,
)
from fakebase.base_classes import AccountBase
from fakebase.orm import CoinbaseOrder
from fakebase.types import OrderSide, OrderStatus, OrderType

import coinbase_ml.common.action as action
from coinbase_ml.common import constants as c
from coinbase_ml.common.utils.preprocessing_utils import clamp_to_range, softmax

Account = TypeVar("Account", bound=AccountBase)


class Actionizer(Generic[Account]):
    """
    Actionizer [summary]
    """

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
    def _available_product(self) -> Decimal:
        """
        _available_product [summary]

        Returns:
            Decimal: [description]
        """
        return Decimal(
            self._funds[c.PRODUCT_CURRENCY].balance
            - self._funds[c.PRODUCT_CURRENCY].holds
        )

    @property
    def _available_quote(self) -> Decimal:
        """
        _available_quote [summary]

        Returns:
            Decimal: [description]
        """
        return Decimal(
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
    def price(self) -> Decimal:
        """
        price [summary]

        Raises:
            AttributeError: [description]

        Returns:
            Decimal: [description]
        """
        if self.order_side is None:
            raise AttributeError

        min_value = get_currency_min_value(c.QUOTE_CURRENCY)

        return compose(
            partial(round_to_currency_precision, c.QUOTE_CURRENCY),
            Decimal,
            str,
            rpartial(clamp_to_range, min_value, inf),
            lambda price: c.MAX_PRICE * price,
            rpartial(clamp_to_range, 0.0, 1.0),
        )(self._normalized_price)

    @property
    def size(self) -> Decimal:
        """
        size [summary]

        Raises:
            AttributeError: [description]

        Returns:
            Decimal: [description]
        """
        if self.order_side is None:
            raise AttributeError

        transaction_size = (
            self._available_quote * (1 - c.BUY_RESERVE_FRACTION) / self.price
            if self.order_side == OrderSide.buy
            else self._available_product
        )

        return round_to_currency_precision(c.PRODUCT_CURRENCY, transaction_size)
