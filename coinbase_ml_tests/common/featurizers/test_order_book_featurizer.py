"""
Tests for OrderBookFeaturizer
"""
import numpy as np

from fakebase_tests import constants as ftc
from fakebase_tests.test_exchange_integration import create_exchange_with_db_connection
from fakebase.exchange import Exchange
from fakebase.types import OrderSide

import pytest

from coinbase_ml.common.featurizers import OrderBookFeaturizer


@pytest.fixture(name="order_book_featurizer")
def create_order_book_featurizer() -> OrderBookFeaturizer:
    """
    create_order_book_featurizer [summary]

    Returns:
        OrderBookFeaturizer: [description]
    """
    exchange = create_exchange_with_db_connection()

    return OrderBookFeaturizer[Exchange](exchange)


class TestOrderBookFeaturizer:
    """
    Tests for the OrderBookFeaturizer class
    """

    @staticmethod
    @pytest.mark.parametrize("order_side", [OrderSide.buy, OrderSide.sell])
    def test_get_order_book_features(
        create_database_stop_when_finished: None,  # pylint: disable=W0613
        order_book_featurizer: OrderBookFeaturizer,
        order_side: OrderSide,
    ) -> None:
        """
        test_get_order_book_features [summary]

        Args:
            create_database_stop_when_finished (None): [description]
            order_book_featurizer (OrderBookFeaturizer): [description]
            order_side (OrderSide): [description]
        """
        zeros_array = np.zeros((1, 2))
        last_price_volume_array = order_book_featurizer.get_order_book_features(
            order_side
        )
        # Before step is called, the order book is empty so we expected the array to
        # be np.zeros((1, 2)
        np.testing.assert_array_almost_equal(last_price_volume_array, zeros_array, 10)

        # After we call step the array will not be empty, but all we can predict is that
        # it will not be equal to the last array and can be sorted by the 0th column
        # (the price column)
        for _ in range(ftc.TEST_EXCHANGE_NUM_STEPS):
            order_book_featurizer.exchange.step()
            price_volume_array = order_book_featurizer.get_order_book_features(
                order_side
            )

            assert last_price_volume_array.shape != price_volume_array.shape

            sorted_array = price_volume_array[price_volume_array[:, 0].argsort()]
            np.testing.assert_array_almost_equal(price_volume_array, sorted_array, 10)
            last_price_volume_array = price_volume_array
