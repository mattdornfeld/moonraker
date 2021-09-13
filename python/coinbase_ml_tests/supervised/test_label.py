import numpy as np
import pandas as pd
import pytest

from coinbase_ml.supervised.constants import Labels
from coinbase_ml.supervised.types import Prices, Volatility, Returns
from coinbase_ml.supervised.label import (
    calc_triple_barrier_labels_and_barriers,
    calc_triple_barrier_label,
    calc_skewed_triple_barrier_label_and_barriers,
    calc_volatility,
    calc_triple_barrier_label_and_barriers,
    with_barrier_time,
    get_barrier_time_from_iloc,
    calc_returns,
)
from coinbase_ml_tests.supervised.generate_data import (
    generate_prices_for_timestamps_starting_now,
)

LOOKAHEAD_TIME = 20

# pylint: disable = redefined-outer-name


def get_barrier_time(
    timestamps: pd.DatetimeIndex, time: pd.Timestamp, barrier_time_steps: int
) -> pd.Timestamp:
    time_iloc = timestamps.get_loc(time)
    return get_barrier_time_from_iloc(timestamps, time_iloc, barrier_time_steps)


@pytest.fixture
def prices() -> Prices:
    return Prices(
        generate_prices_for_timestamps_starting_now(
            mu=0.0001, sigma=0.01, start_price=5.0, num_timesteps=100
        )
    )


@pytest.fixture
def volatility(prices: Prices) -> Volatility:
    return calc_volatility(prices=prices, span=10)


@pytest.fixture
def returns(prices: Prices) -> Returns:
    return calc_returns(prices)


@pytest.fixture
def labels_and_barriers(prices: Prices) -> pd.DataFrame:
    return calc_triple_barrier_labels_and_barriers(prices, LOOKAHEAD_TIME)


def test_calc_triple_barrier_labels_and_barriers_distribution(
    labels_and_barriers: pd.DataFrame,
) -> None:
    expected_value_counts = {
        Labels.POSITIVE: 25,
        Labels.NEUTRAL: 2,
        Labels.NEGATIVE: 51,
    }
    assert expected_value_counts == labels_and_barriers.label.value_counts().to_dict()


@pytest.mark.parametrize("expected_label", Labels.as_list())
def test_calc_triple_barrier_labels_and_barriers_point_wise(
    labels_and_barriers: pd.DataFrame, returns: Returns, expected_label: float
) -> None:
    filtered_positions = labels_and_barriers[
        labels_and_barriers.label == expected_label
    ]

    timestamps = labels_and_barriers.index
    for time, (label, upper_barrier, lower_barrier) in filtered_positions.iterrows():
        barrier_time = get_barrier_time(timestamps, time, LOOKAHEAD_TIME)
        label = calc_triple_barrier_label(
            returns, upper_barrier, lower_barrier, time, barrier_time
        )
        assert expected_label == label


def test_calc_triple_barrier_label_and_barriers(
    volatility: Volatility, returns: Returns
) -> None:
    expected_std_dev = 0.1
    for time, barrier_time in with_barrier_time(returns.index[2:], LOOKAHEAD_TIME):
        (_, upper_barrier, lower_barrier,) = calc_triple_barrier_label_and_barriers(
            returns, volatility, time, barrier_time, expected_std_dev
        )

        np.testing.assert_almost_equal(
            upper_barrier / volatility[time], expected_std_dev
        )

        np.testing.assert_almost_equal(
            -lower_barrier / volatility[time], expected_std_dev
        )


@pytest.mark.parametrize(
    "expected_label,upside_std_dev,downside_std_dev",
    [
        (Labels.NEGATIVE, 1e10, -1e10),
        (Labels.NEUTRAL, 1e10, 1e10),
        (Labels.POSITIVE, -1e10, 1e10),
    ],
)
def test_calc_skewed_triple_barrier_label_and_barriers(
    volatility: Volatility,
    returns: Returns,
    expected_label: float,
    upside_std_dev: float,
    downside_std_dev: float,
) -> None:
    for time, barrier_time in with_barrier_time(returns.index[2:], LOOKAHEAD_TIME):
        (label, _, _) = calc_skewed_triple_barrier_label_and_barriers(
            returns, volatility, time, barrier_time, upside_std_dev, downside_std_dev
        )
        assert label == expected_label


@pytest.mark.parametrize(
    "expected_label,upper_barrier,lower_barrier",
    [
        (Labels.NEGATIVE, 1e10, 1e10),
        (Labels.NEUTRAL, 1e10, -1e10),
        (Labels.POSITIVE, -1e10, -1e10),
    ],
)
def test_calc_triple_barrier_label(
    returns: Returns, expected_label: float, upper_barrier: float, lower_barrier: float,
) -> None:
    for time, barrier_time in with_barrier_time(returns.index[1:], LOOKAHEAD_TIME):
        label = calc_triple_barrier_label(
            returns, upper_barrier, lower_barrier, time, barrier_time
        )
        assert label == expected_label
