"""
Module for executing actions through an Account
"""
from __future__ import annotations
from datetime import datetime, timedelta
import logging
from typing import Any, Optional, TypeVar

from coinbase_ml.fakebase.base_classes import AccountBase
from coinbase_ml.fakebase.orm import CoinbaseOrder
from coinbase_ml.fakebase.types import OrderStatus

import coinbase_ml.common.actionizers as actionizers
from coinbase_ml.common import constants as c

LOGGER = logging.getLogger(__name__)

Account = TypeVar("Account", bound=AccountBase)


class ActionBase:
    """
    ActionBase is the base class for all actions
    """

    def __init__(self, actionizer: actionizers.Actionizer) -> None:
        """
        __init__ [summary]

        Args:
            actionizer (actionizers.Actionizer): [description]
        """
        self._actionizer = actionizer
        self.order_side = actionizer.order_side

    def __repr__(self) -> str:
        """
        __repr__ [summary]

        Raises:
            NotImplementedError: [description]

        Returns:
            str: [description]
        """
        raise NotImplementedError

    def cancel_expired_orders(self, current_dt: datetime) -> None:
        """
        cancel_expired_orders cancels open orders with a
        order.time + order.time_to_live > current_dt

        Args:
            current_dt (datetime): [description]
        """
        # Call list on the below dict so that this function does
        # not change the size of the dict during iteration
        # Todo: Add in ttl feature
        for order in list(self._actionizer.account.orders.values()):
            if order.order_status != OrderStatus.open:
                continue

            deadline = (
                datetime.max
                if order.time_to_live == timedelta.max
                else order.time + order.time_to_live
            )

            if current_dt >= deadline:
                self._actionizer.account.cancel_order(order.order_id)

    def execute(self) -> Optional[CoinbaseOrder]:
        """
        execute [summary]

        Raises:
            NotImplementedError: [description]

        Returns:
            Optional[CoinbaseOrder]: [description]
        """
        raise NotImplementedError


class Action(ActionBase):
    """
    Action [summary]
    """

    ORDER_TIME_TO_LIVE = timedelta(seconds=30)

    def __init__(self, actionizer: actionizers.Actionizer) -> None:
        super().__init__(actionizer)
        self.price = actionizer.price
        self.size = actionizer.size
        self.time_to_live = Action.ORDER_TIME_TO_LIVE
        self.time_in_force = "GTC"

    def __eq__(self, other: Any) -> bool:
        """
        __eq__ [summary]

        Args:
            other (Any): [description]

        Raises:
            TypeError: [description]

        Returns:
            bool: [description]
        """
        if isinstance(other, Action):
            return_val = (
                self.price == other.price
                and self.size == other.size
                and self.time_in_force == other.time_in_force
                and self.time_to_live == other.time_to_live
            )
        else:
            raise TypeError

        return return_val

    def __repr__(self) -> str:
        """
        __repr__ [summary]

        Returns:
            str: [description]
        """
        return (
            f"<Action order_side: {self.order_side} price: {self.price} size: {self.size} "
            f"cancel_after: {self.time_to_live}>"
        )

    def execute(self) -> Optional[CoinbaseOrder]:
        """
        execute [summary]

        Returns:
            Optional[CoinbaseOrder]: [description]
        """
        if c.VERBOSE:
            LOGGER.info("Placing %s order: %s", self.order_side, self)

        return self._actionizer.account.place_limit_order(
            product_id=c.PRODUCT_ID,
            price=self.price,
            side=self.order_side,
            size=self.size,
            time_in_force=self.time_in_force,
            time_to_live=self.time_to_live,
        )


class NoTransaction(ActionBase):
    """
    NoTransaction [summary]
    """

    def __repr__(self) -> str:
        """
        __repr__ [summary]

        Returns:
            str: [description]
        """
        return "<NoTransaction>"

    def execute(self) -> Optional[CoinbaseOrder]:  # pylint: disable=useless-return
        """
        execute [summary]

        Returns:
            Optional[CoinbaseOrder]: [description]
        """
        if c.VERBOSE:
            LOGGER.info("Executing no transaction")

        return None
