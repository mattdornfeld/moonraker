"""
Configs common to both train and serve
"""
import os
import platform
from pathlib import Path
from typing import Dict

from coinbase_ml.fakebase.types import Currency, ProductId, ProductVolume, QuoteVolume

ACTOR_OUTPUT_DIMENSION = 2
ENVIRONMENT = os.environ.get("ENVIRONMENT", platform.node())
NUM_CHANNELS_IN_TIME_SERIES = 26
ORDER_BOOK_DEPTH = 10
# PRICE_NORMALIZER = 10e3
PRICE_NORMALIZER = 1.0
PRODUCT_CURRENCY = Currency.BTC
QUOTE_CURRENCY = Currency.USD
PRODUCT_ID = ProductId[ProductVolume, QuoteVolume](PRODUCT_CURRENCY, QUOTE_CURRENCY)
# SIZE_NORMALIZER = 1e2
SIZE_NORMALIZER = 1.0
FUNDS_NORMALIZERS: Dict[Currency, float] = {Currency.BTC: 1e1, Currency.USD: 100e3}
VERBOSE = False
SACRED_LOGGER_PORT = 60994

# gcp configs
GCP_PROJECT_NAME = "moonraker"
MODEL_BUCKET_NAME = os.environ.get("MODEL_BUCKET_NAME", "moonraker-trained-models")
SERVICE_ACCOUNT_JSON = Path(
    os.environ.get("SERVICE_ACCOUNT_JSON", "/secrets/service-account.json")
)

# mongodb configs
MONGO_DB_URL = os.environ.get("MONGO_DB_URL", "mongodb://root:password@mongo:27017")
SACRED_DB_NAME = "sacred"

# arrow configs
ARROW_SOCKETS_BASE_DIR = Path("/tmp/moonraker/coinbaseml/arrow_sockets")
