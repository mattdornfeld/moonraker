from datetime import datetime, timedelta

import pytest as pytest
import sqlvalidator

from coinbase_ml.supervised.load_data import build_ohlc_data_query


@pytest.fixture
def ohlc_data_query() -> str:
    time_delta = timedelta(seconds=30)
    start_time = datetime.now()
    end_time = start_time + 10 * time_delta

    return build_ohlc_data_query(start_time, end_time, time_delta)


@pytest.mark.parametrize("sql_query", [pytest.lazy_fixture("ohlc_data_query")])
def test_queries_are_valid(sql_query: str) -> None:
    assert sqlvalidator.parse(sql_query).is_valid()
