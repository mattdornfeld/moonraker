"""
 [summary]
"""
from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal
import logging
from math import inf
from typing import ClassVar, Generic, Optional, TypeVar

import numpy as np
from funcy import compose, partial, rpartial

from fakebase.utils.currency_utils import (
    get_currency_min_value,
    round_to_currency_precision,
)
from fakebase.base_classes import AccountBase
from fakebase.orm import CoinbaseOrder
from fakebase.types import OrderSide

from coinbase_ml.common import constants as c
from coinbase_ml.common.utils.preprocessing_utils import clamp_to_range, softmax

LOGGER = logging.getLogger(__name__)

Account = TypeVar("Account", bound=AccountBase)


@dataclass
class Action:
    """
    Action [summary]
    """

    order_side: ClassVar[Optional[str]] = None
    price: Optional[Decimal] = None
    size: Optional[Decimal] = None
    time_in_force: Optional[str] = "GTC"
    time_to_live: Optional[timedelta] = c.ORDER_TIME_TO_LIVE

    def __repr__(self) -> str:
        """
        __repr__ [summary]

        Returns:
            str: [description]
        """
        return (
            f"<{self.__class__.__name__} price: {self.price} size: {self.size} "
            f"time_in_force: {self.time_in_force} cancel_after: {self.time_to_live}>"
        )


class NoTransaction(Action):
    """
    NoTransaction [summary]
    """

    def __repr__(self) -> str:
        return "<NoTransaction>"


class PlaceBuyOrder(Action):
    """
    PlaceBuyOrder [summary]
    """

    order_side = OrderSide.buy


class PlaceSellOrder(Action):
    """
    PlaceSellOrder [summary]
    """

    order_side = OrderSide.sell


class ActionExecutor(Generic[Account]):
    """
     [summary]
    """

    def __init__(self, account: Account):
        """
        __init__ [summary]

        Args:
            account (Account): [description]
        """
        self.account = account

    @staticmethod
    def _calc_transaction_price(normalized_transaction_price: float) -> Decimal:
        """
        _calc_transaction_price [summary]

        Args:
            normalized_transaction_price (float): [description]

        Returns:
            Decimal: [description]
        """
        min_value = get_currency_min_value(c.QUOTE_CURRENCY)

        return compose(
            partial(round_to_currency_precision, c.QUOTE_CURRENCY),
            Decimal,
            str,
            rpartial(clamp_to_range, min_value, inf),
            lambda price: c.MAX_PRICE * price,
            rpartial(clamp_to_range, 0.0, 1.0),
        )(normalized_transaction_price)

    @staticmethod
    def _calc_transaction_size(
        available_product: Decimal,
        available_quote: Decimal,
        is_buy: bool,
        transaction_price: Decimal,
    ) -> Decimal:
        """
        _calc_transaction_size [summary]

        Args:
            available_product (Decimal): available product currency
            available_quote (Decimal): available quote currency
            is_buy (bool): [description]
            transaction_price (Decimal): [description]

        Returns:
            Decimal: [description]
        """
        transaction_size = (
            available_quote * (1 - c.BUY_RESERVE_FRACTION) / transaction_price
            if is_buy
            else available_product
        )

        return round_to_currency_precision(c.PRODUCT_CURRENCY, transaction_size)

    def cancel_expired_orders(self, current_dt: datetime) -> None:
        """
        cancel_expired_orders cancels open orders with a
        order.time + order.time_to_live > current_dt

        Args:
            current_dt (datetime): [description]
        """
        # Call list on the below dict so that this function does
        # not change the size of the dict during iteration
        for order in list(self.account.orders.values()):
            if order.order_status != "open":
                continue

            deadline = (
                datetime.max
                if order.time_to_live == timedelta.max
                else order.time + order.time_to_live
            )

            if current_dt >= deadline:
                self.account.cancel_order(order.order_id)

    def translate_model_prediction_to_action(self, prediction: np.ndarray) -> Action:
        """
        translate_model_prediction_to_action [summary]

        Args:
            prediction (np.ndarray): [description]

        Returns:
            Action: [description]
        """
        (
            transaction_buy,
            transaction_none,
            normalized_price,
            transaction_sell,
        ) = prediction

        is_buy, no_transaction, _ = softmax(
            [transaction_buy, transaction_none, transaction_sell]
        )
        if no_transaction:
            return NoTransaction()

        transaction_price = self._calc_transaction_price(normalized_price)

        funds = self.account.funds
        available_product = Decimal(
            funds[c.PRODUCT_CURRENCY].balance - funds[c.PRODUCT_CURRENCY].holds
        )
        available_quote = Decimal(
            funds[c.QUOTE_CURRENCY].balance - funds[c.QUOTE_CURRENCY].holds
        )
        transaction_size = self._calc_transaction_size(
            available_product, available_quote, is_buy, transaction_price
        )

        return (
            PlaceBuyOrder(price=transaction_price, size=transaction_size)
            if is_buy
            else PlaceSellOrder(price=transaction_price, size=transaction_size)
        )

    def act(self, action: Action) -> Optional[CoinbaseOrder]:
        """
        act [summary]

        Args:
            action (Action): [description]

        Returns:
            Optional[CoinbaseOrder]: [description]
        """
        if isinstance(action, NoTransaction):
            if c.VERBOSE:
                LOGGER.info("No Transaction Action received")
            return_val = None

        else:
            if c.VERBOSE:
                LOGGER.info("Placing %s order: %s", action.order_side, action)
            return_val = self.account.place_limit_order(
                product_id=c.PRODUCT_ID,
                price=action.price,
                side=action.order_side,
                size=action.size,
                time_in_force=action.time_in_force,
                time_to_live=action.time_to_live,
            )

        return return_val
