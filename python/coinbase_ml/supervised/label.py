"""Module for labeling a price time series with long/short positions using the triple barrier method
"""
from typing import Tuple, Iterator

import numpy as np
import pandas as pd

from coinbase_ml.common.utils.validate_args import validate_args
from coinbase_ml.supervised.constants import Labels, Columns


def get_barrier_time_from_iloc(
    timestamps: pd.DatetimeIndex, time_iloc: int, barrier_time_steps: int
) -> pd.Timestamp:
    """Returns barrier timestamp for index time_iloc
    """
    return timestamps[time_iloc + barrier_time_steps]


def with_barrier_time(
    timestamps: pd.DatetimeIndex, barrier_time_steps: int
) -> Iterator[Tuple[pd.Timestamp, pd.Timestamp]]:
    """Returns an Iterator of tuples (current timestamp, barrier timestamp)
    """
    for i, time in enumerate(timestamps[:-barrier_time_steps]):
        yield time, get_barrier_time_from_iloc(timestamps, i, barrier_time_steps)


@validate_args(**{"t1,t2": lambda t1, t2: t2 > t1})
def calc_cumalative_returns(
    returns: pd.Series, t1: pd.Timestamp, t2: pd.Timestamp
) -> pd.Series:
    """Returns the cumulative returns between timestamps t1 and 2
    """
    return returns[t1:t2].cumsum()


def calc_volatility(prices: pd.Series, span: int = 100) -> pd.Series:
    """Returns the exponential moving std for 'prices' for a window size of 'span'
    """
    prices.fillna(method="ffill", inplace=True)
    returns = prices.pct_change()
    return returns.ewm(span=span).std()


def calc_breakout_direction(
    cumulative_returns: pd.Series, upper_barrier: float, lower_barrier: float
) -> float:
    """Generates long/short labels based on behavior of `cumulative_returns`

    Returns 1.0 if `cumulative_returns` passes `upper_barrier` first
    Returns -1.0 if `cumulative_returns` passes `lower_barrier` first
    Returns 0.0 if `cumulative_returns` passes neither (i.e. passes the vertical barrier first)
    """
    first_upper_breakout = cumulative_returns[
        cumulative_returns.ge(upper_barrier)
    ].first_valid_index()
    first_lower_breakout = cumulative_returns[
        cumulative_returns.le(lower_barrier)
    ].first_valid_index()
    u = pd.Timestamp.max if first_upper_breakout is None else first_upper_breakout
    l = pd.Timestamp.max if first_lower_breakout is None else first_lower_breakout

    label: float
    if u < l:
        label = Labels.POSITIVE
    elif u > l:
        label = Labels.NEGATIVE
    else:
        label = Labels.NEUTRAL

    return label


@validate_args(**{"time,barrier_time": lambda t1, t2: t2 > t1})
def calc_triple_barrier_label(
    returns: pd.Series,
    upper_barrier: float,
    lower_barrier: float,
    time: pd.Timestamp,
    barrier_time: pd.Timestamp,
) -> float:
    """Generates long/short labels using the triple barrier method

    See `calc_breakout_direction` for more detailed description.
    """
    cumulative_returns = calc_cumalative_returns(returns, time, barrier_time)
    is_valid = (
        (not any(cumulative_returns.isna()))
        and np.isfinite(upper_barrier)
        and np.isfinite(lower_barrier)
    )
    if not is_valid:
        return np.nan

    return calc_breakout_direction(cumulative_returns, upper_barrier, lower_barrier)


def calc_skewed_triple_barrier_label_and_barriers(
    returns: pd.Series,
    volatility: pd.Series,
    time: pd.Timestamp,
    barrier_time: pd.Timestamp,
    upside_std_dev: float,
    downside_std_dev: float,
) -> Tuple[float, float, float]:
    """Calculates triple barrier for an asymmetric upside and downside std_dev

    Returns: (label, upper_barrier, lower_barrier)
    """
    upper_barrier = volatility[time] * upside_std_dev
    lower_barrier = -abs(volatility[time]) * downside_std_dev
    label = calc_triple_barrier_label(
        returns, upper_barrier, lower_barrier, time, barrier_time
    )

    return label, upper_barrier, lower_barrier


@validate_args(std_dev=lambda x: x > 0)
def calc_triple_barrier_label_and_barriers(
    returns: pd.Series,
    volatility: pd.Series,
    time: pd.Timestamp,
    barrier_time: pd.Timestamp,
    std_dev: float,
) -> Tuple[float, float, float]:
    """Calculates triple barrier for a symmetric upside and downside std_dev

    Returns: (label, upper_barrier, lower_barrier)
    """
    return calc_skewed_triple_barrier_label_and_barriers(
        returns, volatility, time, barrier_time, std_dev, std_dev
    )


@validate_args(
    prices=lambda x: isinstance(x.index, pd.DatetimeIndex),
    barrier_time=lambda x: x >= 1,
    std_dev=lambda x: x > 0,
    span=lambda x: x > 0,
)
def calc_triple_barrier_labels_and_barriers(
    prices: pd.Series, barrier_time_steps: int, std_dev: float = 2.5, span: int = 100
) -> pd.DataFrame:
    """Calculates triple barrier labels and barriers

    Returns DataFrame with columns (label, upper_barrier, lower_barrier)
    """
    prices.fillna(method="ffill", inplace=True)
    labels_and_barriers = pd.DataFrame(
        index=prices.index,
        columns=[Columns.LABEL, Columns.UPPER_BARRIER, Columns.LOWER_BARRIER],
    )

    returns = prices.pct_change()
    volatility = calc_volatility(prices, span)

    for time, barrier_time in with_barrier_time(prices.index, barrier_time_steps):
        (
            labels_and_barriers[Columns.LABEL][time],
            labels_and_barriers[Columns.UPPER_BARRIER][time],
            labels_and_barriers[Columns.LOWER_BARRIER][time],
        ) = calc_triple_barrier_label_and_barriers(
            returns, volatility, time, barrier_time, std_dev
        )

    return labels_and_barriers
