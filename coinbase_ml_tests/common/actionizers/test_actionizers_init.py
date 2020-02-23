"""
Module for testing the coinbase_ml/common/actionizers module
"""
from decimal import Decimal
from typing import Tuple, Optional

import numpy as np

from fakebase_tests.test_exchange_unit import create_exchange  # pylint: disable=W0611
from fakebase.account import Account
from fakebase.exchange import Exchange
from fakebase.types import OrderSide

import pytest

from coinbase_ml.common import constants as cc
from coinbase_ml.common.action import Action, NoTransaction
from coinbase_ml.common.actionizers import Actionizer


NORMALIZED_ORDER_PRICE = 0.9


def order_side_to_float(order_side: OrderSide) -> Tuple[float, float, float]:
    """
    order_side_to_float [summary]

    Args:
        order_side (OrderSide): [description]

    Returns:
        Tuple[float, float, float]: [description]
    """
    if order_side == OrderSide.buy:
        return_val = (1.0, 0.0, 0.0)
    elif order_side == OrderSide.sell:
        return_val = (0.0, 0.0, 1.0)
    else:
        return_val = (0.0, 1.0, 0.0)

    return return_val


def create_actionizer(account: Account, order_side: OrderSide) -> Actionizer:
    """
    create_actionizer [summary]

    Args:
        account (Account): [description]
        order_side (OrderSide): [description]

    Returns:
        Actionizer: [description]
    """
    is_buy, is_none, is_sell = order_side_to_float(order_side)
    actor_prediction = np.array([is_buy, is_none, NORMALIZED_ORDER_PRICE, is_sell])
    actionizer = Actionizer(account, actor_prediction)

    return actionizer


class TestActionizer:
    """
     [summary]
    """

    @staticmethod
    @pytest.mark.parametrize("order_side", [OrderSide.buy, OrderSide.sell, None])
    def test_get_action(
        create_exchange: Tuple[
            Account, Exchange
        ],  # pylint: disable=redefined-outer-name
        order_side: Optional[OrderSide],
    ) -> None:
        """
        test_get_action [summary]

        Args:
            create_exchange (Tuple[Account, Exchange]): [description]
            order_side (Optional[OrderSide]): [description]
        """
        account, _ = create_exchange
        actionizer = create_actionizer(account, order_side)
        action = actionizer.get_action()

        if isinstance(action, Action):
            expected_size = {
                OrderSide.buy: Decimal("8.50427350"),
                OrderSide.sell: Decimal("10.0"),
            }[order_side]

            assert action.price == Decimal(str(NORMALIZED_ORDER_PRICE * cc.MAX_PRICE))
            assert action.time_to_live == cc.ORDER_TIME_TO_LIVE
            assert action.order_side == order_side
            assert action.size == expected_size
        else:
            assert isinstance(action, NoTransaction)

    @staticmethod
    @pytest.mark.parametrize("order_side", [OrderSide.buy, OrderSide.sell, None])
    def test_execute_action(
        create_exchange: Tuple[
            Account, Exchange
        ],  # pylint: disable=redefined-outer-name
        order_side: Optional[OrderSide],
    ) -> None:
        """
        test_execute_action [summary]

        Args:
            create_exchange (Tuple[Account, Exchange]): [description]
            order_side (Optional[OrderSide]): [description]
        """
        account, _ = create_exchange
        actionizer = create_actionizer(account, order_side)
        action = actionizer.get_action()
        order = action.execute()

        if isinstance(action, NoTransaction):
            assert not account.orders
        else:
            assert order.order_id in account.orders
