"""
config constants
"""
from datetime import datetime
import os

from dateutil.tz import UTC

from coinbase_ml.common import constants as cc

UTC_MAX = datetime.utcfromtimestamp(2147483647).astimezone(UTC)

# sacred configs
EXPERIMENT_NAME = f"{str(cc.PRODUCT_ID).lower()}-serve"
METRICS_RECORD_FREQUENCY = 5

# ray configs
SERVED_POLICY_ADDRESS = "localhost"
SERVED_POLICY_PORT = 9093

# coinbase configs
COINBASE_API_KEY_B64SECRET = os.environ.get("COINBASE_API_KEY_B64SECRET", "test")
COINBASE_API_KEY_NAME = os.environ.get("COINBASE_API_KEY_NAME", "test")
COINBASE_API_KEY_PASSPHRASE = os.environ.get("COINBASE_API_KEY_PASSPHRASE", "test")
COINBASE_API_URL = os.environ.get(
    "COINBASE_API_URL", "https://api-public.sandbox.pro.coinbase.com"
)
COINBASE_WEBSOCKET_API_URL = os.environ.get(
    "COINBASE_WEBSOCKET_API_URL", "wss://ws-feed-public.sandbox.pro.coinbase.com"
)
COINBASE_WEBSOCKET_CHANNELS = ["full", "level2"]

# kafka configs
COINBASE_STREAM_KAFKA_TOPIC = "coinbase_level_2_full_stream_json"
