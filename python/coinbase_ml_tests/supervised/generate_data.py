"""Generates mock price data
"""

from datetime import datetime, timedelta

import numpy as np
import pandas as pd

from coinbase_ml.supervised.types import Prices


def generate_prices(
    mu: float,
    sigma: float,
    start_price: float,
    time_interval: float,
    dt: float,
    seed: int = 0,
) -> Prices:
    """Generates a prices time series from a Geometric Brownian Motion process
    """
    np.random.seed(seed)
    num_timesteps = round(time_interval / dt)
    timesteps = np.arange(0, num_timesteps)

    brownian_motion = np.cumsum(
        np.random.standard_normal(size=num_timesteps)
    ) * np.sqrt(dt)

    geometric_brownian_motion = start_price * np.exp(
        (mu - 0.5 * sigma ** 2) * timesteps + sigma * brownian_motion
    )

    return Prices(pd.Series(geometric_brownian_motion))


def generate_prices_for_timestamps(
    mu: float,
    sigma: float,
    start_price: float,
    start_time: datetime,
    end_time: datetime,
    time_delta: timedelta,
    seed: int = 0,
) -> Prices:
    """Like `generate_prices` but used pd.Timestamps as indices
    """
    timestamps = pd.date_range(start=start_time, end=end_time, freq=time_delta)
    time_interval = (end_time - start_time) / time_delta
    prices = generate_prices(mu, sigma, start_price, time_interval, 1, seed)
    prices.index = timestamps[:-1]

    return Prices(prices)


def generate_prices_for_timestamps_starting_now(
    mu: float,
    sigma: float,
    start_price: float,
    num_timesteps: int,
    time_delta: timedelta = timedelta(seconds=60),
    seed: int = 0,
) -> Prices:
    """Like `generate_prices_for_timestamps` but first index will be current timestamp
    """
    start_time = datetime.now()
    end_time = start_time + num_timesteps * time_delta

    return generate_prices_for_timestamps(
        mu, sigma, start_price, start_time, end_time, time_delta, seed
    )


def generate_random_normal_series(
    mu: float, std_dev: float, size: int, seed: int = 0
) -> pd.Series:
    """Generates a pd.Series from a Gaussian process
    """
    np.random.seed(seed)
    return pd.Series(np.random.normal(mu, std_dev, size))


def generate_constant_series(value: float, size: int) -> pd.Series:
    """Generates a series with a constant value
    """
    return pd.Series(value * np.ones(size))
