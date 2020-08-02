"""
Tests for Featurizer
"""
import numpy as np
from pytest_cases import unpack_fixture

from coinbase_ml.common import constants as c
from coinbase_ml.common.action import NoTransaction
from coinbase_ml.common.featurizers import AccountFeaturizer
from coinbase_ml.common.featurizers import Featurizer
from coinbase_ml.fakebase.account import Account
from coinbase_ml.fakebase.exchange import Exchange
from coinbase_ml.fakebase.orm import CoinbaseCancellation, CoinbaseOrder
from coinbase_ml.fakebase.types import Liquidity, OrderSide, OrderStatus
from coinbase_ml_tests import constants as tc
from coinbase_ml_tests.common.featurizers.fixtures import (
    BUY_ORDER_PRICE,
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
                    (
                        BUY_ORDER_PRICE
                        * tc.TEST_ORDER_SIZE
                        * (1 + Liquidity.maker.fee_fraction)
                    ).amount,
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
        order = CoinbaseOrder(
            order_id=tc.TEST_ORDER_ID,
            order_status=OrderStatus.received,
            order_type=tc.TEST_ORDER_TYPE,
            product_id=tc.PRODUCT_ID,
            price=tc.TEST_ORDER_PRICE.get_min_value(),
            side=OrderSide.sell,
            size=c.PRODUCT_ID.product_volume_type("10.00"),
            time=account.exchange.interval_start_dt,
        )

        account.exchange.step(insert_orders=[order])
        place_orders_and_update(account, featurizer, no_transaction)

        expected_match_features = np.array(
            [
                0.0e00,
                0.0e00,
                0.0e00,
                0.0e00,
                0.0e00,
                5.0e11,
                5.0e09,
                0.0e00,
                5.0e11,
                0.0e00,
            ]
        )

        match_features = featurizer.get_observation().time_series[0, 6:16]
        np.testing.assert_almost_equal(expected_match_features, match_features, 10)
