"""
 module for testing InfoDictFeaturizer
"""
from typing import Dict

import numpy as np
import pytest
from pytest_cases import fixture_ref, pytest_parametrize_plus, unpack_fixture

from coinbase_ml.common.action import ActionBase, NoTransaction
from coinbase_ml.common.featurizers import Featurizer, Metrics
from coinbase_ml.fakebase.account import Account
from coinbase_ml.fakebase.exchange import Exchange
from coinbase_ml.fakebase.orm import CoinbaseOrder
from coinbase_ml.fakebase.types import Currency, OrderSide, OrderStatus
from coinbase_ml_tests import constants as tc
from coinbase_ml_tests.common.featurizers.fixtures import (
    create_featurizer,
    place_orders_and_update,
)
from coinbase_ml_tests.fixtures import (  # pylint: disable = unused-import
    create_exchange,
)

# pylint: disable=invalid-name,unbalanced-tuple-unpacking
(
    account,
    featurizer,
    no_transaction,
    buy_transaction,
    sell_transaction,
) = unpack_fixture(
    "account, featurizer, no_transaction, buy_transaction, sell_transaction",
    create_featurizer,
)
# pylint: enable=invalid-name,unbalanced-tuple-unpacking

# pylint: disable=redefined-outer-name
class TestInfoDictFeaturizer:
    """
     [summary]
    """

    @staticmethod
    @pytest.mark.parametrize("order_side", [OrderSide.buy, OrderSide.sell])
    def test_get_info_dict_fees_and_volume(
        account: Account,
        featurizer: Featurizer[Exchange],
        no_transaction: NoTransaction,
        order_side: OrderSide,
    ) -> None:
        """
        test_get_info_dict_fees_and_volume [summary]

        Args:
            account (Account): [description]
            featurizer (Featurizer[Exchange]): [description]
            no_transaction (NoTransaction): [description]
            order_side (OrderSide): [description]
        """
        order = CoinbaseOrder(
            order_id=tc.TEST_ORDER_ID,
            order_status=OrderStatus.received,
            order_type=tc.TEST_ORDER_TYPE,
            product_id=tc.PRODUCT_ID,
            price=tc.TEST_ORDER_PRICE
            if order_side == OrderSide.buy
            else tc.TEST_PRICE_TYPE.get_min_value(),
            side=order_side,
            size=tc.TEST_PRODUCT_TYPE.get_min_value(),
            time=account.exchange.interval_start_dt,
        )

        account.exchange.step(insert_orders=[order])
        place_orders_and_update(account, featurizer, no_transaction)
        step_interval = account.exchange.step_interval
        match = account.matches[step_interval][0]

        fee_metric = {
            OrderSide.buy: Metrics.BUY_FEES_PAID,
            OrderSide.sell: Metrics.SELL_FEES_PAID,
        }[order_side]

        volume_metric = {
            OrderSide.buy: Metrics.BUY_VOLUME_TRADED,
            OrderSide.sell: Metrics.SELL_VOLUME_TRADED,
        }[order_side]

        pytest.approx(float(match.fee.amount), featurizer.get_info_dict()[fee_metric])
        pytest.approx(
            float(match.usd_volume.amount), featurizer.get_info_dict()[volume_metric]
        )

        assert account.matches

    @staticmethod
    def test_get_info_dict_portfolio_value(
        account: Account,
        featurizer: Featurizer[Exchange],
        no_transaction: NoTransaction,
    ) -> None:
        """
        test_get_info_dict_portfolio_value [summary]

        Args:
            account (Account): [description]
            featurizer (Featurizer[Exchange]): [description]
            no_transaction (NoTransaction): [description]
        """
        buy_order, sell_order = place_orders_and_update(
            account, featurizer, no_transaction
        )

        mid_price = (buy_order.price + sell_order.price) / 2
        usd_funds = account.funds[Currency.USD].balance
        btc_funds = account.funds[Currency.BTC].balance
        portfolio_value = usd_funds + mid_price * btc_funds

        assert pytest.approx(
            float(portfolio_value.amount),
            featurizer.get_info_dict()[Metrics.PORTFOLIO_VALUE],
        )

    @staticmethod
    @pytest_parametrize_plus(
        "transaction, expected_num_transactions",
        [
            (
                fixture_ref("buy_transaction"),
                {
                    Metrics.NUM_BUY_ORDERS_PLACED: 1.0,
                    Metrics.NUM_SELL_ORDERS_PLACED: 0.0,
                    Metrics.NUM_NO_TRANSACTIONS: 0.0,
                },
            ),
            (
                fixture_ref("sell_transaction"),
                {
                    Metrics.NUM_BUY_ORDERS_PLACED: 0.0,
                    Metrics.NUM_SELL_ORDERS_PLACED: 1.0,
                    Metrics.NUM_NO_TRANSACTIONS: 0.0,
                },
            ),
            (
                fixture_ref("no_transaction"),
                {
                    Metrics.NUM_BUY_ORDERS_PLACED: 0.0,
                    Metrics.NUM_SELL_ORDERS_PLACED: 0.0,
                    Metrics.NUM_NO_TRANSACTIONS: 1.0,
                },
            ),
        ],
    )
    def test_get_info_dict_num_orders_placed(
        account: Account,
        expected_num_transactions: Dict[Metrics, float],
        featurizer: Featurizer[Exchange],
        transaction: ActionBase,
    ) -> None:
        """
        test_get_info_dict_num_orders_placed [summary]

        Args:
            account (Account): [description]
            expected_num_transactions (Dict[str, float]): [description]
            featurizer (Featurizer[Exchange]): [description]
            transaction (ActionBase): [description]
        """
        place_orders_and_update(account, featurizer, transaction)

        assert expected_num_transactions.items() <= featurizer.get_info_dict().items()

    @staticmethod
    def test_get_info_dict_roi(
        account: Account,
        featurizer: Featurizer[Exchange],
        no_transaction: NoTransaction,
    ) -> None:
        """
        test_get_info_dict_roi [summary]

        Args:
            account (Account): [description]
            featurizer (Featurizer[Exchange]): [description]
            no_transaction (NoTransaction): [description]
        """
        place_orders_and_update(account, featurizer, no_transaction)

        old_portfolio_value = featurizer.get_info_dict()[Metrics.PORTFOLIO_VALUE]
        account.funds[
            tc.QUOTE_CURRENCY
        ].balance = tc.PRODUCT_ID.quote_volume_type.get_zero_volume()
        account.funds[
            tc.QUOTE_CURRENCY
        ].holds = tc.PRODUCT_ID.quote_volume_type.get_zero_volume()
        account.exchange.step()
        featurizer.update_state_buffer(no_transaction)

        portfolio_value = featurizer.get_info_dict()[Metrics.PORTFOLIO_VALUE]
        expected_roi = (portfolio_value - old_portfolio_value) / old_portfolio_value
        actual_roi = featurizer.get_info_dict()[Metrics.ROI]

        np.testing.assert_almost_equal(expected_roi, actual_roi)
