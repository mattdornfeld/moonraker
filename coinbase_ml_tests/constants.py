"""
 [summary]
"""
from datetime import datetime, timedelta
import os

from dateutil import parser

from coinbase_ml.fakebase.types import (
    Currency,
    OrderId,
    OrderSide,
    OrderStatus,
    OrderType,
    ProductId,
    ProductVolume,
    QuoteVolume,
)
from coinbase_ml.fakebase.exchange import Exchange

EXCHANGE_END_DT = parser.parse("2019-08-30 18:23:03.87")
EXCHANGE_START_DT = parser.parse("2019-08-30 18:22:19.983")
EXCHANGE_TIME_DELTA = timedelta(seconds=10)
TEST_EXCHANGE_NUM_STEPS = 5
TEST_EXCHANGE_PKL_PATH = "coinbase_ml_tests/data/test_exchange_checkpoint.p"
TEST_ORDER_CLIENT_OID = "test"
TEST_ORDER_ID = OrderId("test-order-id")
TEST_ORDER_POST_ONLY = False
TEST_ORDER_SEQUENCE = 1234
TEST_ORDER_SIDE = OrderSide.buy
TEST_ORDER_STATUS = OrderStatus.received
TEST_ORDER_TIME = datetime.min
TEST_ORDER_TIME_IN_FORCE = "gtc"
TEST_ORDER_TIME_TO_LIVE = 1.5 * EXCHANGE_TIME_DELTA
TEST_ORDER_TYPE = OrderType.limit
TEST_TRADE_ID = 1


PRODUCT_CURRENCY = Currency.BTC
QUOTE_CURRENCY = Currency.USD
PRODUCT_ID = ProductId[ProductVolume, QuoteVolume](PRODUCT_CURRENCY, QUOTE_CURRENCY)

TEST_PRICE_TYPE = PRODUCT_ID.price_type
TEST_PRODUCT_TYPE = PRODUCT_ID.product_volume_type
TEST_QUOTE_TYPE = PRODUCT_ID.quote_volume_type

ZERO_PRODUCT = PRODUCT_ID.product_volume_type.get_zero_volume()
ZERO_QUOTE = PRODUCT_ID.quote_volume_type.get_zero_volume()

TEST_ORDER_FUNDS = TEST_QUOTE_TYPE("1000.00")
TEST_ORDER_PRICE = TEST_PRICE_TYPE("10000.00")
TEST_ORDER_SIZE = TEST_PRODUCT_TYPE("1.000000")
TEST_WALLET_PRODUCT_FUNDS = TEST_PRODUCT_TYPE("10.0")
TEST_WALLET_QUOTE_FUNDS = TEST_QUOTE_TYPE("100000.00")


POSTGRES_CONTAINER_NAME = "coinbase_ml_tests_postgres"
POSTGRES_IMAGE_NAME = "postgres:9.6"
TEST_SQL_SRC_DIR = f"{os.getcwd()}/coinbase_ml_tests/data/moonraker.sql"
TMP_SQL_DIR = "/tmp/coinbase_ml/docker-entrypoint-initdb.d"

EXCHANGE = Exchange(
    end_dt=EXCHANGE_END_DT,
    product_id=PRODUCT_ID,
    start_dt=EXCHANGE_START_DT,
    time_delta=EXCHANGE_TIME_DELTA,
    test_mode=True,
)
