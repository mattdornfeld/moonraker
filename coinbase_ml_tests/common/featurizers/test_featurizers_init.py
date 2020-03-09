"""
Tests for Featurizer
"""
from typing import Dict, Tuple, cast

import numpy as np
import pytest
from pytest_cases import fixture_ref, pytest_fixture_plus, pytest_parametrize_plus

from coinbase_ml.common import constants as c
from coinbase_ml.common.action import Action, ActionBase, NoTransaction
from coinbase_ml.common.actionizers import Actionizer
from coinbase_ml.common.featurizers import AccountFeaturizer
from coinbase_ml.common.featurizers import Featurizer, Metrics
from coinbase_ml.common.reward import LogReturnRewardStrategy
from coinbase_ml.fakebase.account import Account
from coinbase_ml.fakebase.exchange import Exchange
from coinbase_ml.fakebase.orm import CoinbaseCancellation, CoinbaseOrder, CoinbaseMatch
from coinbase_ml.fakebase.types.currency import Currency
from ... import constants as tc
from ...fixtures import create_exchange  # pylint: disable=unused-import

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


class TestFeaturizer:
    """
    TestFeaturizer
    """

    @staticmethod
    def test_account_funds_feature(
        account: Account,
        featurizer: Featurizer[Exchange],
        no_transaction: NoTransaction,
    ) -> None:
        """
        test_account_funds_feature [summary]

        Args:
            account (Account): [description]
            featurizer (Featurizer[Exchange]): [description]
            no_transaction (NoTransaction): [description]
        """
        place_orders_and_update(account, featurizer, no_transaction)

        expected_accound_funds = (
            np.array(
                [
                    tc.TEST_WALLET_QUOTE_FUNDS.amount,
                    (BUY_ORDER_PRICE * tc.TEST_ORDER_SIZE).amount,
                    tc.TEST_WALLET_PRODUCT_FUNDS.amount,
                    tc.TEST_ORDER_SIZE.amount,
                ]
            )
            .astype(float)
            .reshape(1, 4)
        )
        observation = featurizer.get_observation()

        np.testing.assert_almost_equal(
            expected_accound_funds / AccountFeaturizer.NORMALIZER_ARRAY,
            observation.account_funds,
            10,
        )

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

    @staticmethod
    def test_order_book_feature(
        account: Account,
        featurizer: Featurizer[Exchange],
        no_transaction: NoTransaction,
    ) -> None:
        """
        test_order_book_feature [summary]

        Args:
            account (Account): [description]
            featurizer (Featurizer[Exchange]): [description]
            no_transaction (NoTransaction): [description]
        """
        buy_order, _ = place_orders_and_update(account, featurizer, no_transaction)
        observation = featurizer.get_observation()

        expected_order_book = np.zeros((1, 4 * c.ORDER_BOOK_DEPTH))
        expected_order_book[0, 0] = (
            float(tc.TEST_ORDER_PRICE.amount) / c.PRICE_NORMALIZER
        )
        expected_order_book[0, 1] = float(tc.TEST_ORDER_SIZE.amount) / c.SIZE_NORMALIZER
        expected_order_book[0, 2] = float(BUY_ORDER_PRICE.amount) / c.PRICE_NORMALIZER
        expected_order_book[0, 3] = float(tc.TEST_ORDER_SIZE.amount) / c.SIZE_NORMALIZER

        np.testing.assert_almost_equal(expected_order_book, observation.order_book, 10)

        account.cancel_order(buy_order.order_id)
        account.exchange.step()
        featurizer.update_state_buffer(no_transaction)
        observation = featurizer.get_observation()

        expected_order_book = np.vstack(
            (expected_order_book, np.zeros((1, 4 * c.ORDER_BOOK_DEPTH)))
        )
        expected_order_book[1, 0] = (
            float(tc.TEST_ORDER_PRICE.amount) / c.PRICE_NORMALIZER
        )
        expected_order_book[1, 1] = float(tc.TEST_ORDER_SIZE.amount) / c.SIZE_NORMALIZER

        np.testing.assert_almost_equal(expected_order_book, observation.order_book, 10)

        account.place_limit_order(
            product_id=tc.PRODUCT_ID,
            price=tc.TEST_ORDER_PRICE / 0.5,
            side=tc.TEST_ORDER_SIDE.get_opposite_side(),
            size=tc.TEST_ORDER_SIZE,
        )

        place_orders_and_update(account, featurizer, no_transaction)

        expected_order_book = np.vstack(
            (expected_order_book, np.zeros((1, 4 * c.ORDER_BOOK_DEPTH)))
        )
        expected_order_book[2, 0] = (
            float(tc.TEST_ORDER_PRICE.amount) / c.PRICE_NORMALIZER
        )
        expected_order_book[2, 1] = (
            float(tc.TEST_ORDER_SIZE.amount) / c.SIZE_NORMALIZER / 0.5
        )
        expected_order_book[2, 2] = float(BUY_ORDER_PRICE.amount) / c.PRICE_NORMALIZER
        expected_order_book[2, 3] = float(tc.TEST_ORDER_SIZE.amount) / c.SIZE_NORMALIZER
        expected_order_book[2, 4] = (
            float(tc.TEST_ORDER_PRICE.amount) / c.PRICE_NORMALIZER / 0.5
        )
        expected_order_book[2, 5] = float(tc.TEST_ORDER_SIZE.amount) / c.SIZE_NORMALIZER

        observation = featurizer.get_observation()
        np.testing.assert_almost_equal(expected_order_book, observation.order_book, 10)

    @staticmethod
    def test_time_series_cancellation_features(
        account: Account,
        featurizer: Featurizer[Exchange],
        no_transaction: NoTransaction,
    ) -> None:
        """
        test_time_series_cancellation_features [summary]

        Args:
            account (Account): [description]
            featurizer (Featurizer[Exchange]): [description]
            no_transaction (NoTransaction): [description]
        """
        place_orders_and_update(account, featurizer, no_transaction)
        cancellation = CoinbaseCancellation(
            price=tc.TEST_ORDER_PRICE,
            product_id=tc.PRODUCT_ID,
            order_id=tc.TEST_ORDER_ID,
            remaining_size=tc.TEST_ORDER_SIZE,
            time=tc.TEST_ORDER_TIME,
            side=tc.TEST_ORDER_SIDE,
        )

        account.exchange.step()
        account.exchange.received_cancellations.append(cancellation)
        featurizer.update_state_buffer(no_transaction)

        expected_cancellation_features = np.array(
            [0.9428090416, 0.9428090416, 0.0, 0.0, 0.0, 0.0]
        )
        cancellation_features = featurizer.get_observation().time_series[1, :6]
        np.testing.assert_almost_equal(
            expected_cancellation_features, cancellation_features, 10
        )

    @staticmethod
    def test_time_series_order_features(
        account: Account,
        featurizer: Featurizer[Exchange],
        no_transaction: NoTransaction,
    ) -> None:
        """
        test_time_series_order_features [summary]

        Args:
            account (Account): [description]
            featurizer (Featurizer[Exchange]): [description]
            no_transaction (NoTransaction): [description]
        """
        place_orders_and_update(account, featurizer, no_transaction)

        expected_order_features = np.array(
            [
                5.0e11,
                5.0e11,
                0.0e00,
                5.0e11,
                0.0e00,
                5.0e11,
                5.0e15,
                0.0e00,
                5.0e11,
                0.0e00,
            ]
        )
        order_features = featurizer.get_observation().time_series[0, 16:26]
        np.testing.assert_almost_equal(expected_order_features, order_features, 10)

    @staticmethod
    def test_time_series_match_features(
        account: Account,
        featurizer: Featurizer[Exchange],
        no_transaction: NoTransaction,
    ) -> None:
        """
        test_time_series_match_features [summary]

        Args:
            account (Account): [description]
            featurizer (Featurizer[Exchange]): [description]
            no_transaction (NoTransaction): [description]
        """
        place_orders_and_update(account, featurizer, no_transaction)

        expected_match_features = np.array(
            [
                0.0e00,
                0.0e00,
                0.0e00,
                0.0e00,
                0.0e00,
                5.0e11,
                5.0e15,
                0.0e00,
                5.0e11,
                0.0e00,
            ]
        )
        match_features = featurizer.get_observation().time_series[0, 6:16]
        np.testing.assert_almost_equal(expected_match_features, match_features, 10)
