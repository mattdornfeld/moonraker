"""
Tests for Featurizer
"""
from decimal import Decimal
from typing import Tuple

import numpy as np

from fakebase.account import Account
from fakebase.exchange import Exchange
from fakebase.orm import CoinbaseCancellation, CoinbaseOrder, CoinbaseMatch
from fakebase_tests import constants as ftc
from fakebase_tests.test_exchange_unit import create_exchange  # pylint: disable=W0611

from pytest_cases import pytest_fixture_plus

from coinbase_ml.common import constants as c
from coinbase_ml.common.featurizers import AccountFeaturizer
from coinbase_ml.common.featurizers import Featurizer
from coinbase_ml.common.reward import LogReturnRewardStrategy

BUY_ORDER_PRICE = Decimal("1.00")


@pytest_fixture_plus(unpack_into="account, featurizer")
def create_featurizer(
    create_exchange: Tuple[Account, Exchange]  # pylint: disable=W0621
) -> Tuple[Account, Featurizer]:
    """
    create_account_featurizer [summary]

    Args:
        create_exchange (Tuple[Account, Exchange]): [description]

    Returns:
        AccountFeaturizer: [description]
    """
    account, exchange = create_exchange

    return account, Featurizer[Exchange](exchange, LogReturnRewardStrategy, 3)


def place_orders_and_update(
    account: Account, featurizer: Featurizer
) -> Tuple[CoinbaseOrder, CoinbaseOrder]:
    """
    place_orders_and_update [summary]

    Args:
        account (Account): [description]
        featurizer (Featurizer): [description]

    Returns:
        Tuple[CoinbaseOrder, CoinbaseOrder]: [description]
    """
    buy_order = account.place_limit_order(
        product_id=ftc.PRODUCT_ID,
        price=BUY_ORDER_PRICE,
        side=ftc.TEST_ORDER_SIDE,
        size=ftc.TEST_ORDER_SIZE,
    )

    sell_order = account.place_limit_order(
        product_id=ftc.PRODUCT_ID,
        price=ftc.TEST_ORDER_PRICE,
        side=ftc.TEST_ORDER_SIDE.get_opposite_side(),
        size=ftc.TEST_ORDER_SIZE,
    )

    account.exchange.step()

    match = CoinbaseMatch(
        price=ftc.TEST_ORDER_PRICE,
        product_id=ftc.PRODUCT_ID,
        maker_order_id=ftc.TEST_ORDER_ID,
        taker_order_id=ftc.TEST_ORDER_ID,
        trade_id=ftc.TEST_TRADE_ID,
        side=ftc.TEST_ORDER_SIDE.get_opposite_side(),
        size=ftc.TEST_ORDER_SIZE,
        time=ftc.TEST_ORDER_TIME,
    )

    account.exchange.matches.append(match)

    featurizer.update_state_buffer()

    return buy_order, sell_order


class TestFeaturizer:
    """
    TestFeaturizer
    """

    @staticmethod
    def test_account_funds_feature(
        account: Account, featurizer: Featurizer[Exchange],
    ) -> None:
        """
        test_update_state_buffer [summary]

        Args:
            featurizer (Featurizer[Exchange]): [description]
        """
        place_orders_and_update(account, featurizer)

        expected_accound_funds = (
            np.array(
                [
                    ftc.TEST_WALLET_QUOTE_FUNDS,
                    BUY_ORDER_PRICE * ftc.TEST_ORDER_SIZE,
                    ftc.TEST_WALLET_PRODUCT_FUNDS,
                    ftc.TEST_ORDER_SIZE,
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
    def test_order_book_feature(
        account: Account, featurizer: Featurizer[Exchange],
    ) -> None:
        """
        test_order_book_feature [summary]

        Args:
            account (Account): [description]
            featurizer (Featurizer[Exchange]): [description]
        """
        buy_order, _ = place_orders_and_update(account, featurizer)
        observation = featurizer.get_observation()

        expected_order_book = np.zeros((1, 4 * c.ORDER_BOOK_DEPTH))
        expected_order_book[0, 0] = float(ftc.TEST_ORDER_PRICE) / c.PRICE_NORMALIZER
        expected_order_book[0, 1] = float(ftc.TEST_ORDER_SIZE) / c.SIZE_NORMALIZER
        expected_order_book[0, 2] = float(BUY_ORDER_PRICE) / c.PRICE_NORMALIZER
        expected_order_book[0, 3] = float(ftc.TEST_ORDER_SIZE) / c.SIZE_NORMALIZER

        np.testing.assert_almost_equal(expected_order_book, observation.order_book, 10)

        account.cancel_order(buy_order.order_id)
        account.exchange.step()
        featurizer.update_state_buffer()
        observation = featurizer.get_observation()

        expected_order_book = np.vstack(
            (expected_order_book, np.zeros((1, 4 * c.ORDER_BOOK_DEPTH)))
        )
        expected_order_book[1, 0] = float(ftc.TEST_ORDER_PRICE) / c.PRICE_NORMALIZER
        expected_order_book[1, 1] = float(ftc.TEST_ORDER_SIZE) / c.SIZE_NORMALIZER

        np.testing.assert_almost_equal(expected_order_book, observation.order_book, 10)

        account.place_limit_order(
            product_id=ftc.PRODUCT_ID,
            price=2 * ftc.TEST_ORDER_PRICE,
            side=ftc.TEST_ORDER_SIDE.get_opposite_side(),
            size=ftc.TEST_ORDER_SIZE,
        )

        place_orders_and_update(account, featurizer)

        expected_order_book = np.vstack(
            (expected_order_book, np.zeros((1, 4 * c.ORDER_BOOK_DEPTH)))
        )
        expected_order_book[2, 0] = float(ftc.TEST_ORDER_PRICE) / c.PRICE_NORMALIZER
        expected_order_book[2, 1] = 2 * float(ftc.TEST_ORDER_SIZE) / c.SIZE_NORMALIZER
        expected_order_book[2, 2] = float(BUY_ORDER_PRICE) / c.PRICE_NORMALIZER
        expected_order_book[2, 3] = float(ftc.TEST_ORDER_SIZE) / c.SIZE_NORMALIZER
        expected_order_book[2, 4] = 2 * float(ftc.TEST_ORDER_PRICE) / c.PRICE_NORMALIZER
        expected_order_book[2, 5] = float(ftc.TEST_ORDER_SIZE) / c.SIZE_NORMALIZER

        observation = featurizer.get_observation()
        np.testing.assert_almost_equal(expected_order_book, observation.order_book, 10)

    @staticmethod
    def test_time_series_cancellation_features(
        account: Account, featurizer: Featurizer[Exchange],
    ) -> None:
        """
        test_time_series_cancellation_features [summary]

        Args:
            account (Account): [description]
            featurizer (Featurizer[Exchange]): [description]
        """
        place_orders_and_update(account, featurizer)
        cancellation = CoinbaseCancellation(
            price=ftc.TEST_ORDER_PRICE,
            product_id=ftc.PRODUCT_ID,
            order_id=ftc.TEST_ORDER_ID,
            remaining_size=ftc.TEST_ORDER_SIZE,
            time=ftc.TEST_ORDER_TIME,
            side=ftc.TEST_ORDER_SIDE,
        )

        account.exchange.step()
        account.exchange.received_cancellations.append(cancellation)
        featurizer.update_state_buffer()

        expected_cancellation_features = np.array(
            [0.9428090416, 0.9428090416, 0.0, 0.0, 0.0, 0.0]
        )
        cancellation_features = featurizer.get_observation().time_series[1, :6]
        np.testing.assert_almost_equal(
            expected_cancellation_features, cancellation_features, 10
        )

    @staticmethod
    def test_time_series_order_features(
        account: Account, featurizer: Featurizer[Exchange],
    ) -> None:
        """
        test_time_series_order_feature [summary]

        Args:
            account (Account): [description]
            featurizer (Featurizer[Exchange]): [description]
        """
        place_orders_and_update(account, featurizer)

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
        account: Account, featurizer: Featurizer[Exchange],
    ) -> None:
        """
        test_time_series_match_features [summary]

        Args:
            account (Account): [description]
            featurizer (Featurizer[Exchange]): [description]
        """
        place_orders_and_update(account, featurizer)

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
