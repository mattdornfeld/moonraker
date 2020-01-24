"""
Tests for TimeSeriesFeaturizer
"""
import numpy as np

from fakebase.exchange import Exchange
from fakebase_tests import constants as ftc
from fakebase_tests.test_exchange_integration import create_exchange_with_db_connection

import pytest

from coinbase_ml.common.featurizers import TimeSeriesFeaturizer
from coinbase_ml_tests.utils import assert_arrays_not_equal


@pytest.fixture(name="time_series_featurizer")
def create_time_series_featurizer() -> TimeSeriesFeaturizer:
    """
    create_time_series_featurizer [summary]

    Returns:
        TimeSeriesFeaturizer: [description]
    """
    exchange = create_exchange_with_db_connection()

    return TimeSeriesFeaturizer[Exchange](exchange)


class TestTimeSeriesFeaturizer:
    """
    Tests for the TimeSeriesFeaturizer class
    """

    @staticmethod
    def test_cancellation_operators(
        create_database_stop_when_finished: None,  # pylint: disable=W0613
        time_series_featurizer: TimeSeriesFeaturizer,
    ) -> None:
        """
        test_cancellation_operators [summary]

        Args:
            create_database_stop_when_finished (None): [description]
        """
        expected_operator_names = [
            "OrderSide.buy_count",
            "OrderSide.buy_price_mean",
            "OrderSide.buy_price_stdev",
            "OrderSide.sell_count",
            "OrderSide.sell_price_mean",
            "OrderSide.sell_price_stdev",
        ]

        operator_names = [o.name for o in time_series_featurizer.cancellation_operators]

        assert expected_operator_names == operator_names

    @staticmethod
    def test_get_time_series_features(
        create_database_stop_when_finished: None,  # pylint: disable=W0613
        time_series_featurizer: TimeSeriesFeaturizer,
    ) -> None:
        """
        test_get_time_series_features [summary]

        Args:
            create_database_stop_when_finished (None): [description]
            time_series_featurizer (TimeSeriesFeaturizer): [description]
        """
        zeros_array = np.zeros((len(time_series_featurizer.operators),))
        last_features = time_series_featurizer.get_time_series_features()
        np.testing.assert_almost_equal(last_features, zeros_array, 10)

        for _ in range(ftc.TEST_EXCHANGE_NUM_STEPS):
            assert len(last_features) == 26
            time_series_featurizer.exchange.step()
            features = time_series_featurizer.get_time_series_features()
            assert_arrays_not_equal(features, last_features)
            last_features = features

    @staticmethod
    def test_match_operators(
        create_database_stop_when_finished: None,  # pylint: disable=W0613
        time_series_featurizer: TimeSeriesFeaturizer,
    ) -> None:
        """
        test_match_operators [summary]

        Args:
            create_database_stop_when_finished (None): [description]
        """
        expected_operator_names = [
            "OrderSide.buy_count",
            "OrderSide.buy_price_mean",
            "OrderSide.buy_price_stdev",
            "OrderSide.buy_size_mean",
            "OrderSide.buy_size_stdev",
            "OrderSide.sell_count",
            "OrderSide.sell_price_mean",
            "OrderSide.sell_price_stdev",
            "OrderSide.sell_size_mean",
            "OrderSide.sell_size_stdev",
        ]

        operator_names = [o.name for o in time_series_featurizer.match_operators]

        assert expected_operator_names == operator_names

    @staticmethod
    def test_order_operators(
        create_database_stop_when_finished: None,  # pylint: disable=W0613
        time_series_featurizer: TimeSeriesFeaturizer,
    ) -> None:
        """
        test_order_operators [summary]

        Args:
            create_database_stop_when_finished (None): [description]
        """
        expected_operator_names = [
            "OrderSide.buy_count",
            "OrderSide.buy_price_mean",
            "OrderSide.buy_price_stdev",
            "OrderSide.buy_size_mean",
            "OrderSide.buy_size_stdev",
            "OrderSide.sell_count",
            "OrderSide.sell_price_mean",
            "OrderSide.sell_price_stdev",
            "OrderSide.sell_size_mean",
            "OrderSide.sell_size_stdev",
        ]

        operator_names = [o.name for o in time_series_featurizer.order_operators]

        assert expected_operator_names == operator_names
