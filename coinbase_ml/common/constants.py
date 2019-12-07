"""
Configs common to both train and serve
TODO: go through train/constants.py and rm duplicates
"""
import os
import platform
from datetime import timedelta
from decimal import Decimal
from pathlib import Path
from typing import Dict

ACTOR_OUTPUT_DIMENSION = 4
ENVIRONMENT = os.environ.get("ENVIRONMENT", platform.node())
BUY_RESERVE_FRACTION = Decimal("0.005")
MAX_PRICE = 13000.00
NUM_CHANNELS_IN_TIME_SERIES = 26
ORDER_BOOK_BIN_SIZE = Decimal("0.01")
ORDER_BOOK_DEPTH = 10
ORDER_TIME_TO_LIVE = timedelta(seconds=10)
PRICE_NORMALIZER = 10e3
PRODUCT_ID = "BTC-USD"
PRODUCT_CURRENCY, QUOTE_CURRENCY = PRODUCT_ID.split("-")
SIZE_NORMALIZER = 1e2
FUNDS_NORMALIZERS: Dict[str, float] = dict(
    BTC=1e1, USD=100e3,
)

# gcp configs
GCP_PROJECT_NAME = "moonraker"
MODEL_BUCKET_NAME = os.environ.get("MODEL_BUCKET_NAME", "moonraker-trained-models")
SERVICE_ACCOUNT_JSON = Path(
    os.environ.get("SERVICE_ACCOUNT_JSON", "/secrets/service-account.json")
)

# mongodb configs
MONGO_DB_URL = os.environ.get("MONGO_DB_URL", "mongodb://root:password@mongo:27017")
SACRED_DB_NAME = "sacred"
