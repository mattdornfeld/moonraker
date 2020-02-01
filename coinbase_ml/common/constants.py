"""
Configs common to both train and serve
"""
import os
import platform
from datetime import timedelta
from decimal import Decimal
from pathlib import Path
from typing import Dict

from fakebase.types import Currency, ProductId

ACTOR_OUTPUT_DIMENSION = 4
ENVIRONMENT = os.environ.get("ENVIRONMENT", platform.node())
BUY_RESERVE_FRACTION = Decimal("0.005")
MAX_PRICE = 13000.00
NUM_CHANNELS_IN_TIME_SERIES = 26
ORDER_BOOK_BIN_SIZE = Decimal("0.01")
ORDER_BOOK_DEPTH = 10
ORDER_TIME_TO_LIVE = timedelta(seconds=30)
PRICE_NORMALIZER = 10e3
PRODUCT_CURRENCY = Currency.BTC
QUOTE_CURRENCY = Currency.USD
PRODUCT_ID = ProductId(PRODUCT_CURRENCY, QUOTE_CURRENCY)
SIZE_NORMALIZER = 1e2
FUNDS_NORMALIZERS: Dict[Currency, float] = {Currency.BTC: 1e1, Currency.USD: 100e3}
VERBOSE = False

# gcp configs
GCP_PROJECT_NAME = "moonraker"
MODEL_BUCKET_NAME = os.environ.get("MODEL_BUCKET_NAME", "moonraker-trained-models")
SERVICE_ACCOUNT_JSON = Path(
    os.environ.get("SERVICE_ACCOUNT_JSON", "/secrets/service-account.json")
)

# mongodb configs
MONGO_DB_URL = os.environ.get("MONGO_DB_URL", "mongodb://root:password@mongo:27017")
SACRED_DB_NAME = "sacred"
