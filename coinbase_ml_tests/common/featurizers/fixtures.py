"""
 [summary]
"""
from typing import Tuple, cast

import numpy as np
from pytest_cases import pytest_fixture_plus

from coinbase_ml.common.action import Action, ActionBase, NoTransaction
from coinbase_ml.common.actionizers import Actionizer
from coinbase_ml.common.featurizers import Featurizer
from coinbase_ml.common.reward import LogReturnRewardStrategy
from coinbase_ml.fakebase.account import Account
from coinbase_ml.fakebase.exchange import Exchange
from coinbase_ml.fakebase.orm import CoinbaseOrder, CoinbaseMatch
from coinbase_ml_tests import constants as tc

BUY_ORDER_PRICE = tc.PRODUCT_ID.price_type("1.00")


@pytest_fixture_plus(
    unpack_into="account, featurizer, no_transaction, buy_transaction, sell_transaction"
)
def create_featurizer(
    create_exchange: Tuple[Account, Exchange]  # pylint: disable=W0621
) -> Tuple[Account, Featurizer, NoTransaction, Action, Action]:
    """
    create_featurizer [summary]

    Args:
        create_exchange (Tuple[Account, Exchange], optional): [description]. Defaults to W0621.

    Returns:
        Tuple[Account, Featurizer, NoTransaction]: [description]
    """
    account, exchange = create_exchange
    no_transaction = cast(NoTransaction, Actionizer[Account](account).get_action())
    buy_actionizer = Actionizer[Account](account, np.array([1.0, 0.0, 0.1, 0.0]))
    sell_actionizer = Actionizer[Account](account, np.array([0.0, 0.0, 0.1, 1.0]))
    buy_transaction = cast(Action, buy_actionizer.get_action())
    sell_transaction = cast(Action, sell_actionizer.get_action())

    return (
        account,
        Featurizer[Exchange](exchange, LogReturnRewardStrategy, 3),
        no_transaction,
        buy_transaction,
        sell_transaction,
    )


def place_orders_and_update(
    account: Account, featurizer: Featurizer, action: ActionBase
) -> Tuple[CoinbaseOrder, CoinbaseOrder]:
    """
    place_orders_and_update [summary]

    Args:
        account (Account): [description]
        featurizer (Featurizer): [description]
        action (NoTransaction): [description]

    Returns:
        Tuple[CoinbaseOrder, CoinbaseOrder]: [description]
    """
    buy_order = account.place_limit_order(
        product_id=tc.PRODUCT_ID,
        price=BUY_ORDER_PRICE,
        side=tc.TEST_ORDER_SIDE,
        size=tc.TEST_ORDER_SIZE,
    )

    sell_order = account.place_limit_order(
        product_id=tc.PRODUCT_ID,
        price=tc.TEST_ORDER_PRICE,
        side=tc.TEST_ORDER_SIDE.get_opposite_side(),
        size=tc.TEST_ORDER_SIZE,
    )

    account.exchange.step()

    match = CoinbaseMatch(
        price=tc.TEST_ORDER_PRICE,
        product_id=tc.PRODUCT_ID,
        maker_order_id=tc.TEST_ORDER_ID,
        taker_order_id=tc.TEST_ORDER_ID,
        trade_id=tc.TEST_TRADE_ID,
        side=tc.TEST_ORDER_SIDE.get_opposite_side(),
        size=tc.TEST_ORDER_SIZE,
        time=tc.TEST_ORDER_TIME,
    )

    account.exchange.matches.append(match)

    featurizer.update_state_buffer(action)

    return buy_order, sell_order
