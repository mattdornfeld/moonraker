"""Loads data from BigQuery
"""


from datetime import datetime, timedelta

import pandas as pd
from google.cloud import bigquery

from coinbase_ml.common.constants import GCP_PROJECT_NAME
from coinbase_ml.supervised.types import Prices


def build_ohlc_data_query(
    start_time: datetime, end_time: datetime, time_delta: timedelta
) -> str:
    """Build query for retrieving OHLC data from BigQuery
    """
    return f"""
        SELECT
          TIMESTAMP_SECONDS(DIV(UNIX_SECONDS(time), {time_delta.seconds}) * {time_delta.seconds}) time,
          cast(SUM(size) as float64) volume,
          cast(MAX(price) as float64) high,
          cast(MIN(price) as float64) low,
          cast(ARRAY_AGG(price
          ORDER BY
            time ASC
          LIMIT
            1)[SAFE_OFFSET(0)] as float64) open,
          cast(ARRAY_AGG(price
          ORDER BY
            time DESC
          LIMIT
            1)[SAFE_OFFSET(0)] as float64) close,
        FROM
          `moonraker.exchange_events_prod.matches`
        WHERE
          time BETWEEN TIMESTAMP("{start_time.isoformat()}")
          AND TIMESTAMP("{end_time.isoformat()}")
        GROUP BY
          1
        ORDER BY
          time ASC
        """


def get_ohlc_data(
    start_time: datetime, end_time: datetime, time_delta: timedelta
) -> pd.DataFrame:
    """Get OHLC from BigQuery as DataFrame
    """
    client = bigquery.Client()

    return client.query(
        build_ohlc_data_query(start_time, end_time, time_delta),
        project=GCP_PROJECT_NAME,
    ).to_dataframe()


def get_closing_prices(
    start_time: datetime, end_time: datetime, time_delta: timedelta
) -> Prices:
    """Gets closing prices time series from OHLC data
    """
    df = get_ohlc_data(start_time, end_time, time_delta)
    prices = df["close"]
    prices.index = pd.DatetimeIndex(df["time"]).tz_convert(None)  # type: ignore

    return Prices(prices)
